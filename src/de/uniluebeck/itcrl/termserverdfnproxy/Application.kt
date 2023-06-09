package de.uniluebeck.itcrl.termserverdfnproxy

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.fasterxml.jackson.databind.SerializationFeature
import com.natpryce.konfig.*
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.content.TextContent
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustSelfSignedStrategy
import org.apache.http.ssl.SSLContextBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.KeyStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import kotlin.io.path.Path
import kotlin.io.path.notExists
import kotlin.text.toCharArray
import com.beust.klaxon.Parser.Companion as KlaxonParser

object Upstream : PropertyGroup() {
    val address by stringType
    val protocol by stringType
    val port by intType
}

object Proxy : PropertyGroup() {
    val listen by intType
    val publicAddress by stringType
    val protocol by stringType
    val useKeystore by booleanType
}

object Ssl : PropertyGroup() {
    object Keystore : PropertyGroup() {
        val type by stringType
        val filename by stringType
        val password by stringType
    }

    object Keypair : PropertyGroup() {
        val password by stringType
    }
}

lateinit var configuration: ConfigurationProperties //lateinit because we need to initialize it in main()

fun sslConfig(): SSLContext = SSLContextBuilder.create().let { builder ->
    return@let when (configuration[Proxy.useKeystore]) {
        true -> {
            val keyStorePassword = configuration[Ssl.Keystore.password].toCharArray()
            val keyPassword = configuration[Ssl.Keypair.password].toCharArray()
            val keyStore = KeyStore.getInstance(configuration[Ssl.Keystore.type])
            val keyStorePath = Path(configuration[Ssl.Keystore.filename]).toAbsolutePath()
            mainLogger.info("Using keystore for SSL configuration, path: {}", keyStorePath)
            if (keyStorePath.notExists()) {
                throw FileNotFoundException("The keystore file ${configuration[Ssl.Keystore.filename]} does not exist!")
            }
            keyStore.load(FileInputStream(configuration[Ssl.Keystore.filename]), keyStorePassword)
            keyStore.aliases().toList().joinToString {
                "'$it'"
            }.let {
                mainLogger.info("Keystore aliases: {}", it)
            }
            builder.loadKeyMaterial(keyStore, keyPassword)
        }

        else -> builder
    }.apply {
        loadTrustMaterial(TrustSelfSignedStrategy())
    }.build()
}

val regexTextBody = Regex("(application|text)/(atom|fhir)?\\+?(xml|json|html|plain)")

val mainLogger: Logger = LoggerFactory.getLogger("termserver-proxy")

/**
 * adapted from https://github.com/ktorio/ktor-samples/blob/1.3.0/other/reverse-proxy/src/ReverseProxyApplication.kt
 */
fun main(args: Array<String>) {
    val parser = ArgParser("termserver-proxy")
    val configPath by parser.option(
        ArgType.String,
        shortName = "c",
        fullName = "config",
        description = "Path to configuration file"
    )
    parser.parse(args)
    configuration = when (val configAbsPath = configPath?.let {
        Path(it).toAbsolutePath()
    }) {
        null -> {
            mainLogger.info("No config file specified, using default config")
            try {
                ConfigurationProperties.fromResource("proxy.conf")
            } catch (e: Misconfiguration) {
                throw FileNotFoundException(
                    "The default config file could not be found at proxy.conf! " +
                            "You can use the -c/--config command line option to specify a config file."
                )
            }

        }

        else -> {
            if (configAbsPath.notExists()) {
                throw FileNotFoundException("The config file path $configAbsPath does not exist!")
            }
            mainLogger.info("Using config file $configAbsPath")
            ConfigurationProperties.fromFile(configAbsPath.toFile())
        }
    }

    val server = embeddedServer(Jetty, configuration[Proxy.listen], module = Application::proxyAppModule)
    server.start(wait = true)
}

fun addCors(call: ApplicationCall) {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT, DELETE, OPTIONS")
    call.response.header(
        HttpHeaders.AccessControlAllowHeaders,
        "X-FHIR-Starter,Accept,Authorization,Cache-Control,Content-Type,Access-Control-Request-Method,Access-Control-Request-Headers,DNT,If-Match,If-None-Match,If-Modified-Since,Keep-Alive,Origin,User-Agent,X-Requested-With,Prefer"
    )
    call.response.header(HttpHeaders.AccessControlMaxAge, 60 * 60 * 24)
}

