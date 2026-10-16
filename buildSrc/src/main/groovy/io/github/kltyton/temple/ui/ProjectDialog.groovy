package io.github.kltyton.temple.ui

import io.github.kltyton.temple.workspace.ProjectCreation
import io.github.kltyton.temple.workspace.ProjectFiles
import io.github.kltyton.temple.workspace.TargetPlan
import io.github.kltyton.temple.workspace.BuildScripts
import groovy.json.JsonSlurper
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

final class ProjectDialog {
    static void main(String[] arguments) {
        UIManager.setLookAndFeel(UIManager.systemLookAndFeelClassName)
        File root = new File(arguments[0]).canonicalFile
        Map<String, JTextField> fields = [
            mod_id: new JTextField(), mod_name: new JTextField(), mod_group_id: new JTextField('io.github.kltyton'),
            mod_authors: new JTextField(), mod_version: new JTextField('1.0.0'), mod_license: new JTextField('MIT'),
            mod_description: new JTextField('A Minecraft mod.'), directory: new JTextField()
        ]
        Map<String, String> keys = [mod_name:'name', mod_group_id:'package', mod_authors:'authors',
                                   mod_version:'version', mod_license:'license', mod_description:'description', directory:'directory']
        Map<String, JLabel> labels = fields.collectEntries { name, field -> [(name): new JLabel()] }
        JPanel properties = new JPanel(new GridLayout(0, 2, 12, 8))
        JButton browse = new JButton()
        JComboBox<BuildScripts.Dsl> dsl = new JComboBox<>(BuildScripts.Dsl.values())
        JLabel dslLabel = new JLabel()
        JComboBox<String> license = new JComboBox<>(['MIT', 'Apache-2.0', 'BSD-3-Clause', 'MPL-2.0', 'LGPL-3.0-only', 'GPL-3.0-only', 'All Rights Reserved'] as String[])
        license.editable = true
        license.addActionListener { fields.mod_license.text = license.selectedItem.toString() }
        fields.each { name, field ->
            properties.add(labels[name])
            if (name == 'directory') {
                JPanel directory = new JPanel(new BorderLayout(8, 0))
                directory.add(field, BorderLayout.CENTER)
                directory.add(browse, BorderLayout.EAST)
                properties.add(directory)
            } else { properties.add(name == 'mod_license' ? license : field) }
        }
        properties.add(dslLabel)
        properties.add(dsl)
        TargetSelectionPanel targets = new TargetSelectionPanel(
                { text -> new JsonSlurper().parseText(text) } as java.util.function.Function,
                [], { selections -> } as java.util.function.Consumer)
        def translate = {
            labels.each { name, label -> label.text = name == 'mod_id' ? 'Mod ID' : targets.messages().get(keys[name]) }
            browse.text = targets.messages().get('browse')
            dslLabel.text = targets.messages().get('buildDsl')
        }
        translate()
        targets.addPropertyChangeListener('language', { event -> translate() } as java.beans.PropertyChangeListener)
        browse.addActionListener {
            JFileChooser chooser = new JFileChooser(root.parentFile)
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.dialogTitle = targets.messages().get('chooseDirectory')
            if (chooser.showSaveDialog(properties) == JFileChooser.APPROVE_OPTION) fields.directory.text = chooser.selectedFile.absolutePath
        }
        JPanel form = new JPanel(new BorderLayout(0, 12))
        form.add(properties, BorderLayout.NORTH)
        form.add(targets, BorderLayout.CENTER)
        while (DialogForm.confirm(form, 'createTitle', targets)) {
            try {
                List<Map<String, String>> selected = targets.selections()
                if (selected.empty) { throw new IllegalArgumentException(targets.messages().get('selectTarget')) }
                Properties identity = new Properties()
                fields.findAll { it.key != 'directory' }.each { name, field -> identity.setProperty(name, field.text.trim()) }
                identity.setProperty('temple_build_dsl', dsl.selectedItem.id)
                if (!fields.directory.text.trim()) { throw new IllegalArgumentException(targets.messages().get('chooseDirectory')) }
                File created = ProjectCreation.create(root, new File(fields.directory.text.trim()), identity,
                        selected.collect { it.blueprint }.unique())
                TargetPlan.apply(created.toPath(), selected, true)
                JOptionPane.showMessageDialog(null, targets.messages().get('created') + '\n' + created)
                return
            } catch (IllegalArgumentException | IOException problem) {
                JOptionPane.showMessageDialog(null, problem.message, targets.messages().get('createFailed'), JOptionPane.ERROR_MESSAGE)
            }
        }
    }
}
