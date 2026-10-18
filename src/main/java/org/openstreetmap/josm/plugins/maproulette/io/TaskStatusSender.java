// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
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
        final var result = send(modifiedTasks);
        result.failed().values().stream().filter(IOException.class::isInstance)
                .forEach(ExceptionDialogUtil::explainException);
        fireSent(result.updated());
    }

    /**
     * The outcome of sending statuses
     *
     * @param updated The tasks whose status was sent, as the server returned them, by id
     * @param failed  The statuses that were not sent, with the reason. A {@link RuntimeException} was already reported.
     */
    public record Result(Map<Long, Task> updated, Map<ModifiedTask, Exception> failed) {
    }

    /**
     * Send the status of modified tasks and release them, without telling the user or the listeners. Must not be called
     * on the EDT.
     *
     * @param modifiedTasks The modified tasks
     * @return What was sent and what failed
     * @see #fireSent(Map)
     */
    public static Result send(List<ModifiedTask> modifiedTasks) {
        final var updatedTasks = new HashMap<Long, Task>(modifiedTasks.size());
        final var failures = new LinkedHashMap<ModifiedTask, Exception>();
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
                ModifiedObjects.setSentStatus(entry.task().id(), entry.status());
                TaskResults.sent(entry.task(), entry.status());
                // A skip has already released the lock
                updatedTasks.put(entry.task().id(),
                        entry.status() == TaskStatus.SKIPPED ? TaskAPI.get(entry.task().id())
                                : TaskAPI.release(entry.task().id()));
                ModifiedObjects.removeLockedTask(entry.task());
            } catch (IOException e) {
                if (!sent) {
                    failures.put(entry, e);
                } else {
                    // The status is set; only the release failed, and the lock expires on its own
                    ExceptionDialogUtil.explainException(e);
                }
            } catch (RuntimeException e) {
                // One task failing must not stop the others (or reach the worker)
                BugReport.intercept(e).put("task", entry.task().id()).warn();
                if (!sent) {
                    failures.put(entry, e);
                }
            } finally {
                ModifiedObjects.removeModifiedTask(entry);
                if (!sent) {
                    // Nothing to link the changeset to
                    ModifiedObjects.removeUnlinkedFixed(entry.task());
                }
            }
        }
        return new Result(updatedTasks, failures);
    }

    /**
     * Tell the listeners which tasks were sent
     *
     * @param updatedTasks The tasks whose status was sent, by id
     */
    public static void fireSent(Map<Long, Task> updatedTasks) {
        GuiHelper.runInEDT(() -> STATUS_SENT.fireEvent(l -> l.accept(updatedTasks)));
    }
}
