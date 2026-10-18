// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults.Link;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test {@link TaskResults}
 */
@BasicPreferences
class TaskResultsTest {
    // An id that other tests don't lock
    private static final Task TASK = new Task(9116, "Bench", null, null, 10, null, null, new DataSet(), null, null,
            null, null, null, null, 0, null, null, null, false, null, null);

    @AfterEach
    void tearDown() {
        ModifiedObjects.removeLockedTask(TASK);
        TaskResults.clearAll();
    }

    /**
     * A link that finishes after the task was started again does not replace the new start's empty state
     */
    @Test
    void testLateLinkAfterRestart() {
        TaskResults.sent(TASK, TaskStatus.FIXED);
        ModifiedObjects.addLockedTask(TASK);
        assertNull(TaskResults.get(TASK.id()), "Start clears the result");
        TaskResults.linked(TASK.id(), 702495, true);
        assertNull(TaskResults.get(TASK.id()), "The started task shows no result");
        TaskResults.waiting(TASK.id(), 702495);
        assertNull(TaskResults.get(TASK.id()));
    }

    /**
     * A link is recorded while the result is kept, even if the task is still locked because its release failed
     */
    @Test
    void testLinkKeepsResult() {
        ModifiedObjects.addLockedTask(TASK);
        TaskResults.sent(TASK, TaskStatus.FIXED);
        TaskResults.linked(TASK.id(), 702495, true);
        assertEquals(new TaskResults.Result(TASK.id(), TaskStatus.FIXED, 702495, Link.LINKED),
                TaskResults.get(TASK.id()));
        ModifiedObjects.removeLockedTask(TASK);
        TaskResults.clearAll();
        // A link retried after a restart of JOSM, when no status was sent in this session
        TaskResults.linked(TASK.id(), 702495, false);
        assertEquals(Link.FAILED, TaskResults.get(TASK.id()).link());
    }

    /**
     * The done this session list keeps a task after it is started again, takes its link, and moves a task finished again
     * to the end (#117)
     */
    @Test
    void testSession() {
        final var other = new Task(9117, "Other bench", null, null, 10, null, null, new DataSet(), null, null, null,
                null, null, null, 0, null, null, null, false, null, null);
        TaskResults.sent(TASK, TaskStatus.FIXED);
        TaskResults.sent(other, TaskStatus.SKIPPED);
        ModifiedObjects.addLockedTask(TASK);
        TaskResults.linked(TASK.id(), 702495, true);
        assertEquals(List.of(9116L, 9117L), TaskResults.session().stream().map(done -> done.task().id()).toList(),
                "Starting a task again keeps it in the list");
        assertEquals(new TaskResults.Result(TASK.id(), TaskStatus.FIXED, 702495, Link.LINKED),
                TaskResults.session().get(0).result(), "The list shows the link");
        ModifiedObjects.removeLockedTask(TASK);
        TaskResults.sent(TASK, TaskStatus.ALREADY_FIXED);
        assertEquals(List.of(9117L, 9116L), TaskResults.session().stream().map(done -> done.task().id()).toList());
        assertEquals(TaskStatus.ALREADY_FIXED, TaskResults.session().get(1).result().status());
        // A link for a task that was not finished in this session doesn't add it
        TaskResults.linked(9999, 702496, true);
        assertEquals(2, TaskResults.session().size());
        TaskResults.clearAll();
        assertTrue(TaskResults.session().isEmpty(), "A server switch empties the list");
    }
}
