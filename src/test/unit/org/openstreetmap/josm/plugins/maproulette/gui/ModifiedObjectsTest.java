// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link ModifiedObjects}
 */
@BasicPreferences
class ModifiedObjectsTest {
    private static final Task TASK = new Task(1, "", null, null, 15318, null, null, null, null, null, null, null, null,
            null, 0, null, null, null, false, null, null);

    @AfterEach
    void tearDown() {
        ModifiedObjects.forgetAll();
    }

    /**
     * Starting, stopping and forgetting tasks tell the lock listeners on the EDT, so Stop follows a lock dropped
     * elsewhere
     *
     * @throws Exception if the EDT can't be waited for
     */
    @Test
    void testLockListener() throws Exception {
        final var calls = new AtomicInteger();
        final Runnable listener = () -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            calls.incrementAndGet();
        };
        ModifiedObjects.addLockListener(listener);
        try {
            ModifiedObjects.addLockedTask(TASK);
            ModifiedObjects.addLockedTask(TASK);
            SwingUtilities.invokeAndWait(() -> {
                /* Sync */ });
            assertEquals(1, calls.get(), "Only a change is reported");
            ModifiedObjects.removeLockedTask(TASK);
            SwingUtilities.invokeAndWait(() -> {
                /* Sync */ });
            assertEquals(2, calls.get());
            ModifiedObjects.addLockedTask(TASK);
            ModifiedObjects.forgetAll();
            SwingUtilities.invokeAndWait(() -> {
                /* Sync */ });
            assertEquals(4, calls.get());
        } finally {
            ModifiedObjects.removeLockListener(listener);
        }
        ModifiedObjects.addLockedTask(TASK);
        SwingUtilities.invokeAndWait(() -> {
            /* Sync */ });
        assertEquals(4, calls.get(), "A removed listener is not called");
    }

    /**
     * A lock the logged-in user holds from an earlier session is theirs, not another mapper's (#112)
     */
    @Test
    void testOwnLockIsNotOthers() {
        final var point = TaskListPanelTest.point(1, 15318, "SLC benches", 2L);
        assertTrue(ModifiedObjects.isLockedByOther(point), "Without a known user, every lock is another mapper's");
        final var originalAuth = MapRouletteClient.getInstance().auth();
        MapRouletteClient.getInstance().setAuthProvider(new AuthProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Set<Capability> capabilities() {
                return Set.of();
            }

            @Override
            public void authenticate(HttpClient client, boolean loginRequired) {
                // Not used
            }

            @Override
            public OptionalLong knownUserId() {
                return OptionalLong.of(2);
            }
        });
        try {
            assertFalse(ModifiedObjects.isLockedByOther(point));
            assertTrue(ModifiedObjects.isLockedByOther(TaskListPanelTest.point(1, 15318, "SLC benches", 3L)));
            assertFalse(ModifiedObjects.isLockedByOther(TaskListPanelTest.point(1, 15318, "SLC benches", null)));
        } finally {
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }
}
