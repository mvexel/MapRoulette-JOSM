// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.ActionEvent;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Zoom to task in the task list selects the task's objects that are loaded, as the panel's Zoom to task does (#188)
 */
@BasicPreferences
@Main
@MapRouletteConfig
@Projection
class GoToTaskLocationTest {
    private static final long TASK_ID = 147197178;

    /**
     * The task's object is in the edit layer: it is selected, and the other objects are not
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testSelectsLoadedObjects() throws Exception {
        final var dataSet = new DataSet();
        // The OSM object of task 147197178
        final var taskNode = new Node(new LatLon(47.463575, -120.342239));
        taskNode.setOsmId(6318952236L, 1);
        dataSet.addPrimitive(taskNode);
        final var other = new Node(new LatLon(47.46, -120.34));
        other.setOsmId(1, 1);
        dataSet.addPrimitive(other);
        dataSet.setSelected(other);
        assertEquals(Set.of(taskNode), zoomTo(dataSet));
    }

    /**
     * The task's object isn't loaded: the selection stays, and nothing is downloaded
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testObjectsNotLoaded() throws Exception {
        final var dataSet = new DataSet();
        final var other = new Node(new LatLon(47.46, -120.34));
        other.setOsmId(1, 1);
        dataSet.addPrimitive(other);
        dataSet.setSelected(other);
        assertEquals(Set.of(other), zoomTo(dataSet));
        assertEquals(1, dataSet.allPrimitives().size(), "Nothing downloaded");
    }

    /**
     * Use Zoom to task on a task list row for task {@value #TASK_ID}
     *
     * @param dataSet The edit data
     * @return The selection afterwards
     * @throws Exception if the test fails
     */
    private static Set<OsmPrimitive> zoomTo(DataSet dataSet) throws Exception {
        WireMocks.current().register(get(urlPathEqualTo("/api/v2/task/" + TASK_ID))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "GoToTaskLocationTest", null));
        final var model = new DefaultTableModel(
                new Object[][] { { TaskListPanelTest.point(TASK_ID, 28410, "Wenatchee buildings", null) } },
                new Object[] { "Task" });
        final var table = new JTable(model);
        table.setRowSorter(new TableRowSorter<>(model));
        table.getSelectionModel().setSelectionInterval(0, 0);
        final var menu = new JPopupMenu();
        final var action = new GoToTaskLocation();
        final var item = menu.add(action);
        menu.setInvoker(table);
        GuiHelper.runInEDTAndWait(
                () -> action.actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, "zoom")));
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        return Set.copyOf(dataSet.getAllSelected());
    }
}
