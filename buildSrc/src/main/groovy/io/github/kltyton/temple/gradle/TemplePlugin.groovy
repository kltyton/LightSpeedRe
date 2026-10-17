package io.github.kltyton.temple.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.github.kltyton.temple.artifact.DistributionInspector
import io.github.kltyton.temple.integration.MinecraftTemplates
import io.github.kltyton.temple.ui.ProjectDialog
import io.github.kltyton.temple.ui.PublishDialog
import io.github.kltyton.temple.ui.TargetDialog
import io.github.kltyton.temple.workspace.ProjectFiles
import io.github.kltyton.temple.workspace.TargetDefinition
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

final class TemplePlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('base')
        project.pluginManager.apply('java-base')
        File root = project.rootDir
        List<TargetDefinition> targets = ProjectFiles.targets(root)
        Properties identity = ProjectFiles.read(new File(root, 'gradle.properties'))
        File active = new File(root, '.temple/active-target')
        String selected = active.isFile() ? active.getText('UTF-8').trim() : identity.getProperty('temple_default_target')
        TargetDefinition current = selected ? targets.find { it.id == selected } : targets.first()
        JavaToolchainService toolchains = project.extensions.getByType(JavaToolchainService)

        project.tasks.register('listTargets') {
            group = 'temple'
            description = '显示全部 Minecraft / Loader / Java 目标。'
            doLast { project.logger.lifecycle(JsonOutput.prettyPrint(JsonOutput.toJson(targets.collect { it.describe() }))) }
        }
        project.tasks.register('writeCiMatrix') {
            group = 'temple'
            description = '为 CI 输出启用目标，不编译游戏。'
            doLast {
                def entries = targets.findAll { it.properties.getProperty('ci_enabled', 'true') == 'true' }
                        .collectMany { target -> ['windows-latest', 'ubuntu-latest'].collect { os -> target.describe() + [os: os] } }
                String json = JsonOutput.toJson([include: entries])
                String output = System.getenv('GITHUB_OUTPUT')
                if (output) { new File(output).append("matrix=${json}\n", 'UTF-8') }
                project.logger.lifecycle(json)
            }
        }
        project.tasks.register('setupTargetWrappers') {
            group = 'temple'
            description = '初次创建工程后安装各目标 Wrapper，保留各自 Gradle 版本。'
            dependsOn project.tasks.named('wrapper')
            doLast {
                targets.each { target ->
                    ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar'].each { name ->
                        File destination = new File(target.directory, name)
                        if (!destination.exists()) { ProjectFiles.copy(new File(root, name), destination) }
                    }
                }
            }
        }
        project.tasks.register('setupProject') {
            group = 'temple'
            description = '初始化新建工程的目标 Wrapper，不构建游戏。'
            dependsOn 'setupTargetWrappers'
        }
        project.tasks.register('selectTarget') {
            group = 'temple'
            description = '显示根任务当前使用的默认目标。'
            doLast { project.logger.lifecycle(current ? "当前目标 ${current.id}；每个目标分组均提供 select 任务。" :
                    "默认目标 ${selected} 不存在；使用 select_<target> 选择已有目标。") }
        }
        registerDialog(project, toolchains, 'createProject', ProjectDialog, null)
        registerDialog(project, toolchains, 'addTarget', TargetDialog, null)
        project.tasks.register('exportMinecraftTemplates') {
            group = 'temple'
            description = '更新 Minecraft Development 原生向导模板资产。'
            doLast { MinecraftTemplates.export(root, new File(root, 'integrations/minecraft-development/templates')) }
        }

        List<String> buildTasks = []
        targets.each { target ->
            String suffix = target.taskSuffix()
            String category = "temple · ${target.loader} ${target.minecraft}"
            String buildName = "build_${suffix}"
            registerTarget(project, toolchains, target, buildName, 'build', category)
            buildTasks.add(buildName)
            registerTarget(project, toolchains, target, "runClient_${suffix}", 'runClient', category)
            registerTarget(project, toolchains, target, "runServer_${suffix}", 'runServer', category)
            String datagenTask = target.properties.getProperty('datagen_task')
            if (datagenTask) {
                registerTarget(project, toolchains, target, "runDatagen_${suffix}", datagenTask, category)
            }
            project.tasks.register("select_${suffix}") {
                group = category
                description = "选择 ${target.id} 的构建与 common 编辑环境。"
                doLast {
                    ProjectFiles.select(root, target)
                    project.logger.lifecycle("当前目标：${target.id}；重新加载 Gradle 模型以切换 common 编辑环境，全部目标保持独立关联。")
                }
            }
            project.tasks.register("verify_${suffix}") {
                group = category
                description = '检查已有发行 JAR，不重新构建。'
                doLast { project.logger.lifecycle(JsonOutput.prettyPrint(JsonOutput.toJson(DistributionInspector.verify(root, target)))) }
            }
            ['modrinth', 'curseforge', 'maven'].each { platform ->
                registerDialog(project, toolchains, "publish_${platform}_${suffix}", PublishDialog, target, platform, category)
            }
        }
        project.tasks.register('buildAllTargets') {
            group = 'build'
            description = '构建全部独立目标，使用各自 Wrapper 与 Java 工具链。'
            dependsOn buildTasks
        }
        if (current != null) {
            project.tasks.named('build') { dependsOn "build_${current.taskSuffix()}" }
            registerTarget(project, toolchains, current, 'runClient', 'runClient', 'temple')
            registerTarget(project, toolchains, current, 'runServer', 'runServer', 'temple')
            String currentDatagen = current.properties.getProperty('datagen_task')
            if (currentDatagen) registerTarget(project, toolchains, current, 'runDatagen', currentDatagen, 'temple')
        } else {
            project.tasks.named('build') {
                doFirst { throw new IllegalArgumentException("Default target does not exist: ${selected}. Use select_<target>.") }
            }
            ['runClient', 'runServer', 'runDatagen'].each { action ->
                project.tasks.register(action) {
                    group = 'temple'
                    doLast { throw new IllegalArgumentException("Default target does not exist: ${selected}. Use select_<target>.") }
                }
            }
        }
        project.tasks.register('verifyAllDistributions') {
            group = 'verification'
            doLast { targets.each { project.logger.lifecycle(JsonOutput.toJson(DistributionInspector.verify(root, it))) } }
        }
    }

    private static void registerTarget(Project project, JavaToolchainService toolchains,
                                       TargetDefinition target, String name, String action, String category) {
        project.tasks.register(name, JavaExec) { task ->
            group = category
            description = "${target.id}: ${action}，使用目标自己的 Wrapper。"
            onlyIf {
                if (project.findProperty('templePlan') == 'true') {
                    project.logger.lifecycle("${target.id}: ${action}; Gradle Java ${target.gradleJavaVersion}; compiler Java ${target.javaVersion}")
                    return false
                }
                true
            }
            javaLauncher.set(toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(target.gradleJavaVersion) })
            classpath = project.files(new File(target.directory, 'gradle/wrapper/gradle-wrapper.jar'))
            mainClass.set('org.gradle.wrapper.GradleWrapperMain')
            workingDir target.directory
            boolean distributionClient = action == 'runClient' &&
                    project.findProperty('lightspeed.distributionRun') == 'true'
            args ":${distributionClient ? 'runDistributionClient' : action}", '--console=plain', '--no-daemon'
            ['lightspeed.distributionRun', 'lightspeed.runDirectory', 'lightspeed.outputVariant'].each { key ->
                def value = project.findProperty(key)
                if (value != null) args "-P${key}=${value}"
            }
            doFirst {
                File wrapper = new File(target.directory, 'gradle/wrapper/gradle-wrapper.jar')
                if (!wrapper.isFile()) { throw new IllegalArgumentException('先点击 temple > setupProject 初始化 Wrapper') }
                def runtime = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(target.gradleJavaVersion) }.get()
                def compiler = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(target.javaVersion) }.get()
                args "-Porg.gradle.java.installations.paths=${runtime.metadata.installationPath.asFile},${compiler.metadata.installationPath.asFile}"
                String temp = System.getProperty('java.io.tmpdir')
                args "-Djava.io.tmpdir=${temp}"
                environment 'TEMP', temp
                environment 'TMP', temp
                environment 'TMPDIR', temp
                environment 'JAVA_HOME', runtime.metadata.installationPath.asFile.absolutePath
            }
        }
    }

    private static void registerDialog(Project project, JavaToolchainService toolchains, String name,
                                       Class entry, TargetDefinition target, String platform = null,
                                       String category = 'temple') {
        project.tasks.register(name, JavaExec) { task ->
            group = category
            description = target == null ? (name == 'createProject' ? '可视化创建新项目；拒绝覆盖已有目录。' :
                    '可视化添加版本/Loader 目标。') : "可视化发布 ${target.id} 到 ${platform}，凭据仅存在于当前进程。"
            int runtimeVersion = target?.gradleJavaVersion ?: Integer.parseInt(System.getProperty('java.specification.version'))
            javaLauncher.set(toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(runtimeVersion) })
            classpath = project.files([entry, ProjectFiles, JsonSlurper, JsonOutput, GroovySystem].collect {
                new File(it.protectionDomain.codeSource.location.toURI())
            }.unique())
            mainClass.set(entry.name)
            jvmArgs '-Djava.awt.headless=false'
            jvmArgs '-Djava.net.useSystemProxies=true'
            args project.rootDir.absolutePath
            doFirst {
                def runtime = javaLauncher.get()
                if (target != null) {
                    def compiler = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(target.javaVersion) }.get()
                    args target.id, platform, runtime.executablePath.asFile.absolutePath,
                            "${runtime.metadata.installationPath.asFile},${compiler.metadata.installationPath.asFile}"
                }
                String temp = System.getProperty('java.io.tmpdir')
                systemProperty 'java.io.tmpdir', temp
                environment 'TEMP', temp
                environment 'TMP', temp
                environment 'TMPDIR', temp
            }
        }
    }
}
