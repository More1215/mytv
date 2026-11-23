pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyTV"

// Clean architecture : le compilateur garantit le sens des dépendances
//   :app  ->  :domain  <-  :data      (et :app -> :data uniquement pour l'injection Hilt)
include(":app")
include(":domain")
include(":data")
