// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;

import javax.swing.Action;
import javax.swing.JTable;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.api.enums.Difficulty;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.PublicUser;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

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
                        new PointReview(null, null, null, null, null, null, null, null, null), 0, null, false))));
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
}
