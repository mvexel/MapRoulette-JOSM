// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Collections;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.download.DownloadDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Show the MapRoulette panel. The panel lives in the map window, so with no map this opens one with an empty
 * MapRoulette layer.
 */
public class ShowPanelAction extends JosmAction {
    /**
     * Create a new action
     */
    public ShowPanelAction() {
        super(tr("Show MapRoulette panel"), "dialogs/user_no_image", tr("Show the MapRoulette panel, opening a map if needed"),
                Shortcut.registerShortcut("maproulette:show_panel", tr("MapRoulette: Show MapRoulette panel"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!MainApplication.isDisplayingMapView()) {
            final var layer = new MapRouletteClusteredPointLayer(emptyLayerBounds(), Collections.emptyList());
            // Adding the first layer opens the map, which adds the panel
            MainApplication.getLayerManager().addLayer(layer);
            MainApplication.getMap().mapView.addMouseListener(layer);
        }
        final var dialog = MapRouletteDialog.get();
        if (dialog != null) {
            dialog.unfurlDialog();
        }
    }

    /**
     * Get the area the new map shows: the last download area, else the world
     *
     * @return The bounds for the empty layer
     */
    static Bounds emptyLayerBounds() {
        final var saved = DownloadDialog.getSavedDownloadBounds();
        return saved != null ? saved : new Bounds(-85, -180, 85, 180);
    }
}
