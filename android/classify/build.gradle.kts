plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The indexing benchmark (src/test/.../bench) drives the whole enrichment path, as :core-index does.
    testImplementation(project(":finance"))
    testImplementation(project(":search"))
}

// The indexing benchmark is opt-in: `-Pdak.bench=true` (see docs/performance.md). Plain `test` never runs it.
val runBench = providers.gradleProperty("dak.bench").orNull == "true"
tasks.test {
    if (runBench) {
        systemProperty("dak.bench", "true")
        maxHeapSize = "1g"
        testLogging.showStandardStreams = true
    } else {
        exclude("**/bench/*Benchmark*")
    }
}
