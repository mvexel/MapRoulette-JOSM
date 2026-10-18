// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test {@link UnpublishedChallenge}, with challenges and projects recorded from the dev server on 2026-10-18
 */
@BasicPreferences
@MapRouletteConfig
class UnpublishedChallengeTest {
    /**
     * The explanation names the disabled part
     */
    @Test
    void testExplain() {
        assertEquals("Challenge \"Bench details pilot (dev OSM, guests)\" isn't published yet. Only its owner can "
                + "publish it.", UnpublishedChallenge.explain(4), "Challenge disabled, project enabled");
        assertEquals(
                "Challenge \"SLC benches (A39 test, delete me)\" isn't published yet: its project \"Test Mapper's "
                        + "Project\" is disabled. Only its owner can enable it.",
                UnpublishedChallenge.explain(9010), "Challenge enabled, project disabled");
        assertNull(UnpublishedChallenge.explain(9011), "Both enabled");
        assertNull(UnpublishedChallenge.explain(999_999), "Unknown challenge");
    }
}
