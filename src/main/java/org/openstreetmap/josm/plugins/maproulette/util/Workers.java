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
     * Run a job on {@link MainApplication#worker}, and show an exception thrown by the job to the user as a bug report.
     * JOSM's default exception handler does the same for a plain {@code execute}; reporting here also covers jobs
     * that would otherwise be passed to {@code submit}, whose exceptions JOSM only logs.
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
