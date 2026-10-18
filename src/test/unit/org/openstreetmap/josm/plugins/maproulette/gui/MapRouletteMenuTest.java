// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenuItem;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link MapRouletteMenu}
 */
@BasicPreferences
@Main
@MapRouletteConfig
@Projection
class MapRouletteMenuTest {
    /**
     * The menu holds the ways into the plugin, and works before a map is open (#110)
     */
    @Test
    void testMenu() {
        final var findChallenge = new FindChallengeAction();
        final var menu = MapRouletteMenu.install(findChallenge);
        final var menuBar = MainApplication.getMenu();
        try {
            assertEquals("MapRoulette", menu.getText());
            assertTrue(List.of(menuBar.getComponents()).contains(menu));
            final var items = new ArrayList<JMenuItem>();
            for (var i = 0; i < menu.getItemCount(); i++) {
                if (menu.getItem(i) != null) {
                    items.add(menu.getItem(i));
                }
            }
            assertEquals(List.of("Find a challenge…", "Load tasks in current view", "Open challenge or task link…",
                    "Show MapRoulette panel", "Log in to MapRoulette…", "MapRoulette preferences…",
                    "How MapRoulette in JOSM works"), items.stream().map(JMenuItem::getText).toList());
            assertSame(findChallenge, items.get(0).getAction());
            assertSame(menuBar.openLocation, items.get(2).getAction());
            // No item has a default shortcut
            assertTrue(items.stream().allMatch(item -> item.getAccelerator() == null));

            final var loadTasks = items.get(1).getAction();
            assertFalse(loadTasks.isEnabled(), "Loading tasks in the view needs a map");
            MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testMenu", null));
            assertTrue(loadTasks.isEnabled());
        } finally {
            menuBar.remove(menu);
        }
    }
}
