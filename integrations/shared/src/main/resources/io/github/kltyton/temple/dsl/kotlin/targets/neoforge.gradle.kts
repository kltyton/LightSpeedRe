import net.neoforged.moddevgradle.dsl.NeoForgeExtension

plugins {
    id("net.neoforged.moddev")
    id("me.modmuss50.mod-publish-plugin")
}
apply(from = file("../../gradle/target-conventions/base.gradle.kts"))
val loader_version: String by project
val mod_id: String by project
val java_version: String by project
extensions.configure<NeoForgeExtension> {
    version = loader_version
    runs {
        create("client") { client() }
        create("server") { server(); programArgument("--nogui") }
        create("data") {
            if (java_version.toInt() >= 25) clientData() else data()
            programArguments.addAll("--mod", mod_id, "--all", "--output", file("src/generated/resources").absolutePath,
                    *(project.extra["templeDatagenExistingArgs"] as Array<String>))
        }
    }
    mods.create(mod_id) {
        sourceSet(project.extensions.getByType<SourceSetContainer>().getByName("main"))
        sourceSet(project(":common").extensions.getByType<SourceSetContainer>().getByName("main"))
    }
}
apply(from = file("../../gradle/target-conventions/publish.gradle.kts"))
