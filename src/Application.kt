package de.uniluebeck.itcrl

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.fasterxml.jackson.databind.SerializationFeature
import com.natpryce.konfig.*
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.content.TextContent
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.jackson.*
import io.ktor.network.tls.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.netty.*
import io.ktor.util.*
import io.ktor.utils.io.*
import org.conscrypt.Conscrypt
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.text.toCharArray
import com.beust.klaxon.Parser.Companion as KlaxonParser

object upstream : PropertyGroup() {
    val adress by stringType
    val protocol by stringType
}

object proxy : PropertyGroup() {
    val listen by intType
    val publicadress by stringType
    val protocol by stringType
}

object ssl : PropertyGroup() {
    object keystore : PropertyGroup() {
        val type by stringType
        val filename by stringType
        val password by stringType
    }

    object keypair : PropertyGroup() {
        val alias by stringType
        val password by stringType
    }

    object trustedCertificates : PropertyGroup() {
        val load by booleanType
        val aliasList by stringType
    }
}

val configuration = ConfigurationProperties.fromResource("proxy.conf")

fun clientCertificates(): CertificateAndKey {
    val keyStore = KeyStore.getInstance(configuration[ssl.keystore.type])
    val keystorePassword = configuration[ssl.keystore.password].toCharArray()
    val keypairPassword = configuration[ssl.keypair.password].toCharArray()
    keyStore.load(FileInputStream(configuration[ssl.keystore.filename]), keystorePassword)
    val keypairAlias = configuration[ssl.keypair.alias]
    val cert = keyStore.getCertificate(keypairAlias) as X509Certificate
    val key = keyStore.getKey(keypairAlias, keypairPassword) as PrivateKey
    if (ssl.trustedCertificates.load.equals(true)) {
        // STOPSHIP: 06.10.20 not implemented
        throw NotImplementedError()
    }
    return CertificateAndKey(arrayOf(cert), key)
}

val regexTextBody = Regex("(application|text)/(atom|fhir)?\\+?(xml|json|html|plain)")

/**
 * adapted from https://github.com/ktorio/ktor-samples/blob/1.3.0/other/reverse-proxy/src/ReverseProxyApplication.kt
 */
@KtorExperimentalAPI
fun main() {
    //handle modern TLS 1.3 and TLS 1.2 with modern cipher suites, instead of relying on the JDKs security implementation
    //adapted from https://gist.github.com/Karewan/4b0270755e7053b471fdca4419467216
    Security.insertProviderAt(Conscrypt.newProvider(), 1)
    @Suppress("BlockingMethodInNonBlockingContext", "UNCHECKED_CAST")
    val server = embeddedServer(Jetty, configuration[proxy.listen]) {

        /*
         * used for sending out JSON
         */
        install(ContentNegotiation) {
            jackson {
                //pretty-print
                enable(SerializationFeature.INDENT_OUTPUT)
            }
        }

        /**
         * read the client certificates from disk
         */
        val clientCertificates = clientCertificates()

        /**
         * SUPPRESSED: CIO is experimental, but works fine for this app
         */
        fun getClient() = HttpClient(CIO) {
            //FHIR specifies a text body for 404 requests. If this is not set, recv'ing this payload
            //when the request 404's results in exceptions
            expectSuccess = false
            //configure the HTTPS engine
            engine {
                https {
                    //cipherSuites = CIOCipherSuites.SupportedSuites
                    //add the provided client certificates
                    certificates.add(clientCertificates)
                    //TODO add method for adding untrusted trusted certificates
                }
            }
        }

        //we intercept all requests (regardless of routing) at the Call state and pass them to the upstream
        intercept(ApplicationCallPipeline.Call) {
            val upstreamUri = "${configuration[upstream.protocol]}://${
                configuration[upstream.adress].replace(
                    Regex("https?://"),
                    ""
                )
            }"
            val requestUri = upstreamUri + this.context.request.uri
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
                var receivedErrorString: String = "none given"
                val issue = mutableListOf(
                    mapOf(
                        "code" to proxyResponse.status.value.toString(), "severity" to "error",
                        "details" to mapOf("text" to "upstream server did not accept request " +
                                "with status code ${proxyResponse.status.value}")
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
                Regex("(https?:)?//${configuration[upstream.adress]}"),
                "${configuration[proxy.protocol]}://${configuration[proxy.publicadress]}"
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
                    log.debug("responding with text, ${text.length} chars, starting ${text.substring(0, 30)}")
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
                                key != HttpHeaders.ContentType && key != HttpHeaders.ContentLength
                            })
                        }
                        override val status: HttpStatusCode?
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

