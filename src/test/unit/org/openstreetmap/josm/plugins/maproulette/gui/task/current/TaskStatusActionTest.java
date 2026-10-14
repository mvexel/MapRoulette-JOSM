// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.Action;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link TaskStatusAction}
 */
@BasicPreferences
class TaskStatusActionTest {
    private static final Task TASK = new Task(1, "", null, null, 15318, null, null, null, null, null, null, null, null,
            null, 0, null, null, null, false, null, null);

    @Test
    @MapRouletteLogin
    void testEnabledWithLogin() {
        final var action = new TaskStatusAction(TaskStatus.FIXED, () -> TASK, () -> null);
        action.updateEnabledState();
        assertTrue(action.isEnabled());
    }

    @Test
    void testDisabledWithoutLogin() {
        final var action = new TaskStatusAction(TaskStatus.FIXED, () -> TASK, () -> null);
        action.updateEnabledState();
        assertFalse(action.isEnabled());
        assertTrue(((String) action.getValue(Action.SHORT_DESCRIPTION)).contains("MapRoulette website"));
    }
}
