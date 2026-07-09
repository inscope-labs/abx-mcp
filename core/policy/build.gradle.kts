plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.inscopelabs.abxmcp.core.policy"
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
    implementation(libs.androidx.documentfile)
    implementation(project(":core:session"))
    implementation(project(":core:audit"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
