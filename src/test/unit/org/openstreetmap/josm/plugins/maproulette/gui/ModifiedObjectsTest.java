// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
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
}
