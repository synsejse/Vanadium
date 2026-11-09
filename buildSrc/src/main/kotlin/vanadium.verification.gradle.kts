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

val runtimeMod = layout.projectDirectory.file(".vanadium/mods/c2me-fabric-mc${property("minecraft_version")}-${property("c2me_version")}.jar")
val runtimeUrl = providers.gradleProperty("c2me_url").get()
val runtimeHash = providers.gradleProperty("c2me_sha512").get()

val prepareRuntimeMods = tasks.register<PrepareRuntimeMod>("prepareRuntimeMods") {
    group = "fabric"
    description = "Downloads and verifies pinned C2ME without adding its nested mods to the dev classpath"
    downloadUrl.set(runtimeUrl)
    sha512.set(runtimeHash)
    destination.set(runtimeMod)
}

val configurationCache = objects.newInstance(BuildFeaturesAccess::class.java).features.configurationCache.requested.getOrElse(false)
liveRuns.forEach { (fixture, directory) ->
    tasks.named<JavaExec>("run${fixture.runName.replaceFirstChar(Char::uppercase)}") {
        group = "verification"
        dependsOn(prepareRuntimeMods, fixture.jarTask)
        timeout.set(Duration.ofMinutes(fixture.timeoutMinutes))
        args("nogui")
        doFirst(PrepareLiveRun(directory.asFile, configurationCache, layout.projectDirectory.file(".vanadium/eula.txt").asFile,
            runtimeMod.asFile, layout.buildDirectory.file("test-mods/${fixture.jarName}").get().asFile, fixture.runName))
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
