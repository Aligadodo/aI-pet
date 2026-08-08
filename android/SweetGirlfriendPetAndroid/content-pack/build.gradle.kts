plugins {
    id("com.android.library")
}

android {
    namespace = "com.sweetgirlfriend.pet.content"
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
    api(project(":pet-runtime"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
