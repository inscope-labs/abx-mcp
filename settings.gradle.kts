pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "ABC Server"

val stage = providers.gradleProperty("stage").getOrElse("0").toInt()

include(":app")
if (stage >= 2) include(":core:keystore")
if (stage >= 3) include(":core:audit")
if (stage >= 5) {
  include(":core:session")
  include(":core:tunnel")
  include(":core:policy")
  include(":core:filesystem")
  include(":core:mcp")
}
