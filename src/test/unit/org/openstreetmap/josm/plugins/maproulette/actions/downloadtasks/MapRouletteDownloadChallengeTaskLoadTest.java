// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Loading a challenge puts its open tasks on a MapRoulette layer (#134)
 */
@Main
@MapRouletteConfig
@Projection
class MapRouletteDownloadChallengeTaskLoadTest {
    @Test
    void testLoadChallenge() throws Exception {
        // A recorded response of the task box route
        WireMocks.current().register(put(urlPathEqualTo("/api/v2/tasks/box/-180/-85/180/85")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(Files.readString(Path.of(
                        "src/test/resources/__files/api/v2/tasks/box/-119.1139411/36.0770518/-119.0992458/36.0839216")))));
        new MapRouletteDownloadChallengeTask().loadChallenge(15318, NullProgressMonitor.INSTANCE).get();
        final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
        assertEquals(1, layers.size());
        assertEquals(4, layers.get(0).getTasks().size());
    }
}
