import net.fabricmc.loom.api.fabricapi.FabricApiExtension

plugins {
    id("net.fabricmc.fabric-loom")
    id("me.modmuss50.mod-publish-plugin")
}
apply(from = file("../../gradle/target-conventions/base.gradle.kts"))
val minecraft_version: String by project
val loader_version: String by project
val fabric_api_version: String by project
val mod_id: String by project
dependencies {
    add("minecraft", "com.mojang:minecraft:$minecraft_version")
    add("implementation", "net.fabricmc:fabric-loader:$loader_version")
    add("implementation", "net.fabricmc.fabric-api:fabric-api:$fabric_api_version")
}
extensions.getByType<FabricApiExtension>().configureDataGeneration {
    modId.set(mod_id)
    createSourceSet.set(false)
    outputDirectory.set(file("src/generated/resources"))
    client.set(true)
}
apply(from = file("../../gradle/target-conventions/publish.gradle.kts"))
