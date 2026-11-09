import java.net.URI
import java.net.ServerSocket
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.io.File
import java.util.UUID
import org.gradle.api.configuration.BuildFeatures
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
    // Minecraft 26.3 is unobfuscated: jar and sourcesJar are the distributable artifacts.
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
    dependsOn(tasks.jar)
    val release = tasks.jar.flatMap { it.archiveFile }
    inputs.file(release)
    systemProperty("vanadium.releaseJar", release.get().asFile.absolutePath)
    systemProperty("vanadium.expectedVersion", project.version.toString())
    systemProperty("vanadium.expectedMinecraft", project.property("minecraft_version").toString())
}

loom {
    accessWidenerPath = file("src/main/resources/vanadium.accesswidener")
    runs {
        named("server") {
            runDirectory.set(file(providers.gradleProperty("vanadiumRunDir").getOrElse("run")))
        }
    }
}

val testOutput = sourceSets.test.map { it.output }
val liveRuns = listOf("integration", "featureBench").associateWith {
    layout.projectDirectory.dir(".vanadium/runs/$it-${UUID.randomUUID()}")
}

loom.runs {
    liveRuns.forEach { (mode, directory) ->
        create(mode) {
            server()
            runDirectory.set(directory)
            generateRunConfig.set(false)
            systemProperties.put("vanadium.live.mode", mode)
            systemProperties.put("vanadium.live.jfr", providers.gradleProperty("live.jfr").getOrElse("false"))
            jvmArguments.addAll("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
            if (mode == "featureBench") {
                listOf("workers" to "4", "rounds" to "12", "scale" to "1", "filter" to "", "jfr" to "true").forEach { (key, default) ->
                    systemProperties.put("vanadium.bench.$key", providers.gradleProperty("bench.$key").getOrElse(default))
                }
            }
        }
    }
}

/** Test-only mod jars for live checks; never part of the release jar. */
fun registerTestMod(name: String, summary: String, packageDir: String, modJson: String, extra: List<String> = listOf()) =
    tasks.register<Jar>(name) {
        group = "verification"
        description = summary
        dependsOn("testClasses")
        archiveFileName = "${modJson.substringBefore('.')}.jar"
        destinationDirectory = layout.buildDirectory.dir("test-mods")
        from(testOutput) { include("com/synsenetwork/vanadium/$packageDir/**", "com/synsenetwork/vanadium/live/**") }
        from("src/test/resources/$modJson") { rename { "fabric.mod.json" } }
        extra.forEach { from("src/test/resources/$it") }
    }

registerTestMod("integrationChecksJar", "Packages live Fabric server integration tests",
    "integration", "integration-checks.fabric.mod.json")
registerTestMod("featureBenchJar", "Packages isolated, paired feature benchmarks",
    "featurebench", "feature-bench.fabric.mod.json", listOf("feature-bench.mixins.json"))

val runtimeMod = layout.projectDirectory.file(".vanadium/mods/c2me-fabric-mc${property("minecraft_version")}-${property("c2me_version")}.jar")
val runtimeUrl = providers.gradleProperty("c2me_url").get()
val runtimeHash = providers.gradleProperty("c2me_sha512").get()

abstract class PrepareRuntimeMod : DefaultTask() {
    @get:Input abstract val downloadUrl: Property<String>
    @get:Input abstract val sha512: Property<String>
    @get:OutputFile abstract val destination: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    private fun hash(path: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-512")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun prepare() {
        val target = destination.get().asFile.toPath()
        if (!Files.exists(target)) {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, "c2me-", ".download")
            try {
                val connection = URI(downloadUrl.get()).toURL().openConnection().apply {
                    connectTimeout = 60_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Vanadium-development")
                }
                connection.getInputStream().use { input -> Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING) }
                check(hash(temporary) == sha512.get()) { "C2ME checksum mismatch" }
                Files.move(temporary, target)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        check(hash(target) == sha512.get()) { "C2ME checksum mismatch: $target" }
        logger.lifecycle("Verified runtime mod: $target")
    }
}

val prepareRuntimeMods = tasks.register<PrepareRuntimeMod>("prepareRuntimeMods") {
    group = "fabric"
    description = "Downloads and verifies pinned C2ME without adding its nested mods to the dev classpath"
    downloadUrl.set(runtimeUrl)
    sha512.set(runtimeHash)
    destination.set(runtimeMod)
}

class PrepareLiveRun(
    private val run: File,
    private val configurationCache: Boolean,
    private val eula: File,
    private val runtimeMod: File,
    private val fixture: File,
    private val mode: String,
) : Action<Task> {
    override fun execute(task: Task) {
        check(!configurationCache) { "Live runs require --no-configuration-cache for fresh launch directories" }
        check(eula.isFile && eula.readLines().any { it.trim() == "eula=true" }) {
            "Accept the Minecraft EULA in .vanadium/eula.txt before running live tests"
        }
        check(!run.exists()) { "Refusing to reuse a live-test world: $run" }
        run.resolve("mods").mkdirs()
        run.resolve("config").mkdirs()
        runtimeMod.copyTo(run.resolve("mods/${runtimeMod.name}"))
        fixture.copyTo(run.resolve("mods/${fixture.name}"))
        eula.copyTo(run.resolve("eula.txt"))
        val port = ServerSocket(0).use { it.localPort }
        run.resolve("server.properties").writeText("""
            server-ip=127.0.0.1
            server-port=$port
            online-mode=true
            level-seed=12345
            level-type=minecraft:flat
            generator-settings={"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}],"biome":"minecraft:plains","structure_overrides":[]}
            max-tick-time=${if (mode == "featureBench") -1 else 60000}
            view-distance=4
            simulation-distance=4
            max-players=1
            spawn-protection=0
            pause-when-empty-seconds=-1
        """.trimIndent() + "\n")
        run.resolve("config/vanadium.toml").writeText("workers = 4\ncellSize = 2\n")
        run.resolve("config/c2me.toml").writeText("version = 3\nglobalExecutorParallelism = 4\n")
        task.logger.lifecycle("Isolated live run: $run")
    }
}

class VerifyLiveRun(private val mode: String, private val jfr: Boolean) : Action<Task> {
    override fun execute(task: Task) {
        val run = (task as JavaExec).workingDir
        val marker = run.resolve("vanadium-live-passed.txt")
        check(marker.isFile && marker.readText().trim() == mode) {
            "Live checks/tick/save/shutdown did not complete; inspect $run"
        }
        check(!jfr || run.resolve("live.jfr").isFile) { "Missing JFR recording in $run" }
        val log = run.resolve("logs/latest.log")
        check(log.isFile) { "Missing server log in $run" }
        check(log.useLines { lines -> lines.none { "/ERROR]" in it || " ERROR]" in it || "Exception in server tick loop" in it } }) {
            "Server logged errors; inspect $log"
        }
        task.logger.lifecycle("$mode passed; evidence retained in $run")
    }
}

abstract class BuildFeaturesAccess {
    @get:javax.inject.Inject abstract val features: BuildFeatures
}

val configurationCache = objects.newInstance(BuildFeaturesAccess::class.java).features.configurationCache.requested.getOrElse(false)
liveRuns.forEach { (mode, directory) ->
    val testJar = if (mode == "integration") "integration-checks.jar" else "feature-bench.jar"
    tasks.named<JavaExec>("run${mode.replaceFirstChar(Char::uppercase)}") {
        group = "verification"
        dependsOn(prepareRuntimeMods, if (mode == "integration") "integrationChecksJar" else "featureBenchJar")
        timeout.set(Duration.ofMinutes(if (mode == "integration") 5 else 20))
        args("nogui")
        doFirst(PrepareLiveRun(directory.asFile, configurationCache, layout.projectDirectory.file(".vanadium/eula.txt").asFile,
            runtimeMod.asFile, layout.buildDirectory.file("test-mods/$testJar").get().asFile, mode))
        doLast(VerifyLiveRun(mode, providers.gradleProperty("live.jfr").getOrElse("false").toBoolean()))
    }
}

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
