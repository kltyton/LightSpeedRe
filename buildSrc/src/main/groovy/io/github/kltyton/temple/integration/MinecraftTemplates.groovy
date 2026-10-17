package io.github.kltyton.temple.integration

import groovy.json.JsonOutput
import io.github.kltyton.temple.workspace.ProjectFiles
import io.github.kltyton.temple.workspace.TargetDefinition
import io.github.kltyton.temple.workspace.TargetPlan

final class MinecraftTemplates {
    static void export(File root, File output) {
        List<TargetDefinition> targets = ProjectFiles.targets(root)
        Properties identity = ProjectFiles.read(new File(root, 'gradle.properties'))
        String original = identity.getProperty('mod_java_package', identity.getProperty('mod_group_id'))
        File assets = new File(output, 'assets')
        assets.mkdirs()
        Set<String> blueprints = TargetPlan.BLUEPRINTS as Set
        List<Map<String, Object>> files = []
        Set<File> exportedAssets = [] as Set
        ProjectFiles.projectFiles(root).each { File source ->
            String relative = root.toPath().relativize(source.toPath()).toString().replace('\\', '/')
            if (relative == 'gradle.properties' || source.name.endsWith('.jar')) { return }
            String destination = relative
            String content = source.getText('UTF-8')
            if (source.name.endsWith('.java') && relative.tokenize('/')[0] in ['common', 'targets']) {
                destination = relative.replace(original.replace('.', '/'), '${BUILD_COORDS.groupId.replace(".", "/")}')
                content = literalParts(content, original, '${BUILD_COORDS.groupId}')
            } else { content = literal(content) }
            File asset = new File(assets, relative + '.ft')
            exportedAssets.add(asset)
            asset.parentFile.mkdirs()
            asset.setText(content, 'UTF-8')
            Map<String, Object> descriptor = [:]
            descriptor.put('template', 'assets/' + relative + '.ft')
            descriptor.put('destination', destination)
            descriptor.put('reformat', false)
            if (relative.startsWith('targets/')) {
                String id = relative.tokenize('/')[1]
                descriptor.condition = "\$TARGET_PLAN.contains('\"blueprint\":\"${id}\"')"
                if (id in blueprints) {
                    files.add([template: descriptor.template,
                               destination: relative.replace('targets/', 'gradle/target-blueprints/'), reformat: false])
                }
            }
            files.add(descriptor)
        }
        String properties = '#[[org.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8\norg.gradle.daemon=false\n]]#' +
                'mod_id=${MOD_ID}\nmod_name=${MOD_NAME}\nmod_group_id=${BUILD_COORDS.groupId}\n' +
                'mod_authors=${AUTHORS}\nmod_version=${BUILD_COORDS.version}\nmod_license=${LICENSE}\n' +
                'mod_description=${DESCRIPTION}\ntemple_idea_project=true\ntemple_build_dsl=${BUILD_DSL}\n'
        new File(assets, 'project.properties.ft').setText(properties, 'UTF-8')
        exportedAssets.add(new File(assets, 'project.properties.ft'))
        files.add([template: 'assets/project.properties.ft', destination: 'gradle.properties', reformat: false])
        File wrapper = new File(root, 'gradle/wrapper/gradle-wrapper.jar')
        new File(assets, 'wrapper.base64.ft').setText(wrapper.bytes.encodeBase64().toString(), 'UTF-8')
        exportedAssets.add(new File(assets, 'wrapper.base64.ft'))
        files.add([template: 'assets/wrapper.base64.ft', destination: 'gradle/wrapper/kltyton-wrapper.base64', reformat: false])
        targets.each { target ->
            String condition = "\$TARGET_PLAN.contains('\"blueprint\":\"${target.id}\"')"
            files.add([template: 'assets/wrapper.base64.ft',
                       destination: "targets/${target.id}/gradle/wrapper/kltyton-wrapper.base64",
                       condition: condition, reformat: false])
        }
        def descriptor = [version: 3, label: 'KltytonTemple', group: 'mod',
            properties: [
                [name:'BUILD_DSL', type:'kltyton_build_dsl', label:'Gradle 脚本语言 / Gradle script language', default:'groovy', order:9, visible:false],
                [name:'BUILD_COORDS', type:'build_system_coordinates', order:10, visible:false],
                [name:'MOD_ID', type:'string', label:'Mod ID', default:'my_mod', validator:'[a-z][a-z0-9_]{1,63}', visible:false,
                 derives:[parents:['PROJECT_NAME'], method:'replace',
                          parameters:[regex:'[^a-z0-9_]+', replacement:'_', lowercase:true, maxLength:64]]],
                [name:'MOD_NAME', type:'string', label:'Mod 名称 / Mod name', inheritFrom:'PROJECT_NAME', default:'My Mod', visible:false],
                [name:'TARGET_PLAN', type:'kltyton_targets', label:'Minecraft / Loader', parameters:[layout:'split']],
                [name:'AUTHORS', type:'string', label:'作者 / Authors', default:'kltyton', visible:false],
                [name:'LICENSE', type:'string', label:'许可证 / License', default:'MIT', visible:false],
                [name:'DESCRIPTION', type:'string', label:'描述 / Description', default:'A Minecraft mod.', visible:false],
                [name:'JDK', type:'jdk', default:21, order:20, visible:false]
            ],
            files: files,
            finalizers: [[type:'kltyton_targets'], [type:'import_gradle_project'],
                         [type:'run_gradle_tasks', tasks:['setupProject']]]
        ]
        new File(output, '.mcdev.template.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(descriptor)), 'UTF-8')
        assets.eachFileRecurse { File asset ->
            if (asset.isFile() && asset.name.endsWith('.ft') && !exportedAssets.contains(asset) && !asset.delete()) {
                throw new IOException("Could not remove obsolete template asset: ${asset}")
            }
        }
        List<File> emptyCandidates = []
        assets.eachDirRecurse { emptyCandidates.add(it) }
        emptyCandidates.sort { -it.absolutePath.length() }.each {
            if (it.list().length == 0 && !it.delete()) throw new IOException("Could not remove empty template directory: ${it}")
        }
    }

    private static String literal(String text) {
        '#set($END_BLOCK = \'\u005d\u005d#\')\n' + literalBody(text)
    }

    private static String literalBody(String text) {
        text.split(java.util.regex.Pattern.quote(']]#'), -1)
                .collect { '#[[' + it + ']]#' }.join('\$END_BLOCK')
    }

    private static String literalParts(String text, String original, String expression) {
        '#set($END_BLOCK = \'\u005d\u005d#\')\n' +
                text.split(java.util.regex.Pattern.quote(original), -1).collect { literalBody(it) }.join(expression)
    }
}
