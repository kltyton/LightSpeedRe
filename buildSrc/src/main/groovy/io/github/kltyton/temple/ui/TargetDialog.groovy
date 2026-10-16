package io.github.kltyton.temple.ui

import io.github.kltyton.temple.workspace.TargetPlan
import groovy.json.JsonSlurper
import javax.swing.*

final class TargetDialog {
    static void main(String[] arguments) {
        UIManager.setLookAndFeel(UIManager.systemLookAndFeelClassName)
        File root = new File(arguments[0]).canonicalFile
        TargetSelectionPanel form = new TargetSelectionPanel(
                { text -> new JsonSlurper().parseText(text) } as java.util.function.Function,
                [], { selections -> } as java.util.function.Consumer)
        while (DialogForm.confirm(form, 'addTitle', form)) {
            try {
                TargetPlan.apply(root.toPath(), form.selections(), false)
                JOptionPane.showMessageDialog(null, form.messages().get('added'))
                return
            } catch (IllegalArgumentException | IOException problem) {
                JOptionPane.showMessageDialog(null, problem.message, form.messages().get('addFailed'), JOptionPane.ERROR_MESSAGE)
            }
        }
    }
}
