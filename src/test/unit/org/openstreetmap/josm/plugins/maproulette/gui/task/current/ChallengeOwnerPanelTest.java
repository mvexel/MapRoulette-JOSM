// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.ChallengeAPI;
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
        Config.getPref().put("osm-server.url", null);
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
        WireMocks.current().register(get(urlPathEqualTo("/api/v2/user/2/public")).willReturn(aResponse().withStatus(200)
                .withBody(Files.readString(Path.of("src/test/resources/__files/api/v2/user/11353/public")))));
        assertEquals(Optional.empty(), ChallengeOwnerPanel.lookUp(2));
    }

    /**
     * The block is hidden when the lookup fails
     */
    @Test
    void testLookUpFails() {
        WireMocks.current()
                .register(get(urlPathEqualTo("/api/v2/user/918586/public")).willReturn(aResponse().withStatus(404)));
        assertEquals(Optional.empty(), ChallengeOwnerPanel.lookUp(918586));
    }

    /**
     * The owner is a user of the OSM server JOSM edits on, not always openstreetmap.org: a dev server's names would
     * message someone else there
     */
    @Test
    void testOsmUrls() {
        Config.getPref().put("osm-server.url", "https://master.apis.dev.openstreetmap.org/api");
        assertEquals("https://master.apis.dev.openstreetmap.org/user/Mr%20Bench%2FSLC",
                ChallengeOwnerPanel.profileUrl("Mr Bench/SLC"));
        assertEquals("https://master.apis.dev.openstreetmap.org/message/new/Mr%20Bench%2FSLC",
                ChallengeOwnerPanel.messageUrl("Mr Bench/SLC"));
        Config.getPref().put("osm-server.url", null);
        assertEquals("https://www.openstreetmap.org/user/Mr%20Bench%2FSLC",
                ChallengeOwnerPanel.profileUrl("Mr Bench/SLC"));
    }

    /**
     * The line is hidden without a task, shown once the owner is known, and Discussion only with a website
     *
     * @throws Exception if the test fails
     */
    @Test
    void testVisibility() throws Exception {
        WireMocks.current()
                .register(get(urlPathEqualTo("/api/v2/user/918586/public")).willReturn(aResponse().withStatus(200)
                        .withBody(Files.readString(Path.of("src/test/resources/__files/api/v2/user/11353/public"))
                                .replace("11353", "918586"))));
        final var challenge = ChallengeAPI.challenge(15318);
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(ChallengeOwnerPanel::new);
        for (var website : new boolean[] { false, true }) {
            Config.getPref().put("maproulette.website.url", website ? "https://mr.example.org" : null);
            GuiHelper.runInEDTAndWait(() -> panel.setChallenge(challenge));
            MainApplication.worker.submit(() -> {
            }).get();
            GuiHelper.runInEDTAndWait(() -> {
            });
            assertTrue(panel.isVisible());
            assertEquals(website, panel.showsDiscussion());
        }
        GuiHelper.runInEDTAndWait(() -> panel.setChallenge(null));
        assertFalse(panel.isVisible());
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

    /**
     * A display name is text, not markup
     */
    @Test
    void testLink() {
        assertEquals("<html><a href=\"\">a&lt;b&gt; &amp; c</a></html>", ChallengeOwnerPanel.link("a<b> & c"));
    }
}
