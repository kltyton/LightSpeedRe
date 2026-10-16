import java.util.zip.ZipFile

apply(plugin = "maven-publish")
fun setting(name: String) = project.property(name).toString()
val loader = setting("loader")
val archiveTask = when {
    tasks.findByName("remapJar") is AbstractArchiveTask -> "remapJar"
    tasks.findByName("reobfJar") is AbstractArchiveTask -> "reobfJar"
    else -> "jar"
}
val productionTask = tasks.named<AbstractArchiveTask>(archiveTask)
val productionFile = productionTask.flatMap { it.archiveFile }
val releaseTask = if (tasks.findByName("reobfJar") != null) tasks.named("reobfJar") else productionTask
val artifactRecord = layout.buildDirectory.file("temple/artifact.properties")
val javaPackage = project.findProperty("mod_java_package")?.toString() ?: setting("mod_group_id")
val entryClass = project.findProperty("mod_entry_class")?.toString() ?: "$javaPackage.TempleCommon"
val verifyDistribution = tasks.register("verifyDistribution") {
    group = "verification"
    dependsOn(productionTask, releaseTask)
    inputs.file(productionFile)
    inputs.properties(mapOf("mod_id" to setting("mod_id"), "mod_java_package" to javaPackage,
            "mod_entry_class" to entryClass, "loader" to loader, "minecraft_version" to setting("minecraft_version")))
    outputs.file(artifactRecord)
    doLast {
        val artifact = productionFile.get().asFile
        val metadata = when (loader) {
            "fabric" -> "fabric.mod.json"
            "forge" -> "META-INF/mods.toml"
            else -> "META-INF/neoforge.mods.toml"
        }
        ZipFile(artifact).use { zip ->
            listOf(metadata, javaPackage.replace('.', '/') + "/BuildInfo.class", entryClass.replace('.', '/') + ".class").forEach { name ->
                require(zip.getEntry(name) != null) { "Missing $name in $artifact" }
            }
            val text = zip.getInputStream(zip.getEntry(metadata)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(!text.contains("\${")) { "Unexpanded metadata in $artifact" }
        }
        artifactRecord.get().asFile.apply {
            parentFile.mkdirs()
            writeText("artifact=${artifact.absolutePath.replace('\\', '/')}\n", Charsets.UTF_8)
        }
    }
}
tasks.named("build") { dependsOn(verifyDistribution) }
tasks.configureEach { if (name.startsWith("publish")) dependsOn(verifyDistribution) }
val platform = providers.gradleProperty("publish_platform").getOrElse("none")
require(platform in setOf("none", "modrinth", "curseforge", "both", "maven")) { "Unknown publish_platform: $platform" }
// The plugin instance belongs to the target script's classloader.
project.extensions.getByName("publishMods").withGroovyBuilder {
    setProperty("file", productionFile)
    setProperty("changelog", providers.environmentVariable("PUBLISH_CHANGELOG").getOrElse("See the project changelog."))
    setProperty("type", getProperty("STABLE"))
    (getProperty("modLoaders") as ListProperty<String>).add(loader)
    if (platform in setOf("curseforge", "both")) "curseforge" {
        setProperty("projectId", providers.environmentVariable("CURSEFORGE_PROJECT_ID")
                .orElse(providers.provider { project.findProperty("publish_curseforge_project_id")?.toString() }))
        setProperty("accessToken", providers.environmentVariable("CURSEFORGE_TOKEN"))
        (getProperty("minecraftVersions") as ListProperty<String>).add(setting("minecraft_version"))
        setProperty("client", true)
        setProperty("server", true)
    }
    if (platform in setOf("modrinth", "both")) "modrinth" {
        setProperty("projectId", providers.environmentVariable("MODRINTH_PROJECT_ID")
                .orElse(providers.provider { project.findProperty("publish_modrinth_project_id")?.toString() }))
        setProperty("accessToken", providers.environmentVariable("MODRINTH_TOKEN"))
        (getProperty("minecraftVersions") as ListProperty<String>).add(setting("minecraft_version"))
    }
}
tasks.named("publishMods") {
    dependsOn(verifyDistribution)
    doFirst { require(platform !in setOf("none", "maven")) { "Select -Ppublish_platform=modrinth, curseforge or both before uploading." } }
}
extensions.configure<PublishingExtension> {
    publications.create<MavenPublication>("mod") {
        artifact(productionFile) { builtBy(verifyDistribution) }
        artifact(tasks.named("sourcesJar"))
        groupId = project.group.toString()
        artifactId = project.extensions.getByType<BasePluginExtension>().archivesName.get()
        version = project.version.toString()
    }
    val address = providers.environmentVariable("TEMPLE_MAVEN_URL")
    if (address.isPresent) repositories.maven {
        name = "remote"
        url = uri(address.get())
        credentials {
            username = providers.environmentVariable("MAVEN_USERNAME").orNull
            password = providers.environmentVariable("MAVEN_PASSWORD").orNull
        }
    }
}
tasks.named("publish") {
    dependsOn(verifyDistribution)
    doFirst { require(providers.environmentVariable("TEMPLE_MAVEN_URL").isPresent) { "Set TEMPLE_MAVEN_URL before Maven publishing." } }
}
tasks.withType<GenerateModuleMetadata>().configureEach { isEnabled = false }
