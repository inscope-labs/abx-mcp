plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.inscopelabs.abxmcp.core.tunnel"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(project(":core:session"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
