package io.github.kltyton.temple.ui

import io.github.kltyton.temple.artifact.DistributionInspector
import io.github.kltyton.temple.workspace.ProjectFiles
import java.awt.GridLayout
import javax.swing.*

final class PublishDialog {
    static void main(String[] arguments) {
        UIManager.setLookAndFeel(UIManager.systemLookAndFeelClassName)
        File root = new File(arguments[0]).canonicalFile
        String id = arguments[1]
        String platform = arguments[2]
        File javaExecutable = new File(arguments[3])
        String installations = arguments[4]
        def target = ProjectFiles.targets(root).find { it.id == id }
        def artifact = DistributionInspector.verify(root, target)
        JTextField projectId = new JTextField()
        JPasswordField token = new JPasswordField()
        JTextField changelog = new JTextField()
        JTextField address = new JTextField()
        JTextField username = new JTextField()
        JPanel form = new JPanel(new GridLayout(0, 2, 12, 8))
        form.add(new JLabel('目标')); form.add(new JLabel(id))
        form.add(new JLabel('发行包')); form.add(new JLabel(new File(artifact.artifact.toString()).name))
        form.add(new JLabel(platform == 'maven' ? 'Maven HTTPS 地址' : '平台项目 ID'))
        form.add(platform == 'maven' ? address : projectId)
        if (platform == 'maven') { form.add(new JLabel('用户名')); form.add(username) }
        form.add(new JLabel(platform == 'maven' ? '密码' : 'Token')); form.add(token)
        form.add(new JLabel('更新说明')); form.add(changelog)
        if (JOptionPane.showConfirmDialog(null, form, "发布到 ${platform}",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) { return }
        char[] secret = token.password
        try {
            if (secret.length == 0 || platform != 'maven' && !projectId.text.trim()) {
                throw new IllegalArgumentException('请填写当前平台凭据')
            }
            if (platform == 'maven' && !address.text.trim().startsWith('https://')) {
                throw new IllegalArgumentException('Maven 地址必须使用 HTTPS')
            }
            if (JOptionPane.showConfirmDialog(null, "确认上传 ${id} 到 ${platform}？",
                    '确认远程发布', JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return }
            List<String> command = [javaExecutable.absolutePath, '-cp',
                    new File(target.directory, 'gradle/wrapper/gradle-wrapper.jar').absolutePath,
                    'org.gradle.wrapper.GradleWrapperMain',
                    platform == 'maven' ? ':publish' : ':publishMods',
                    "-Ppublish_platform=${platform}", "--console=plain", '--no-daemon',
                    "-Dorg.gradle.java.installations.paths=${installations}"]
            ProcessBuilder process = new ProcessBuilder(command).directory(target.directory).inheritIO()
            Map<String, String> environment = process.environment()
            environment.put('JAVA_HOME', javaExecutable.parentFile.parentFile.absolutePath)
            environment.put('PUBLISH_CHANGELOG', changelog.text)
            if (platform == 'maven') {
                environment.put('TEMPLE_MAVEN_URL', address.text.trim())
                environment.put('MAVEN_USERNAME', username.text)
                environment.put('MAVEN_PASSWORD', new String(secret))
            } else {
                String prefix = platform.toUpperCase(Locale.ROOT)
                environment.put(prefix + '_PROJECT_ID', projectId.text.trim())
                environment.put(prefix + '_TOKEN', new String(secret))
            }
            int result = process.start().waitFor()
            if (result != 0) { throw new IOException("发布失败，退出码 ${result}；请查看 Gradle 控制台") }
            JOptionPane.showMessageDialog(null, '平台上传任务成功；平台审核状态需另行确认。')
        } catch (IllegalArgumentException | IOException problem) {
            JOptionPane.showMessageDialog(null, problem.message, '发布失败', JOptionPane.ERROR_MESSAGE)
            throw problem
        } finally {
            Arrays.fill(secret, (char) 0)
            token.text = ''
        }
    }
}
