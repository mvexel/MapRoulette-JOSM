// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.download.DownloadDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Show the MapRoulette panel. Without a map, this adds an empty MapRoulette layer first: JOSM's panels live in the
 * map, which only exists once a layer is open.
 */
public class ShowPanelAction extends JosmAction {
    /**
     * Create a new action
     */
    public ShowPanelAction() {
        super(tr("Show MapRoulette panel"), "dialogs/user_no_image",
                tr("Show the MapRoulette task list and current task"),
                Shortcut.registerShortcut("maproulette:show_panel", tr("MapRoulette: Show panel"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        show();
    }

    /**
     * Show the MapRoulette panel, opening a map with an empty MapRoulette layer if there is none. Call on the EDT.
     *
     * @return The panel, or {@code null} if JOSM has no map after all
     */
    public static MapRouletteDialog show() {
        if (MainApplication.getMap() == null) {
            final var layer = new MapRouletteClusteredPointLayer(emptyMapBounds(), List.of());
            MainApplication.getLayerManager().addLayer(layer);
            MainApplication.getMap().mapView.addMouseListener(layer);
        }
        final var dialog = MapRouletteDialog.get();
        if (dialog != null) {
            dialog.unfurlDialog();
        }
        return dialog;
    }

    /**
     * Where the empty map opens: the last download area, else the whole world
     *
     * @return The bounds
     */
    static Bounds emptyMapBounds() {
        final var saved = DownloadDialog.getSavedDownloadBounds();
        return saved != null ? saved : new Bounds(-85, -180, 85, 180);
    }
}
