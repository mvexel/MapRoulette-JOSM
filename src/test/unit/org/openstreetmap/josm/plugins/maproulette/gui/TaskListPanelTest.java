// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.widgets.FilterField;
import org.openstreetmap.josm.plugins.maproulette.actions.IgnoreAction;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.data.HiddenList;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.plugins.maproulette.util.LoggingHandler;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;

/**
 * Test class for {@link TaskListPanel}
 */
@LoggingHandler
@Main
@MapRouletteConfig
@Projection
public class TaskListPanelTest {

    /**
     * Fire the ignore action
     *
     * @param panel      The panel to use
     * @param ignoreType The ignore action to use
     */
    public static void fireIgnoreAction(TaskListPanel panel, IgnoreAction.IgnoreType ignoreType) {
        final var table = find(panel, JTable.class, t -> true);
        final var menu = table.getComponentPopupMenu();
        final var menuItem = (JMenuItem) switch (ignoreType) {
        case IGNORE_TASK -> menu.getComponent(2);
        case IGNORE_CHALLENGE -> menu.getComponent(3);
        };
        menu.setInvoker(table);
        final var action = menuItem.getAction();
        assertEquals(ignoreType.getButtonText(), action.getValue(Action.NAME));
        action.actionPerformed(new ActionEvent(menuItem, 0, "ignore"));
    }

    @Test
    void testDuplicateKeyIssue() throws ExecutionException, InterruptedException {
        final var panel = new TaskListPanel();
        final var action = getDownloadAction(panel);
        final var bounds = new Bounds(36.0770518, -119.1139411, 36.0839216, -119.0992458);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testDuplicateKeyIssue", null));
        new MockUp<MapViewState.MapViewRectangle>() {
            @Mock
            public Bounds getLatLonBoundsBox() {
                return bounds;
            }
        };
        action.actionPerformed(null);
        MainApplication.worker.submit(() -> {
            /* Sync thread */ }).get();
        action.actionPerformed(null);
        MainApplication.worker.submit(() -> {
            /* Sync thread */ }).get();
    }

    /**
     * Non-regression test: a destroyed panel must not stay registered with the layer manager or {@link HiddenList}
     */
    @Test
    void testDestroyRemovesListeners() throws ReflectiveOperationException {
        final var registered = new ArrayList<LongPredicate>();
        new MockUp<HiddenList>() {
            @Mock
            void addListUpdater(Invocation invocation, LongPredicate checkFunction) {
                registered.add(checkFunction);
                invocation.proceed();
            }
        };
        final var panel = new TaskListPanel();
        final var layerEvents = new AtomicInteger();
        new MockUp<TaskListPanel>() {
            @Mock
            void layerAdded(Invocation invocation, LayerManager.LayerAddEvent e) {
                if (invocation.getInvokedInstance() == panel) {
                    layerEvents.incrementAndGet();
                }
            }
        };
        assertEquals(1, registered.size());
        assertTrue(getHiddenListTests().contains(registered.get(0)));
        panel.destroy();
        assertFalse(getHiddenListTests().contains(registered.get(0)),
                "The destroyed panel should not be asked whether tasks are hidden");
        MainApplication.getLayerManager()
                .addLayer(new OsmDataLayer(new DataSet(), "testDestroyRemovesListeners", null));
        assertEquals(0, layerEvents.get(), "The destroyed panel should not get layer events");
    }

