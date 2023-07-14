@file:Suppress("ClassName")

package de.uniluebeck.itcrl.termserverdfnproxy

import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.Misconfiguration
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import kotlin.io.path.Path
import kotlin.io.path.notExists
import kotlin.system.exitProcess

lateinit var configuration: ConfigurationProperties // lateinit because we need to initialize it in main()

val regexTextBody = Regex("(application|text)/(atom|fhir)?\\+?(xml|json|html|plain)")

val mainLogger: Logger = LoggerFactory.getLogger("termserver-proxy")

/**
 * adapted from https://github.com/ktorio/ktor-samples/blob/1.3.0/other/reverse-proxy/src/ReverseProxyApplication.kt
 */
fun main(args: Array<String>) {
    val parser = ArgParser("termserver-proxy")
    val configPath by parser.option(
        ArgType.String, shortName = "c", fullName = "config", description = "Path to configuration file"
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
                    "The default config file could not be found at proxy.conf! " + "You can use the -c/--config command line option to specify a config file."
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

    val server = embeddedServer(factory = Jetty, environment = configureEnvironment())
    server.start(wait = true)
}

fun configureEnvironment(): ApplicationEngineEnvironment {
    @Suppress("HttpUrlsUsage")
    return applicationEngineEnvironment {
        log = mainLogger
        val httpEnabled = configuration.getOrNull(proxy.http.enabled)
        val httpsEnabled = configuration.getOrNull(proxy.https.enabled)
        val host = configuration[proxy.hostname]
        (if (httpEnabled == null && httpsEnabled == null) {
            mainLogger.error("Neither HTTP nor HTTPS is enabled in the config! Use properties 'proxy.http.enabled' and/or 'proxy.https.enabled' to enable one or both.")
            exitProcess(1)
        })

        if (httpEnabled == true) {
            val portInUrl = when (val httpPort = configuration[proxy.http.port]) {
                80 -> ""
                else -> ":$httpPort"
            }
            val httpEndpoint = "http://$host$portInUrl"
            mainLogger.info("HTTP enabled on $httpEndpoint")
            this.connector {
                port = configuration[proxy.http.port]
            }
        }

        if (httpsEnabled == true) {
            val portInUrl = when (val httpsPort = configuration[proxy.https.port]) {
                443 -> ""
                else -> ":$httpsPort"
            }
            val httpsEndpoint = "https://$host$portInUrl"
            configureHttps(httpsEndpoint)
        }

        module(Application::proxyAppModule)
    }
}

private fun ApplicationEngineEnvironmentBuilder.configureHttps(httpsEndpoint: String) {
    when (configuration.getOrElse(proxy.https.behindReverseProxy, false)) {
        true -> mainLogger.info("HTTPS enabled behind reverse proxy at $httpsEndpoint")
        else -> {
            mainLogger.info("HTTPS enabled on $httpsEndpoint")
            val httpsKeystore = loadKeystoreFromProperties(
                filename = proxy.https.keystore.filename,
                keystoreType = proxy.https.keystore.type,
                keystorePassword = proxy.https.keystore.password,
                usagePurpose = "HTTPS"
            )
            val privateKeyPassword = configuration.getOrElse(
                proxy.https.keypair.password,
                configuration[proxy.https.keystore.password]
            )
            val alias = when (val keyAlias = configuration.getOrNull(proxy.https.keypair.alias)) {
                null -> {
                    val firstAlias = httpsKeystore.aliases().toList().first()
                    mainLogger.warn("No key alias specified for HTTPS, using first key ('$firstAlias') in keystore")
                    firstAlias
                }

                else -> {
                    mainLogger.info("Using key alias '$keyAlias' for HTTPS")
                    if (!httpsKeystore.containsAlias(keyAlias)) {
                        mainLogger.error("The key alias '$keyAlias' does not exist in the keystore!")
                        exitProcess(2)
                    }
                    keyAlias
                }
            }
            this.sslConnector(
                keyStore = httpsKeystore,
                keyAlias = alias,
                keyStorePassword = { configuration[proxy.https.keystore.password].toCharArray() },
                privateKeyPassword = { privateKeyPassword.toCharArray() },
            ) {
                port = configuration[proxy.https.port]
                keyStorePath = configuration[proxy.https.keystore.filename].let {
                    Path(it).toAbsolutePath().toFile()
                }
            }
        }
    }
}


