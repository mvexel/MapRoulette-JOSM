// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.KeyEvent;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import org.openstreetmap.josm.actions.PreferencesAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.plugins.maproulette.actions.HowItWorksAction;
import org.openstreetmap.josm.plugins.maproulette.actions.LoadTasksInViewAction;
import org.openstreetmap.josm.plugins.maproulette.actions.ShowPanelAction;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteLoginPreference;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRoulettePreferences;

/**
 * The top-level MapRoulette menu, so a new user finds the plugin before any map is open
 */
public final class MapRouletteMenu {
    private MapRouletteMenu() {
        // Hide the constructor
    }

    /**
     * Add the MapRoulette menu to JOSM's main menu. Call once, on the EDT.
     *
     * @param findChallenge The action that finds a challenge
     * @return The menu
     */
    public static JMenu install(FindChallengeAction findChallenge) {
        final var mainMenu = MainApplication.getMenu();
        final var menu = mainMenu.addMenu(marktr("MapRoulette"), tr("MapRoulette"), KeyEvent.CHAR_UNDEFINED,
                mainMenu.getDefaultMenuPos(), ht("/Plugin/MapRoulette"));
        MainMenu.add(menu, findChallenge);
        MainMenu.add(menu, new LoadTasksInViewAction());
        final var openLink = new JMenuItem(mainMenu.openLocation);
        openLink.setText(tr("Open challenge or task link…"));
        openLink.setToolTipText(tr("Load a MapRoulette challenge or task from its link"));
        menu.add(openLink);
        MainMenu.add(menu, new ShowPanelAction());
        menu.addSeparator();
        MainMenu.add(menu, PreferencesAction.forPreferenceSubTab(tr("Log in to MapRoulette…"),
                tr("Log in to MapRoulette in the preferences"), MapRouletteLoginPreference.class));
        MainMenu.add(menu, PreferencesAction.forPreferenceTab(tr("MapRoulette preferences…"),
                tr("Open the MapRoulette preferences"), MapRoulettePreferences.class));
        menu.addSeparator();
        MainMenu.add(menu, new HowItWorksAction());
        return menu;
    }
}
