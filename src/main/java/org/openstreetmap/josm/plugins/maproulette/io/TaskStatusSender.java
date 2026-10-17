// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.ListenerList;
import org.openstreetmap.josm.tools.bugreport.BugReport;

/**
 * Sends the status the user picked for a task, as soon as it is picked (D15)
 */
public final class TaskStatusSender {
    /**
     * Listeners for tasks whose status was sent
     */
    private static final ListenerList<Consumer<Map<Long, Task>>> STATUS_SENT = ListenerList.create();

    private TaskStatusSender() {
        // Hide constructor
    }

    /**
     * Add a listener for tasks whose status was sent. It is called on the EDT.
     *
     * @param consumer The consumer will receive the updated tasks by id
     */
    public static void addListener(Consumer<Map<Long, Task>> consumer) {
        STATUS_SENT.addListener(consumer);
    }

    /**
     * Remove a listener
     *
     * @param consumer The listener to remove
     */
    public static void removeListener(Consumer<Map<Long, Task>> consumer) {
        STATUS_SENT.removeListener(consumer);
    }

    /**
     * Send the status of modified tasks and release them. A status that could not be sent is dropped: the task stays
     * started, and the user can pick a status again. Must not be called on the EDT.
     *
     * @param modifiedTasks The modified tasks
     */
    public static void sendStatuses(List<ModifiedTask> modifiedTasks) {
        final var updatedTasks = new HashMap<Long, Task>(modifiedTasks.size());
        final var failures = new ArrayList<Exception>();
        // The statuses belong to this server: if the user switches servers meanwhile, nothing more is sent (#86)
        final var server = MapRouletteConfig.getBaseUrl();
        for (ModifiedTask entry : modifiedTasks) {
            // Skip a status replaced by a newer one in the meantime, or forgotten after a server switch
            if (entry.status() == TaskStatus.CREATED || ModifiedObjects.getModifiedTask(entry.task().id()) != entry
                    || !server.equals(MapRouletteConfig.getBaseUrl())) {
                continue;
            }
            var sent = false;
            try {
                TaskAPI.updateStatus(entry.task().id(), entry.status(), entry.comment(), entry.tags(),
                        entry.reviewRequested(), entry.completionResponses());
                sent = true;
                if (!server.equals(MapRouletteConfig.getBaseUrl())) {
                    continue;
                }
                // A skip has already released the lock
                updatedTasks.put(entry.task().id(),
                        entry.status() == TaskStatus.SKIPPED ? TaskAPI.get(entry.task().id())
                                : TaskAPI.release(entry.task().id()));
                ModifiedObjects.removeLockedTask(entry.task());
            } catch (IOException e) {
                failures.add(e);
            } catch (RuntimeException e) {
                // One task failing must not stop the others (or reach the worker)
                BugReport.intercept(e).put("task", entry.task().id()).warn();
            } finally {
                ModifiedObjects.removeModifiedTask(entry);
                if (!sent) {
                    // Nothing to link the changeset to
                    ModifiedObjects.removeUnlinkedFixed(entry.task());
                }
            }
        }
        failures.forEach(ExceptionDialogUtil::explainException);
        GuiHelper.runInEDT(() -> STATUS_SENT.fireEvent(l -> l.accept(updatedTasks)));
    }
}
