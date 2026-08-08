plugins {
    id("com.android.application")
}

android {
    namespace = "com.sweetgirlfriend.pet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sweetgirlfriend.pet"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.5"
        testInstrumentationRunner =
            "com.sweetgirlfriend.pet.app.PetPackInstallGateInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(project(":wallpaper-host"))
    implementation(project(":overlay-host"))
    testImplementation("junit:junit:4.13.2")
}
