// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.parsers.ClusteredPointParser;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;

/**
 * Test class for {@link MapRouletteDownloadTaskBox}
 */
class MapRouletteDownloadTaskBoxTest {
    /**
     * Tasks locked by someone else, including members of a bundle someone else locked, are not shown (#46)
     *
     * @throws IOException if the test data could not be read
     */
    @Test
    void testWithoutLockedByOthers() throws IOException {
        final var json = Files.readString(
                Path.of("src/test/resources/__files/api/v2/tasks/box/-108.4962538/39.082404/-108.4962538/39.082404"));
        final var object = json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1);
        final var free = point(object, 1, null);
        final var bundleMember = point(object, 2, 99L);
        final var bundleMemberOwnLock = point(object, 2, null);
        final var reviewerClaim = point(object, 3, 99L);
        final var mine = point(object, 4, 7L);
        final var points = (ClusteredPoint[]) ClusteredPointParser.parse(new StringReader(
                "[" + Arrays.stream(new String[] { free, bundleMember, bundleMemberOwnLock, reviewerClaim, mine })
                        .collect(Collectors.joining(",")) + "]"));
        assertEquals(99L, points[1].lockedBy());
        final var myTask = new Task(4, "", null, null, 27887, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, false, null, null);
        ModifiedObjects.addLockedTask(myTask);
        try {
            final var shown = MapRouletteDownloadTaskBox.withoutLockedByOthers(points);
            assertArrayEquals(new long[] { 1, 4 }, Arrays.stream(shown).mapToLong(ClusteredPoint::id).toArray());
        } finally {
            ModifiedObjects.removeLockedTask(myTask);
        }
    }

    private static String point(String object, long id, Long lockedBy) {
        final var withId = object.replace("\"id\": 136226437", "\"id\": " + id);
        return lockedBy == null ? withId : withId.replaceFirst("\\{", "{\"lockedBy\": " + lockedBy + ",");
    }
}
