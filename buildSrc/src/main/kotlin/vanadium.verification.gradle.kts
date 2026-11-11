import com.synsenetwork.vanadium.gradle.LiveFixture
import com.synsenetwork.vanadium.gradle.BuildFeaturesAccess
import com.synsenetwork.vanadium.gradle.PrepareRuntimeMod
import com.synsenetwork.vanadium.gradle.PrepareLiveRun
import com.synsenetwork.vanadium.gradle.VerifyLiveRun
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import java.time.Duration
import java.util.UUID

val sourceSets = extensions.getByType<SourceSetContainer>()
val loom = extensions.getByType<LoomGradleExtensionAPI>()

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(tasks.named<Jar>("jar"))
    val release = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(release)
    systemProperty("vanadium.releaseJar", release.get().asFile.absolutePath)
    systemProperty("vanadium.expectedVersion", project.version.toString())
    systemProperty("vanadium.expectedMinecraft", project.property("minecraft_version").toString())
}

val testOutput = sourceSets.named("test").map { it.output }
val liveRuns = LiveFixture.entries.associateWith {
    layout.projectDirectory.dir(".vanadium/runs/${it.runName}-${UUID.randomUUID()}")
}

loom.runs {
    liveRuns.forEach { (fixture, directory) ->
        create(fixture.runName) {
            server()
            runDirectory.set(directory)
            generateRunConfig.set(false)
            systemProperties.put("vanadium.live.mode", fixture.runName)
            systemProperties.put("vanadium.live.jfr", providers.gradleProperty("live.jfr").getOrElse("false"))
            jvmArguments.addAll("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
            if (fixture == LiveFixture.FEATURE_BENCH) {
                listOf("workers" to "4", "rounds" to "12", "scale" to "1", "filter" to "", "jfr" to "true").forEach { (key, default) ->
                    systemProperties.put("vanadium.bench.$key", providers.gradleProperty("bench.$key").getOrElse(default))
                }
            }
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
        from(testOutput) { include("com/synsenetwork/vanadium/${fixture.packageDirectory}/**", "com/synsenetwork/vanadium/live/**") }
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

val configurationCache = objects.newInstance(BuildFeaturesAccess::class.java).features.configurationCache.requested.getOrElse(false)
liveRuns.forEach { (fixture, directory) ->
    tasks.named<JavaExec>("run${fixture.runName.replaceFirstChar(Char::uppercase)}") {
        group = "verification"
        dependsOn(runtimeMods, fixture.jarTask)
        timeout.set(Duration.ofMinutes(fixture.timeoutMinutes))
        args("nogui")
        doFirst(PrepareLiveRun(directory.asFile, configurationCache, layout.projectDirectory.file(".vanadium/eula.txt").asFile,
            runtimeMods.map { it.get().destination.get().asFile }, layout.buildDirectory.file("test-mods/${fixture.jarName}").get().asFile, fixture.runName))
        doLast(VerifyLiveRun(fixture.runName, providers.gradleProperty("live.jfr").getOrElse("false").toBoolean()))
    }
}

fun registerBench(name: String, summary: String, mainClass: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = summary
    dependsOn("testClasses")
    classpath = sourceSets.getByName("test").runtimeClasspath
    this.mainClass = "com.synsenetwork.vanadium.bench.$mainClass"
    jvmArgs("-XX:+UseParallelGC")
}

registerBench("benchScheduler", "Runs the scheduler micro-benchmark", "SchedulerBench")
registerBench("benchWorkerPool", "Compares small, large, balanced and uneven worker waves", "WorkerPoolBench")
registerBench("benchAreaMap", "Measures tracking-area moves, teleports and radius changes", "AreaMapBench")
