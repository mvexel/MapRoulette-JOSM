// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link MapRouletteServerPreference}
 */
@BasicPreferences
@MapRouletteConfig
class MapRouletteServerPreferenceTest {
    @AfterEach
    void tearDown() {
        ModifiedObjects.forgetAll();
    }

    /**
     * Switching servers forgets the tasks of the old one, so nothing about them is sent to the new one (#86)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testServerSwitchForgetsTasks() throws Exception {
        final var task = TaskAPI.get(147197178);
        ModifiedObjects.addLockedTask(task);
        ModifiedObjects.addModifiedTask(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null));
        ModifiedObjects.addUnlinkedFixed(task);
        assertEquals(Set.of(task.id()), MapRouletteServerPreference.forgetTasks("https://old.example.org/api/v2"));
        assertTrue(ModifiedObjects.getLockedTasks().isEmpty());
        assertTrue(ModifiedObjects.getModifiedTasks().isEmpty());
        assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty());
    }
}
