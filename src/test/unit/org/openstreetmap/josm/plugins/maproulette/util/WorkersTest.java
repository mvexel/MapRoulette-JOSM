// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.tools.bugreport.BugReportQueue;

/**
 * Test class for {@link Workers}
 */
class WorkersTest {
    /**
     * An exception in a job is reported to the user, not only logged (#44)
     *
     * @throws InterruptedException
     *             if the wait is interrupted
     */
    @Test
    void testExceptionIsReported() throws InterruptedException {
        final var message = "WorkersTest#testExceptionIsReported";
        final var reported = new CountDownLatch(1);
        BugReportQueue.getInstance().addBugReportHandler(e -> {
            if (message.equals(e.getCause().getMessage())) {
                reported.countDown();
                return true;
            }
            return false;
        });
        Workers.execute(() -> {
            throw new IllegalStateException(message);
        });
        assertTrue(reported.await(10, TimeUnit.SECONDS));
    }

    /**
     * While JOSM quits, the worker refuses jobs: they are dropped instead of failing the caller (B2)
     */
    @Test
    void testRefusedJobIsDropped() {
        try (var quitting = new QuittingWorker()) {
            assertFalse(Workers.tryExecute(() -> {
                // Never runs
            }));
            assertDoesNotThrow(() -> Workers.execute(() -> {
                // Never runs
            }));
        }
        assertTrue(Workers.tryExecute(() -> {
            // Runs on the worker again
        }));
    }
}
