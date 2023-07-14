@file:Suppress("ClassName")

package de.uniluebeck.itcrl.termserverdfnproxy

import com.natpryce.konfig.*

object proxy : PropertyGroup() {

    val hostname by stringType
    val path by stringType

    object http: PropertyGroup() {
        val enabled by booleanType
        val port by intType
        val redirectToHttps by booleanType
    }

    object https: PropertyGroup() {
        val enabled by booleanType
        val port by intType
        val behindReverseProxy by booleanType

        object keystore : PropertyGroup() {
            val type by stringType
            val filename by stringType
            val password by stringType
        }

        object keypair : PropertyGroup() {
            val alias by stringType
            val password by stringType
        }

        object hsts : PropertyGroup() {
            val enabled by booleanType
            val maxAgeSeconds by longType
            val includeSubDomains by booleanType
            val preload by booleanType
        }
    }
}

object upstream : PropertyGroup() {
    val address by stringType
    val protocol by stringType
    val port by intType
}

object mutualTls : PropertyGroup() {

    val enabled by booleanType
    val useSslKeystore by booleanType

    object keystore : PropertyGroup() {
        val type by stringType
        val filename by stringType
        val password by stringType
    }

    object keypair : PropertyGroup() {
        val password by stringType
        val alias by stringType
    }
}

object trust : PropertyGroup() {
    val all by booleanType
    val selfSigned by booleanType
}