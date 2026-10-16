// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;

import javax.swing.SwingUtilities;

import javax.swing.text.html.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Changeset;
import org.openstreetmap.josm.data.osm.ChangesetCache;
import org.openstreetmap.josm.data.osm.ChangesetCacheEvent;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
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
        ModifiedObjects.getUnlinkedFixed().forEach(ModifiedObjects::removeUnlinkedFixed);
        ModifiedObjects.getLockedTasks().forEach(ModifiedObjects::removeLockedTask);
    }

    /**
     * Non-regression test: a runtime exception from MapRoulette must not reach the worker
     */
    @Test
    @MapRouletteLogin
    void testRuntimeExceptionDoesNotEscape() {
        final var task = assertDoesNotThrow(() -> TaskAPI.get(147197178));
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addModifiedTask(modified);
        ModifiedObjects.addUnlinkedFixed(task);
        new MockUp<TaskAPI>() {
            @Mock
            boolean updateStatus(long task, TaskStatus status, String comment, String tags, Boolean requestReview,
                    Map<String, Option> completionResponses) {
                throw new IllegalStateException("Unexpected server response");
            }
        };
        assertDoesNotThrow(() -> LateUploadHook.sendStatuses(List.of(modified)));
        assertDoesNotThrow(LateUploadHookTest::sync);
        assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty(), "A status that was not sent is not linked");
    }

    /**
     * A picked status is sent and the task released at once, as on the web (D15)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStatusSentAtOnce() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addLockedTask(task);
        ModifiedObjects.addModifiedTask(modified);
        ModifiedObjects.addUnlinkedFixed(task);
        MainApplication.worker.submit(() -> LateUploadHook.sendStatuses(List.of(modified))).get(10, TimeUnit.SECONDS);
        sync();
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())));
        WireMocks.current().verifyThat(getRequestedFor(urlPathEqualTo(taskPath + "/release")));
        WireMocks.current().verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
        assertNull(ModifiedObjects.getLockedTask(task.id()));
        assertEquals(List.of(task), ModifiedObjects.getUnlinkedFixed(), "The changeset is linked after the upload");
    }

    /**
     * Without a MapRoulette login, no link is sent and the task keeps waiting
     */
    @Test
    void testNoLinkWithoutLogin() {
        final var task = assertDoesNotThrow(() -> TaskAPI.get(147197178));
        ModifiedObjects.addUnlinkedFixed(task);
        final var wireMock = WireMocks.current();
        final int requestsBefore = wireMock.find(anyRequestedFor(anyUrl())).size();
        hook(true).modifyChangesetTags(new HashMap<>());
        assertDoesNotThrow(LateUploadHookTest::sync);
        wireMock.verifyThat(exactly(requestsBefore), anyRequestedFor(anyUrl()));
        assertEquals(List.of(task), ModifiedObjects.getUnlinkedFixed());
    }

    /**
     * JOSM calls the hook on the EDT; the link is sent from the worker (#34)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testLinkSentOffEdt() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(task);
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
            assertEquals(List.of(false), onEdt);
            // No uploaded primitives, so no changeset id: MapRoulette finds the changeset itself
            WireMocks.current().verifyThat(
                    putRequestedFor(urlPathEqualTo(taskPath + "/changeset")).withQueryParam("changesetId", absent()));
            assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty());
        } finally {
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }

    /**
     * After a successful upload, a task marked Fixed is linked to the closed changeset JOSM uploaded to (#80, D15)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testFixedLinkedAfterSuccessfulUpload() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(task);
        final var node = uploadedNode(false);
        final var hook = hook(true);
        final var linkedBeforeUpload = new boolean[1];
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
            // What JOSM does next in the same EDT event: queue the upload, which succeeds
            MainApplication.worker.execute(() -> {
                linkedBeforeUpload[0] = !WireMocks.current()
                        .find(putRequestedFor(urlPathEqualTo(taskPath + "/changeset"))).isEmpty();
                GuiHelper.runInEDTAndWait(() -> {
                    node.setModified(false);
                    node.setChangesetId(701181);
                });
            });
        });
        sync();
        assertFalse(linkedBeforeUpload[0], "The link must wait for the upload");
        WireMocks.current().verifyThat(exactly(0),
                putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())));
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/changeset"))
                .withQueryParam("changesetId", equalTo("701181")));
        assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty());
    }

    /**
     * A failed or cancelled upload leaves the primitives modified: the task waits for the next upload
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testFixedWaitsAfterFailedUpload() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(task);
        final var node = uploadedNode(false);
        final var hook = hook(true);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
        });
        sync();
        WireMocks.current().verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
        assertEquals(List.of(task), ModifiedObjects.getUnlinkedFixed());
    }

    /**
     * MapRoulette only links a task to a closed changeset, so a changeset left open is linked once JOSM closes it
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testOpenChangesetLinkedWhenClosed() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(task);
        final var node = uploadedNode(false);
        final var hook = hook(false);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
            // The upload succeeds, and JOSM leaves the changeset open
            MainApplication.worker.execute(() -> GuiHelper.runInEDTAndWait(() -> {
                node.setModified(false);
                node.setChangesetId(701182);
            }));
        });
        sync();
        WireMocks.current().verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
        final var changeset = new Changeset(701182);
        changeset.setOpen(false);
        hook.changesetCacheUpdated(new ChangesetCacheEvent() {
            @Override
            public ChangesetCache getSource() {
                return ChangesetCache.getInstance();
            }

            @Override
            public Collection<Changeset> getAddedChangesets() {
                return List.of();
            }

            @Override
            public Collection<Changeset> getRemovedChangesets() {
                return List.of();
            }

            @Override
            public Collection<Changeset> getUpdatedChangesets() {
                return List.of(changeset);
            }
        });
        sync();
        WireMocks.current().verifyThat(1, putRequestedFor(urlPathEqualTo(taskPath + "/changeset"))
                .withQueryParam("changesetId", equalTo("701182")));
    }

    /**
     * A status MapRoulette rejects is not sent: the user is told why, and the task stays started so another status can
     * be picked
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testRejectedStatusNotSent() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        WireMocks.current().register(
                put(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())).willReturn(aResponse().withStatus(400)
                        .withBody("{\"status\":\"KO\",\"message\":\"Invalid task status supplied.\"}")));
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addLockedTask(task);
        ModifiedObjects.addModifiedTask(modified);
        ModifiedObjects.addUnlinkedFixed(task);
        TestUtils.assumeWorkingJMockit();
        final var explained = new CopyOnWriteArrayList<Exception>();
        new MockUp<ExceptionDialogUtil>() {
            @Mock
            void explainException(Exception exception) {
                explained.add(exception);
            }
        };
        MainApplication.worker.submit(() -> LateUploadHook.sendStatuses(List.of(modified))).get(10, TimeUnit.SECONDS);
        sync();
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
        assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty(), "A status that was not sent is not linked");
        assertEquals(task, ModifiedObjects.getLockedTask(task.id()), "The task stays started");
        assertEquals(1, explained.size(), "The user is told");
        assertEquals("Invalid task status supplied.",
                ((MapRouletteHttpException) explained.get(0)).serverMessage().orElseThrow(),
                "with MapRoulette's reason");
    }

    /**
     * A refused changeset link does not undo the status that was already sent (#80)
     *
     * @param handler The log handler
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    @LoggingHandler
    void testRefusedLinkKeepsSentStatus(LoggingHandler.TestHandler handler) throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        WireMocks.current().register(put(urlPathEqualTo(taskPath + "/changeset"))
                .willReturn(aResponse().withStatus(403).withBody("{\"error\":\"changeset_not_owned\"}")));
        ModifiedObjects.addUnlinkedFixed(task);
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
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
        assertTrue(explained.isEmpty(), "No error dialog for the link");
        final var messages = handler.getRecords().stream().map(LogRecord::getMessage).filter(Objects::nonNull).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("is Fixed") && m.contains("did not link")),
                messages::toString);
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
