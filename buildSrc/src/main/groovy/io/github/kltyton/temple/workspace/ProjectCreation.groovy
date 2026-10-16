package io.github.kltyton.temple.workspace

import java.nio.file.Path

final class ProjectCreation {
    static File create(File template, File destination, Properties identity, Collection<String> chosen) {
        File output = destination.canonicalFile
        Path base = template.canonicalFile.toPath()
        if (output.exists() || output.toPath().startsWith(base)) {
            throw new IllegalArgumentException("Choose a new directory outside the template: ${output}")
        }
        ProjectFiles.identity(identity)
        BuildScripts.Dsl requestedDsl = BuildScripts.Dsl.parse(identity.getProperty('temple_build_dsl', 'groovy'))
        if (new File(template, 'build.gradle.kts').isFile() && requestedDsl == BuildScripts.Dsl.GROOVY) {
            throw new IllegalArgumentException('This source project uses Kotlin DSL. Select Kotlin DSL or use the original KltytonTemple template for Groovy.')
        }
        List<TargetDefinition> all = ProjectFiles.targets(template)
        Set<String> selected = chosen.empty ? all.collect { it.id } as Set : chosen as Set
        if (!all.collect { it.id }.containsAll(selected)) { throw new IllegalArgumentException('Unknown target selection') }
        Properties old = ProjectFiles.read(new File(template, 'gradle.properties'))
        String oldPackage = old.getProperty('mod_java_package', old.getProperty('mod_group_id'))
        String newPackage = identity.getProperty('mod_java_package', identity.getProperty('mod_group_id'))
        List<File> files = ProjectFiles.projectFiles(template)
        files = files.findAll { File file ->
            String relative = base.relativize(file.toPath()).toString().replace('\\', '/')
            !relative.startsWith('targets/') || relative.tokenize('/')[1] in selected
        }
        output.mkdirs()
        files.each { File source ->
            String relative = base.relativize(source.toPath()).toString().replace('\\', '/')
            boolean gameSource = relative.tokenize('/')[0] in ['common', 'versions', 'loaders', 'targets']
            if (gameSource) { relative = relative.replace(oldPackage.replace('.', '/'), newPackage.replace('.', '/')) }
            File target = new File(output, relative)
            target.parentFile.mkdirs()
            if (source.name.endsWith('.java') && gameSource) {
                target.setText(source.getText('UTF-8').replace(oldPackage, newPackage), 'UTF-8')
            } else { ProjectFiles.copy(source, target) }
        }
        identity.each { key, value -> old.setProperty(key.toString(), value.toString()) }
        old.remove('publish_curseforge_project_id')
        old.remove('publish_modrinth_project_id')
        old.setProperty('temple_default_target', selected.first())
        ProjectFiles.write(new File(output, 'gradle.properties'), old)
        TargetPlan.BLUEPRINTS.each { name ->
            File blueprint = new File(template, "targets/${name}")
            if (blueprint.isDirectory()) {
                ProjectFiles.distributableFiles(blueprint).each { source ->
                    ProjectFiles.copy(source, new File(output, "gradle/target-blueprints/${name}/" +
                            blueprint.toPath().relativize(source.toPath()).toString()))
                }
            }
        }
        File state = new File(output, '.temple/active-target')
        state.parentFile.mkdirs()
        state.setText(selected.first() + '\n', 'UTF-8')
        BuildScripts.createProject(output.toPath())
        output
    }

    static TargetDefinition add(File root, TargetDefinition blueprint, Properties changes) {
        String minecraft = changes.getProperty('minecraft_version')
        if (!(minecraft ==~ /[0-9]+(\.[0-9]+)*/)) { throw new IllegalArgumentException('Invalid Minecraft version') }
        File destination = new File(root, "targets/${blueprint.loader}-${minecraft}")
        if (destination.exists()) { throw new IllegalArgumentException("Target already exists: ${destination}") }
        if (blueprint.loader == 'fabric' && !changes.getProperty('fabric_api_version')) {
            throw new IllegalArgumentException('Supply the matching Fabric API version')
        }
        Properties values = new Properties()
        values.putAll(blueprint.properties)
        values.putAll(changes)
        values.setProperty('loader', blueprint.loader)
        values.setProperty('minecraft_version_range', "[${minecraft}]")
        values.setProperty('shared_sources', blueprint.properties.getProperty('shared_sources').replace(blueprint.minecraft, minecraft))
        ['loader_version', 'java_version', 'gradle_java_version'].each { key ->
            if (!values.getProperty(key)) { throw new IllegalArgumentException("Missing ${key}") }
        }
        ProjectFiles.distributableFiles(blueprint.directory).each { source ->
            File target = new File(destination, blueprint.directory.toPath().relativize(source.toPath()).toString())
            ProjectFiles.copy(source, target)
        }
        ProjectFiles.write(new File(destination, 'gradle.properties'), values)
        String version = changes.getProperty('wrapper_version')
        if (version) {
            if (!(version ==~ /[0-9]+(\.[0-9]+)+/)) { throw new IllegalArgumentException('Invalid Gradle version') }
            File wrapper = new File(destination, 'gradle/wrapper/gradle-wrapper.properties')
            Properties wrapperValues = ProjectFiles.read(wrapper)
            wrapperValues.setProperty('distributionUrl', "https://services.gradle.org/distributions/gradle-${version}-bin.zip")
            wrapperValues.remove('distributionSha256Sum')
            ProjectFiles.write(wrapper, wrapperValues)
        }
        ProjectFiles.targets(root).find { it.directory == destination.canonicalFile }
    }
}
