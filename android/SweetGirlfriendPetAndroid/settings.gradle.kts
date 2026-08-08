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

rootProject.name = "SweetGirlfriendPetAndroid"
include(":app")
include(":pet-runtime")
include(":content-pack")
include(":pet-renderer")
include(":wallpaper-host")
include(":overlay-host")
