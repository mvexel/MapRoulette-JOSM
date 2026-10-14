// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import javax.swing.text.html.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

import mockit.Mock;
import mockit.MockUp;

/**
 * Test class for {@link LateUploadHook}
 */
@BasicPreferences
@MapRouletteConfig
class LateUploadHookTest {
    @AfterEach
    void tearDown() {
        ModifiedObjects.getModifiedTasks().forEach(ModifiedObjects::removeModifiedTask);
    }

    /**
     * Non-regression test: a runtime exception from MapRoulette must not abort the OSM upload
     */
    @Test
    @MapRouletteLogin
    void testRuntimeExceptionDoesNotAbortUpload() {
        final var task = assertDoesNotThrow(() -> TaskAPI.get(147197178));
        ModifiedObjects.addModifiedTask(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null));
        new MockUp<TaskAPI>() {
            @Mock
            boolean updateStatus(long task, TaskStatus status, String comment, String tags, Boolean requestReview,
                    Map<String, Option> completionResponses) {
                throw new IllegalStateException("Unexpected server response");
            }
        };
        assertDoesNotThrow(() -> new LateUploadHook().modifyChangesetTags(new HashMap<>()));
    }

    /**
     * Without a MapRoulette login, no task status is sent and the task stays modified
     */
    @Test
    void testNoStatusPushWithoutLogin() {
        final var task = assertDoesNotThrow(() -> TaskAPI.get(147197178));
        final var modifiedTask = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addModifiedTask(modifiedTask);
        final var wireMock = WireMocks.current();
        final int requestsBefore = wireMock.find(anyRequestedFor(anyUrl())).size();
        new LateUploadHook().modifyChangesetTags(new HashMap<>());
        wireMock.verifyThat(exactly(requestsBefore), anyRequestedFor(anyUrl()));
        assertEquals(modifiedTask, ModifiedObjects.getModifiedTask(task.id()));
    }
}
