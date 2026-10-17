// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

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
     * Find a challenge browses the website's challenges, and a server without a website has no URL (#111)
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
}
