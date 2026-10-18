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
 * Select the current task's objects and zoom to them, as clicking the task's name in the panel does
 */
public class ZoomToTaskAction extends JosmAction {
    /**
     * Create a new action
     */
    public ZoomToTaskAction() {
        super(tr("Zoom to task"), "dialogs/autoscale/selection",
                tr("Select the current task''s objects and zoom to them"),
                Shortcut.registerShortcut("maproulette:zoom_to_current_task", tr("MapRoulette: Zoom to current task"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, "maproulette/zoom_to_current_task", true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!CurrentTaskPanel.zoomToTask()) {
            new Notification(tr("Start a task first, then this zooms to it.")).setIcon(JOptionPane.INFORMATION_MESSAGE)
                    .show();
        }
    }
}
