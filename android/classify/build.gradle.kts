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
    // Phone-number spans in EntityExtractor (PhoneNumberUtil.findNumbers, offline metadata).
    implementation(libs.libphonenumber)
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
        systemProperty("dak.bench.out", layout.buildDirectory.file("reports/dak-bench.txt").get().asFile.path)
        maxHeapSize = "1g"
        testLogging.showStandardStreams = true
        // `-Pdak.bench.jfr=<file.jfr>` also records a CPU profile (inspect with `jfr print --events jdk.ExecutionSample`).
        providers.gradleProperty("dak.bench.jfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    } else {
        exclude("**/bench/*Benchmark*")
    }
}
