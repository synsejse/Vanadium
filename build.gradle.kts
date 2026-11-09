import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
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

    include(implementation("org.jctools:jctools-core:${property("jctools_version")}")!!)

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

loom {
    accessWidenerPath = file("src/main/resources/vanadium.accesswidener")
    runs {
        named("server") {
            runDirectory.set(file(providers.gradleProperty("vanadiumRunDir").getOrElse("run")))
        }
    }
}

apply(plugin = "vanadium.verification")

tasks.jar {
    val archiveBaseName = base.archivesName.get()
    from("LICENSE") { rename { "${it}_$archiveBaseName" } }
}
