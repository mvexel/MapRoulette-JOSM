// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import javax.swing.text.html.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.tools.HttpClient;

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
        assertDoesNotThrow(LateUploadHookTest::sync);
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

    /**
     * JOSM calls the hook on the EDT; the status is sent from the worker (#34)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testStatusSentOffEdt() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var wireMock = WireMocks.current();
        final var taskPath = "/api/v2/task/" + task.id();
        wireMock.register(
                put(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())).willReturn(aResponse().withStatus(200)));
        wireMock.register(get(urlPathEqualTo(taskPath + "/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        wireMock.register(put(urlPathEqualTo(taskPath + "/changeset"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        ModifiedObjects.addLockedTask(task);
        ModifiedObjects.addModifiedTask(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null));
        final var originalAuth = MapRouletteClient.getInstance().auth();
        final var onEdt = new CopyOnWriteArrayList<Boolean>();
        MapRouletteClient.getInstance().setAuthProvider(new AuthProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Set<Capability> capabilities() {
                return EnumSet.allOf(Capability.class);
            }

            @Override
            public void authenticate(HttpClient request) {
                onEdt.add(SwingUtilities.isEventDispatchThread());
            }
        });
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> new LateUploadHook().modifyChangesetTags(new HashMap<>()));
            sync();
            assertEquals(List.of(false, false, false), onEdt);
            wireMock.verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
            assertNull(ModifiedObjects.getModifiedTask(task.id()));
            assertFalse(ModifiedObjects.getLockedTasks().contains(task));
        } finally {
            ModifiedObjects.removeLockedTask(task);
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }

    private static void sync() throws Exception {
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
