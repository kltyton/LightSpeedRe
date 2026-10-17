import java.util.Properties

pluginManagement {
    val versions = java.util.Properties().apply { file("gradle.properties").reader(Charsets.UTF_8).use { load(it) } }
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.minecraftforge.net/")
        mavenCentral()
    }
    plugins {
        versions.getProperty("loom_version")?.let { id("net.fabricmc.fabric-loom") version it }
        versions.getProperty("moddev_version")?.let {
            id("net.neoforged.moddev") version it
            id("net.neoforged.moddev.legacyforge") version it
        }
        versions.getProperty("forgegradle_version")?.let { id("net.minecraftforge.gradle") version it }
        id("me.modmuss50.mod-publish-plugin") version versions.getProperty("publish_plugin_version")
    }
}
val identity = Properties().apply { file("../../gradle.properties").reader(Charsets.UTF_8).use { load(it) } }
rootProject.name = identity.getProperty("mod_name") + "-" + rootDir.name

include(":common")
val selection = file("../../.temple/active-target")
val activeTarget = if (selection.isFile) selection.readText(Charsets.UTF_8).trim() else identity.getProperty("temple_default_target")
val active = activeTarget == rootDir.name
val commonContext = if (active) file("../../common") else file("../../common/.gradle/contexts/${rootDir.name}")
commonContext.mkdirs()
project(":common").projectDir = commonContext
project(":common").buildFileName = if (active) "build.gradle.kts" else "../../../build.gradle.kts"
