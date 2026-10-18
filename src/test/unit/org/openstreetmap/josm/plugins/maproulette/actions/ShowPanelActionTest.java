// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link ShowPanelAction}
 */
@Main
@MapRouletteConfig
@Projection
class ShowPanelActionTest {
    /**
     * Without a map, showing the panel opens one with an empty MapRoulette layer, so the panel exists (#110)
     */
    @Test
    void testShowWithoutMap() {
        assertEquals(0, MainApplication.getLayerManager().getLayers().size());
        GuiHelper.runInEDTAndWait(() -> new ShowPanelAction().actionPerformed(null));
        final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
        assertEquals(1, layers.size());
        assertEquals(0, layers.get(0).getTasks().size());
        assertNotNull(MainApplication.getMap());
        // Tasks loaded into this layer later must be clickable
        assertTrue(Arrays.asList(MainApplication.getMap().mapView.getMouseListeners()).contains(layers.get(0)));
        // A second show doesn't add another layer
        GuiHelper.runInEDTAndWait(() -> new ShowPanelAction().actionPerformed(null));
        assertEquals(1, MainApplication.getLayerManager().getLayers().size());
    }

    /**
     * The empty map opens on the last download area, or the world without one
     */
    @Test
    void testEmptyMapBounds() {
        Config.getPref().put("osm-download.bounds", null);
        assertEquals(new Bounds(-85, -180, 85, 180), ShowPanelAction.emptyMapBounds());
        Config.getPref().put("osm-download.bounds", "40.7;-111.9;40.8;-111.8");
        try {
            assertEquals(new Bounds(40.7, -111.9, 40.8, -111.8), ShowPanelAction.emptyMapBounds());
        } finally {
            Config.getPref().put("osm-download.bounds", null);
        }
    }
}
