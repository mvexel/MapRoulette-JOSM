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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JTable;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.widgets.FilterField;
import org.openstreetmap.josm.plugins.maproulette.actions.IgnoreAction;
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

    private static <T extends Component> T findOrNull(Container container, Class<T> type, Predicate<T> test) {
        try {
            return find(container, type, test);
        } catch (AssertionError e) {
            return null;
        }
    }
}
