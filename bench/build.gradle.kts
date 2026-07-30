plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.iamjosephmj.bench"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.iamjosephmj.bench"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation(project(":bridge-runtime"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // Android's unit-test stub jar throws "not mocked" for org.json.* by default;
    // pull in the real org.json implementation so Report.toJson is testable off-device.
    testImplementation("org.json:json:20231013")
}
