// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Test class for {@link ChallengeOwnerPanel}
 */
@MapRouletteConfig
class ChallengeOwnerPanelTest {
    @AfterEach
    void tearDown() {
        ChallengeOwnerPanel.clearNames();
        Config.getPref().put("maproulette.website.url", null);
    }

    @Test
    void testLookUp() {
        assertEquals(Optional.of("mvexelmrtest"), ChallengeOwnerPanel.lookUp(11353));
    }

    /**
     * MapRoulette answers with its own user of that id when no OSM user has it, which would name someone else
     *
     * @throws Exception if the test fails
     */
    @Test
    void testLookUpOtherUser() throws Exception {
        WireMocks.current().register(get(urlPathEqualTo("/api/v2/user/2/public")).willReturn(aResponse()
                .withStatus(200).withBody(Files.readString(Path.of("src/test/resources/__files/api/v2/user/11353/public")))));
        assertEquals(Optional.empty(), ChallengeOwnerPanel.lookUp(2));
    }

    /**
     * The block is hidden when the lookup fails
     */
    @Test
    void testLookUpFails() {
        WireMocks.current().register(
                get(urlPathEqualTo("/api/v2/user/918586/public")).willReturn(aResponse().withStatus(404)));
        assertEquals(Optional.empty(), ChallengeOwnerPanel.lookUp(918586));
    }

    @Test
    void testOsmUrls() {
        final var osm = Config.getUrls().getOSMWebsite().replaceAll("/+$", "");
        assertEquals(osm + "/user/Mr%20Bench%2FSLC", ChallengeOwnerPanel.profileUrl("Mr Bench/SLC"));
        assertEquals(osm + "/message/new/Mr%20Bench%2FSLC", ChallengeOwnerPanel.messageUrl("Mr Bench/SLC"));
    }

    /**
     * The discussion is on the website, not the API host, and is left out when the server has no known website
     */
    @Test
    void testDiscussionUrl() {
        assertEquals(Optional.empty(), ChallengeOwnerPanel.discussionUrl(15318));
        Config.getPref().put("maproulette.website.url", "https://mr.example.org/");
        assertEquals(Optional.of("https://mr.example.org/browse/challenges/15318?tab=conversation"),
                ChallengeOwnerPanel.discussionUrl(15318));
    }
}
