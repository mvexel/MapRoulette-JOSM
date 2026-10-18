// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link TaskMarker}
 */
@BasicPreferences
class TaskMarkerTest {
    private static ClusteredPoint point(long id, TaskStatus status, Long lockedBy) {
        return new ClusteredPoint(id, 1, "", "n" + id, 10, "Benches", new Point(0, 0), null, "", Instant.EPOCH, null, 2,
                status, null, null, null, null, new PointReview(null, null, null, null, null, null, null, null, null),
                0, null, false, lockedBy, Map.of());
    }

    @ParameterizedTest
    @CsvSource({ "CREATED,OPEN", "SKIPPED,OPEN", "TOO_HARD,OPEN", "FIXED,DONE", "ALREADY_FIXED,DONE",
            "DELETED,LOCKED" })
    void testKindFromStatus(TaskStatus status, TaskMarker.Kind expected) {
        assertEquals(expected, TaskMarker.kind(point(900_113, status, null)));
    }

    @Test
    void testKindOfLockedTasks() {
        final var point = point(900_114, TaskStatus.CREATED, 99L);
        assertEquals(TaskMarker.Kind.LOCKED, TaskMarker.kind(point), "Another mapper holds it");
        final var mine = new Task(900_114, "", null, null, 10, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, false, null, null);
        ModifiedObjects.addLockedTask(mine);
        try {
            assertEquals(TaskMarker.Kind.STARTED, TaskMarker.kind(point));
        } finally {
            ModifiedObjects.removeLockedTask(mine);
        }
    }

    /**
     * The symbols differ in their fill, so they can be told apart without the ring colour
     */
    @Test
    void testIcons() {
        assertEquals(Color.WHITE.getRGB(), fill(TaskMarker.icon(TaskMarker.Kind.OPEN, false)));
        assertEquals(TaskMarker.BLUE.getRGB(), fill(TaskMarker.icon(TaskMarker.Kind.STARTED, false)));
        assertEquals(TaskMarker.DONE.getRGB(), fill(TaskMarker.icon(TaskMarker.Kind.DONE, false)));
        assertFalse(has(TaskMarker.icon(TaskMarker.Kind.OPEN, false), TaskMarker.HIGHLIGHT));
        assertTrue(has(TaskMarker.icon(TaskMarker.Kind.OPEN, true), TaskMarker.HIGHLIGHT), "Selected");
        assertTrue(has(TaskMarker.groupIcon(), Color.WHITE));
    }

    private static BufferedImage draw(Icon icon) {
        final var image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_RGB);
        final var g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        icon.paintIcon(new JLabel(), g, 0, 0);
        g.dispose();
        return image;
    }

    /** The colour a little above the centre, inside the ring and clear of the check mark */
    private static int fill(Icon icon) {
        final var image = draw(icon);
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2 - 3);
    }

    private static boolean has(Icon icon, Color color) {
        final var image = draw(icon);
        for (var x = 0; x < image.getWidth(); x++) {
            for (var y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) == color.getRGB()) {
                    return true;
                }
            }
        }
        return false;
    }
}
