// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        TaskResults.sent(TASK.id(), TaskStatus.FIXED);
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
        TaskResults.sent(TASK.id(), TaskStatus.FIXED);
        TaskResults.linked(TASK.id(), 702495, true);
        assertEquals(new TaskResults.Result(TASK.id(), TaskStatus.FIXED, 702495, Link.LINKED),
                TaskResults.get(TASK.id()));
        ModifiedObjects.removeLockedTask(TASK);
        TaskResults.clearAll();
        // A link retried after a restart of JOSM, when no status was sent in this session
        TaskResults.linked(TASK.id(), 702495, false);
        assertEquals(Link.FAILED, TaskResults.get(TASK.id()).link());
    }
}
