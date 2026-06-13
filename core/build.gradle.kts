plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    jacoco                                              // line/branch coverage
    id("info.solidsoft.pitest") version "1.15.0"        // mutation testing (PIT)
}

repositories {
    mavenCentral()
    // The Gradle Tooling API is published here, not on Maven Central.
    maven("https://repo.gradle.org/gradle/libs-releases")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Warm build connection for the gradle adapter.
    implementation("org.gradle:gradle-tooling-api:8.10.2")
    // Tooling API logs through SLF4J; bind it to a no-op so it never spams the daemon.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.arxael.MainKt")
}

kotlin {
    jvmToolchain(21)
}

// Bake the resolved version (root VERSION file — see root build.gradle.kts) into a classpath resource so the
// RUNNING daemon can report its own version on /health and /metrics. The jar manifest does NOT carry
// Implementation-Version, and a jar *filename* isn't introspectable at runtime — a generated resource is the
// robust path. BuildInfo reads "/arxael-version"; falls back to "0.0.0-dev" for a bare classpath.
val generateVersionResource by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/version")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("arxael-version").asFile.apply {
            parentFile.mkdirs()
            writeText(v)
        }
    }
}
// Put the generated file on the runtime classpath via processResources (it copies the task's output dir into
// the resources output) rather than declaring build/generated/version as a source ROOT. `resources.srcDir(task)`
// registers a source root that IDEs flag as "missing" whenever build/ is absent — a fresh checkout, after a
// clean, or in the window between a build-wipe and the next Gradle sync. `from(task)` has no such phantom root,
// so the IDE never flags it, while BuildInfo still reads "/arxael-version" off the classpath.
tasks.processResources { from(generateVersionResource) }

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Mutation testing — the real quality signal (does a test actually catch a broken change?).
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("dev.arxael.*"))
    targetTests.set(setOf("dev.arxael.*"))
    // Mutation testing measures test strength over PURE LOGIC. We exclude code where a mutation score is
    // meaningless/misleading and which is covered by integration/smoke tests instead:
    //   - generated kotlinx-serialization code ($$serializer) — not our logic;
    //   - the process entry point (Main) — wiring, exercised by smoke.sh;
    //   - the HTTP surface (InvokeServer) — loopback I/O, exercised live by smoke.sh + bench drivers;
    //   - the Gradle Tooling-API adapters (GradleAdapter, DepCacheWarmer) — they shell to a real toolchain.
    //     Their PURE parts (CulpritAttribution, ModuleGraphProbe.parse, AdaptiveSizer, ...) ARE mutation-tested.
    excludedClasses.set(setOf(
        "dev.arxael.protocol.*",
        "dev.arxael.Main*", "dev.arxael.MainKt*",
        "dev.arxael.BuildInfo*",                            // version-resource read (IO + fallback) — not logic

        "dev.arxael.invoke.InvokeServer*",
        "dev.arxael.adapter.gradle.*",
        // process-shelling adapters + their runner (ProcessBuilder I/O — covered by adapter unit tests):
        "dev.arxael.adapter.ExecAdapter*", "dev.arxael.adapter.CommandAdapter*", "dev.arxael.adapter.ProcExec*",
        "dev.arxael.merge.DepCacheWarmer*",
        // consolidator: filesystem I/O + a background thread loop (its pure copy logic is unit-tested):
        "dev.arxael.merge.DepCacheConsolidator*",
    ))
    // Kotlin auto-inserts `Intrinsics.checkNotNull(...)` null-guards on every platform-type access; PIT's
    // VoidMethodCallMutator "removes" them, but they're EQUIVALENT MUTANTS (the values are never null in
    // tests, so no test can observe their removal). Not mutating calls to Intrinsics strips that noise so the
    // score reflects real pure-logic test strength, not unkillable compiler scaffolding.
    avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
    threads.set(4)
    timestampedReports.set(false)
    outputFormats.set(setOf("HTML", "XML"))
}
