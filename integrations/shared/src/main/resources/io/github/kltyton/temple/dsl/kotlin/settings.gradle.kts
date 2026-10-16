import java.util.Base64
import java.util.Properties

pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

val identity = Properties().apply { file("gradle.properties").reader(Charsets.UTF_8).use { load(it) } }
rootProject.name = identity.getProperty("mod_name", "KltytonTemple")
val targets = file("targets").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
require(targets.isNotEmpty()) { "No target directories found" }
val wrapperRoots = listOf(file("gradle/wrapper")) + targets.map { it.resolve("gradle/wrapper") }
wrapperRoots.forEach { directory ->
    val encoded = directory.resolve("kltyton-wrapper.base64")
    val wrapper = directory.resolve("gradle-wrapper.jar")
    if (encoded.isFile && !wrapper.exists()) wrapper.writeBytes(Base64.getDecoder().decode(encoded.readText().trim()))
}
val packageName = identity.getProperty("mod_java_package", identity.getProperty("mod_group_id"))
require(packageName != null && Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(packageName) &&
        Regex("[a-z][a-z0-9_]{1,63}").matches(identity.getProperty("mod_id", ""))) { "Invalid mod identity" }
targets.forEach { directory ->
    val constants = directory.resolve("build/generated/sources/temple/${packageName.replace('.', '/')}/BuildInfo.java")
    constants.parentFile.mkdirs()
    constants.writeText("package $packageName;\n\npublic final class BuildInfo {\n" +
            "    public static final String MOD_ID = \"${identity.getProperty("mod_id")}\";\n" +
            "    private BuildInfo() {}\n}\n", Charsets.UTF_8)
}
