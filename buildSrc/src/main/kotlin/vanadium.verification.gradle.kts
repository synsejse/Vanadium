import com.synsenetwork.vanadium.gradle.BuildFeaturesAccess
import com.synsenetwork.vanadium.gradle.CheckModDependencies
import com.synsenetwork.vanadium.gradle.InstallRuntimeMods
import com.synsenetwork.vanadium.gradle.LiveFixture
import com.synsenetwork.vanadium.gradle.LiveLaunch
import com.synsenetwork.vanadium.gradle.PrepareLiveRun
import com.synsenetwork.vanadium.gradle.PrepareRuntimeMod
import com.synsenetwork.vanadium.gradle.VerifyLiveRun
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import java.time.Duration
import java.util.UUID

val sourceSets = extensions.getByType<SourceSetContainer>()
val loom = extensions.getByType<LoomGradleExtensionAPI>()
val javaToolchain = extensions.getByType<JavaPluginExtension>().toolchain

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(tasks.named<Jar>("jar"))
    val release = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(release)
    systemProperty("vanadium.releaseJar", release.get().asFile.absolutePath)
    systemProperty("vanadium.expectedVersion", project.version.toString())
    systemProperty("vanadium.expectedMinecraft", project.property("minecraft_version").toString())
}

// The manifest's dependency minima are hand-written; keep them on the pins the build compiles and tests against.
val checkModDependencies = tasks.register<CheckModDependencies>("checkModDependencies") {
    group = "verification"
    description = "Checks that fabric.mod.json dependency minima match the pinned versions"
    manifest.set(layout.projectDirectory.file("src/main/resources/fabric.mod.json"))
    fun pin(name: String) = providers.gradleProperty(name).map { ">=$it" }
    expected.put("fabricloader", pin("loader_version"))
    expected.put("fabric-language-kotlin", pin("fabric_kotlin_version"))
    // The Fabric API version's `+26.3` is build metadata, which version ranges ignore.
    expected.put("fabric-api", providers.gradleProperty("fabric_version").map { ">=${it.substringBefore('+')}" })
    expected.put("minecraft", providers.gradleProperty("minecraft_version"))
    expected.put("java", javaToolchain.languageVersion.map { ">=${it.asInt()}" })
    expected.put("cloth-config2", pin("cloth_config_version"))
    expected.put("c2me", pin("c2me_version"))
    expected.put("lithium", pin("lithium_version"))
    marker.set(layout.buildDirectory.file("tmp/checkModDependencies/checked.txt"))
}
tasks.named("check") { dependsOn(checkModDependencies) }

val testOutput = sourceSets.named("test").map { it.output }
// Loom finalises run directories during configuration, so each is a fresh path chosen now. Everything else a
// fixture launches with is resolved once, and only when its own run reads it.
class LiveRun(val directory: Directory, val launch: Provider<LiveLaunch>)
val liveRuns = LiveFixture.entries.associateWith { fixture ->
    val launch = lazy { LiveLaunch.resolve(fixture) { providers.gradleProperty(it).orNull } }
    LiveRun(layout.projectDirectory.dir(".vanadium/runs/${fixture.runName}-${UUID.randomUUID()}"), providers.provider { launch.value })
}

loom.runs {
    liveRuns.forEach { (fixture, run) ->
        create(fixture.runName) {
            server()
            runDirectory.set(run.directory)
            generateRunConfig.set(false)
            systemProperties.putAll(run.launch.map { it.systemProperties })
            jvmArguments.addAll(run.launch.map { it.jvmArgs })
        }
    }
}

liveRuns.keys.forEach { fixture ->
    tasks.register<Jar>(fixture.jarTask) {
        group = "verification"
        description = "Packages the ${fixture.runName} live fixture"
        dependsOn("testClasses")
        archiveFileName = fixture.jarName
        destinationDirectory = layout.buildDirectory.dir("test-mods")
        from(testOutput) {
            include("com/synsenetwork/vanadium/${fixture.packageDirectory}/**", "com/synsenetwork/vanadium/live/**")
            // Unit tests of fixture helpers stay in the test task.
            exclude("**/*Test.class", "**/*Test$*.class")
        }
        from("src/test/resources/${fixture.descriptor}") { rename { "fabric.mod.json" } }
        fixture.resources.forEach { from("src/test/resources/$it") }
    }
}

