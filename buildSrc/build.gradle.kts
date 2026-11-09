import java.util.Properties

plugins {
    `kotlin-dsl`
}

repositories {
    maven("https://maven.fabricmc.net/")
    gradlePluginPortal()
    mavenCentral()
}

val versions = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}

dependencies {
    implementation("net.fabricmc:fabric-loom:${versions.getProperty("loom_version")}")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}
