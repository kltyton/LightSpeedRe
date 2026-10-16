import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.api.fabricapi.FabricApiExtension

buildscript {
    val loom_version: String by project
    val publish_plugin_version: String by project
    repositories { gradlePluginPortal(); maven("https://maven.fabricmc.net/"); mavenCentral() }
    dependencies {
        classpath("net.fabricmc:fabric-loom:$loom_version")
        classpath("me.modmuss50:mod-publish-plugin:$publish_plugin_version")
    }
}
plugins { java }
apply(plugin = "fabric-loom")
apply(plugin = "me.modmuss50.mod-publish-plugin")
apply(from = file("../../gradle/target-conventions/base.gradle.kts"))
val minecraft_version: String by project
val loader_version: String by project
val fabric_api_version: String by project
val mod_id: String by project
dependencies {
    add("minecraft", "com.mojang:minecraft:$minecraft_version")
    add("mappings", project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    add("modImplementation", "net.fabricmc:fabric-loader:$loader_version")
    add("modImplementation", "net.fabricmc.fabric-api:fabric-api:$fabric_api_version")
}
extensions.getByType<FabricApiExtension>().configureDataGeneration {
    modId.set(mod_id)
    createSourceSet.set(false)
    outputDirectory.set(file("src/generated/resources"))
    client.set(true)
}
apply(from = file("../../gradle/target-conventions/publish.gradle.kts"))
