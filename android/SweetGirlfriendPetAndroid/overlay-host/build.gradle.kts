plugins {
    id("com.android.library")
}

android {
    namespace = "com.sweetgirlfriend.pet.overlay"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
