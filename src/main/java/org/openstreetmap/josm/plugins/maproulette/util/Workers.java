// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import java.util.concurrent.RejectedExecutionException;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.bugreport.BugReport;

/**
 * Run plugin jobs on the JOSM worker
 */
public final class Workers {
    private Workers() {
        // Hide constructor
    }

    /**
     * Run a job on {@link MainApplication#worker}, and show an exception thrown by the job to the user as a bug report.
     * JOSM's default exception handler does the same for a plain {@code execute}; reporting here also covers jobs
     * that would otherwise be passed to {@code submit}, whose exceptions JOSM only logs. While JOSM quits, the job is
     * dropped.
     *
     * @param job The job to run
     */
    public static void execute(Runnable job) {
        final boolean queued = tryExecute(() -> {
            try {
                job.run();
            } catch (RuntimeException exception) {
                BugReport.intercept(exception).warn();
            }
        });
        if (!queued) {
            Logging.debug("MapRoulette: JOSM is quitting, dropped a job");
        }
    }

    /**
     * Run a job on {@link MainApplication#worker} unless JOSM is quitting. JOSM shuts the worker down before it
     * removes the layers, so code that runs from layer, paint or timer events must not fail when the worker refuses a
     * job: the exception would stop JOSM from quitting.
     *
     * @param job The job to run
     * @return {@code false} if the worker refused the job
     */
    public static boolean tryExecute(Runnable job) {
        try {
            MainApplication.worker.execute(job);
            return true;
        } catch (RejectedExecutionException rejectedExecutionException) {
            Logging.trace(rejectedExecutionException);
            return false;
        }
    }
}
