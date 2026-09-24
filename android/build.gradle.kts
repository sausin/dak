import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // Code coverage (build-time only; docs/testing.md). Applied here for the merged report.
    alias(libs.plugins.kover)
}

// ---- Coverage (Kover) -------------------------------------------------------------------------------------------
// Every module applies Kover and declares a "coverage" variant (jvm for the pure-Kotlin modules, debug / freeDebug
// for the Android ones). The root merges those into one report:
//   ./gradlew koverXmlReportCoverage koverHtmlReportCoverage
//   -> <module>/build/reports/kover/reportCoverage.xml and build/reports/kover/reportCoverage.xml (merged)
// scripts/jvm-test.sh reuses this file (minus the Android plugin lines), so keep Android-only names out of it.

kover {
    currentProject {
        // Nothing of its own to measure: the root's variant only merges the modules' "coverage" variants.
        createVariant("coverage") {}
    }
}

dependencies {
    subprojects.forEach { kover(project(it.path)) }
}

// Generated code and Compose UI are excluded everywhere (module reports and the merged one) so the number
// measures hand-written, unit-testable logic. Rationale: docs/testing.md#what-is-excluded.
allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        extensions.configure<KoverProjectExtension> {
            reports {
                filters {
                    excludes {
                        classes(
                            // Hilt / Dagger
                            "*_Factory", "*_Factory\$*", "*_MembersInjector", "*_Provide*Factory*",
                            "Hilt_*", "*.Hilt_*", "*_HiltModules*", "*_HiltComponents*", "*_GeneratedInjector",
                            "*_ComponentTreeDeps", "*_Hilt*", "dagger.*", "hilt_aggregated_deps.*",
                            "*_AssistedFactory*", "*_Impl\$*", "*_Impl",
                            // Android build outputs
                            "*.BuildConfig", "*.R", "*.R\$*", "*.Manifest", "*.Manifest\$*",
                            // Compose compiler output, kotlinx.serialization generated serializers
                            "*ComposableSingletons*", "*\$\$serializer",
                        )
                        annotatedBy(
                            // Compose UI functions (and @Preview) are not unit-tested; their state holders are.
                            "androidx.compose.runtime.Composable",
                            "androidx.compose.ui.tooling.preview.Preview",
                            // Anything a code generator marks as generated.
                            "javax.annotation.processing.Generated",
                            "javax.annotation.Generated",
                            "dagger.internal.DaggerGenerated",
                        )
                    }
                }
            }
        }
    }
}
