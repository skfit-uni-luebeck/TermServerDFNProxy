package de.mii.termserv.dfnproxy

import com.natpryce.konfig.Key
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.conn.ssl.TrustSelfSignedStrategy
import org.apache.http.ssl.SSLContextBuilder
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.net.ssl.SSLContext
import kotlin.io.path.Path
import kotlin.io.path.notExists

fun loadKeystoreFromProperties(
    filename: Key<String>, keystoreType: Key<String>, keystorePassword: Key<String>, usagePurpose: String
): KeyStore {
    val keyStore = KeyStore.getInstance(configuration[keystoreType])!!
    val keyStorePath = Path(configuration[filename]).toAbsolutePath()
    mainLogger.info("Using keystore for {}, path: {}", usagePurpose, keyStorePath)
    if (keyStorePath.notExists()) {
        throw FileNotFoundException("The keystore file ${configuration[filename]} does not exist!")
    }
    keyStore.load(FileInputStream(configuration[filename]), configuration[keystorePassword].toCharArray())

    keyStore.aliases().toList().let { aliases ->
        mainLogger.info("Keystore aliases: {}", aliases.joinToString {
            "'$it'"
        })
    }

    return keyStore
}

fun generateClientSslContext(): SSLContext = SSLContextBuilder.create().let { builder ->
    val mutualTlsEnabled = configuration.getOrElse(mutualTls.enabled, false)
    val useSslKeystoreForMutualTls = configuration.getOrElse(mutualTls.useSslKeystore, false)
    return@let when (mutualTlsEnabled) {
        true -> {
            val keyStore = when (useSslKeystoreForMutualTls) {
                false -> loadKeystoreFromProperties(
                    mutualTls.keystore.filename, mutualTls.keystore.type, mutualTls.keystore.password, "mutual TLS"
                )

                true -> loadKeystoreFromProperties(
                    filename = proxy.https.keystore.filename,
                    keystoreType = proxy.https.keystore.type,
                    keystorePassword = proxy.https.keystore.password,
                    usagePurpose = "mutual TLS"
                )
            }
            val desiredAlias = when (useSslKeystoreForMutualTls) {
                true -> configuration.getOrNull(proxy.https.keypair.alias)
                else -> configuration.getOrNull(mutualTls.keypair.alias)
            }
            val keyPassword = when (useSslKeystoreForMutualTls) {
                false -> configuration[mutualTls.keypair.password].toCharArray()
                true -> configuration[proxy.https.keypair.password].toCharArray()
            }

            when (desiredAlias) {
                null -> builder.loadKeyMaterial(keyStore, keyPassword)
                /**
                 * If the user has set the alias in the configuration, we load the key material with the alias
                 */
                else -> {
                    if (!keyStore.aliases().toList().contains(desiredAlias)) {
                        throw IllegalArgumentException("The alias '$desiredAlias' is not in the keystore")
                    }
                    mainLogger.info("Using alias '{}' for mutual TLS", desiredAlias)
                    builder.loadKeyMaterial(
                        keyStore, keyPassword
                    ) { _, _ -> desiredAlias }
                }

            }
        }

        else -> {
            val mutualTlsKeys =
                listOf<Key<*>>(mutualTls.keystore.filename, mutualTls.keystore.password, mutualTls.keypair.password)
            val superfluousConfiguration = mutualTlsKeys.associateWith { configuration.contains(it) }
            if (superfluousConfiguration.any { it.value }) {
                mainLogger.warn("Mutual TLS is disabled, but the following configuration keys are set: {}",
                    superfluousConfiguration.filter { it.value }.keys.joinToString { "'${it.name}'" })
            }
            builder
        }

    }.apply {
        val trustAll = configuration.getOrElse(trust.all, false)
        var trustSelfSigned = configuration.getOrElse(trust.selfSigned, false)
        if (trustAll && trustSelfSigned) {
            mainLogger.warn("Both trust all and trust self-signed are set to true, ignoring trust self-signed")
            trustSelfSigned = false
        }
        when {
            trustAll -> {
                mainLogger.info("Using trust all strategy for SSL configuration")
                loadTrustMaterial(TrustAllStrategy())
            }

            trustSelfSigned -> {
                mainLogger.info("Using trust self-signed strategy for SSL configuration")
                loadTrustMaterial(TrustSelfSignedStrategy())
            }
        }
    }.build()
}