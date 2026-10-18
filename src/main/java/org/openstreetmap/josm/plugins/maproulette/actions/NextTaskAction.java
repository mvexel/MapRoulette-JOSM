// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Start the next task the panel offers after a finished one, as its Start next task button does (#117)
 */
public class NextTaskAction extends JosmAction {
    /**
     * Create a new action
     */
    public NextTaskAction() {
        super(tr("Next task"), "dialogs/next", tr("Start the next task in the challenge of the task just finished"),
                Shortcut.registerShortcut("maproulette:next_task", tr("MapRoulette: Next task"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, "maproulette/next_task", true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!CurrentTaskPanel.startNextTask()) {
            new Notification(tr("Finish a task first, then MapRoulette offers the next one."))
                    .setIcon(JOptionPane.INFORMATION_MESSAGE).show();
        }
    }
}
