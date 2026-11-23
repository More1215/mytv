// Module domain : Kotlin pur, AUCUNE dépendance Android.
// Il contient les modèles métier, les interfaces de repositories et les use cases.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Annotation @Inject seule (pas Hilt) : les use cases restent indépendants du framework.
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
