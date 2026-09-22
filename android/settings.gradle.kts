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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dak"

// Pure-Kotlin (JVM) modules: no Android dependency, fast unit tests, reusable by future platforms.
include(
    ":core-model",
    ":mms-pdu",
    ":premium-api",
    ":classify",
    ":finance",
    ":automations",
    ":search",
    ":backup",
    ":settings-registry",
)

// Android modules. `-Pdak.jvmOnly=true` skips them (for environments without the Android SDK).
if (providers.gradleProperty("dak.jvmOnly").orNull != "true") {
    include(
        ":core-telephony",
        ":core-index",
        ":app",
    )
}
