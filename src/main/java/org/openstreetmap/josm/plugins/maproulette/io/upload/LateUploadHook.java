// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.ListenerList;
import org.openstreetmap.josm.tools.bugreport.BugReport;

/**
 * A late upload hook for uploading task status'
 */
public class LateUploadHook implements UploadHook {
    /**
     * A list of listeners for uploaded tasks
     */
    private static final ListenerList<Consumer<Map<Long, Task>>> MODIFIED_TASKS_UPLOADED = ListenerList.create();

    /**
     * Add an upload listener
     *
     * @param consumer The consumer will receive a sorted array of ids
     */
    public static void addUploadListener(Consumer<Map<Long, Task>> consumer) {
        MODIFIED_TASKS_UPLOADED.addListener(consumer);
    }

    /**
     * Remove an upload listener
     *
     * @param consumer The listener to remove
     */
    public static void removeUploadListener(Consumer<Map<Long, Task>> consumer) {
        MODIFIED_TASKS_UPLOADED.removeListener(consumer);
    }

    /**
     * JOSM calls this on the EDT, so the status updates are sent from the worker. The worker runs them before the
     * upload, which JOSM queues on the worker after this returns.
     */
    @Override
    public void modifyChangesetTags(Map<String, String> tags) {
        if (!MapRouletteAuth.isAvailable()) {
            return;
        }
        final var modifiedTasks = ModifiedObjects.getModifiedTasks();
        MainApplication.worker.execute(() -> sendStatuses(modifiedTasks));
    }

    /**
     * Send the status of modified tasks, release them and link them to the changeset
     *
     * @param modifiedTasks The modified tasks
     */
    private static void sendStatuses(List<ModifiedTask> modifiedTasks) {
        final var updatedTasks = new HashMap<Long, Task>(modifiedTasks.size());
        for (ModifiedTask entry : modifiedTasks) {
            if (entry.status() != TaskStatus.CREATED) {
                try {
                    TaskAPI.updateStatus(entry.task().id(), entry.status(), entry.comment(), entry.tags(),
                            entry.reviewRequested(), entry.completionResponses());
                    updatedTasks.put(entry.task().id(), TaskAPI.release(entry.task().id()));
                    ModifiedObjects.removeLockedTask(entry.task());
                    ModifiedObjects.removeModifiedTask(entry);
                    TaskAPI.changeset(entry.task().id());
                } catch (IOException e) {
                    ExceptionDialogUtil.explainException(e);
                } catch (RuntimeException e) {
                    // One task failing must not stop the others (or reach the worker)
                    BugReport.intercept(e).put("task", entry.task().id()).warn();
                }
            }
        }
        GuiHelper.runInEDT(() -> MODIFIED_TASKS_UPLOADED.fireEvent(l -> l.accept(updatedTasks)));
    }
}
