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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;

import javax.swing.SwingUtilities;

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
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.plugins.maproulette.util.LoggingHandler;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.tools.HttpClient;

import com.github.tomakehurst.wiremock.stubbing.Scenario;

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
        ChangesetLinks.forgetAll();
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
            final var hook = hook(true);
            final var node = uploadedNode(false);
            GuiHelper.runInEDTAndWaitWithException(() -> {
                hook.checkUpload(new APIDataSet(node.getDataSet()));
                hook.modifyChangesetTags(new HashMap<>());
                // The upload succeeds, without a changeset id: MapRoulette finds the changeset itself
                node.setModified(false);
            });
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
        final var hook = hook(true);
        final var node = uploadedNode(false);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
            // The upload succeeds, without a changeset id: MapRoulette finds the changeset itself
            node.setModified(false);
        });
        sync();
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
        assertTrue(explained.isEmpty(), "No error dialog for the link");
        final var messages = handler.getRecords().stream().map(LogRecord::getMessage).filter(Objects::nonNull).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("is Fixed") && m.contains("did not link")),
                messages::toString);
    }

    /**
     * MapRoulette can close a changeset JOSM does not know about; the timer tries the waiting links again until
     * MapRoulette no longer answers that the changeset is open
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testOpenChangesetLinkedOnTick() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        final var wireMock = WireMocks.current();
        wireMock.register(
                put(urlPathEqualTo(taskPath + "/changeset")).inScenario("open").whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"changeset_open\"}"))
                        .willSetStateTo("closed"));
        wireMock.register(put(urlPathEqualTo(taskPath + "/changeset")).inScenario("open").whenScenarioStateIs("closed")
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        ChangesetLinks.await(List.of(task), 701183, server());
        ChangesetLinks.retryAll();
        assertTrue(ChangesetLinks.awaits(701183), "Still open: the link waits");
        ChangesetLinks.retryAll();
        assertFalse(ChangesetLinks.awaits(701183), "Linked");
        wireMock.verifyThat(2, putRequestedFor(urlPathEqualTo(taskPath + "/changeset")).withQueryParam("changesetId",
                equalTo("701183")));
        ChangesetLinks.retryAll();
        wireMock.verifyThat(2, putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
    }

    /**
     * A link MapRoulette refuses for good is not tried again
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testRefusedPendingLinkDropped() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        WireMocks.current().register(put(urlPathEqualTo(taskPath + "/changeset"))
                .willReturn(aResponse().withStatus(403).withBody("{\"error\":\"changeset_not_owned\"}")));
        ChangesetLinks.await(List.of(task), 701184, server());
        ChangesetLinks.retry(701184);
        assertFalse(ChangesetLinks.awaits(701184));
        WireMocks.current().verifyThat(1, putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
    }

    /**
     * MapRoulette links a task only within a day of it being marked Fixed; after that the link is dropped unsent
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testExpiredPendingLinkDropped() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ChangesetLinks.await(List.of(task), 701185, server());
        ChangesetLinks.retry(-1, Instant.now().plus(ChangesetLinks.WINDOW).plusSeconds(60));
        assertFalse(ChangesetLinks.awaits(701185));
        WireMocks.current().verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
    }

    /**
     * A task marked Fixed is linked only to an upload that has its OSM objects; the other one waits (#97)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testFixedNotLinkedToUnrelatedUpload() throws Exception {
        final var inUpload = TaskAPI.get(147197178);
        final var notInUpload = TaskAPI.get(132279499);
        final var inUploadPath = stub(inUpload.id(), TaskStatus.FIXED);
        final var notInUploadPath = stub(notInUpload.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(inUpload);
        ModifiedObjects.addUnlinkedFixed(notInUpload);
        upload(uploadedNode(false), 701188);
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(inUploadPath + "/changeset"))
                .withQueryParam("changesetId", equalTo("701188")));
        WireMocks.current().verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(notInUploadPath + "/changeset")));
        assertEquals(List.of(notInUpload), ModifiedObjects.getUnlinkedFixed(), "It waits for an upload that has it");
    }

    /**
     * A task that names no OSM objects, such as a missing feature, is linked to the upload that follows it
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testMissingFeatureLinked() throws Exception {
        final var task = new Task(1, "", null, null, 28410, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, false, null, null);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(task);
        final var node = uploadedNode(false);
        node.setOsmId(1, 1);
        upload(node, 701189);
        WireMocks.current().verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/changeset"))
                .withQueryParam("changesetId", equalTo("701189")));
        assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty());
    }

    /**
     * A task whose status could not be sent while the upload ran is not linked
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testFailedStatusNotLinked() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(task);
        final var node = uploadedNode(false);
        final var hook = hook(true);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
            MainApplication.worker.execute(() -> GuiHelper.runInEDTAndWait(() -> {
                // The status was refused, and the upload succeeds
                ModifiedObjects.removeUnlinkedFixed(task);
                node.setModified(false);
                node.setChangesetId(701190);
            }));
        });
        sync();
        WireMocks.current().verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
    }

    /**
     * A link waiting for a changeset is not sent after the user switched to another MapRoulette server: the task id
     * there may belong to an unrelated task (#86)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testPendingLinkNotSentToOtherServer() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ChangesetLinks.await(List.of(task), 701186, server());
        final var config = org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getInstance();
        try {
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.setInstance(
                    new org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig(server() + "/other"));
            ChangesetLinks.retryAll();
        } finally {
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.setInstance(config);
        }
        assertFalse(ChangesetLinks.awaits(701186));
        ChangesetLinks.await(List.of(task), 701186, server());
        assertEquals(Set.of(task.id()), ChangesetLinks.forgetAll(), "Forgotten when the user switches servers");
        assertFalse(ChangesetLinks.awaits(701186));
        WireMocks.current().verifyThat(exactly(0), anyRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
    }

    /**
     * After an upload, the tasks marked Fixed are not linked if the user switched servers while it ran (#86)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testNoLinkAfterServerSwitchDuringUpload() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = stub(task.id(), TaskStatus.FIXED);
        ModifiedObjects.addUnlinkedFixed(task);
        final var node = uploadedNode(false);
        final var hook = hook(true);
        final var config = org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getInstance();
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> {
                hook.checkUpload(new APIDataSet(node.getDataSet()));
                hook.modifyChangesetTags(new HashMap<>());
                MainApplication.worker.execute(() -> GuiHelper.runInEDTAndWait(() -> {
                    node.setModified(false);
                    node.setChangesetId(701187);
                    org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig
                            .setInstance(new org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig(
                                    server() + "/other"));
                }));
            });
            sync();
        } finally {
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.setInstance(config);
        }
        WireMocks.current().verifyThat(exactly(0), anyRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
        assertFalse(ChangesetLinks.awaits(701187));
    }

    /**
     * Get the MapRoulette server of the test
     *
     * @return The base URL
     */
    private static String server() {
        return org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl();
    }

    /**
     * Upload a node with a hook that closes the changeset; the upload succeeds
     *
     * @param node        The modified node
     * @param changesetId The changeset the upload goes to
     * @throws Exception if the test fails
     */
    private static void upload(Node node, long changesetId) throws Exception {
        final var hook = hook(true);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(node.getDataSet()));
            hook.modifyChangesetTags(new HashMap<>());
            MainApplication.worker.execute(() -> GuiHelper.runInEDTAndWait(() -> {
                node.setModified(false);
                node.setChangesetId((int) changesetId);
            }));
        });
        sync();
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
        // The OSM object of task 147197178
        node.setOsmId(6318952236L, 1);
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
