package io.github.kltyton.temple.ui

import java.awt.Component
import javax.swing.*

final class DialogForm {
    static boolean confirm(Component form, String title, TargetSelectionPanel selection) {
        JButton confirm = new JButton(selection.messages().get('confirm'))
        JButton cancel = new JButton(selection.messages().get('cancel'))
        JOptionPane pane = new JOptionPane(form, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION,
                null, [confirm, cancel] as Object[], confirm)
        JDialog dialog = pane.createDialog(null, selection.messages().get(title))
        def translate = { event ->
            confirm.text = selection.messages().get('confirm')
            cancel.text = selection.messages().get('cancel')
            dialog.title = selection.messages().get(title)
        } as java.beans.PropertyChangeListener
        selection.addPropertyChangeListener('language', translate)
        boolean accepted = false
        confirm.addActionListener { accepted = true; dialog.dispose() }
        cancel.addActionListener { dialog.dispose() }
        dialog.rootPane.defaultButton = confirm
        dialog.visible = true
        selection.removePropertyChangeListener('language', translate)
        accepted
    }
}
