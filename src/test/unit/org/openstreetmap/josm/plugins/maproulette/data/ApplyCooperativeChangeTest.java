// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.plugins.maproulette.api.model.ElementCreate;
import org.openstreetmap.josm.plugins.maproulette.api.model.ElementTagChange;
import org.openstreetmap.josm.plugins.maproulette.api.model.ElementUpdate;
import org.openstreetmap.josm.plugins.maproulette.api.model.OSMChange;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link ApplyCooperativeChange}
 */
@BasicPreferences
class ApplyCooperativeChangeTest {
    private static OSMChange changeFor(long... nodeIds) {
        final var updates = new ElementUpdate[nodeIds.length];
        for (int i = 0; i < nodeIds.length; i++) {
            updates[i] = new ElementUpdate(nodeIds[i], OsmPrimitiveType.NODE, Integer.MIN_VALUE,
                    new ElementTagChange(Map.of("highway", "crossing"), new String[0]));
        }
        return new OSMChange(new ElementCreate[0], updates);
    }

    /**
     * Applying a tag fix to an object that is not in the edit layer must not throw
     *
     * @param version The version the task expects
     */
    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, 3 })
    void testMissingPrimitiveIsSkipped(int version) {
        final var ds = new DataSet();
        final var change = new OSMChange(new ElementCreate[0], new ElementUpdate[] { new ElementUpdate(1,
                OsmPrimitiveType.NODE, version, new ElementTagChange(Map.of("highway", "crossing"), new String[0])) });
        assertNull(assertDoesNotThrow(() -> new ApplyCooperativeChange(change).generateCommand(ds)));
    }

    /**
     * A task is applied completely or not at all: if one of its objects is missing, the others are not changed
     */
    @Test
    void testPartialChangeIsNotApplied() {
        final long presentId = 1;
        final var ds = new DataSet();
        final var node = new Node(presentId, 1);
        node.setCoor(new LatLon(0, 0));
        ds.addPrimitive(node);
        assertNull(new ApplyCooperativeChange(changeFor(presentId, 2)).generateCommand(ds));
        assertNull(node.get("highway"));
    }

    /**
     * Objects are changed when all of them are present
     */
    @Test
    void testPresentPrimitivesAreChanged() {
        final long presentId = 1;
        final var ds = new DataSet();
        final var node = new Node(presentId, 1);
        node.setCoor(new LatLon(0, 0));
        ds.addPrimitive(node);
        final var command = new ApplyCooperativeChange(changeFor(presentId)).generateCommand(ds);
        assertNotNull(command);
        command.executeCommand();
        assertEquals("crossing", node.get("highway"));
    }
}
