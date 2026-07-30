plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.iamjosephmj.bridge.compat"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions.unitTests.isIncludeAndroidResources = true
    publishing { singleVariant("release") { withSourcesJar() } }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.github.iamjosephmj.bridge"
                artifactId = "bridge-compat"
                version = "0.5.0-rc.5"
                from(components["release"])
            }
        }
    }
}

dependencies {
    api(project(":bridge-runtime"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
