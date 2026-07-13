import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties
import java.io.FileInputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

val versionProps = Properties().apply {
  load(FileInputStream(rootProject.file("version.properties")))
}

val stage = (project.findProperty("stage") as String?)?.toIntOrNull() ?: 0

if (stage >= 7) {
  apply(plugin = "org.jetbrains.kotlin.plugin.compose")
  apply(plugin = "com.google.devtools.ksp")
  apply(plugin = "io.github.takahirom.roborazzi")
  apply(plugin = "com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
  apply(plugin = "com.google.gms.google-services")
}

android {
  namespace = "com.inscopelabs.abxmcp"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.inscopelabs.abxmcp"
    minSdk = 24
    targetSdk = if (stage >= 7) 36 else 35
    versionCode = versionProps.getProperty("versionCode").trim().toInt()
    versionName = versionProps.getProperty("versionName").trim()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
    val keystoreFile = file(keystorePath)
    if (keystoreFile.exists()) {
      create("release") {
        storeFile = keystoreFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    } else {
      create("release") {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = System.getenv("DEBUG_STORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = System.getenv("DEBUG_STORE_PASSWORD") ?: "android"
      keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
      keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = (stage >= 8)
      isShrinkResources = false
      if (stage >= 8) {
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      }
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = (stage >= 7)
    buildConfig = (stage >= 7)
  }

  sourceSets {
    getByName("main") {
      if (stage == 0) {
        manifest.srcFile("src/stage0/AndroidManifest.xml")
        java.setSrcDirs(listOf("src/stage0/java"))
        res.setSrcDirs(listOf("src/stage0/res"))
      }
    }
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    disable.add("InvalidFragmentVersionForActivityResult")
    abortOnError = false
    checkReleaseBuilds = false
  }
}

if (stage >= 7) {
  configure<com.google.android.libraries.mapsplatform.secrets_gradle_plugin.SecretsPluginExtension> {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.abxmcp"
  }
  val googleServices = extensions.findByName("googleServices")
  if (googleServices != null) {
    try {
      val method = googleServices.javaClass.methods.firstOrNull { 
        it.name == "setMissingGoogleServicesStrategy" && it.parameterCount == 1 
      }
      method?.invoke(googleServices, MissingGoogleServicesStrategy.WARN)
    } catch (e: Exception) {
      // ignore
    }
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation("androidx.appcompat:appcompat:1.7.0")

  if (stage >= 2) implementation(project(":core:keystore"))
  if (stage >= 3) implementation(project(":core:audit"))
  if (stage >= 5) {
    implementation(project(":core:session"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:policy"))
    implementation(project(":core:mcp"))
    implementation(project(":core:filesystem"))
  }

  if (stage >= 7) {
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.converter.moshi)
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.recaptcha)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logging.interceptor)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.androidx.documentfile)
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  if (stage == 0) {
    exclude { element ->
      element.file.absolutePath.contains("src/main/java")
    }
  }
}

kotlin {
  sourceSets.all {
    println("DEBUG: Kotlin source set name: $name dirs: ${kotlin.srcDirs}")
    if (name == "debug" || name == "release") {
      if (stage == 0) {
        kotlin.setSrcDirs(listOf("src/stage0/java"))
      }
    }
  }
}
