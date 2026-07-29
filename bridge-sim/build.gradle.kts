plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.iamjosephmj.bridge.sim"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}

dependencies {
    api(project(":bridge-runtime"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
