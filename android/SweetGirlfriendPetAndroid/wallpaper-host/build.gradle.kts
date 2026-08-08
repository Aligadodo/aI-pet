plugins {
    id("com.android.library")
}

android {
    namespace = "com.sweetgirlfriend.pet.wallpaper"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":pet-runtime"))
    implementation(project(":content-pack"))
    implementation(project(":pet-renderer"))
}