fun registerRuntimeMod(name: String, fileName: String) = tasks.register<PrepareRuntimeMod>("prepare${name.replaceFirstChar(Char::uppercase)}") {
    group = "fabric"
    description = "Downloads and verifies the pinned $name release jar"
    downloadUrl.set(providers.gradleProperty("${name}_url"))
    sha512.set(providers.gradleProperty("${name}_sha512"))
    destination.set(layout.projectDirectory.file(".vanadium/mods/$fileName"))
}

// Release jars only: C2ME's nested mods must not enter Loom's development classpath.
val minecraftVersion = property("minecraft_version")
val prepareC2me = registerRuntimeMod("c2me", "c2me-fabric-mc$minecraftVersion-${property("c2me_version")}.jar")
val prepareLithium = registerRuntimeMod("lithium", "lithium-fabric-${property("lithium_version")}+mc$minecraftVersion.jar")
val runtimeMods = listOf(prepareC2me, prepareLithium)

// Lithium is required: Vanadium compiles against the verified release jar and tests run with it.
val lithiumJar = files(prepareLithium.flatMap { it.destination }).builtBy(prepareLithium)
dependencies {
    "compileOnly"(lithiumJar)
    "testCompileOnly"(lithiumJar)
}
tasks.named<Test>("test") {
    classpath += lithiumJar
}

val prepareRuntimeMods = tasks.register("prepareRuntimeMods") {
    group = "fabric"
    description = "Downloads and verifies pinned C2ME and Lithium without adding them to the dev classpath"
    dependsOn(prepareC2me, prepareLithium)
}

val setupRuntimeMods = tasks.register<InstallRuntimeMods>("setupRuntimeMods") {
    group = "fabric"
    description = "Installs verified C2ME and Lithium in the interactive run directory"
    dependsOn(prepareRuntimeMods)
    this.runtimeMods.from(prepareC2me.flatMap { it.destination }, prepareLithium.flatMap { it.destination })
    modsDirectory.set(loom.runs.named("client").flatMap { it.runDirectory }.map { it.dir("mods") })
}
listOf("runClient", "runServer").forEach { name ->
    tasks.named(name) { dependsOn(setupRuntimeMods) }
}

val configurationCache = objects.newInstance(BuildFeaturesAccess::class.java).features.configurationCache.requested.getOrElse(false)
liveRuns.forEach { (fixture, run) ->
    tasks.named<JavaExec>(fixture.runTask) {
        group = fixture.group
        fixture.description?.let { description = it }
        dependsOn(runtimeMods, fixture.jarTask)
        timeout.set(run.launch.map { Duration.ofMinutes(it.timeoutMinutes) })
        args("nogui")
        doFirst(PrepareLiveRun(run.directory.asFile, configurationCache, layout.projectDirectory.file(".vanadium/eula.txt").asFile,
            runtimeMods.map { it.get().destination.get().asFile }, layout.buildDirectory.file("test-mods/${fixture.jarName}").get().asFile,
            run.launch.map { it.settings }))
        doLast(VerifyLiveRun(run.launch))
    }
}

fun registerBench(name: String, summary: String, mainClass: String) = tasks.register<JavaExec>(name) {
    group = "benchmark"
    description = summary
    dependsOn("testClasses")
    classpath = sourceSets.getByName("test").runtimeClasspath
    this.mainClass = "com.synsenetwork.vanadium.bench.$mainClass"
    jvmArgs("-XX:+UseParallelGC")
}

registerBench("benchScheduler", "Runs the scheduler micro-benchmark", "SchedulerBench")
registerBench("benchWorkerPool", "Compares small, large, balanced and uneven worker waves", "WorkerPoolBench")
registerBench("benchAreaMap", "Measures tracking-area moves, teleports and radius changes", "AreaMapBench")
registerBench("benchSectionIndex", "Compares entity section box queries on the column index and the previous two-map layout", "SectionIndexBench")
registerBench("benchCrowdQueries", "Compares push-sized queries in crowded entity sections: full scan against cells, with moving entities", "CrowdQueryBench")
