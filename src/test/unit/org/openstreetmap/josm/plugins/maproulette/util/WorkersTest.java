// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

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
}
