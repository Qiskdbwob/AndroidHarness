buildscript {
    dependencies {
        constraints {
            // AGP uses these together for build-time crypto; keep them off the app classpath.
            listOf("bcprov", "bcpkix", "bcutil").forEach { artifact ->
                add("classpath", "org.bouncycastle:${artifact}-jdk18on:1.80.2") {
                    because("CVE-2025-14813 security backport")
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
