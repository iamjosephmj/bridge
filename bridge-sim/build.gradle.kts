plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.iamjosephmj.bridge.sim"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    publishing { singleVariant("release") { withSourcesJar() } }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.github.iamjosephmj.bridge"
                artifactId = "bridge-sim"
                version = "0.5.0-rc.4"
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
    testImplementation(libs.kotlinx.coroutines.test)
}
