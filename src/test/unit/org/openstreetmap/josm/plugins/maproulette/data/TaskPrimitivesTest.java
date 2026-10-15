// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;

/**
 * Test class for {@link TaskPrimitives}
 */
@MapRouletteConfig
@MapRouletteLogin
class TaskPrimitivesTest {
    @Test
    void testGetPrimitiveIdsNode() {
        final var task = assertDoesNotThrow(() -> TaskAPI.start(132279499));
        assertFalse(TaskPrimitives.getPrimitiveIds(task).isEmpty());
        assertEquals(1, TaskPrimitives.getPrimitiveIds(task).size());
        final var nodeId = TaskPrimitives.getPrimitiveIds(task).iterator().next();
        final var expectedId = new SimplePrimitiveId(9494185766L, OsmPrimitiveType.NODE);
        assertEquals(expectedId, nodeId);
    }

    @Test
    void testGetPrimitiveIdsNull() {
        assertTrue(TaskPrimitives.getPrimitiveIds(null).isEmpty());
    }

    @Test
    void testGetPrimitiveIdsNoIdentifier() {
        final var task = assertDoesNotThrow(() -> TaskAPI.start(132279499));
        task.geometries().allPrimitives().forEach(p -> p.remove("osmIdentifier"));
        assertTrue(TaskPrimitives.getPrimitiveIds(task).isEmpty());
    }

    /**
     * The select action asks for the primitives on the EDT to update its enabled state, so this must not go to the
     * server
     *
     * @throws Exception if the test fails
     */
    @Test
    void testCachedPrimitiveIdsOnEdt() throws Exception {
        final var uncached = new Task(1, "", null, null, 990_044L, null, null, null, null, null, null, null, null, null,
                0, null, null, null, false, null, null);
        final var task = assertDoesNotThrow(() -> TaskAPI.start(132279499));
        assertFalse(TaskPrimitives.getPrimitiveIds(task).isEmpty(), "Caches the challenge of the task");
        final var results = new Object[2];
        GuiHelper.runInEDTAndWaitWithException(() -> {
            results[0] = TaskPrimitives.cachedPrimitiveIds(uncached);
            results[1] = TaskPrimitives.cachedPrimitiveIds(task);
        });
        assertEquals(Optional.empty(), results[0]);
        assertEquals(Optional.of(TaskPrimitives.getPrimitiveIds(task)), results[1]);
    }
}
