// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Component;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteHttpException;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.plugins.maproulette.util.LoggingHandler;
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
        assertDoesNotThrow(() -> hook(true).modifyChangesetTags(new HashMap<>()));
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
        hook(true).modifyChangesetTags(new HashMap<>());
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
            public void authenticate(HttpClient request, boolean loginRequired) {
                onEdt.add(SwingUtilities.isEventDispatchThread());
            }
        });
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> hook(true).modifyChangesetTags(new HashMap<>()));
            sync();
            assertEquals(List.of(false, false, false), onEdt);
            // No uploaded primitives, so no changeset id: MapRoulette finds the changeset itself
            wireMock.verifyThat(
                    putRequestedFor(urlPathEqualTo(taskPath + "/changeset")).withQueryParam("changesetId", absent()));
            assertNull(ModifiedObjects.getModifiedTask(task.id()));
            assertFalse(ModifiedObjects.getLockedTasks().contains(task));
        } finally {
            ModifiedObjects.removeLockedTask(task);
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }

    /**
     * Only FIXED waits for the upload: other statuses are still sent before it
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testOtherStatusSentBeforeUpload() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.SKIPPED);
        ModifiedObjects.addModifiedTask(new ModifiedTask(task, TaskStatus.SKIPPED, null, null, null, null));
        final var node = uploadedNode(true);
        final var hook = hook(true);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
        });
        sync();
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.SKIPPED.code())));
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
    }

    /**
     * FIXED is sent after a successful upload, then the task is linked to the closed changeset (#45)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testFixedSentAfterSuccessfulUpload() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addModifiedTask(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null));
        final var node = uploadedNode(false);
        final var hook = hook(true);
        final var sentBeforeUpload = new boolean[1];
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
            // What JOSM does next in the same EDT event: queue the upload, which succeeds
            MainApplication.worker.execute(() -> {
                sentBeforeUpload[0] = !WireMocks.current()
                        .find(putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code()))).isEmpty();
                GuiHelper.runInEDTAndWait(() -> {
                    node.setModified(false);
                    node.setChangesetId(701181);
                });
            });
        });
        sync();
        assertFalse(sentBeforeUpload[0], "FIXED must wait for the upload");
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())));
        // MapRoulette's own guess at the changeset can miss it (#80), so name the one JOSM uploaded to
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/changeset"))
                .withQueryParam("changesetId", equalTo("701181")));
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
    }

    /**
     * Stop Task's Drop runs on the worker before the FIXED job of an upload queued earlier: that FIXED is not sent (#78)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testFixedDroppedWhileUploadQueued() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addModifiedTask(modified);
        final var node = uploadedNode(true);
        final var hook = hook(true);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
            // What Stop Task's Drop queues, before the FIXED job
            MainApplication.worker.execute(() -> ModifiedObjects.removeModifiedTask(modified));
        });
        sync();
        WireMocks.current().verifyThat(exactly(0),
                putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())));
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
    }

    /**
     * A failed or cancelled upload leaves the primitives modified: FIXED is kept for the next upload (#45)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testFixedHeldAfterFailedUpload() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        final var modifiedTask = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addModifiedTask(modifiedTask);
        final var node = uploadedNode(false);
        final var hook = hook(true);
        TestUtils.assumeWorkingJMockit();
        new MockUp<ConditionalOptionPaneUtil>() {
            @Mock
            void showMessageDialog(String preferenceKey, Component parent, Object message, String title,
                    int messageType) {
                // The held message; nothing to click in a test
            }
        };
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
        });
        sync();
        WireMocks.current().verifyThat(exactly(0),
                putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())));
        assertEquals(modifiedTask, ModifiedObjects.getModifiedTask(task.id()));
    }

    /**
     * MapRoulette only links a task to a closed changeset, so an open one is not linked (#45)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testOpenChangesetNotLinked() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addModifiedTask(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null));
        final var node = uploadedNode(true);
        final var hook = hook(false);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
        });
        sync();
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())));
        WireMocks.current().verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
    }

    /**
     * A status MapRoulette rejects (an invalid status change or a paused challenge) is dropped, not sent with every
     * later upload
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testRejectedStatusDropped() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        WireMocks.current().register(
                put(urlPathEqualTo(taskPath + "/" + TaskStatus.SKIPPED.code())).willReturn(aResponse().withStatus(400)
                        .withBody("{\"status\":\"KO\",\"message\":\"Invalid task status supplied.\"}")));
        ModifiedObjects.addModifiedTask(new ModifiedTask(task, TaskStatus.SKIPPED, null, null, null, null));
        TestUtils.assumeWorkingJMockit();
        final var explained = new CopyOnWriteArrayList<Exception>();
        new MockUp<ExceptionDialogUtil>() {
            @Mock
            void explainException(Exception exception) {
                explained.add(exception);
            }
        };
        GuiHelper.runInEDTAndWaitWithException(() -> hook(true).modifyChangesetTags(new HashMap<>()));
        sync();
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
        assertEquals(1, explained.size(), "The user is told");
        assertEquals("Invalid task status supplied.",
                ((MapRouletteHttpException) explained.get(0)).serverMessage().orElseThrow(),
                "with MapRoulette's reason");
    }

    /**
     * A 403 that MapRoulette will repeat drops the status; one a new login or the server's settings can lift keeps it
     *
     * @param body    The 403 response body
     * @param dropped {@code true} if the status should be dropped
     * @param handler The log handler
     * @throws Exception if the test fails
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "{\"error\":\"choice_task_status\"}|true",
            "{\"error\":\"challenge_draft\"}|true", "{\"status\":\"Forbidden\",\"message\":\"no\"}|true",
            "{\"error\":\"mobile_writes_disabled\"}|false", "{\"error\":\"insufficient_scope\"}|false" })
    @MapRouletteLogin
    @LoggingHandler
    void testRefusedStatus(String body, boolean dropped, LoggingHandler.TestHandler handler) throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        WireMocks.current().register(put(urlPathEqualTo(taskPath + "/" + TaskStatus.SKIPPED.code()))
                .willReturn(aResponse().withStatus(403).withBody(body)));
        final var modified = new ModifiedTask(task, TaskStatus.SKIPPED, null, null, null, null);
        ModifiedObjects.addModifiedTask(modified);
        TestUtils.assumeWorkingJMockit();
        final var explained = new CopyOnWriteArrayList<Exception>();
        new MockUp<ExceptionDialogUtil>() {
            @Mock
            void explainException(Exception exception) {
                explained.add(exception);
            }
        };
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> hook(true).modifyChangesetTags(new HashMap<>()));
            sync();
            assertEquals(dropped, ModifiedObjects.getModifiedTask(task.id()) == null);
            assertEquals(1, explained.size(), "The user is told why");
            assertEquals(dropped,
                    handler.getRecords().stream().anyMatch(
                            r -> r.getMessage().contains("task " + task.id()) && r.getMessage().contains("dropped")),
                    "and which task's status was dropped");
        } finally {
            ModifiedObjects.removeModifiedTask(modified);
        }
    }

    /**
     * Stub the MapRoulette calls for sending a status
     *
     * @param id     The task id
     * @param status The status
     * @return The task path
     */
    private static String stub(long id, TaskStatus status) {
        final var wireMock = WireMocks.current();
        final var taskPath = "/api/v2/task/" + id;
        wireMock.register(put(urlPathEqualTo(taskPath + "/" + status.code())).willReturn(aResponse().withStatus(200)));
        wireMock.register(get(urlPathEqualTo(taskPath + "/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        wireMock.register(put(urlPathEqualTo(taskPath + "/changeset"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        return taskPath;
    }

    /**
     * Create a modified node to upload
     *
     * @param uploaded {@code true} if the upload succeeds (the node is not modified afterwards)
     * @return The node
     */
    private static Node uploadedNode(boolean uploaded) {
        final var node = new Node(LatLon.ZERO);
        node.setOsmId(1, 1);
        node.setModified(true);
        new DataSet().addPrimitive(node);
        if (uploaded) {
            node.setModified(false);
        }
        return node;
    }

    /**
     * Create the hook without the upload dialog
     *
     * @param closesChangeset {@code true} if the changeset is closed after the upload
     * @return The hook
     */
    private static LateUploadHook hook(boolean closesChangeset) {
        return new LateUploadHook() {
            @Override
            boolean isChangesetClosedAfterUpload() {
                return closesChangeset;
            }
        };
    }

    /**
     * Wait for the hook's jobs: the EDT event that queues the FIXED job, the worker, then the listeners on the EDT
     *
     * @throws Exception if the test fails
     */
    private static void sync() throws Exception {
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
