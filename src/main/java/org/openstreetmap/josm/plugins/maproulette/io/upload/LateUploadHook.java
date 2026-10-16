// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.osm.ChangesetCacheEvent;
import org.openstreetmap.josm.data.osm.ChangesetCacheListener;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.io.UploadDialog;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.ListenerList;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.bugreport.BugReport;

/**
 * Sends task statuses, and links the tasks marked Fixed to the changeset of the upload that follows (D15)
 */
public class LateUploadHook implements UploadHook, ChangesetCacheListener {
    /**
     * A list of listeners for updated tasks
     */
    private static final ListenerList<Consumer<Map<Long, Task>>> MODIFIED_TASKS_UPLOADED = ListenerList.create();

    /**
     * Tasks marked Fixed whose changeset is still open, by changeset id. MapRoulette only links a closed changeset.
     */
    private static final Map<Long, List<Task>> AWAITING_CLOSE = new ConcurrentHashMap<>();

    /**
     * Add a listener for tasks whose status was sent
     *
     * @param consumer The consumer will receive the updated tasks by id
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
     * The primitives of the upload in progress, captured in {@link #checkUpload(APIDataSet)}
     */
    private List<OsmPrimitive> uploading = List.of();

    /**
     * JOSM calls this on the EDT, after the upload dialog and right before {@link #modifyChangesetTags(Map)}
     */
    @Override
    public boolean checkUpload(APIDataSet apiDataSet) {
        this.uploading = List.copyOf(apiDataSet.getPrimitives());
        return true;
    }

    /**
     * JOSM calls this on the EDT, then queues the upload on the worker. The statuses were sent when the user picked
     * them; after a successful upload, the tasks marked Fixed are linked to its changeset. After a failed or cancelled
     * upload, they wait for the next one.
     */
    @Override
    public void modifyChangesetTags(Map<String, String> tags) {
        final var fixed = ModifiedObjects.getUnlinkedFixed();
        if (fixed.isEmpty() || !MapRouletteAuth.isAvailable()) {
            return;
        }
        final var primitives = this.uploading;
        final var closed = isChangesetClosedAfterUpload();
        // The upload is queued on the worker after this returns; queue the job from a later EDT event so that it runs
        // after the upload
        SwingUtilities.invokeLater(() -> MainApplication.worker.execute(() -> {
            final long changesetId = GuiHelper.runInEDTAndWaitAndReturn(
                    () -> isUploaded(primitives) ? Math.max(uploadedChangeset(primitives), 0) : -1);
            if (changesetId < 0) {
                return;
            }
            fixed.forEach(ModifiedObjects::removeUnlinkedFixed);
            if (closed || changesetId == 0) {
                fixed.forEach(task -> link(task, changesetId));
            } else {
                AWAITING_CLOSE.merge(changesetId, fixed, (a, b) -> Stream.concat(a.stream(), b.stream()).toList());
            }
        }));
    }

    /**
     * Link the tasks waiting for a changeset once JOSM closes it
     */
    @Override
    public void changesetCacheUpdated(ChangesetCacheEvent event) {
        Stream.concat(event.getAddedChangesets().stream(), event.getUpdatedChangesets().stream())
                .filter(changeset -> !changeset.isOpen()).forEach(changeset -> {
                    final var tasks = AWAITING_CLOSE.remove((long) changeset.getId());
                    if (tasks != null) {
                        MainApplication.worker.execute(() -> tasks.forEach(task -> link(task, changeset.getId())));
                    }
                });
    }

    /**
     * Check if the changeset is closed after the upload. MapRoulette only links a task to a closed changeset.
     *
     * @return {@code true} if JOSM closes the changeset after the upload
     */
    boolean isChangesetClosedAfterUpload() {
        return UploadDialog.getUploadDialog().getUploadStrategySpecification().isCloseChangesetAfterUpload();
    }

    /**
     * Check if the primitives were uploaded. JOSM clears the modified flag of uploaded primitives and purges uploaded
     * deletions; a failed, cancelled or partial upload leaves some of them new or modified.
     *
     * @param primitives The primitives of the upload
     * @return {@code true} if all were uploaded
     */
    static boolean isUploaded(Collection<OsmPrimitive> primitives) {
        return primitives.stream().allMatch(p -> p.getDataSet() == null || !p.isNew() && !p.isModified());
    }

    /**
     * Get the changeset the primitives were uploaded to
     *
     * @param primitives The uploaded primitives
     * @return The changeset id, or {@code 0} if there is none
     */
    static long uploadedChangeset(Collection<OsmPrimitive> primitives) {
        return primitives.stream().mapToLong(OsmPrimitive::getChangesetId).max().orElse(0);
    }

    /**
     * Link a task to the changeset. The status is already sent, so a refusal here only means the task stays unlinked.
     * Must not be called on the EDT.
     *
     * @param task        The task marked Fixed
     * @param changesetId The uploaded changeset, or {@code 0} to let MapRoulette find it
     */
    private static void link(Task task, long changesetId) {
        try {
            TaskAPI.changeset(task.id(), changesetId);
        } catch (IOException e) {
            Logging.warn(e);
            final var message = tr("MapRoulette task {0} is Fixed, but MapRoulette did not link it to your changeset.",
                    Long.toString(task.id()));
            if (GraphicsEnvironment.isHeadless()) {
                Logging.info(message);
            } else {
                GuiHelper.runInEDT(() -> new Notification(message).setIcon(JOptionPane.INFORMATION_MESSAGE)
                        .setDuration(Notification.TIME_LONG).show());
            }
        }
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
        for (ModifiedTask entry : modifiedTasks) {
            // Skip a status replaced by a newer one in the meantime
            if (entry.status() == TaskStatus.CREATED || ModifiedObjects.getModifiedTask(entry.task().id()) != entry) {
                continue;
            }
            var sent = false;
            try {
                TaskAPI.updateStatus(entry.task().id(), entry.status(), entry.comment(), entry.tags(),
                        entry.reviewRequested(), entry.completionResponses());
                sent = true;
                updatedTasks.put(entry.task().id(), TaskAPI.release(entry.task().id()));
                ModifiedObjects.removeLockedTask(entry.task());
            } catch (IOException e) {
                failures.add(e);
            } catch (RuntimeException e) {
                // One task failing must not stop the others (or reach the worker)
                BugReport.intercept(e).put("task", entry.task().id()).warn();
            } finally {
                ModifiedObjects.removeModifiedTask(entry);
                if (!sent) {
                    ModifiedObjects.removeUnlinkedFixed(entry.task());
                }
            }
        }
        failures.forEach(ExceptionDialogUtil::explainException);
        GuiHelper.runInEDT(() -> MODIFIED_TASKS_UPLOADED.fireEvent(l -> l.accept(updatedTasks)));
    }
}
