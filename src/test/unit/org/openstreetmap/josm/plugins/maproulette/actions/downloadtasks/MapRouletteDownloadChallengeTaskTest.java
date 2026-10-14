// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link MapRouletteDownloadChallengeTask}
 */
@BasicPreferences
class MapRouletteDownloadChallengeTaskTest {
    @ParameterizedTest
    @ValueSource(strings = { "https://maproulette.org/browse/challenges/12345",
            "http://maproulette.org/browse/challenges/12345", "https://maproulette.org/browse/challenges/12345/" })
    void testAcceptsChallengeUrls(String url) {
        assertTrue(new MapRouletteDownloadChallengeTask().acceptsUrl(url, false));
    }

    @Test
    void testRejectsMalformedScheme() {
        assertFalse(
                new MapRouletteDownloadChallengeTask().acceptsUrl("https//maproulette.org/browse/challenges/1", false));
    }
}
