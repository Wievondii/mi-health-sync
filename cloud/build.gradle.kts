plugins { id("com.android.library") }

android {
    namespace = "com.mihealthsync.cloud"
    compileSdk = 36
    defaultConfig.minSdk = 28
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.json:json:20250517")
}
