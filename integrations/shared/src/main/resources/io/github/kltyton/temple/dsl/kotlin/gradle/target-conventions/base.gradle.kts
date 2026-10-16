import groovy.json.JsonOutput
import java.util.Properties

val templeRoot = file("../..").canonicalFile
val shared = Properties().apply { templeRoot.resolve("gradle.properties").reader(Charsets.UTF_8).use { load(it) } }
shared.forEach { key, value -> if (!project.hasProperty(key.toString())) extra[key.toString()] = value.toString() }
fun setting(name: String) = project.property(name).toString()
val modId = setting("mod_id")
val javaPackage = project.findProperty("mod_java_package")?.toString() ?: setting("mod_group_id")
require(Regex("[a-z][a-z0-9_]{1,63}").matches(modId) &&
        Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(setting("mod_group_id"))) { "Invalid mod identity" }
group = setting("mod_group_id")
version = setting("mod_version")
val loader = setting("loader")
val javaVersion = setting("java_version").toInt()
extensions.configure<BasePluginExtension> { archivesName.set("$modId-$loader-${setting("minecraft_version")}") }
extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
}
val layers = setting("shared_sources").split(',').map { name ->
    val directory = templeRoot.resolve(name.trim()).canonicalFile
    require(directory != templeRoot && directory.toPath().startsWith(templeRoot.toPath()) &&
            !directory.toPath().startsWith(templeRoot.resolve("targets").toPath())) { "Invalid shared source layer: $name" }
    directory
}
val resourceLayers = listOf(file("src/main/resources")) + layers.reversed().map { it.resolve("src/main/resources") }
extra["templeDatagenExistingArgs"] = resourceLayers.filter { it.isDirectory }.asReversed()
        .flatMap { listOf("--existing", it.absolutePath) }.toTypedArray()
extensions.configure<SourceSetContainer> {
    named("main") {
        java.srcDirs(layers.map { it.resolve("src/main/java") })
        resources.setSrcDirs(listOf(resourceLayers.first(), file("src/generated/resources")) + resourceLayers.drop(1))
    }
    named("test") {
        java.srcDirs(layers.map { it.resolve("src/test/java") })
        resources.srcDirs(layers.map { it.resolve("src/test/resources") })
    }
}
val constantsDirectory = layout.buildDirectory.dir("generated/sources/temple")
val generateConstants = tasks.register("generateTempleConstants") {
    inputs.properties(mapOf("mod_id" to modId, "mod_java_package" to javaPackage))
    outputs.dir(constantsDirectory)
    doLast {
        val destination = constantsDirectory.get().file(javaPackage.replace('.', '/') + "/BuildInfo.java").asFile
        destination.parentFile.mkdirs()
        destination.writeText("package $javaPackage;\n\npublic final class BuildInfo {\n" +
                "    public static final String MOD_ID = ${JsonOutput.toJson(modId)};\n" +
                "    private BuildInfo() {}\n}\n", Charsets.UTF_8)
    }
}
extensions.getByType<SourceSetContainer>().named("main") { java.srcDir(constantsDirectory) }
tasks.named("compileJava") { dependsOn(generateConstants) }
tasks.named<Jar>("sourcesJar") { dependsOn(generateConstants); duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val raw = listOf("mod_id", "mod_name", "mod_license", "mod_version", "mod_authors", "mod_description",
            "mod_group_id", "minecraft_version", "minecraft_version_range", "loader_version", "loader_version_range",
            "java_version").associateWith { setting(it) } + ("fml_version_range" to (project.findProperty("fml_version_range")?.toString() ?: ""))
    val escaped = raw.mapValues { JsonOutput.toJson(it.value).let { json -> json.substring(1, json.length - 1) } }
    inputs.properties(raw)
    filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) { expand(escaped) }
}
tasks.named<Jar>("jar") { from(templeRoot.resolve("LICENSE")) { rename { "LICENSE_$modId" } } }
repositories { mavenCentral() }
val localJars = fileTree("libs") { include("*.jar"); exclude("*-sources.jar", "*-javadoc.jar") }
dependencies { add(if (loader == "fabric" && javaVersion < 25) "modImplementation" else "implementation", localJars) }
