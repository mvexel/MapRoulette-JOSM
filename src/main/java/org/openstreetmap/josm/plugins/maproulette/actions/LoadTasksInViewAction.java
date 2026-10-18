// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks.MapRouletteDownloadTaskBox;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Load the MapRoulette tasks in the current map view, from the menu. Needs a map.
 */
public class LoadTasksInViewAction extends JosmAction {
    /**
     * Create a new action
     */
    public LoadTasksInViewAction() {
        super(tr("Load tasks in current view"), "download", tr("Load the MapRoulette tasks in the current map view"),
                Shortcut.registerShortcut("maproulette:load_tasks_in_view",
                        tr("MapRoulette: Load tasks in current view"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!MainApplication.isDisplayingMapView()) {
            return;
        }
        final var bounds = MainApplication.getMap().mapView.getState().getViewArea().getLatLonBoundsBox();
        new MapRouletteDownloadTaskBox().download(new DownloadParams().withNewLayer(false), bounds,
                NullProgressMonitor.INSTANCE);
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.isDisplayingMapView());
    }
}
