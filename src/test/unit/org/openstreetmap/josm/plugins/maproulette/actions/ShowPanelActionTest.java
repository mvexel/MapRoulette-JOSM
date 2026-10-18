// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.HTTP;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link ShowPanelAction}
 */
@BasicPreferences
@HTTP
@Main
@MapRouletteConfig
@Projection
class ShowPanelActionTest {
    /**
     * With no map, the action opens one with an empty MapRoulette layer, so the panel has somewhere to live (#110)
     */
    @Test
    void testOpensMap() {
        assertFalse(MainApplication.isDisplayingMapView());
        new ShowPanelAction().actionPerformed(null);
        assertTrue(MainApplication.isDisplayingMapView());
        final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
        assertEquals(1, layers.size());
    }

    /**
     * With a map, the action only shows the panel
     */
    @Test
    void testKeepsMap() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testKeepsMap", null));
        new ShowPanelAction().actionPerformed(null);
        assertEquals(1, MainApplication.getLayerManager().getLayers().size());
    }

    /**
     * The new map shows the last download area, else the world
     */
    @Test
    void testEmptyLayerBounds() {
        Config.getPref().put("osm-download.bounds", null);
        assertEquals(new Bounds(-85, -180, 85, 180), ShowPanelAction.emptyLayerBounds());
        Config.getPref().put("osm-download.bounds", "40.7;-111.9;40.8;-111.8");
        assertEquals(new Bounds(40.7, -111.9, 40.8, -111.8), ShowPanelAction.emptyLayerBounds());
    }
}
