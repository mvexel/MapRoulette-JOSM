// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.bugreport.BugReport;

/**
 * Run plugin jobs on the JOSM worker
 */
public final class Workers {
    private Workers() {
        // Hide constructor
    }

    /**
     * Run a job on {@link MainApplication#worker}. JOSM's worker only logs an exception thrown by a job; this shows
     * it to the user as a bug report instead.
     *
     * @param job The job to run
     */
    public static void execute(Runnable job) {
        MainApplication.worker.execute(() -> {
            try {
                job.run();
            } catch (RuntimeException exception) {
                BugReport.intercept(exception).warn();
            }
        });
    }
}
