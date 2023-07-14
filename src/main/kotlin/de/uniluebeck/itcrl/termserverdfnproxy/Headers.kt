package de.uniluebeck.itcrl.termserverdfnproxy

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlin.system.exitProcess


fun addCors(call: ApplicationCall) {
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT, DELETE, OPTIONS")
    call.response.header(
        HttpHeaders.AccessControlAllowHeaders,
        "X-FHIR-Starter,Accept,Authorization,Cache-Control,Content-Type,Access-Control-Request-Method," + "Access-Control-Request-Headers,DNT,If-Match,If-None-Match,If-Modified-Since,Keep-Alive," + "Origin,User-Agent,X-Requested-With,Prefer"
    )
    call.response.header(HttpHeaders.AccessControlMaxAge, 60 * 60 * 24)
}

fun addMoreHeaders(call: ApplicationCall, upstreamUri: String) {
    call.response.header(HttpHeaders.Server, "termserver-proxy")
    call.response.header("X-Upstream-Url", upstreamUri)
}

val hstsHeader: String? by lazy {
    if (!configuration.getOrElse(proxy.https.enabled, false)) {
        mainLogger.debug("HSTS is disabled because HTTPS is disabled.")
        return@lazy null
    }
    if (!configuration.getOrElse(proxy.https.hsts.enabled, false)) {
        mainLogger.debug("HSTS is disabled because it is disabled in the configuration.")
        return@lazy null
    }
    val includeSubDomains = configuration.getOrElse(proxy.https.hsts.includeSubDomains, true)
    val preload = configuration.getOrElse(proxy.https.hsts.preload, false)
    val maxAge = configuration.getOrElse(proxy.https.hsts.maxAgeSeconds, 31_536_000)

    val hstsHeader = buildString {
        append("max-age=$maxAge")
        if (includeSubDomains) append("; includeSubDomains")
        if (preload) append("; preload")
    }
    mainLogger.info("HSTS header: $hstsHeader")
    return@lazy hstsHeader
}

fun addHsts(call: ApplicationCall) {
    val httpsEnabled = configuration.getOrElse(proxy.https.enabled, false)
    val hstsEnabled = configuration.getOrElse(proxy.https.hsts.enabled, false)
    if (hstsEnabled && !httpsEnabled) {
        mainLogger.error("HSTS is enabled but HTTPS is not! Please enable HTTPS or disable HSTS.")
        exitProcess(2)
    }
    if (hstsEnabled) {
        call.response.header(HttpHeaders.StrictTransportSecurity, hstsHeader!!)
    }
}