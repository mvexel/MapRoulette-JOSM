// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link FindChallengeAction}
 */
@BasicPreferences
@MapRouletteConfig
class FindChallengeActionTest {
    /**
     * The dialog links to the website's challenge list, and a server without a website has no link (#111)
     */
    @Test
    void testBrowseUrl() {
        assertTrue(FindChallengeAction.browseUrl().isEmpty(), "The test server has no website");
        Config.getPref().put("maproulette.website.url", "https://example.org/");
        try {
            assertEquals(Optional.of("https://example.org/browse/challenges"), FindChallengeAction.browseUrl());
        } finally {
            Config.getPref().put("maproulette.website.url", null);
        }
    }

    /**
     * A challenge can be pasted as a number or as any website link that names it (#111)
     */
    @Test
    void testChallengeId() {
        assertEquals(OptionalLong.of(12), FindChallengeAction.challengeId(" 12 "));
        assertEquals(OptionalLong.of(12),
                FindChallengeAction.challengeId("https://maproulette.org/browse/challenges/12"));
        assertEquals(OptionalLong.of(12),
                FindChallengeAction.challengeId("https://maproulette.org/challenge/12/task/34"));
        assertEquals(OptionalLong.of(12),
                FindChallengeAction.challengeId("https://maproulette.org/browse/challenges/12?tab=conversation"));
        assertTrue(FindChallengeAction.challengeId("https://maproulette.org/browse/challenges").isEmpty());
        assertTrue(FindChallengeAction.challengeId("bench").isEmpty());
        assertTrue(FindChallengeAction.challengeId(null).isEmpty());
    }
}
