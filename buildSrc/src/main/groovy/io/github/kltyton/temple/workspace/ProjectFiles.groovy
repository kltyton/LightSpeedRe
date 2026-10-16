package io.github.kltyton.temple.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

final class ProjectFiles {
    static final Set<String> EXCLUDED = ['.git', '.idea', '.gradle', '.temple', '.omo', '.codex', '.agents',
                                         '.settings', 'build', 'run', 'runs', 'out', '__pycache__', 'node_modules', 'libs'] as Set
    static final Set<String> PRIVATE_FILES = ['AGENT.md', 'AGENTS.md', 'AGENTS.override.md', 'CLAUDE.md',
                                              'GEMINI.md', 'NEXT_AGENT.md', 'temple.local.properties', '.project', '.classpath'] as Set
    static final Set<String> PROJECT_ROOTS = ['agent', 'LICENSES', 'buildSrc', 'common', 'versions', 'loaders', 'targets',
                                              'gradle', 'integrations', 'docs', '.github'] as Set
    static final Set<String> PROJECT_ROOT_FILES = ['build.gradle', 'settings.gradle', 'gradle.properties',
                                                    'build.gradle.kts', 'settings.gradle.kts',
                                                   'gradlew', 'gradlew.bat', 'LICENSE', 'README.md', 'README.en.md',
                                                   '.gitignore', '.gitattributes'] as Set

    static Properties read(File file) {
        Properties values = new Properties()
        file.withReader('UTF-8') { values.load(it) }
        values
    }

    static void write(File file, Properties values) {
        file.parentFile.mkdirs()
        file.withWriter('UTF-8') { values.store(it, null) }
    }

    static List<TargetDefinition> targets(File root) {
        File directory = new File(root, 'targets')
        if (!directory.isDirectory()) { throw new IllegalArgumentException("Missing targets directory: ${directory}") }
        List<TargetDefinition> result = []
        directory.listFiles().findAll { it.isDirectory() }.sort { it.name }.each { File target ->
            Properties values = read(new File(target, 'gradle.properties'))
            String loader = values.getProperty('loader')
            String minecraft = values.getProperty('minecraft_version')
            if (!(loader in ['forge', 'fabric', 'neoforge']) || !(minecraft ==~ /[0-9]+(\.[0-9]+)*/) ||
                    target.name != "${loader}-${minecraft}") {
                throw new IllegalArgumentException("Invalid target identity: ${target}")
            }
            ['java_version', 'gradle_java_version'].each { key ->
                if (!(values.getProperty(key, '') ==~ /[0-9]+/) || values.getProperty(key).toInteger() < 8) {
                    throw new IllegalArgumentException("Invalid ${key}: ${target}")
                }
            }
            ['build', 'settings'].each { name ->
                int count = ["${name}.gradle", "${name}.gradle.kts"].count { new File(target, it).isFile() }
                if (count != 1) { throw new IllegalArgumentException("Expected one ${name}.gradle or ${name}.gradle.kts: ${target}") }
            }
            ['gradle/wrapper/gradle-wrapper.properties'].each { name ->
                if (!new File(target, name).isFile()) { throw new IllegalArgumentException("Missing ${name}: ${target}") }
            }
            sourceLayers(root, values)
            result.add(new TargetDefinition(target, values))
        }
        if (result.empty) { throw new IllegalArgumentException('No targets available') }
        result
    }

    static List<File> sourceLayers(File root, Properties values) {
        Path base = root.canonicalFile.toPath()
        List<File> result = []
        String names = values.getProperty('shared_sources')
        if (!names) { throw new IllegalArgumentException('Missing shared_sources') }
        names.split(',').each { name ->
            File folder = new File(root, name.trim()).canonicalFile
            Path path = folder.toPath()
            if (path == base || !path.startsWith(base) || path.startsWith(base.resolve('targets')) || folder in result) {
                throw new IllegalArgumentException("Invalid or duplicate shared layer: ${name}")
            }
            result.add(folder)
        }
        result
    }

    static void identity(Properties values) {
        if (!(values.getProperty('mod_id', '') ==~ /[a-z][a-z0-9_]{1,63}/) ||
                !(values.getProperty('mod_group_id', '') ==~ /[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+/)) {
            throw new IllegalArgumentException('Mod ID or Java package is invalid')
        }
        ['mod_name', 'mod_authors', 'mod_version', 'mod_license', 'mod_description'].each { key ->
            String value = values.getProperty(key, '')
            if (!value || value.contains('\n') || value.contains('\r')) {
                throw new IllegalArgumentException("Expected single-line ${key}")
            }
        }
    }

    static List<File> distributableFiles(File root) {
        List<File> files = []
        collect(root, root, files)
        files
    }

    static List<File> projectFiles(File root) {
        distributableFiles(root).findAll { File source ->
            String relative = root.toPath().relativize(source.toPath()).toString().replace('\\', '/')
            (relative.tokenize('/')[0] in PROJECT_ROOTS || relative in PROJECT_ROOT_FILES) &&
                    !relative.startsWith('integrations/idea-plugin/') &&
                    !relative.startsWith('integrations/minecraft-development/')
        }
    }

    private static void collect(File base, File directory, List<File> into) {
        directory.listFiles().each { File child ->
            if (child.name in EXCLUDED || child.name in PRIVATE_FILES || child.name.startsWith('.env') ||
                    child.name.endsWith('.local.properties') || child.name.endsWith('.local.json')) { return }
            if (Files.isSymbolicLink(child.toPath())) { throw new IllegalArgumentException("Inspect symlink first: ${child}") }
            if (child.isDirectory()) { collect(base, child, into) }
            else if (!child.name.endsWith('.jar') || child.name == 'gradle-wrapper.jar') { into.add(child) }
        }
    }

    static void copy(File source, File destination) {
        destination.parentFile.mkdirs()
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
    }

    static void prepareIdentity(File root, TargetDefinition target) {
        Properties identity = read(new File(root, 'gradle.properties'))
        ProjectFiles.identity(identity)
        String name = identity.getProperty('mod_java_package', identity.getProperty('mod_group_id'))
        File output = new File(target.directory, "build/generated/sources/temple/${name.replace('.', '/')}/BuildInfo.java")
        output.parentFile.mkdirs()
        output.setText("package ${name};\n\npublic final class BuildInfo {\n" +
                "    public static final String MOD_ID = \"${identity.getProperty('mod_id')}\";\n" +
                "    private BuildInfo() {}\n}\n", 'UTF-8')
    }

    static void select(File root, TargetDefinition target) {
        if (target.directory.parentFile.parentFile.canonicalFile != root.canonicalFile) {
            throw new IllegalArgumentException('Target belongs to another project')
        }
        prepareIdentity(root, target)
        File state = new File(root, '.temple/active-target')
        state.parentFile.mkdirs()
        state.setText(target.id + '\n', 'UTF-8')
    }
}
