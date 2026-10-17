// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Optional;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Find a challenge to work on: browse the challenges on the MapRoulette website, or, for a server without a website,
 * open a challenge by its URL
 */
public class FindChallengeAction extends JosmAction {
    /**
     * Create a new action
     */
    public FindChallengeAction() {
        super(tr("Find a challenge…"), "dialogs/search", tr("Find a MapRoulette challenge to work on"),
                Shortcut.registerShortcut("maproulette:find_challenge", tr("MapRoulette: Find a challenge"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        browseUrl().ifPresentOrElse(OpenBrowser::displayUrl,
                () -> MainApplication.getMenu().openLocation.actionPerformed(e));
    }

    /**
     * Get the challenge list on the MapRoulette website
     *
     * @return The URL, if the server has a known website
     */
    static Optional<String> browseUrl() {
        return MapRouletteServerPreference.getWebsiteUrl().map(url -> url.replaceAll("/+$", "") + "/browse/challenges");
    }
}
