// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.spi.preferences.Config;
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

    /**
     * Task links go to the website, never to the API host: a server other than maproulette.org has no known website
     * until the user sets one (#105)
     */
    @Test
    void testTaskUrl() {
        final var config = org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getInstance();
        try {
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig
                    .setInstance(new org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig(
                            "https://mr-api.osm.lol/api/v2"));
            assertEquals(Optional.empty(), MapRouletteServerPreference.getTaskUrl(1, 27), "Dev has no known website");
            Config.getPref().put("maproulette.website.url", "https://mr.example.org/");
            assertEquals(Optional.of("https://mr.example.org/challenge/1/task/27"),
                    MapRouletteServerPreference.getTaskUrl(1, 27), "A set website");
            Config.getPref().put("maproulette.website.url", null);
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig
                    .setInstance(new org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig(
                            "https://maproulette.org/api/v2"));
            assertEquals(Optional.of("https://maproulette.org/challenge/1/task/27"),
                    MapRouletteServerPreference.getTaskUrl(1, 27), "Production");
        } finally {
            Config.getPref().put("maproulette.website.url", null);
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.setInstance(config);
        }
    }
}
