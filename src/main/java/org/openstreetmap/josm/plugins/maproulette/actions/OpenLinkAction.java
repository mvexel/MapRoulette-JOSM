// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Open a challenge or task link from the MapRoulette website, with JOSM's Open Location dialog (which knows
 * MapRoulette links)
 */
public class OpenLinkAction extends JosmAction {
    /**
     * Create a new action
     */
    public OpenLinkAction() {
        super(tr("Open challenge or task link…"), "openlocation",
                tr("Load a MapRoulette challenge or task from a link to the MapRoulette website"),
                Shortcut.registerShortcut("maproulette:open_link", tr("MapRoulette: Open challenge or task link"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, "maproulette/open_link", true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        MainApplication.getMenu().openLocation.actionPerformed(e);
    }
}
