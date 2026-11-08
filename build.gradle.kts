import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.12"
    kotlin("jvm") version "2.4.21"
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = property("archives_base_name") as String
}

repositories {
    maven("https://maven.shedaniel.me/")
    maven("https://jitpack.io")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    api("me.shedaniel.cloth:cloth-config-fabric:${property("cloth_config_version")}") {
        exclude(group = "net.fabricmc.fabric-api")
    }

    // MixinSquared API for the C2ME-compat MixinCanceller; provided at runtime by C2ME, so compileOnly.
    compileOnly("com.github.bawnorton.mixinsquared:mixinsquared-fabric:0.3.3")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    // Minecraft 26.2 is unobfuscated: jar and sourcesJar are the distributable artifacts.
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.test {
    useJUnitPlatform()
}

loom {
    accessWidenerPath = file("src/main/resources/vanadium.accesswidener")
    runs {
        named("server") {
            runDir = providers.gradleProperty("vanadiumRunDir").getOrElse("run")
        }
    }
}

val testOutput = sourceSets.test.map { it.output }

/** Test-only mod jars for live checks; never part of the release jar. */
fun registerTestMod(name: String, summary: String, packageDir: String, modJson: String, extra: List<String> = listOf()) =
    tasks.register<Jar>(name) {
        group = "verification"
        description = summary
        dependsOn("testClasses")
        archiveFileName = "${modJson.substringBefore('.')}.jar"
        destinationDirectory = layout.buildDirectory.dir("test-mods")
        from(testOutput) { include("com/synsenetwork/vanadium/$packageDir/**") }
        from("src/test/resources/$modJson") { rename { "fabric.mod.json" } }
        extra.forEach { from("src/test/resources/$it") }
    }

registerTestMod("blockUpdateChecksJar", "Packages the live block-update and dirty-chunk regression checks",
    "integration", "block-update-checks.fabric.mod.json")
registerTestMod("featureBenchJar", "Packages isolated, paired feature benchmarks",
    "featurebench", "feature-bench.fabric.mod.json", listOf("feature-bench.mixins.json"))

// Runtime mods for the dev server (C2ME, required by fabric.mod.json) are not managed by Gradle: Loom
// cannot unpack C2ME's nested mods onto the dev classpath. Put the release jars into run/mods instead.
fun registerBench(name: String, summary: String, mainClass: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = summary
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    this.mainClass = "com.synsenetwork.vanadium.bench.$mainClass"
    jvmArgs("-XX:+UseParallelGC")
}

registerBench("benchScheduler", "Runs the scheduler micro-benchmark", "SchedulerBench")
registerBench("benchWorkerPool", "Compares small, large, balanced and uneven worker waves", "WorkerPoolBench")
registerBench("benchAreaMap", "Measures tracking-area moves, teleports and radius changes", "AreaMapBench")

tasks.jar {
    val archiveBaseName = base.archivesName.get()
    from("LICENSE") { rename { "${it}_$archiveBaseName" } }
}
