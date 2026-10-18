// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;

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
                    "Show MapRoulette panel", "Next task", "Zoom to task", "MapRoulette login…",
                    "MapRoulette preferences…", "How MapRoulette in JOSM works"), items);
            assertTrue(Arrays.asList(mainMenu.getComponents()).contains(menu), "The menu is in the main menu");
            final Component load = menu.getMenuComponent(1);
            assertFalse(load.isEnabled(), "Without a map there is no view to load tasks from");
        } finally {
            mainMenu.remove(menu);
        }
    }

    /**
     * JOSM keys toolbar buttons by the action's toolbar id, which defaults to the icon name. The menu actions
     * must not reuse a core id, or they take over JOSM's own Download, Search and Open Location buttons.
     */
    @Test
    void testToolbarIdsDoNotTakeOverCoreButtons() {
        final var mainMenu = MainApplication.getMenu();
        final var menu = MapRouletteMenu.add(mainMenu);
        try {
            final Set<Object> ours = new HashSet<>();
            collectToolbarIds(menu, ours);
            assertEquals(9, ours.size(), "Every item has its own toolbar id: " + ours);
            for (Object id : ours) {
                assertTrue(id.toString().startsWith("maproulette/") || id.toString().startsWith("preference_"),
                        "Toolbar id " + id + " is the plugin's own");
            }
            final var toolbar = MainApplication.getToolbar();
            toolbar.refreshToolbarControl();
            for (String coreId : List.of("download", "dialogs/search")) {
                final var action = toolbarAction(toolbar.control, coreId);
                assertNotNull(action, "JOSM's " + coreId + " button is in the toolbar");
                assertFalse(String.valueOf(action.getValue(Action.SHORT_DESCRIPTION)).contains("MapRoulette"),
                        "JOSM's " + coreId + " button is still JOSM's, not " + action.getValue(Action.NAME));
            }
        } finally {
            mainMenu.remove(menu);
        }
    }

    private static void collectToolbarIds(MenuElement element, Set<Object> ids) {
        if (element instanceof JMenuItem item && !(item instanceof JMenu) && item.getAction() != null
                && item.getAction().getValue("toolbar") != null) {
            ids.add(item.getAction().getValue("toolbar"));
        }
        for (MenuElement child : element.getSubElements()) {
            collectToolbarIds(child, ids);
        }
    }

    private static Action toolbarAction(Container toolbar, String id) {
        for (Component component : toolbar.getComponents()) {
            if (component instanceof AbstractButton button) {
                final Action action = button.getAction();
                if (action != null && id.equals(action.getValue("toolbar"))) {
                    return action;
                }
            }
        }
        return null;
    }
}
