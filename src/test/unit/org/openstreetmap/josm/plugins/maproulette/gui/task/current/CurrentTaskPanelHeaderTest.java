// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
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
     * The header says which task is started, and the line below it which of its objects are selected (#114)
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

        assertEquals("Task 27", CurrentTaskPanel.header(TASK, false));
        assertEquals("Working on task 27", CurrentTaskPanel.header(TASK, true));
        final var done = new Task(28, "Bench 28", null, null, 15318, null, null, new DataSet(), null,
                TaskStatus.ALREADY_FIXED, null, null, null, null, 0, null, null, null, false, null, null);
        assertEquals("Task 28 · Done · Already fixed", CurrentTaskPanel.header(done, false), "#146");
        assertNull(CurrentTaskPanel.selection(ids, dataSet, "Bench"));
        assertNull(CurrentTaskPanel.selection(ids, null, "Bench"));

        dataSet.setSelected(bench);
        final var one = CurrentTaskPanel.selection(ids, dataSet, "Bench");
        assertTrue(one != null && one.endsWith(" selected"), one);

        dataSet.setSelected(bench, other);
        assertEquals("2 objects selected", CurrentTaskPanel.selection(ids, dataSet, "Bench"));

        // A task with one object: the same name as the header and the list (#145)
        assertEquals("Bench \"sue\" selected",
                CurrentTaskPanel.selection(List.of(bench.getPrimitiveId()), dataSet, "Bench \"sue\""));
    }
}
