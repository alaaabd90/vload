plugins { id("com.android.application") }
val signing = requireLocalProperties()
android {
    namespace = "app.vload.phoneaudit"
    compileSdk = 35
    defaultConfig { applicationId = "app.vload.phoneaudit"; minSdk = 23; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    signingConfigs {
        create("owner") {
            storeFile = rootProject.file("release.keystore")
            storePassword = signing.getProperty("KEYSTORE_PASS")
            keyAlias = signing.getProperty("ALIAS_NAME")
            keyPassword = signing.getProperty("ALIAS_PASS")
        }
    }
    buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("owner") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
