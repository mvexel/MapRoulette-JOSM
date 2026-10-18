// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Load the MapRoulette tasks in the current map view, as the panel's Load tasks here does
 */
public class LoadTasksInViewAction extends JosmAction {
    /**
     * Create a new action
     */
    public LoadTasksInViewAction() {
        super(tr("Load tasks here"), "download", tr("Load the MapRoulette tasks in the current map view"),
                Shortcut.registerShortcut("maproulette:load_tasks_in_view", tr("MapRoulette: Load tasks here"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, null, true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (ShowPanelAction.show() != null && TaskListPanel.get() != null) {
            TaskListPanel.get().loadTasksInView();
        }
    }

    @Override
    protected void updateEnabledState() {
        // There is no view to load from without a map
        setEnabled(MainApplication.isDisplayingMapView());
    }
}
