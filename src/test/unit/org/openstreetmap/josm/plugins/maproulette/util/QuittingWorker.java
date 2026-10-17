// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import java.util.concurrent.RejectedExecutionException;

import org.openstreetmap.josm.gui.progress.swing.ProgressMonitorExecutor;

import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;

/**
 * Make {@link org.openstreetmap.josm.gui.MainApplication#worker} refuse jobs until closed, as it does after JOSM shut
 * it down while quitting. The real worker can't be shut down in tests: it is shared.
 */
public final class QuittingWorker extends MockUp<ProgressMonitorExecutor> implements AutoCloseable {
    private volatile boolean refusing = true;

    @Mock
    public void execute(Invocation invocation, Runnable command) {
        if (this.refusing) {
            throw new RejectedExecutionException("QuittingWorker");
        }
        invocation.proceed(command);
    }

    @Override
    public void close() {
        this.refusing = false;
    }
}
