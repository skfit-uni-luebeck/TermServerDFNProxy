@file:Suppress("HttpUrlsUsage")

package de.uniluebeck.itcrl.termserverdfnproxy

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.httpsredirect.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.utils.io.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.system.exitProcess

fun Application.proxyAppModule() {
    /**
     * used for sending out JSON
     */
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }

    val httpsEnabled = configuration.getOrElse(proxy.https.enabled, false)
    val hstsEnabled = configuration.getOrElse(proxy.https.hsts.enabled, false)
    when {
        hstsEnabled && !httpsEnabled -> {
            log.error("HSTS is enabled but HTTPS is not! Please enable HTTPS or disable HSTS.")
            exitProcess(2)
        }

        hstsEnabled && httpsEnabled -> {
            val redirectPort = configuration.getOrElse(proxy.https.port, 443)
            log.info("Enabling HSTS with header: $hstsHeader")
            log.info("Redirecting HTTP to HTTPS on port $redirectPort")
            install(HttpsRedirect) {
                // The port to redirect to. By default, 443, the default HTTPS port.
                sslPort = redirectPort
                // 301 Moved Permanently, or 302 Found redirect.
                permanentRedirect = true
            }
        }
    }

    val clientSslContext = generateClientSslContext()

    fun getClient() = HttpClient(Apache) {
        /**
         * FHIR specifies a text body for 404 requests. If this is not set, receiving this payload
         * results in an exception.
         */
        expectSuccess = false
        //configure the HTTPS engine
        engine {
            customizeClient {
                setSSLContext(clientSslContext)
            }
        }
    }

    /**
     * we intercept all requests (regardless of routing) at the [ApplicationCallPipeline.Call] stage and pass
     * them to the upstream
     */

    intercept(ApplicationCallPipeline.Call) {

        val requestId = UUID.randomUUID().toString()

        if (this.context.request.httpMethod == HttpMethod.Options) {
            mainLogger.info("OPTIONS from ${context.request.origin}")
            addCors(call)
            addHsts(call)
            call.respond(HttpStatusCode.NoContent)
            return@intercept //OPTIONS means we are done!
        }

        val upstreamUri = "${configuration[upstream.protocol]}://${
            configuration[upstream.address].replace(
                Regex("https?://"), ""
            )
        }"
        val requestPath = this.context.request.uri.trimStart('/')
        val requestUri = "$upstreamUri:${configuration[upstream.port]}/$requestPath".also { mainLogger.info(it) }

        /**
         * we need to provide a body for POST, PUT, PATCH, but none otherwise
         */
        val payloadToUpstreamSize = call.request.headers[HttpHeaders.ContentLength]?.toIntOrNull()?.let {
            "$it bytes payload, "
        } ?: ""
        mainLogger.info(
            "${call.request.origin.serverHost}:${call.request.origin.serverPort} " + "${
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } " + "${call.request.httpMethod.value} " + "\"${call.request.uri}\" " + payloadToUpstreamSize + "${call.request.headers[HttpHeaders.Accept] ?: "-"} " + "\"${call.request.headers[HttpHeaders.UserAgent]}\"" + "; request-id $requestId"
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
                    /**
                     * GET, DELETE, HEAD, etc. - no body in our request to the server is expected
                     */
                    client.request(requestUri) {
                        method = call.request.httpMethod
                    }
                }
            }
            proxiedHeaders = proxyResponse.headers
            location = proxiedHeaders[HttpHeaders.Location]
            contentType = proxiedHeaders[HttpHeaders.ContentType]
            contentLength = proxiedHeaders[HttpHeaders.ContentLength]
            mainLogger.info(
                "status {}, method {}, request-uri {}, content-length {} bytes (body-size {}b); request-id {}",
                proxyResponse.status.value,
                call.request.httpMethod.value,
                requestUri,
                contentLength,
                proxyResponse.bodyAsChannel().toByteArray().size,
                requestId
            )
        }

        /**
         * check if the status code indicates that the server handled the request correctly. This does include 404s,
         * because those indicate that the server handled the request correctly, but the original request is to blame
         */
        if (contentType.equals("text/html")) {
            mainLogger.debug("upstream server responded with HTML")
        }
        if (!listOf(
                HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.NotFound, HttpStatusCode.NoContent
            ).contains(proxyResponse.status) && contentType != "text/html"
        ) {
            /**
             * we want to respond with an OperationOutcome resource in the spirit of FHIR
             */
            var receivedErrorString = "none given"
            val issue = mutableListOf(
                mapOf(
                    "code" to proxyResponse.status.value.toString(), "severity" to "error", "details" to mapOf(
                        "text" to "upstream server did not accept request " + "with status code ${proxyResponse.status.value}"
                    )
                )
            )
            if (contentType?.contains(Regex("json|xml|text")) == true) {

                /**
                 * the server likely has provided an OperationOutcome itself, being FHIR-aware. Use it as a "stack trace"
                 */
                receivedErrorString = proxyResponse.bodyAsText()
                val providedOutcome = Parser.default().parse(StringBuilder(receivedErrorString)) as JsonObject
                @Suppress("UNCHECKED_CAST")
                if (providedOutcome.containsKey("resourceType") && providedOutcome["resourceType"]?.equals("OperationOutcome") == true) {
                    issue += (providedOutcome["issue"] as JsonArray<*>)[0] as Map<String, Any> //UNCHECKED cast, but should be safe for FHIR R4!
                }
            }
            val responseJson = mutableMapOf(
                "resourceType" to "OperationOutcome", "id" to "exception", "issue" to issue
            )
            mainLogger.warn(
                "upstream server did not accept request with status code ${proxyResponse.status} " + "and error '$receivedErrorString'"
            )
            addCors(call)
            addMoreHeaders(call, upstreamUri)
            call.respond(proxyResponse.status, responseJson)
            return@intercept //we are done handling this request
        }

        val proxyHttpPort = configuration[proxy.http.port]
        val proxyHttpsPort = configuration[proxy.https.port]
        val proxyHostname = configuration[proxy.hostname]
        val ourUrl = when (call.request.port()) {
            proxyHttpPort -> "http://$proxyHostname:$proxyHttpPort"
            proxyHttpsPort -> "https://$proxyHostname:$proxyHttpsPort"
            else -> throw IllegalStateException("unknown port ${call.request.port()}")
        }

        /**
         * function to replace all mentions of the upstream URL with our endpoint (e.g. in the syndication feed!)
         */
        fun String.replaceUpstreamUrl(ourEndpoint: String) = this.replace(
            Regex("(https?:)?//${configuration[upstream.address]}(:${configuration[upstream.port]})?"),
            ourEndpoint
        )
        /**
         * if the upstream had a Location header, insert our endpoint here
         */
        if (location != null) {
            call.response.header(HttpHeaders.Location, location!!.replaceUpstreamUrl(ourUrl))
        }

        when {
            /**
             * In the case of text-based content types we download the whole content and process it as a string replacing
             * upstream links.
             */
            contentType?.contains(regexTextBody) == true -> {
                val text = proxyResponse.bodyAsText().replaceUpstreamUrl(ourUrl)
                mainLogger.debug(
                    "responding with text, ${text.length} chars, starting ${
                        text.substring(0, 30).replace("\n", " ")
                    }"
                )
                addCors(call)
                addMoreHeaders(call, upstreamUri)
                addHsts(call)
                call.respond(
                    TextContent(
                        text, ContentType.parse(contentType!!), proxyResponse.status
                    )
                )
                return@intercept
            }

            else -> {
                /**
                 * In the case of other content, we simply pipe it. We return a [OutgoingContent.WriteChannelContent]
                 * propagating the contentLength, the contentType and other headers, and simply copy
                 * the ByteReadChannel from the HTTP client response to the HTTP server ByteWriteChannel response.
                 */
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
                        if (hstsHeader != null) {
                            append(HttpHeaders.StrictTransportSecurity, hstsHeader!!)
                        }
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
