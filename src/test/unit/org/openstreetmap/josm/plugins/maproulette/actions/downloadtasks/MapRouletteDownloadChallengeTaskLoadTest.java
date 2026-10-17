// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;
import org.openstreetmap.josm.tools.HttpClient;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Loading a challenge puts its open tasks on a MapRoulette layer (#134)
 */
@Main
@MapRouletteConfig
@Projection
class MapRouletteDownloadChallengeTaskLoadTest {
    /** A recorded response of the task box route, with four tasks and no locks */
    private static final Path TASKS = Path
            .of("src/test/resources/__files/api/v2/tasks/box/-119.1139411/36.0770518/-119.0992458/36.0839216");

    @Test
    void testLoadChallenge() throws Exception {
        stubTasks(Files.readString(TASKS));
        assertEquals(4, load().size());
    }

    /**
     * Tasks locked by other mappers are listed greyed, and the user's own lock (from an earlier session) is theirs
     */
    @Test
    void testLoadChallengeWithLocks() throws Exception {
        final var tasks = Json.createReader(new StringReader(Files.readString(TASKS))).readArray();
        final var rows = Json.createArrayBuilder()
                // One row per lock: the unlocked row comes first, another mapper's bundle lock second
                .add(tasks.getJsonObject(0)).add(locked(tasks.getJsonObject(0), 99))
                .add(locked(tasks.getJsonObject(1), 99)).add(locked(tasks.getJsonObject(2), 7))
                .add(tasks.getJsonObject(3)).build();
        stubTasks(rows.toString());
        final var originalAuth = MapRouletteClient.getInstance().auth();
        MapRouletteClient.getInstance().setAuthProvider(new UserAuthProvider(7));
        try {
            final var loaded = load().stream().collect(Collectors.toMap(TaskClusteredPoint::id, Function.identity()));
            assertEquals(Set.of(133361784L, 87739664L, 133361785L, 87739780L), loaded.keySet());
            assertEquals(99L, ((ClusteredPoint) loaded.get(133361784L)).lockedBy());
            assertTrue(ModifiedObjects.isLockedByOther(loaded.get(133361784L)));
            assertTrue(ModifiedObjects.isLockedByOther(loaded.get(87739664L)));
            assertFalse(ModifiedObjects.isLockedByOther(loaded.get(133361785L)), "The user's own lock");
            assertFalse(ModifiedObjects.isLockedByOther(loaded.get(87739780L)));
        } finally {
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }

    private static void stubTasks(String body) {
        WireMocks.current().register(put(urlPathEqualTo("/api/v2/tasks/box/-180/-85/180/85"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static Collection<TaskClusteredPoint> load() throws Exception {
        new MapRouletteDownloadChallengeTask().loadChallenge(15318, NullProgressMonitor.INSTANCE).get();
        final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
        assertEquals(1, layers.size());
        return layers.get(0).getTasks();
    }

    private static JsonObject locked(JsonObject task, long lockedBy) {
        return Json.createObjectBuilder(task).add("lockedBy", lockedBy).build();
    }

    /**
     * A logged-in user whose id is only known after a lookup, as with OAuth
     */
    private static final class UserAuthProvider implements AuthProvider {
        private final long id;
        private volatile boolean lookedUp;

        UserAuthProvider(long id) {
            this.id = id;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Set<Capability> capabilities() {
            return EnumSet.allOf(Capability.class);
        }

        @Override
        public void authenticate(HttpClient client, boolean loginRequired) {
            // The test server doesn't check credentials
        }

        @Override
        public OptionalLong userId() {
            this.lookedUp = true;
            return OptionalLong.of(this.id);
        }

        @Override
        public OptionalLong knownUserId() {
            return this.lookedUp ? OptionalLong.of(this.id) : OptionalLong.empty();
        }
    }
}
