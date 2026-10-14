// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link MapRouletteDownloadTask}
 */
@BasicPreferences
class MapRouletteDownloadTaskTest {
    /**
     * Cancelling before a download was started must not throw
     */
    @Test
    void testCancelBeforeDownload() {
        assertDoesNotThrow(() -> new MapRouletteDownloadTask().cancel());
    }
}
