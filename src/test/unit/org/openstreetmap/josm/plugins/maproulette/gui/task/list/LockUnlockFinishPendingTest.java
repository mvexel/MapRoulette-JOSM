// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.swing.Action;
import javax.swing.JTable;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.Difficulty;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.PublicUser;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Stop is disabled while an upload finishes the started task (#128, #130)
 */
@BasicPreferences
@Main
@Projection
@MapRouletteConfig
class LockUnlockFinishPendingTest {
    private static final long TASK_ID = 132279499L;
    private TaskTableModel model;
    private JTable table;

    @BeforeEach
    void setUp() {
        this.model = new TaskTableModel();
        MainApplication.getLayerManager().addAndFireLayerChangeListener(this.model);
        MainApplication.getLayerManager().addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                Collections.singleton(new ClusteredPoint(TASK_ID, 0L, "null", "title", 0L, "parentName",
                        new Point(0, 0), "bounding", "blurb", Instant.EPOCH, Difficulty.NORMAL, 2, TaskStatus.CREATED,
                        null, Instant.EPOCH, 0L, new PublicUser(0L, null, "someone", Instant.EPOCH, null),
                        new PointReview(null, null, null, null, null, null, null, null, null), 0, null, false, null,
                        Map.of()))));
        this.table = new JTable(this.model);
        this.table.setRowSorter(new TableRowSorter<>(this.model));
    }

    @AfterEach
    void tearDown() {
        MainApplication.getLayerManager().removeAndFireLayerChangeListener(this.model);
        ModifiedObjects.setFinishPending(List.of(TASK_ID), false);
        final var locked = ModifiedObjects.getLockedTask(TASK_ID);
        if (locked != null) {
            ModifiedObjects.removeLockedTask(locked);
        }
    }

    /**
     * A Stop would be sent after the upload and lose to its Fixed, so it waits until Fixed is sent
     *
     * @throws Exception if the task fixture can't be read
     */
    @Test
    @MapRouletteLogin
    void testStopDisabledWhileFinishPending() throws Exception {
        ModifiedObjects.addLockedTask(TaskAPI.get(TASK_ID));
        final var action = new LockUnlockTaskAction(this.table);
        action.updateEnabledState();
        assertEquals("Stop Task", action.getValue(Action.NAME));
        assertTrue(action.isEnabled());

        ModifiedObjects.setFinishPending(List.of(TASK_ID), true);
        action.updateEnabledState();
        assertFalse(action.isEnabled(), "The upload in progress finishes the task");
        assertTrue(String.valueOf(action.getValue(Action.SHORT_DESCRIPTION))
                .contains("The upload in progress finishes task 132279499"));

        ModifiedObjects.setFinishPending(List.of(TASK_ID), false);
        action.updateEnabledState();
        assertTrue(action.isEnabled(), "Fixed is sent: Stop works again");
    }
}
