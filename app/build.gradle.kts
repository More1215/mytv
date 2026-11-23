import java.util.Properties

// Module app : UI Compose for TV, ViewModels, navigation, point d'entrée Hilt.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Lit un fichier .properties à la racine s'il existe (tous deux ignorés par git). */
fun loadProps(name: String): Properties = Properties().apply {
    val file = rootProject.file(name)
    if (file.exists()) file.inputStream().use { load(it) }
}

val localProps = loadProps("local.properties")
val keystoreProps = loadProps("keystore.properties")

/** Échappe une valeur pour l'injecter dans un champ BuildConfig de type String. */
fun quoted(value: String?): String = "\"" + (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.mytv"
    // Les dernières bibliothèques AndroidX exigent de compiler contre l'API 37.1+.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.mytv"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Si keystore.properties existe, on signe la release avec ta clé ; sinon avec la clé debug
        // (suffisant pour un sideload perso, mais l'APK ne pourra pas être mis à jour par une
        // version signée autrement sans désinstaller).
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug" // debug et release peuvent cohabiter sur le Fire TV
            // Pré-remplissage du formulaire en DEBUG uniquement, depuis local.properties (jamais commité).
            buildConfigField("String", "DEBUG_PORTAL_URL", quoted(localProps.getProperty("mytv.debug.portalUrl")))
            buildConfigField("String", "DEBUG_PORTAL_USER", quoted(localProps.getProperty("mytv.debug.username")))
            buildConfigField("String", "DEBUG_PORTAL_PASS", quoted(localProps.getProperty("mytv.debug.password")))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Garantie : aucun identifiant n'est jamais embarqué dans un APK release.
            buildConfigField("String", "DEBUG_PORTAL_URL", "\"\"")
            buildConfigField("String", "DEBUG_PORTAL_USER", "\"\"")
            buildConfigField("String", "DEBUG_PORTAL_PASS", "\"\"")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    // :data n'est utilisé que pour que Hilt découvre ses modules ; l'UI n'importe que :domain.
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Lecture vidéo
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)

    // Logos des chaînes
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
}
