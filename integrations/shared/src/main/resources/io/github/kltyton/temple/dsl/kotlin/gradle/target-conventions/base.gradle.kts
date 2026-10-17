import groovy.json.JsonOutput
import java.util.Properties

val templeRoot = file("../..").canonicalFile
val outputVariant = providers.gradleProperty("lightspeed.outputVariant").orNull
if (outputVariant != null) {
    require(Regex("[a-z0-9][a-z0-9-]{0,47}").matches(outputVariant)) { "Invalid lightspeed.outputVariant" }
    layout.buildDirectory.set(layout.projectDirectory.dir("build/candidates/$outputVariant"))
    subprojects {
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("projects/$name"))
    }
}
val shared = Properties().apply { templeRoot.resolve("gradle.properties").reader(Charsets.UTF_8).use { load(it) } }
shared.forEach { key, value -> if (!project.hasProperty(key.toString())) extra[key.toString()] = value.toString() }
fun setting(name: String) = project.property(name).toString()
val modId = setting("mod_id")
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
evaluationDependsOn(":common")
val commonProject = project(":common")
dependencies { add("implementation", project(":common")) }
@Suppress("UNCHECKED_CAST")
val commonResources = commonProject.extra["templeResourceDirectories"] as List<File>
@Suppress("UNCHECKED_CAST")
val commonSources = commonProject.extra["templeSourceDirectories"] as List<File>
val resourceLayers = listOf(file("src/main/resources")) + commonResources
extra["templeDatagenExistingArgs"] = resourceLayers.filter { it.isDirectory }.asReversed()
        .flatMap { listOf("--existing", it.absolutePath) }.toTypedArray()
extensions.configure<SourceSetContainer> {
    named("main") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(listOf(resourceLayers.first(), file("src/generated/resources")))
    }
    named("test") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
        runtimeClasspath += commonProject.extensions.getByType<SourceSetContainer>().named("test").get().output
    }
}
extensions.getByType<SourceSetContainer>().named("main").get().output.classesDirs.setFrom(emptyList<File>())
extensions.getByType<SourceSetContainer>().named("test").get().output.classesDirs.setFrom(emptyList<File>())
tasks.named<JavaCompile>("compileJava") { enabled = false }
tasks.named<JavaCompile>("compileTestJava") { enabled = false }
tasks.named("testClasses") { dependsOn(commonProject.tasks.named("testClasses")) }
tasks.named<Jar>("sourcesJar") {
    from(commonSources, commonResources)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.named<ProcessResources>("processResources") {
    from(commonResources)
    dependsOn(commonProject.tasks.named("compileJava"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val raw = listOf("mod_id", "mod_name", "mod_license", "mod_version", "mod_authors", "mod_description",
            "mod_group_id", "minecraft_version", "minecraft_version_range", "loader_version", "loader_version_range",
            "java_version").associateWith { setting(it) } + mapOf(
            "fml_version_range" to (project.findProperty("fml_version_range")?.toString() ?: ""),
            "mod_java_package" to (project.findProperty("mod_java_package")?.toString() ?: setting("mod_group_id")))
    val escaped = raw.mapValues { JsonOutput.toJson(it.value).let { json -> json.substring(1, json.length - 1) } }
    inputs.properties(raw)
    filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) { expand(escaped) }
}
tasks.named<Jar>("jar") {
    from(commonProject.extensions.getByType<SourceSetContainer>().named("main").map { it.output })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(templeRoot.resolve("LICENSE")) { rename { "LICENSE_$modId" } }
}
repositories { mavenCentral() }
val localJars = fileTree("libs") { include("*.jar"); exclude("*-sources.jar", "*-javadoc.jar") }
dependencies { add(if (loader == "fabric" && javaVersion < 25) "modImplementation" else "implementation", localJars) }
apply(from = file("../../gradle/target-conventions/mixin-compilation.gradle"))
