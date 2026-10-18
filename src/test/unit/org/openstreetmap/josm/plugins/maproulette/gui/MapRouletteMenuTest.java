// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.Arrays;
import java.util.List;

import javax.swing.JMenuItem;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link MapRouletteMenu}
 */
@Main
@MapRouletteConfig
@Projection
class MapRouletteMenuTest {
    /**
     * The menu has the way in for a new user, before any map is open (#110)
     */
    @Test
    void testMenu() {
        final var mainMenu = MainApplication.getMenu();
        final var menu = MapRouletteMenu.add(mainMenu);
        try {
            assertEquals("MapRoulette", menu.getText());
            final List<String> items = Arrays.stream(menu.getMenuComponents()).filter(JMenuItem.class::isInstance)
                    .map(c -> ((JMenuItem) c).getText()).toList();
            assertEquals(List.of("Find a challenge…", "Load tasks here", "Open challenge or task link…",
                    "Show MapRoulette panel", "Next task", "MapRoulette login…", "MapRoulette preferences…",
                    "How MapRoulette in JOSM works"), items);
            assertTrue(Arrays.asList(mainMenu.getComponents()).contains(menu), "The menu is in the main menu");
            final Component load = menu.getMenuComponent(1);
            assertFalse(load.isEnabled(), "Without a map there is no view to load tasks from");
        } finally {
            mainMenu.remove(menu);
        }
    }
}
