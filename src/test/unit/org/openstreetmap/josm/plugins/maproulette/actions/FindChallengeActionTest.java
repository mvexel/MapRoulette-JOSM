// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.NameFinder;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link FindChallengeAction}
 */
@BasicPreferences
@Main
@MapRouletteConfig
@Projection
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
        assertTrue(FindChallengeAction.challengeId("https://maproulette.org/browse/challenges/12abc").isEmpty(),
                "The number ends at a separator");
        assertTrue(FindChallengeAction.challengeId("https://maproulette.org/browse/challenges/1234567890123456789012")
                .isEmpty(), "A number too long for an id is not one");
    }

    /**
     * A link from another server's website would load an unrelated challenge with the same number
     */
    @Test
    void testOtherServer() {
        final var production = Optional.of("https://maproulette.org");
        assertTrue(
                FindChallengeAction.otherServer("https://maproulette.org/browse/challenges/12", production).isEmpty());
        assertTrue(FindChallengeAction.otherServer("12", production).isEmpty(), "A number has no server");
        assertEquals(Optional.of("maproulette.org"),
                FindChallengeAction.otherServer("https://maproulette.org/browse/challenges/12", Optional.empty()),
                "Without a known website, no link can be checked");
        assertEquals(Optional.of("example.org"),
                FindChallengeAction.otherServer("https://example.org/browse/challenges/12", production));
    }

    /**
     * A place with one match loads the tasks in its area, and the task layer opens the map (#187)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testFindNearPlace() throws Exception {
        final var wireMock = nominatim("Omaha, Nebraska", "nominatim/omaha-nebraska.xml");
        // Any tasks will do: the request's area is what is checked
        wireMock.register(put(urlPathMatching("/api/v2/tasks/box/.*"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBodyFile("api/v2/tasks/box/-108.4962538/39.082404/-108.4962538/39.082404")));
        assertFalse(MainApplication.isDisplayingMapView(), "No map yet");
        FindChallengeAction.findNear("Omaha, Nebraska");
        syncWorker();
        // The bounding box of the Omaha boundary in the recorded answer
        wireMock.verifyThat(
                putRequestedFor(urlPathEqualTo("/api/v2/tasks/box/-96.2682/41.190585/-95.871141/41.3930618")));
        assertEquals(1, MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class).size());
        assertTrue(MainApplication.isDisplayingMapView(), "The task layer opened a map");
        // The test map has no size, so its view is the centre point: it is in Omaha
        final var view = MainApplication.getMap().mapView.getState().getViewArea().getLatLonBoundsBox();
        assertTrue(new Bounds(41.190585, -96.2682, 41.3930618, -95.871141).contains(view.getCenter()), view::toString);
    }

    /**
     * A name that matches nothing loads nothing (#187)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testFindNearNoPlace() throws Exception {
        final var wireMock = nominatim("Xyzzyplughnowhere", "nominatim/none.xml");
        FindChallengeAction.findNear("Xyzzyplughnowhere");
        syncWorker();
        wireMock.verifyThat(0, putRequestedFor(urlPathMatching("/api/v2/tasks/box/.*")));
        assertTrue(MainApplication.getLayerManager().getLayers().isEmpty());
    }

    /**
     * Answer place searches from the test server, with a recorded Nominatim answer
     *
     * @param query The searched name
     * @param file  The recorded answer
     * @return The test server
     */
    private static com.github.tomakehurst.wiremock.client.WireMock nominatim(String query, String file) {
        final var wireMock = WireMocks.current();
        final var base = URI.create(org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl());
        NameFinder.NOMINATIM_URL_PROP.put("http://" + base.getHost() + ":" + base.getPort() + "/search?format=xml&q=");
        wireMock.register(get(urlPathEqualTo("/search")).withQueryParam("q", equalTo(query))
                .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBodyFile(file)));
        return wireMock;
    }

    private static void syncWorker() throws Exception {
        // The search, then the download, then the zoom
        for (var i = 0; i < 3; i++) {
            MainApplication.worker.submit(() -> {
                /* Sync worker */ }).get(10, TimeUnit.SECONDS);
            GuiHelper.runInEDTAndWait(() -> {
                /* Sync EDT */ });
        }
    }
}
