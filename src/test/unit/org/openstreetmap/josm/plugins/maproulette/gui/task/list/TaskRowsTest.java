// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.testutils.annotations.TaggingPresets;

/**
 * Test class for {@link TaskRows}
 */
@TaggingPresets
class TaskRowsTest {
    /**
     * Create a task list point
     *
     * @param id         The task id
     * @param title      MapRoulette's task name
     * @param status     The status
     * @param priority   The priority
     * @param lockedBy   The user holding a lock, if any
     * @param properties The properties of the task's feature
     * @return The point
     */
    static ClusteredPoint point(long id, String title, TaskStatus status, int priority, Long lockedBy,
            Map<String, String> properties) {
        return new ClusteredPoint(id, 1, "", title, 18, "SLC benches", new Point(40.76, -111.89), null, "",
                Instant.EPOCH, null, 2, status, null, null, null, null,
                new PointReview(null, null, null, null, null, null, null, null, null), priority, null, false, lockedBy,
                properties);
    }

    @Test
    void testReadableNameUsesNameThenRef() {
        assertEquals("Bench at Liberty Park", TaskRows.readableName(point(1, "n1", null, 0, null,
                Map.of("name", "Bench at Liberty Park", "ref", "B12", "amenity", "bench"))));
        assertEquals("B12", TaskRows.readableName(point(1, "n1", null, 0, null, Map.of("ref", "B12"))));
    }

    @Test
    void testReadableNameUsesPreset() {
        assertEquals("Bench",
                TaskRows.readableName(point(1, "n1", null, 0, null, Map.of("amenity", "bench", "@id", "node/1"))));
    }

    @ParameterizedTest
    @CsvSource({ "n13903246566,Node 13903246566", "w42,Way 42", "r7,Relation 7", "861207014_way_1,861207014_way_1" })
    void testReadableNameFallsBackToOsmId(String title, String expected) {
        assertEquals(expected, TaskRows.readableName(point(1, title, null, 0, null, Map.of())));
    }

    @Test
    void testReadableNameOfTaskWithoutName() {
        assertEquals("Task 5", TaskRows.readableName(point(5, "", null, 0, null, Map.of())));
    }

    @Test
    void testOsmId() {
        assertArrayEquals(new String[] { "node", "123" }, TaskRows.osmId("node/123"));
        assertArrayEquals(new String[] { "way", "4" }, TaskRows.osmId("w4"));
        assertNull(TaskRows.osmId("x4"));
        assertNull(TaskRows.osmId(null));
    }

    @ParameterizedTest
    @CsvSource({ "0,High priority", "1,Medium priority", "2,Low priority" })
    void testPriority(int priority, String expected) {
        assertEquals(expected, TaskRows.priority(priority));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "343|340 m", "4|0 m", "999|1.0 km", "1234|1.2 km" })
    void testDistance(double meters, String expected) {
        assertEquals(expected, TaskRows.distance(meters).replace(',', '.'));
    }

    @Test
    void testDetails() {
        final var point = point(151, "n1", null, 0, null, Map.of());
        assertEquals("Task 151 · High priority", TaskRows.details(point, null));
        final var details = TaskRows.details(point, new LatLon(40.763, -111.89));
        assertTrue(details.startsWith("Task 151 · High priority · "), details);
        assertTrue(details.endsWith(" m"), details);
    }

    @Test
    void testDetailsOfLockedTask() {
        assertEquals("Another mapper is working on it · Task 167",
                TaskRows.details(point(167, "n1", null, 0, 99L, Map.of()), null));
    }

    @Test
    void testDetailsOfChoiceTask() {
        final var choice = new ClusteredPoint(170, 1, "", "n1", 18, "", new Point(0, 0), null, "", Instant.EPOCH, null,
                2, null, "{\"meta\": {\"type\": 3}}", null, null, null, null, 0, null, false, null, Map.of());
        assertEquals("Answer in the Street Tally app · Task 170", TaskRows.details(choice, null));
    }

    @ParameterizedTest
    @CsvSource({ ",OPEN", "CREATED,OPEN", "FIXED,DONE", "ALREADY_FIXED,DONE", "FALSE_POSITIVE,DONE", "SKIPPED,SKIPPED",
            "TOO_HARD,CANT_COMPLETE", "DELETED,UNAVAILABLE" })
    void testChipFromStatus(TaskStatus status, TaskRows.Chip expected) {
        assertEquals(expected, TaskRows.chip(point(1, "n1", status, 0, null, Map.of())));
    }

    @Test
    void testChipOfLockedTasks() {
        final var lockedByOther = point(4, "n1", TaskStatus.CREATED, 0, 99L, Map.of());
        assertEquals(TaskRows.Chip.LOCKED, TaskRows.chip(lockedByOther));
        final var mine = new Task(4, "", null, null, 18, null, null, null, null, null, null, null, null, null, 0, null,
                null, null, false, null, null);
        ModifiedObjects.addLockedTask(mine);
        try {
            // The lock MapRoulette reports is this user's own
            assertEquals(TaskRows.Chip.STARTED, TaskRows.chip(lockedByOther));
            assertFalse(ModifiedObjects.isLockedByOther(lockedByOther));
        } finally {
            ModifiedObjects.removeLockedTask(mine);
        }
    }

    @Test
    void testSearchText() {
        final var text = TaskRows.searchText(point(151, "n9", null, 0, null, Map.of("addr:street", "400 South")),
                "Bench");
        for (var part : new String[] { "Bench", "SLC benches", "151", "n9", "400 South" }) {
            assertTrue(text.contains(part), part);
        }
    }
}
