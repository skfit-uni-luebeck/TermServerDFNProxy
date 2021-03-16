package de.uniluebeck.itcrl.termserverdfnproxy

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.fasterxml.jackson.databind.SerializationFeature
import com.natpryce.konfig.*
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.content.TextContent
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.jackson.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.netty.*
import io.ktor.util.*
import io.ktor.utils.io.*
import org.apache.http.client.methods.HttpHead
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustSelfSignedStrategy
import org.apache.http.ssl.SSLContextBuilder
import org.conscrypt.Conscrypt
import org.eclipse.jetty.http.HttpHeader
import java.io.FileInputStream
import java.security.KeyStore
import java.security.Security
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.*
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

val configuration = ConfigurationProperties.fromResource("proxy.conf")

fun sslConfig(): SSLContext = SSLContextBuilder.create().let {
    return@let when (configuration[Proxy.useKeystore]) {
        true -> {
            val keyStorePassword = configuration[Ssl.Keystore.password].toCharArray()
            val keyPassword = configuration[Ssl.Keypair.password].toCharArray()
            val keyStore = KeyStore.getInstance(configuration[Ssl.Keystore.type])
            keyStore.load(FileInputStream(configuration[Ssl.Keystore.filename]), keyStorePassword)
            it.loadKeyMaterial(keyStore, keyPassword)
        }
        else -> it
    }.loadTrustMaterial(TrustSelfSignedStrategy())
        .build()
}

val regexTextBody = Regex("(application|text)/(atom|fhir)?\\+?(xml|json|html|plain)")

/**
 * adapted from https://github.com/ktorio/ktor-samples/blob/1.3.0/other/reverse-proxy/src/ReverseProxyApplication.kt
 */
fun main() {
    //handle modern TLS 1.3 and TLS 1.2 with modern cipher suites, instead of relying on the JDKs security implementation
    //adapted from https://gist.github.com/Karewan/4b0270755e7053b471fdca4419467216
    Security.insertProviderAt(Conscrypt.newProvider(), 1)
    @Suppress("BlockingMethodInNonBlockingContext", "UNCHECKED_CAST")
    val server = embeddedServer(Jetty, configuration[Proxy.listen]) {

        /*
         * used for sending out JSON
         */
        install(ContentNegotiation) {
            jackson {
                //pretty-print
                enable(SerializationFeature.INDENT_OUTPUT)
            }
        }

        install(CORS) {
            method(HttpMethod.Options)
            anyHost()
        }

        fun getClient() = HttpClient(Apache) {
            //FHIR specifies a text body for 404 requests. If this is not set, recv'ing this payload
            //when the request 404's results in exceptions
            expectSuccess = false
            //configure the HTTPS engine
            engine {
                customizeClient {
                    setSSLContext(sslConfig())
                    setSSLHostnameVerifier(NoopHostnameVerifier())
                }
            }
        }

        //we intercept all requests (regardless of routing) at the Call state and pass them to the upstream
        intercept(ApplicationCallPipeline.Call) {
            val upstreamUri = "${configuration[Upstream.protocol]}://${
                configuration[Upstream.address].replace(
                    Regex("https?://"),
                    ""
                )
            }"
            val requestPath = this.context.request.uri.trimStart('/')
            val requestUri = "$upstreamUri:${configuration[Upstream.port]}/$requestPath".also { log.info(it) }
            //we need to provide a body for POST, PUT, PATCH, but none otherwise

            log.info(
                "${call.request.origin.host}:${call.request.origin.port} " +
                        "${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} " +
                        "${call.request.httpMethod.value} " +
                        "\"${call.request.uri}\" " +
                        "${call.request.headers[HttpHeaders.ContentLength] ?: 0} bytes, " +
                        "${call.request.headers[HttpHeaders.ContentType] ?: "-"} " +
                        "\"${call.request.headers[HttpHeaders.UserAgent]}\""
            )

            suspend fun receiveBody(call: ApplicationCall): Any {
                val receivedContentType = call.request.headers[HttpHeaders.ContentType] ?: "application/octet-stream"
                return when {
                    receivedContentType.matches(regexTextBody) -> {
                        val text = call.receiveText()
                        log.debug("received text, ${text.length} chars, starting ${text.substring(0, 30)}")
                        text
                    }
                    else -> {
                        val stream = call.receiveStream()
                        log.debug("passing binary stream, ${stream.available()} bytes")
                        stream
                    }
                }
            }

            var proxyRequest: HttpStatement
            var proxyResponse: HttpResponse
            var proxiedHeaders: Headers
            var location: String?
            var contentLength: String?
            var contentType: String?

            getClient().use { client ->
                proxyRequest = when (call.request.httpMethod) {
                    HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch -> {
                        client.request(requestUri) {
                            method = call.request.httpMethod
                            header(HttpHeaders.ContentType, call.request.headers[HttpHeaders.ContentType])
                            body = receiveBody(call)
                            //body = call.request.receiveChannel() //pass the provided body to the upstream
                        }
                    }
                    else -> {
                        //GET, DELETE, OPTIONS, HEAD, etc - no body expected
                        client.request(requestUri) {
                            method = call.request.httpMethod
                        }
                    }
                }
                proxyResponse = proxyRequest.execute()
                log.debug("proxyRequest to $requestUri, ${call.request.httpMethod.value}, status ${proxyResponse.status}")
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
                    receivedErrorString = proxyResponse.readText()
                    val providedOutcome = KlaxonParser.default()
                        .parse(StringBuilder(receivedErrorString)) as JsonObject
                    if (providedOutcome.containsKey("resourceType") &&
                        providedOutcome["resourceType"]?.equals("OperationOutcome") == true
                    ) {
                        issue += (providedOutcome["issue"] as JsonArray<*>)[0] as Map<String, Any> //UNCHECKED cast, but should be safe for FHIR R4!
                    }
                }
                val responseJson = mutableMapOf(
                    "resourceType" to "OperationOutcome", "id" to "exception",
                    "issue" to issue
                )
                log.warn(
                    "upstream server did not accept request with status code ${proxyResponse.status} " +
                            "and error '$receivedErrorString'"
                )
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
                    val text = proxyResponse.readText().replaceUpstreamUrl()
                    log.debug("responding with text, ${text.length} chars, starting ${text.substring(0, 30).replace("\n", " ")}")
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
                    log.debug("responding with binary stream, $contentLength bytes")
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
                            proxyResponse.content.copyAndClose(channel)
                        }
                    })
                    return@intercept
                }
            }
        }
    }
    server.start(wait = true)
}

