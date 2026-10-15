// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.time.Duration;

import javax.swing.JOptionPane;
import javax.swing.Timer;

import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteForbiddenException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteNotFoundException;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.tools.Logging;

/**
 * Keep the locks on started tasks while the user edits. MapRoulette drops a lock 1–2 hours after it was taken or last
 * refreshed, and an edit can take longer.
 */
public final class LockRefresher {
    /**
     * How often to refresh: well within the 1 hour lock time
     */
    static final Duration INTERVAL = Duration.ofMinutes(30);
    private static Timer timer;

    private LockRefresher() {
        // Hide constructor
    }

    /**
     * Start refreshing locks
     */
    public static synchronized void start() {
        if (timer == null) {
            // The timer only queues the refresh; the requests run on the worker
            timer = new Timer((int) INTERVAL.toMillis(), e -> Workers.execute(LockRefresher::refreshAll));
            timer.setRepeats(true);
            timer.start();
        }
    }

    /**
     * Stop refreshing locks
     */
    public static synchronized void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    /**
     * Refresh the locks of all started tasks. Call it off the EDT.
     */
    static void refreshAll() {
        final var tasks = ModifiedObjects.getLockedTasks();
        if (tasks.isEmpty() || !MapRouletteClient.getInstance().auth().capabilities()
                .contains(AuthProvider.Capability.REFRESH_LOCK)) {
            // Checked here, not at tick time: the user may log out in between
            return;
        }
        for (var task : tasks) {
            try {
                TaskAPI.refreshLock(task.id());
            } catch (MapRouletteForbiddenException | MapRouletteNotFoundException e) {
                // Someone else has the task now, or it is gone
                Logging.warn("MapRoulette task " + task.id() + " is no longer locked by you");
                Logging.debug(e);
                tell(tr("MapRoulette task {0} is no longer locked by you. Someone else may be working on it.",
                        task.id()));
            } catch (IOException e) {
                // Tried again at the next interval
                Logging.warn("Could not refresh the lock on MapRoulette task " + task.id());
                Logging.debug(e);
            }
        }
    }

    private static void tell(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            Logging.info(message);
        } else {
            GuiHelper.runInEDT(() -> new Notification(message).setIcon(JOptionPane.WARNING_MESSAGE)
                    .setDuration(Notification.TIME_LONG).show());
        }
    }
}