    /**
     * Opening the panel when a MapRoulette layer already exists must not fail
     */
    @Test
    void testOpenWithExistingLayer() {
        MainApplication.getLayerManager()
                .addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0), Collections.emptySet()));
        final var panel = assertDoesNotThrow(TaskListPanel::new);
        panel.destroy();
    }

    /**
     * The list groups tasks under challenge headers, filters by challenge and counts what the filters hide (#112)
     */
    @Test
    void testChallengeFilterAndFooter() throws Exception {
        // Ids that other tests leave off the ignore lists, which last for the whole test run
        final var points = new ArrayList<TaskClusteredPoint>();
        for (long id = 1001; id <= 1003; id++) {
            points.add(point(id, 18, "SLC benches", null));
        }
        points.add(point(1004, 19, "Missing sidewalks", null));
        points.add(point(1005, 18, "SLC benches", 99L));
        MainApplication.getLayerManager().addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0), points));
        // Built on the EDT, as JOSM does: the model fills from the layer on the EDT, which would otherwise race the
        // constructor installing the row sorter. The challenge prefetch runs on the worker and redraws on the EDT.
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(TaskListPanel::new);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        try {
            final var table = find(panel, JTable.class, t -> true);
            assertEquals(5, table.getRowCount(), "Tasks locked by others are listed too");
            @SuppressWarnings("unchecked")
            final JComboBox<Object> challenges = find(panel, JComboBox.class, c -> true);
            assertEquals(List.of("All challenges in view (2)", "Missing sidewalks (1)", "SLC benches (4)"), IntStream
                    .range(0, challenges.getItemCount()).mapToObj(i -> challenges.getItemAt(i).toString()).toList());
            assertEquals(List.of(true, true, false, false, false), headers(table));
            final var tooltip = GuiHelper.runInEDTAndWaitAndReturn(
                    () -> ((JComponent) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0)).getToolTipText());
            assertTrue(tooltip.contains("Missing sidewalks"), "The row tooltip names the challenge: " + tooltip);
            final var labels = new ArrayList<JLabel>();
            collectLabels(GuiHelper.runInEDTAndWaitAndReturn(
                    () -> (Container) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0)), labels);
            assertFalse(labels.isEmpty());
            assertTrue(labels.stream().allMatch(l -> Boolean.TRUE.equals(l.getClientProperty("html.disable"))),
                    "Names from MapRoulette are never rendered as HTML");
            assertEquals(Boolean.TRUE, ((JComponent) challenges.getRenderer()).getClientProperty("html.disable"),
                    "Challenge names in the filter are never rendered as HTML");
            final var showAll = find(panel, JButton.class, b -> "Show all".equals(b.getText()));
            final var footer = (JLabel) showAll.getParent().getComponent(0);
            assertFalse(footer.getParent().isVisible());

            challenges.setSelectedIndex(1);
            assertEquals(1, table.getRowCount());
            assertEquals("4 more tasks are hidden by the filter.", footer.getText());
            assertTrue(footer.getParent().isVisible());

            showAll.doClick();
            assertEquals(5, table.getRowCount());
            assertEquals(0, challenges.getSelectedIndex());
            assertFalse(footer.getParent().isVisible());

            getFilterField(panel).setText("sidewalks");
            assertEquals(1, table.getRowCount(), "The text filter searches the challenge name");
        } finally {
            panel.destroy();
        }
    }

    /**
     * Without a login, the list says why Start is disabled and offers to log in (#116)
     */
    @Test
    void testLoginReason() {
        final var loggedOut = new AtomicBoolean(true);
        new MockUp<LoginControl>() {
            @Mock
            boolean canLogIn() {
                return loggedOut.get();
            }
        };
        MainApplication.getLayerManager().addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                List.of(point(1006, 18, "SLC benches", null))));
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(TaskListPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> {
                /* Sync EDT */ });
            final var reason = find(panel, JLabel.class, l -> "Log in to start tasks:".equals(l.getText()));
            assertTrue(reason.getParent().isVisible());
            assertTrue(find(reason.getParent(), JButton.class, b -> "Log in…".equals(b.getText())).isVisible());
            loggedOut.set(false);
            MapRouletteClient.getInstance().fireAuthChanged();
            GuiHelper.runInEDTAndWait(() -> {
                /* Sync EDT */ });
            assertFalse(reason.getParent().isVisible(), "Logged in: no reason to show");
        } finally {
            GuiHelper.runInEDTAndWait(panel::destroy);
        }
    }

    /**
     * A scroll step moves the list by about a row, not a few pixels (#144)
     */
    @Test
    void testScrollStep() {
        final var panel = new TaskListPanel();
        try {
            final var scrollPanes = new ArrayList<JScrollPane>();
            collectScrollPanes(panel, scrollPanes);
            final var list = scrollPanes.stream()
                    .filter(pane -> SwingUtilities.getAncestorOfClass(JScrollPane.class, findTable(pane)) == pane)
                    .findFirst().orElseThrow();
            final var table = findTable(list);
            // Two lines of text per row
            assertTrue(list.getVerticalScrollBar().getUnitIncrement() > 2
                    * table.getFontMetrics(table.getFont()).getHeight());
        } finally {
            panel.destroy();
        }
    }

    private static void collectScrollPanes(Container container, List<JScrollPane> scrollPanes) {
        for (var component : container.getComponents()) {
            if (component instanceof JScrollPane scrollPane) {
                scrollPanes.add(scrollPane);
            }
            if (component instanceof Container child) {
                collectScrollPanes(child, scrollPanes);
            }
        }
    }

    private static JTable findTable(Container container) {
        for (var component : container.getComponents()) {
            if (component instanceof JTable table) {
                return table;
            }
            if (component instanceof Container child) {
                final var table = findTable(child);
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }

    private static void collectLabels(Container container, List<JLabel> labels) {
        for (var component : container.getComponents()) {
            if (component instanceof JLabel label) {
                labels.add(label);
            } else if (component instanceof Container child) {
                collectLabels(child, labels);
            }
        }
    }

    private static List<Boolean> headers(JTable table) {
        // On the EDT: a challenge fetched in the background re-sorts the table there, and a read from another thread can
        // see the rows in model order halfway through the sort
        final var headers = new ArrayList<Boolean>();
        GuiHelper.runInEDTAndWait(() -> {
            for (var row = 0; row < table.getRowCount(); row++) {
                final var cell = (Container) table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                headers.add(cell.getComponent(0).isVisible());
            }
        });
        return headers;
    }

    /**
     * Create a task list point
     *
     * @param id            The task id
     * @param challenge     The challenge id
     * @param challengeName The challenge name
     * @param lockedBy      The user holding a lock, if any
     * @return The point
     */
    public static ClusteredPoint point(long id, long challenge, String challengeName, Long lockedBy) {
        return new ClusteredPoint(id, 1, "", "n" + id, challenge, challengeName, new Point(0, 0), null, "",
                Instant.EPOCH, null, 2, TaskStatus.CREATED, null, null, null, null,
                new PointReview(null, null, null, null, null, null, null, null, null), 0, null, false, lockedBy,
                Map.of());
    }

    private static List<LongPredicate> getHiddenListTests() throws ReflectiveOperationException {
        final Field tests = HiddenList.class.getDeclaredField("tests");
        tests.setAccessible(true);
        return Arrays.asList((LongPredicate[]) tests.get(null));
    }

    /**
     * Get the download action for a panel
     *
     * @param taskListPanel The panel to use
     * @return The download action
     */
    public static Action getDownloadAction(TaskListPanel taskListPanel) {
        final var action = find(taskListPanel, SideButton.class,
                button -> button.getAction().getClass().getSimpleName().equals("DownloadDataAction")).getAction();
        assertEquals("org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel$DownloadDataAction",
                action.getClass().getName());
        return action;
    }

    /**
     * Get the filter field of a panel
     *
     * @param taskListPanel The panel to use
     * @return The filter field
     */
    public static FilterField getFilterField(TaskListPanel taskListPanel) {
        return find(taskListPanel, FilterField.class, f -> true);
    }

    /**
     * Find a component in a container
     *
     * @param container The container to search
     * @param type      The type of the component
     * @param test      The test the component must pass
     * @param <T>       The type of the component
     * @return The first matching component, depth first
     */
    public static <T extends Component> T find(Container container, Class<T> type, Predicate<T> test) {
        for (var component : container.getComponents()) {
            if (type.isInstance(component) && test.test(type.cast(component))) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                final var found = findOrNull(child, type, test);
                if (found != null) {
                    return found;
                }
            }
        }
        return fail("No " + type.getSimpleName() + " found");
    }

    public static <T extends Component> T findOrNull(Container container, Class<T> type, Predicate<T> test) {
        try {
            return find(container, type, test);
        } catch (AssertionError e) {
            return null;
        }
    }
}
