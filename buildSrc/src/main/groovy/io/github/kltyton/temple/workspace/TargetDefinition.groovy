package io.github.kltyton.temple.workspace

final class TargetDefinition {
    final File directory
    final String id
    final String loader
    final String minecraft
    final int javaVersion
    final int gradleJavaVersion
    final Properties properties

    TargetDefinition(File directory, Properties properties) {
        this.directory = directory.canonicalFile
        this.id = directory.name
        this.properties = properties
        this.loader = properties.getProperty('loader')
        this.minecraft = properties.getProperty('minecraft_version')
        this.javaVersion = Integer.parseInt(properties.getProperty('java_version'))
        this.gradleJavaVersion = Integer.parseInt(properties.getProperty('gradle_java_version'))
    }

    String taskSuffix() { id.replace('-', '_').replace('.', '_') }

    Map<String, Object> describe() {
        [target: id, loader: loader, minecraft: minecraft, java: javaVersion,
         gradle_java: gradleJavaVersion, ci: properties.getProperty('ci_enabled', 'true') == 'true']
    }
}
