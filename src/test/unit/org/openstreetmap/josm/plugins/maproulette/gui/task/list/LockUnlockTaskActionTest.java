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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.AbstractPrimitiveTask;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.enums.Difficulty;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.PublicUser;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Utils;

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
        MainApplication.getLayerManager().addLayer(layer(TaskStatus.CREATED));
        this.table = new JTable(this.model);
        this.table.setRowSorter(new TableRowSorter<>(this.model));
        this.table.getSelectionModel().setSelectionInterval(0, 0);
    }

    /**
     * A layer with task 132279499
     *
     * @param status The task's status
     * @return The layer
     */
    private static MapRouletteClusteredPointLayer layer(TaskStatus status) {
        return new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                Collections.singleton(new ClusteredPoint(132279499L, 0L, "null", "title", 0L, "parentName",
                        new Point(0, 0), "bounding", "blurb", Instant.EPOCH, Difficulty.NORMAL, 2, status, null,
                        Instant.EPOCH, 0L, new PublicUser(0L, null, "someone", Instant.EPOCH, null),
                        new PointReview(null, null, null, null, null, null, null, null, null), 0, null, false, null,
                        Map.of())));
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
            final var dialog = MapRouletteDialog.get();
            assertNotNull(dialog);
            assertTrue(dialog.isDialogShowing());
            assertTrue(dialog.currentTask().showsLockedTask());
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
        // The panel shows the started task with the question: the recorded task, as task 125
        wireMock.register(get(urlPathEqualTo("/api/v2/task/125")).atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(Files.readString(Path.of("src/test/resources/__files/api/v2/task/132279499/start"))
                                .replace("\"id\": 132279499", "\"id\": 125"))));
        final var dialogs = new AtomicInteger();
        new MockUp<JOptionPane>() {
            @Mock
            int showOptionDialog(Component parentComponent, Object message, String title, int optionType,
                    int messageType, Icon icon, Object[] options, Object initialValue) {
                dialogs.incrementAndGet();
                return 1;
            }
        };
        final var action = new LockUnlockTaskAction(this.table);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            action.updateEnabledState();
            action.actionPerformed(null);
        });
        // Start, then the question (queued on the EDT)
        sync();
        sync();
        // Asked in the panel, which shows task 125, not in a dialog (#116)
        assertEquals(0, dialogs.get());
        assertNotNull(stateText("Task 125 is still started"));
        click(choice == 0 ? "Stop task 125 and start 132279499" : "Cancel");
        // The release and the second start
        sync();
        sync();
        return wireMock;
    }

    /**
     * Start while this JOSM has another task started asks in the panel first, without asking MapRoulette (#116).
     * Putting the started task back stops it, then starts the selected one.
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartBlockedPutBack() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/147197178/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        final var started = TaskAPI.get(147197178L);
        GuiHelper.runInEDTAndWait(() -> ModifiedObjects.addLockedTask(started));
        try {
            final var action = new LockUnlockTaskAction(this.table);
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            sync();
            wireMock.verifyThat(0, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNotNull(stateText("Start task 132279499? Task 147197178 is still started."));
            click("Finish task 147197178 first");
            sync();
            wireMock.verifyThat(0, getRequestedFor(urlPathEqualTo("/api/v2/task/147197178/release")));
            assertNotNull(ModifiedObjects.getLockedTask(147197178L), "Finish first keeps the task started");

            GuiHelper.runInEDTAndWaitWithException(() -> action.actionPerformed(null));
            sync();
            click("Stop task 147197178 and start 132279499");
            // The stop, then the start
            for (var i = 0; i < 4; i++) {
                sync();
            }
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/147197178/release")));
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNull(ModifiedObjects.getLockedTask(147197178L));
            assertNotNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            Optional.ofNullable(ModifiedObjects.getLockedTask(147197178L)).ifPresent(ModifiedObjects::removeLockedTask);
            cleanUp();
        }
    }

    /**
     * Find the state line of the current task section, by its start
     *
     * @param start The start of its text
     * @return The label
     */
    static JMultilineLabel stateText(String start) {
        return TaskListPanelTest.find(Objects.requireNonNull(CurrentTaskPanel.get()), JMultilineLabel.class,
                l -> l.getText().contains(Utils.escapeReservedCharactersHTML(start)));
    }

    /**
     * Click a button of the current task section on the EDT
     *
     * @param label The button's label
     */
    static void click(String label) {
        GuiHelper.runInEDTAndWait(() -> TaskListPanelTest
                .find(Objects.requireNonNull(CurrentTaskPanel.get()), JButton.class, b -> label.equals(b.getText()))
                .doClick());
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

    /**
     * Start on a task that is already Fixed asks first; Cancel starts nothing (#142)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartDoneTaskCancelled() throws Exception {
        final var wireMock = WireMocks.current();
        final var messages = startDone(1);
        try {
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).endsWith("|Cancel"), "Cancel is the default: " + messages.get(0));
            wireMock.verifyThat(0, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * Start on a task that is already Fixed asks first; Start anyway starts it (#142)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartDoneTaskConfirmed() throws Exception {
        final var wireMock = WireMocks.current();
        final var messages = startDone(0);
        try {
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).startsWith("Task 132279499 is already done (Fixed, "), messages.get(0));
            assertFalse(messages.get(0).contains("Another mapper"), "Who finished it is not known");
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNotNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * A status sent in this session counts, even though the server list still says open (#146)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartTaskSentDoneAsks() throws Exception {
        final var messages = startDone(TaskStatus.CREATED, 1,
                () -> ModifiedObjects.setSentStatus(132279499L, TaskStatus.ALREADY_FIXED));
        try {
            assertEquals(List.of("Task 132279499 is already done (Already fixed).\nStart it anyway?|Cancel"), messages);
        } finally {
            cleanUp();
        }
    }

    /**
     * Closing the question (Esc or the close button) starts nothing (#142)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartDoneTaskClosed() throws Exception {
        final var wireMock = WireMocks.current();
        assertEquals(1, startDone(JOptionPane.CLOSED_OPTION).size());
        try {
            wireMock.verifyThat(0, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * A task another mapper finished: the question says MapRoulette won't take a new status (#142)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartTaskDoneByOther() throws Exception {
        final var login = MapRouletteClient.getInstance().auth();
        MapRouletteClient.getInstance().setAuthProvider(new AuthProvider() {
            @Override
            public boolean isAvailable() {
                return login.isAvailable();
            }

            @Override
            public Set<Capability> capabilities() {
                return login.capabilities();
            }

            @Override
            public void authenticate(HttpClient client, boolean loginRequired)
                    throws MapRouletteAuthUnavailableException {
                login.authenticate(client, loginRequired);
            }

            @Override
            public OptionalLong knownUserId() {
                return OptionalLong.of(2);
            }
        });
        final var messages = startDone(TaskStatus.ALREADY_FIXED, 1);
        try {
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).contains("(Already fixed, "), messages.get(0));
            assertTrue(messages.get(0).contains("Another mapper finished it"), messages.get(0));
        } finally {
            cleanUp();
        }
    }

    /**
     * A deleted task is not started, and there is no "Start anyway" (#142)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartDeletedTaskRefused() throws Exception {
        final var wireMock = WireMocks.current();
        final var messages = startDone(TaskStatus.DELETED, 0);
        try {
            assertEquals(List.of("Task 132279499 was removed by the challenge owner."), messages);
            wireMock.verifyThat(0, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/start")));
            assertNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * Start on an open task doesn't ask
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartOpenTaskDoesNotAsk() throws Exception {
        final var asked = new AtomicInteger();
        new MockUp<JOptionPane>() {
            @Mock
            int showOptionDialog(Component parentComponent, Object message, String title, int optionType,
                    int messageType, Icon icon, Object[] options, Object initialValue) {
                asked.incrementAndGet();
                return 1;
            }
        };
        final var action = new LockUnlockTaskAction(this.table);
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            sync();
            assertEquals(0, asked.get());
            assertNotNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    /**
     * Click Start on task 132279499 marked Fixed, answering the question with {@code choice}
     *
     * @param choice The option picked in the question
     * @return The questions asked
     */
    private List<String> startDone(int choice) throws Exception {
        return startDone(TaskStatus.FIXED, choice);
    }

    /**
     * Click Start on task 132279499 with a status, answering the question with {@code choice}
     *
     * @param status The task's status
     * @param choice The option picked in the question
     * @return The questions asked, each followed by the option that was the default
     */
    private List<String> startDone(TaskStatus status, int choice) throws Exception {
        return startDone(status, choice, () -> {
            // Nothing set in this session
        });
    }

    private List<String> startDone(TaskStatus status, int choice, Runnable afterLoad) throws Exception {
        MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                .forEach(MainApplication.getLayerManager()::removeLayer);
        MainApplication.getLayerManager().addLayer(layer(status));
        afterLoad.run();
        GuiHelper.runInEDTAndWait(() -> this.table.getSelectionModel().setSelectionInterval(0, 0));
        final var messages = new CopyOnWriteArrayList<String>();
        new MockUp<JOptionPane>() {
            @Mock
            int showOptionDialog(Component parentComponent, Object message, String title, int optionType,
                    int messageType, Icon icon, Object[] options, Object initialValue) {
                if (String.valueOf(message).contains("could not be downloaded")) {
                    // The task's node is not on the test OSM server (#143); keep the task started
                    return 1;
                }
                messages.add(message + "|" + initialValue);
                return choice;
            }

            @Mock
            void showMessageDialog(Component parentComponent, Object message, String title, int messageType) {
                messages.add(String.valueOf(message));
            }
        };
        final var action = new LockUnlockTaskAction(this.table);
        GuiHelper.runInEDTAndWaitWithException(() -> {
            action.updateEnabledState();
            action.actionPerformed(null);
        });
        sync();
        return messages;
    }

    /**
     * Start on a task whose object doesn't exist on the OSM server offers to stop it, and Stop releases it (#143)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testStartWithMissingObjectOffersStop() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        new MockUp<AbstractPrimitiveTask>() {
            @Mock
            void realRun() {
                // The OSM server answers 404
            }

            @Mock
            Set<PrimitiveId> getMissingPrimitives() {
                return Set.of(new SimplePrimitiveId(9494185766L, OsmPrimitiveType.NODE));
            }
        };
        final var messages = new CopyOnWriteArrayList<String>();
        new MockUp<JOptionPane>() {
            @Mock
            int showOptionDialog(Component parentComponent, Object message, String title, int optionType,
                    int messageType, Icon icon, Object[] options, Object initialValue) {
                messages.add(String.valueOf(message));
                return 0;
            }
        };
        final var action = new LockUnlockTaskAction(this.table);
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> {
                action.updateEnabledState();
                action.actionPerformed(null);
            });
            // Start, finding the task's objects, the download, then Stop
            for (var i = 0; i < 4; i++) {
                sync();
            }
            // Asked in the panel, not in a dialog (#116)
            assertEquals(List.of(), messages);
            assertNotNull(stateText("Task 132279499's object (node 9494185766) doesn't exist on"));
            click("Stop task");
            sync();
            sync();
            wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/release")));
            assertNull(ModifiedObjects.getLockedTask(132279499L));
        } finally {
            cleanUp();
        }
    }

    private static void cleanUp() {
        ModifiedObjects.clearSentStatuses(List.of(132279499L));
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
