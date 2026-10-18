// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.KeyEvent;

import javax.swing.JMenu;

import org.openstreetmap.josm.actions.PreferencesAction;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.plugins.maproulette.actions.HowItWorksAction;
import org.openstreetmap.josm.plugins.maproulette.actions.LoadTasksInViewAction;
import org.openstreetmap.josm.plugins.maproulette.actions.NextTaskAction;
import org.openstreetmap.josm.plugins.maproulette.actions.OpenLinkAction;
import org.openstreetmap.josm.plugins.maproulette.actions.ShowPanelAction;
import org.openstreetmap.josm.plugins.maproulette.actions.ZoomToTaskAction;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteLoginPreference;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRoulettePreferences;

/**
 * The MapRoulette menu in JOSM's main menu, so the plugin has a way in before any map is open (#110)
 */
public final class MapRouletteMenu {
    /**
     * The untranslated menu name, also its shortcut id
     */
    private static final String NAME = marktr("MapRoulette");

    private MapRouletteMenu() {
        // Hide the constructor
    }

    /**
     * Add the MapRoulette menu to JOSM's main menu, before the Help menu
     *
     * @param mainMenu JOSM's main menu
     * @return The new menu
     */
    public static JMenu add(MainMenu mainMenu) {
        final var menu = mainMenu.addMenu(NAME, tr(NAME), KeyEvent.CHAR_UNDEFINED, mainMenu.getDefaultMenuPos(),
                ht("/Plugin/MapRoulette"));
        MainMenu.add(menu, new FindChallengeAction());
        MainMenu.add(menu, new LoadTasksInViewAction());
        MainMenu.add(menu, new OpenLinkAction());
        MainMenu.add(menu, new ShowPanelAction());
        MainMenu.add(menu, new NextTaskAction());
        MainMenu.add(menu, new ZoomToTaskAction());
        menu.addSeparator();
        MainMenu.add(menu,
                PreferencesAction.forPreferenceSubTab(tr("MapRoulette login…"),
                        tr("Log in to or out of MapRoulette, in the MapRoulette preferences"),
                        MapRouletteLoginPreference.class));
        MainMenu.add(menu, PreferencesAction.forPreferenceTab(tr("MapRoulette preferences…"),
                tr("Open the MapRoulette preferences"), MapRoulettePreferences.class));
        menu.addSeparator();
        MainMenu.add(menu, new HowItWorksAction());
        return menu;
    }
}
