plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

val ciVersionCode = (System.getenv("DAK_VERSION_CODE") ?: "1").toInt()
// Tagged CI builds pass the tag's version (v1.2.3 -> 1.2.3); local and untagged builds keep the default.
val ciVersionName = System.getenv("DAK_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"

android {
    namespace = "app.dak"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.dak"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from CI secrets when present; otherwise release builds are left unsigned.
    val keystorePath = System.getenv("DAK_KEYSTORE_PATH")
    signingConfigs {
        // One shared debug key for every machine and CI run (a public, throwaway key: debug builds only). Without it
        // each CI runner signs with its own random key and a newer debug APK cannot be installed over an older one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DAK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DAK_KEY_ALIAS")
                keyPassword = System.getenv("DAK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 shrinking, optimisation and obfuscation, plus unused-resource removal (docs/release.md). Debug
            // builds stay unminified. The mapping file (build/outputs/mapping/<variant>/mapping.txt) is uploaded
            // by CI; keep it for every published build to retrace crash reports.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // One project, two flavours that differ only in which PremiumGateway / Entitlements / Translator
    // implementations Hilt binds (see src/free and src/premium).
    flavorDimensions += "tier"
    productFlavors {
        create("free") {
            dimension = "tier"
        }
        create("premium") {
            dimension = "tier"
            applicationIdSuffix = ".premium"
            versionNameSuffix = "-premium"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

// Built-in Kotlin (AGP 9+): jvmTarget follows compileOptions.targetCompatibility.
kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":premium-api"))
    implementation(project(":classify"))
    implementation(project(":finance"))
    implementation(project(":automations"))
    implementation(project(":search"))
    implementation(project(":backup"))
    implementation(project(":settings-registry"))
    implementation(project(":core-telephony"))
    implementation(project(":core-index"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    // Applies baseline-prof.txt (AOT-compiled hot paths) on sideloaded installs too, not only Play installs.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libphonenumber)
    implementation(libs.coil.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Coverage (docs/testing.md): the "coverage" variant is what the root merges and CI gates on. One flavour only:
// free and premium compile the same shared classes (which cannot be merged), so premium-only code (src/premium)
// is not in the metric.
kover {
    currentProject {
        createVariant("coverage") { add("freeDebug") }
    }
}
