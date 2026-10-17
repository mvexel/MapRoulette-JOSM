// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test the header of {@link CurrentTaskPanel}
 */
@BasicPreferences
class CurrentTaskPanelHeaderTest {
    private static final Task TASK = new Task(27, "Bench 27", null, null, 15318, null, null, new DataSet(), null, null,
            null, null, null, null, 0, null, null, null, false, null, null);

    /**
     * The header says which task is started and which of its objects are selected (#114)
     */
    @Test
    void testHeader() {
        final var dataSet = new DataSet();
        final var bench = new Node(LatLon.ZERO);
        bench.setOsmId(1, 1);
        bench.put("amenity", "bench");
        final var other = new Node(LatLon.NORTH_POLE);
        other.setOsmId(2, 1);
        dataSet.addPrimitive(bench);
        dataSet.addPrimitive(other);
        final List<PrimitiveId> ids = List.of(bench.getPrimitiveId(), other.getPrimitiveId());

        assertEquals("Task 27", CurrentTaskPanel.header(TASK, false, ids, dataSet));
        assertEquals("Working on task 27", CurrentTaskPanel.header(TASK, true, ids, dataSet));
        assertEquals("Working on task 27", CurrentTaskPanel.header(TASK, true, ids, null));

        dataSet.setSelected(bench);
        final var one = CurrentTaskPanel.header(TASK, true, ids, dataSet);
        assertTrue(one.startsWith("Working on task 27 · ") && one.endsWith(" selected"), one);

        dataSet.setSelected(bench, other);
        assertEquals("Working on task 27 · 2 objects selected", CurrentTaskPanel.header(TASK, true, ids, dataSet));
    }
}
