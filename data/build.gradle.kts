import java.util.Properties

// Module data : API Xtream (Retrofit/OkHttp), cache Room, préférences DataStore,
// chiffrement des identifiants, et implémentations des repositories du domain.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/** Valeur de mytv.debug.allowNoVpn dans local.properties (fichier ignoré par git). */
fun localAllowNoVpn(): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty("mytv.debug.allowNoVpn")
}

android {
    namespace = "dev.mytv.data"
    // Les dernières bibliothèques AndroidX exigent de compiler contre l'API 37.1+.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        // Kill switch VPN : TOUJOURS actif en release, aucune option pour le désactiver.
        // En debug, il peut être levé via local.properties (mytv.debug.allowNoVpn=true)
        // pour tester sur l'émulateur, qui n'a pas de VPN.
        debug {
            buildConfigField("boolean", "REQUIRE_VPN", (localAllowNoVpn() != "true").toString())
        }
        release {
            buildConfigField("boolean", "REQUIRE_VPN", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Tests JVM : les appels à android.util.Log (logs de debug) ne font rien au lieu de planter.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Schémas Room versionnés dans git : indispensables pour écrire les migrations plus tard.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":domain"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    api(libs.okhttp) // exposé : l'app réutilisera le même client pour Coil et Media3
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.doh)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kxml2)
}
