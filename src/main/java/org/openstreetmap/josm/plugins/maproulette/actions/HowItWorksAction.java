// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Open the page that explains how MapRoulette works in JOSM
 */
public class HowItWorksAction extends JosmAction {
    /**
     * The help page. The one place it is named, so it can move when the page gets its final home.
     */
    public static final String HELP_URL = "https://wiki.openstreetmap.org/wiki/User:Mvexel/MapRoulette";

    /**
     * Create a new action
     */
    public HowItWorksAction() {
        super(tr("How MapRoulette in JOSM works"), "help", tr("Open the MapRoulette help page in your browser"),
                Shortcut.registerShortcut("maproulette:how_it_works", tr("MapRoulette: How MapRoulette in JOSM works"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        OpenBrowser.displayUrl(HELP_URL);
    }
}
