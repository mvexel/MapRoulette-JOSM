// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;
import org.openstreetmap.josm.tools.HttpClient;

import mockit.Mock;
import mockit.MockUp;

/**
 * No MapRoulette request may run on the EDT (epic #3, issue #36).
 * <p>
 * The client guard throws {@link IllegalStateException} on the EDT, but a caller that catches {@code Exception} would
 * hide it. So these tests also record the thread of every request that is actually signed, and drive the UI actions
 * the way Swing does (on the EDT), checking that their requests were made and none of them on the EDT.
 */
@BasicPreferences
@Main
@MapRouletteConfig
@Projection
class MapRouletteClientEdtTest {
    private ThreadRecordingAuthProvider auth;
    private AuthProvider originalAuth;

    @BeforeEach
    void setUp() {
        this.originalAuth = MapRouletteClient.getInstance().auth();
        this.auth = new ThreadRecordingAuthProvider();
        MapRouletteClient.getInstance().setAuthProvider(this.auth);
    }

    @AfterEach
    void tearDown() {
        MapRouletteClient.getInstance().setAuthProvider(this.originalAuth);
    }

    @Test
    void testClientRejectsCallsOnEdt() throws Exception {
        WireMocks.current()
                .register(get(urlPathEqualTo("/api/v2/challenge/1")).willReturn(aResponse().withBody("{}")));
        GuiHelper.runInEDTAndWaitWithException(() -> assertThrows(IllegalStateException.class,
                () -> MapRouletteClient.getInstance().get("/challenge/1", Collections.emptyMap(), reader -> null)));
        WireMocks.current().verifyThat(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void testClientAllowsCallsOffEdt() throws Exception {
        WireMocks.current()
                .register(get(urlPathEqualTo("/api/v2/challenge/1")).willReturn(aResponse().withBody("{}")));
        MainApplication.worker.submit(() -> {
            MapRouletteClient.getInstance().get("/challenge/1", Collections.emptyMap(), reader -> null);
            return null;
        }).get(10, TimeUnit.SECONDS);
        this.auth.assertRequestsOffEdt(1);
    }

    /**
     * The task list's Download Data button, clicked on the EDT
     */
    @Test
    void testDownloadDataAction() throws Exception {
        final var bounds = new Bounds(36.0770518, -119.1139411, 36.0839216, -119.0992458);
        new MockUp<MapViewState.MapViewRectangle>() {
            @Mock
            public Bounds getLatLonBoundsBox() {
                return bounds;
            }
        };
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testDownloadDataAction", null));
        final var panel = new TaskListPanel();
        try {
            GuiHelper.runInEDTAndWaitWithException(() -> TaskListPanelTest.getDownloadAction(panel).actionPerformed(null));
            sync();
            this.auth.assertRequestsOffEdt(1);
        } finally {
            panel.destroy();
        }
    }

    private static void sync() throws Exception {
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }

    /**
     * Records the thread of every signed request. Signing happens on the calling thread just before sending.
     */
    static final class ThreadRecordingAuthProvider implements AuthProvider {
        private final List<Boolean> onEdt = new CopyOnWriteArrayList<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Set<Capability> capabilities() {
            return EnumSet.allOf(Capability.class);
        }

        @Override
        public void sign(HttpClient request) {
            this.onEdt.add(SwingUtilities.isEventDispatchThread());
        }

        /**
         * Check that at least {@code minimum} requests were made, and none on the EDT
         *
         * @param minimum The minimum number of requests the action should have made
         */
        void assertRequestsOffEdt(int minimum) {
            assertTrue(this.onEdt.size() >= minimum,
                    "Expected at least " + minimum + " MapRoulette request(s), got " + this.onEdt.size()
                            + " (did the EDT guard throw and get swallowed?)");
            assertEquals(0, this.onEdt.stream().filter(Boolean::booleanValue).count(),
                    "MapRoulette requests made on the EDT");
        }
    }
}
