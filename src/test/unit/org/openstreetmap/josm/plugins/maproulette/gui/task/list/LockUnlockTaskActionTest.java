// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.enums.Difficulty;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.PublicUser;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;
import org.openstreetmap.josm.tools.HttpClient;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import mockit.Mock;
import mockit.MockUp;

/**
 * Test class for {@link LockUnlockTaskAction}
 */
@BasicPreferences
@Main
@Projection
@MapRouletteConfig
class LockUnlockTaskActionTest {
    private TaskTableModel model;
    private JTable table;

    @BeforeEach
    void setUp() {
        this.model = new TaskTableModel();
        MainApplication.getLayerManager().addAndFireLayerChangeListener(this.model);
        MainApplication.getLayerManager().addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                Collections.singleton(new ClusteredPoint(132279499L, 0L, "null", "title", 0L, "parentName",
                        new Point(0, 0), "bounding", "blurb", Instant.EPOCH, Difficulty.NORMAL, 2, TaskStatus.CREATED,
                        null, Instant.EPOCH, 0L, new PublicUser(0L, null, "someone", Instant.EPOCH, null),
                        new PointReview(null, null, null, null, null, null, null, null, null), 0, null, false, null))));
        this.table = new JTable(this.model);
        this.table.setRowSorter(new TableRowSorter<>(this.model));
        this.table.getSelectionModel().setSelectionInterval(0, 0);
    }

    @AfterEach
    void tearDown() {
        MainApplication.getLayerManager().removeAndFireLayerChangeListener(this.model);
    }

    @Test
    @MapRouletteLogin
    void testStartEnabledWithLogin() {
        final var action = new LockUnlockTaskAction(this.table);
        action.updateEnabledState();
        assertTrue(action.isEnabled());
    }

    @Test
    void testStartDisabledWithoutLogin() {
        final var action = new LockUnlockTaskAction(this.table);
        action.updateEnabledState();
        assertFalse(action.isEnabled());
        assertTrue(((String) action.getValue(Action.SHORT_DESCRIPTION)).contains("MapRoulette website"));
    }

    /**
     * Start, clicked on the EDT, sends its request from the worker and records the lock on the EDT (#32)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testStartRunsOffEdt() throws Exception {
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
            final var action = new LockUnlockTaskAction(this.table);
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            // The worker job hands its result to the EDT with invokeAndWait, so the lock is recorded once it is done
            MainApplication.worker.submit(() -> {
                /* Sync worker */ }).get(10, TimeUnit.SECONDS);
            GuiHelper.runInEDTAndWait(() -> {
                /* Sync EDT */ });
            assertEquals(List.of(false), onEdt);
            assertNotNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            final var locked = ModifiedObjects.getLockedTask(132279499L);
            if (locked != null) {
                ModifiedObjects.removeLockedTask(locked);
            }
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }

    /**
     * MapRoulette still has a lock JOSM forgot (from an earlier session): Start offers to stop it, then starts the task
     * (#90)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartStopsForgottenLock() throws Exception {
        final var wireMock = startWithForgottenLock(0);
        try {
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/125/release")));
            wireMock.verifyThat(2, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNotNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * Declining to stop the forgotten lock leaves it alone
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartKeepsForgottenLock() throws Exception {
        final var wireMock = startWithForgottenLock(1);
        try {
            wireMock.verifyThat(0, getRequestedFor(urlPathEqualTo("/api/v2/task/125/release")));
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * Stop works on the started task when no row is selected, so it can be stopped from anywhere (#108)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStopWithoutSelectionStopsStartedTask() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        final var task = TaskAPI.get(132279499L);
        ModifiedObjects.addLockedTask(task);
        try {
            this.table.clearSelection();
            final var action = new LockUnlockTaskAction(this.table);
            action.updateEnabledState();
            assertTrue(action.isEnabled(), "Stop is enabled with no selection");
            assertEquals("Stop Task", action.getValue(Action.NAME));
            GuiHelper.runInEDTAndWait(() -> action.actionPerformed(null));
            sync();
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/release")));
            assertNull(ModifiedObjects.getLockedTask(132279499L));
            action.updateEnabledState();
            assertFalse(action.isEnabled(), "Nothing left to stop");
        } finally {
            cleanUp();
        }
    }

    /**
     * A double-click on Start sends one start: the action is disabled until the first one returns (#91)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testDoubleClickStartsOnce() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/start")).atPriority(1)
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        final var action = new LockUnlockTaskAction(this.table);
        final var enabledWhileRunning = new AtomicBoolean(true);
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
                enabledWhileRunning.set(action.isEnabled());
                action.actionPerformed(null);
            });
            sync();
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertFalse(enabledWhileRunning.get(), "Disabled while the start runs");
            assertTrue(action.isEnabled(), "Enabled again afterwards");
            assertNotNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * After Start, the Current Task window opens with the started task, so the user finds its status buttons (#92)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartShowsCurrentTask() throws Exception {
        WireMocks.current().register(get(urlPathEqualTo("/api/v2/task/132279499/start")).atPriority(1)
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        final var action = new LockUnlockTaskAction(this.table);
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            sync();
            final var panel = MainApplication.getMap().getToggleDialog(CurrentTaskPanel.class);
            assertNotNull(panel);
            assertTrue(panel.isDialogShowing());
            assertTrue(panel.showsLockedTask());
        } finally {
            cleanUp();
        }
    }

    /**
     * MapRoulette answers 409 naming the task being started when the user already started it: that is a success (#91)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartOfOwnLockIsSuccess() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/start")).atPriority(1)
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"Conflict\",\"message\":\"locked\",\"lockedTaskId\":132279499,"
                                + "\"bundledTasks\":[]}")));
        TestUtils.assumeWorkingJMockit();
        final var explained = new CopyOnWriteArrayList<Exception>();
        new MockUp<ExceptionDialogUtil>() {
            @Mock
            void explainException(Exception exception) {
                explained.add(exception);
            }
        };
        final var action = new LockUnlockTaskAction(this.table);
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            sync();
            assertNotNull(ModifiedObjects.getLockedTask(132279499L));
            assertTrue(explained.isEmpty(), explained::toString);
        } finally {
            cleanUp();
        }
    }

    /**
     * Start a task while MapRoulette holds task 125 for the user, answering the question with {@code choice}
     *
     * @param choice The option picked in the question
     * @return The WireMock client
     */
    private WireMock startWithForgottenLock(int choice) throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/start")).atPriority(1).inScenario("forgotten")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json").withBody(
                        "{\"status\":\"Conflict\",\"message\":\"locked\",\"lockedTaskId\":125,\"bundledTasks\":[]}")));
        wireMock.register(get(urlPathEqualTo("/api/v2/task/125/release")).inScenario("forgotten")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("released")
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/start")).atPriority(1).inScenario("forgotten")
                .whenScenarioStateIs("released").willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        final var asked = new AtomicInteger();
        new MockUp<JOptionPane>() {
            @Mock
            int showOptionDialog(Component parentComponent, Object message, String title, int optionType,
                    int messageType, Icon icon, Object[] options, Object initialValue) {
                asked.incrementAndGet();
                return choice;
            }
        };
        final var action = new LockUnlockTaskAction(this.table);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            action.updateEnabledState();
            action.actionPerformed(null);
        });
        // Start, the question (queued on the EDT), then the second start
        sync();
        sync();
        sync();
        assertEquals(1, asked.get(), "Start must ask before stopping the forgotten task");
        return wireMock;
    }

    /**
     * Stop, clicked on the EDT, releases the lock from the worker (#32). A picked status is sent when it is picked
     * (D15), so Stop drops unsent answers instead of sending them.
     *
     * @throws Exception if the test fails
     */
    @Test
    void testStopRunsOffEdt() throws Exception {
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
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        wireMock.register(
                put(urlPathEqualTo("/api/v2/task/132279499/" + TaskStatus.SKIPPED.code())).willReturn(aResponse()));
        try {
            final var action = new LockUnlockTaskAction(this.table);
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            sync();
            final var locked = ModifiedObjects.getLockedTask(132279499L);
            assertNotNull(locked, "Start must lock the task before Stop can be tested");
            ModifiedObjects.addModifiedTask(new ModifiedTask(locked, TaskStatus.SKIPPED, null, null, null, null));
            onEdt.clear();

            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            sync();
            assertFalse(onEdt.isEmpty(), "Stop made no MapRoulette request");
            assertFalse(onEdt.contains(true), "MapRoulette requests made on the EDT: " + onEdt);
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/release")));
            wireMock.verifyThat(0,
                    putRequestedFor(urlPathEqualTo("/api/v2/task/132279499/" + TaskStatus.SKIPPED.code())));
            assertNull(ModifiedObjects.getLockedTask(132279499L));
            assertNull(ModifiedObjects.getModifiedTask(132279499L));
        } finally {
            final var modified = ModifiedObjects.getModifiedTask(132279499L);
            if (modified != null) {
                ModifiedObjects.removeModifiedTask(modified);
            }
            final var locked = ModifiedObjects.getLockedTask(132279499L);
            if (locked != null) {
                ModifiedObjects.removeLockedTask(locked);
            }
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }

    private static void cleanUp() {
        final var modified = ModifiedObjects.getModifiedTask(132279499L);
        if (modified != null) {
            ModifiedObjects.removeModifiedTask(modified);
        }
        final var locked = ModifiedObjects.getLockedTask(132279499L);
        if (locked != null) {
            ModifiedObjects.removeLockedTask(locked);
        }
    }

    /**
     * Wait for the worker, then the EDT. The worker job hands its result to the EDT with invokeAndWait.
     */
    private static void sync() throws Exception {
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