fun Application.proxyAppModule() {
    /*
     * used for sending out JSON
     */
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }

    val sslConfig = sslConfig()

    fun getClient() = HttpClient(Apache) {
        //FHIR specifies a text body for 404 requests. If this is not set, recv'ing this payload
        //when the request 404's results in exceptions
        expectSuccess = false
        //configure the HTTPS engine
        engine {
            customizeClient {
                setSSLContext(sslConfig)
                setSSLHostnameVerifier(NoopHostnameVerifier())
            }
        }
    }

    //we intercept all requests (regardless of routing) at the Call state and pass them to the upstream
    intercept(ApplicationCallPipeline.Call) {

        if (this.context.request.httpMethod == HttpMethod.Options) {
            mainLogger.info("OPTIONS from ${context.request.origin}")
            addCors(call)
            call.respond(HttpStatusCode.NoContent)
            return@intercept //OPTIONS means we are done!
        }

        val upstreamUri = "${configuration[Upstream.protocol]}://${
            configuration[Upstream.address].replace(
                Regex("https?://"),
                ""
            )
        }"
        val requestPath = this.context.request.uri.trimStart('/')
        val requestUri = "$upstreamUri:${configuration[Upstream.port]}/$requestPath".also { mainLogger.info(it) }
        //we need to provide a body for POST, PUT, PATCH, but none otherwise

        mainLogger.info(
            "${call.request.origin.serverHost}:${call.request.origin.serverPort} " +
                    "${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} " +
                    "${call.request.httpMethod.value} " +
                    "\"${call.request.uri}\" " +
                    "${call.request.headers[HttpHeaders.ContentLength] ?: 0} bytes, " +
                    "${call.request.headers[HttpHeaders.ContentType] ?: "-"} " +
                    "\"${call.request.headers[HttpHeaders.UserAgent]}\""
        )

        var proxyResponse: HttpResponse
        var proxiedHeaders: Headers
        var location: String?
        var contentLength: String?
        var contentType: String?

        getClient().use { client ->
            proxyResponse = when (call.request.httpMethod) {
                HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch -> {
                    client.request(requestUri) {
                        method = call.request.httpMethod
                        header(HttpHeaders.ContentType, call.request.headers[HttpHeaders.ContentType])
                        setBody(call.request.receiveChannel())
                    }
                }

                else -> {
                    //GET, DELETE, OPTIONS, HEAD, etc. - no body in our request to the server is expected
                    client.request(requestUri) {
                        method = call.request.httpMethod
                    }
                }
            }
            mainLogger.debug(
                "proxyRequest to {}, {}, status {}",
                requestUri,
                call.request.httpMethod.value,
                proxyResponse.status
            )
            proxiedHeaders = proxyResponse.headers
            location = proxiedHeaders[HttpHeaders.Location]
            contentType = proxiedHeaders[HttpHeaders.ContentType]
            contentLength = proxiedHeaders[HttpHeaders.ContentLength]
        }

        //check if the status code indicates that the server handled the request correctly. This does not 404s,
        //because those indicate that the server handled the request correctly, but the original request is to blame
        if (!listOf(
                HttpStatusCode.OK,
                HttpStatusCode.Created,
                HttpStatusCode.NotFound,
                HttpStatusCode.NoContent
            ).contains(proxyResponse.status)
        ) {
            //we want to respond with an OperationOutcome resource in the spirit of FHIR
            var receivedErrorString = "none given"
            val issue = mutableListOf(
                mapOf(
                    "code" to proxyResponse.status.value.toString(), "severity" to "error",
                    "details" to mapOf(
                        "text" to "upstream server did not accept request " +
                                "with status code ${proxyResponse.status.value}"
                    )
                )
            )
            if (contentType?.contains(Regex("json|xml|text")) == true) {
                //the server likely has provided an OperationOutcome itself, being FHIR-aware. Use it as an "stack trace"
                receivedErrorString = proxyResponse.bodyAsText()
                val providedOutcome = KlaxonParser.default()
                    .parse(StringBuilder(receivedErrorString)) as JsonObject
                if (providedOutcome.containsKey("resourceType") &&
                    providedOutcome["resourceType"]?.equals("OperationOutcome") == true
                ) {
                    @Suppress("UNCHECKED_CAST")
                    issue += (providedOutcome["issue"] as JsonArray<*>)[0] as Map<String, Any> //UNCHECKED cast, but should be safe for FHIR R4!
                }
            }
            val responseJson = mutableMapOf(
                "resourceType" to "OperationOutcome", "id" to "exception",
                "issue" to issue
            )
            mainLogger.warn(
                "upstream server did not accept request with status code ${proxyResponse.status} " +
                        "and error '$receivedErrorString'"
            )
            addCors(call)
            call.respond(proxyResponse.status, responseJson)
            return@intercept //we are done handling this request
        }

        /**
         * function to replace all mentions of the upstream URL with our endpoint (e.g. in the syndication feed!)
         */
        fun String.replaceUpstreamUrl() = this.replace(
            Regex("(https?:)?//${configuration[Upstream.address]}(:${configuration[Upstream.port]})?"),
            "${configuration[Proxy.protocol]}://${configuration[Proxy.publicAddress]}"
        )
        /**
         * if the upstream had a Location header, insert our endpoint here
         */
        if (location != null) {
            call.response.header(HttpHeaders.Location, location!!.replaceUpstreamUrl())
        }

        when {
            // In the case of text-based content types we download the whole content and process it as a string replacing
            // upstream links.
            contentType?.contains(regexTextBody) == true -> {
                val text = proxyResponse.bodyAsText().replaceUpstreamUrl()
                mainLogger.debug(
                    "responding with text, ${text.length} chars, starting ${
                        text.substring(0, 30).replace("\n", " ")
                    }"
                )
                addCors(call)
                call.respond(
                    TextContent(
                        text,
                        ContentType.parse(contentType!!),
                        proxyResponse.status
                    )
                )
                return@intercept
            }

            else -> {
                // In the case of other content, we simply pipe it. We return a [OutgoingContent.WriteChannelContent]
                // propagating the contentLength, the contentType and other headers, and simply we copy
                // the ByteReadChannel from the HTTP client response, to the HTTP server ByteWriteChannel response.
                mainLogger.debug("responding with binary stream, $contentLength bytes")
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long?
                        get() = contentLength?.toLong()
                    override val contentType: ContentType?
                        get() = contentType?.let { ContentType.parse(it) }

                    /**
                     * pass all the headers, except for the ContentType and ContentLength headers
                     */
                    override val headers: Headers = Headers.build {
                        appendAll(proxiedHeaders.filter { key, _ ->
                            key != HttpHeaders.ContentType && key != HttpHeaders.ContentLength && key != HttpHeaders.TransferEncoding
                        })
                    }
                    override val status: HttpStatusCode
                        get() = proxyResponse.status

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        proxyResponse.bodyAsChannel().copyAndClose(channel)
                    }
                })
                return@intercept
            }
        }
    }
}

