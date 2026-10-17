// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.osm.Changeset;
import org.openstreetmap.josm.data.osm.ChangesetCacheEvent;
import org.openstreetmap.josm.data.osm.ChangesetCacheListener;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.io.UploadDialog;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteLockHeldException;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.io.TaskStatusSender;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.Logging;

/**
 * After a successful upload, finishes the tasks the upload is for (#115), and links the tasks marked Fixed to its
 * changeset (D15)
 */
public class LateUploadHook implements UploadHook, ChangesetCacheListener {
    /**
     * A task whose upload succeeded but whose Fixed could not be sent, so the panel can offer to send it again
     *
     * @param status      The status to send
     * @param changesetId The changeset of the upload
     * @param closed      {@code true} if JOSM closed the changeset after the upload
     * @param server      The MapRoulette server of the task
     */
    private record Unfinished(ModifiedTask status, long changesetId, boolean closed, String server) {
    }

    /**
     * The tasks whose Fixed could not be sent after their upload, by id
     */
    private static final Map<Long, Unfinished> UNFINISHED = new ConcurrentHashMap<>();

    /**
     * Add a listener for tasks whose status was sent
     *
     * @param consumer The consumer will receive the updated tasks by id
     * @see TaskStatusSender#addListener(Consumer)
     */
    public static void addUploadListener(Consumer<Map<Long, Task>> consumer) {
        TaskStatusSender.addListener(consumer);
    }

    /**
     * Remove an upload listener
     *
     * @param consumer The listener to remove
     */
    public static void removeUploadListener(Consumer<Map<Long, Task>> consumer) {
        TaskStatusSender.removeListener(consumer);
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
        // Recorded by the early hook for this upload only: a cancelled or failed upload finishes nothing
        final var finishing = ModifiedObjects.takeFinishing();
        final var fixed = ModifiedObjects.getUnlinkedFixed();
        if (fixed.isEmpty() && finishing.isEmpty() || !MapRouletteAuth.isAvailable()) {
            return;
        }
        final var primitives = this.uploading;
        final var server = MapRouletteConfig.getBaseUrl();
        final var closed = isChangesetClosedAfterUpload();
        // Until Fixed is sent, the user can't pick another status or stop the task: those jobs would queue behind the
        // upload on the worker and lose to the Fixed sent below
        final var finishingIds = finishing.stream().map(status -> status.task().id()).toList();
        ModifiedObjects.setFinishPending(finishingIds, true);
        // The upload is queued on the worker after this returns; queue the job from a later EDT event so that it runs
        // after the upload
        SwingUtilities.invokeLater(() -> MainApplication.worker.execute(() -> {
            try {
                afterUpload(primitives, fixed, finishing, closed, server);
            } finally {
                ModifiedObjects.setFinishPending(finishingIds, false);
            }
        }));
    }

    /**
     * After an upload, send Fixed for the tasks it finishes, and link the tasks marked Fixed to its changeset. Runs on
     * the worker.
     *
     * @param primitives The primitives of the upload
     * @param fixed      The tasks marked Fixed before the upload
     * @param finishing  The statuses the upload finishes
     * @param closed     {@code true} if JOSM closes the changeset after the upload
     * @param server     The MapRoulette server of the tasks
     */
    private static void afterUpload(List<OsmPrimitive> primitives, List<Task> fixed, List<ModifiedTask> finishing,
            boolean closed, String server) {
        final long changesetId = GuiHelper.runInEDTAndWaitAndReturn(
                () -> isUploaded(primitives) ? Math.max(uploadedChangeset(primitives), 0) : -1);
        if (changesetId < 0 || !server.equals(MapRouletteConfig.getBaseUrl())) {
            // Not uploaded: wait for the next upload, and the started task stays started. Another server: the tasks
            // were forgotten.
            return;
        }
        finish(finishing, changesetId, closed, server);
        // Skip a task whose status was not sent in the meantime, and one whose OSM objects are not in this upload:
        // it waits for an upload that has them
        final var unlinked = ModifiedObjects.getUnlinkedFixed().stream().map(Task::id).collect(Collectors.toSet());
        final var linked = fixed.stream().filter(task -> unlinked.contains(task.id()))
                .filter(task -> inUpload(TaskPrimitives.getPrimitiveIds(task), primitives)).toList();
        if (linked.isEmpty()) {
            return;
        }
        linked.forEach(ModifiedObjects::removeUnlinkedFixed);
        if (closed || changesetId == 0) {
            ChangesetLinks.link(linked, changesetId, server);
        } else {
            ChangesetLinks.await(linked, changesetId, server);
        }
    }

    /**
     * Send Fixed for the tasks an upload finished, then link them to its changeset: MapRoulette only links a task that
     * is Fixed. Must not be called on the EDT.
     *
     * @param allFinishing The statuses to send, unless the task was stopped or got another status meanwhile
     * @param changesetId  The changeset of the upload
     * @param closed      {@code true} if JOSM closed the changeset after the upload
     * @param server      The MapRoulette server of the tasks
     */
    static void finish(List<ModifiedTask> allFinishing, long changesetId, boolean closed, String server) {
        // With an asynchronous upload, the user can pick another status or stop the task while it runs; that choice wins
        final var finishing = allFinishing.stream().filter(LateUploadHook::stillFinishing).toList();
        allFinishing.stream().filter(status -> !finishing.contains(status))
                .forEach(status -> UNFINISHED.remove(status.task().id()));
        if (finishing.isEmpty()) {
            return;
        }
        finishing.forEach(status -> {
            UNFINISHED.remove(status.task().id());
            ModifiedObjects.addModifiedTask(status);
            ModifiedObjects.addUnlinkedFixed(status.task());
        });
        final var result = TaskStatusSender.send(finishing);
        result.failed().forEach((status, exception) -> {
            UNFINISHED.put(status.task().id(), new Unfinished(status, changesetId, closed, server));
            explainNotFinished(status.task(), exception);
        });
        final var unlinked = ModifiedObjects.getUnlinkedFixed().stream().map(Task::id).collect(Collectors.toSet());
        final var finished = finishing.stream().filter(status -> !result.failed().containsKey(status))
                .map(ModifiedTask::task).filter(task -> unlinked.contains(task.id())).toList();
        finished.forEach(ModifiedObjects::removeUnlinkedFixed);
        if (!finished.isEmpty()) {
            if (closed || changesetId == 0) {
                ChangesetLinks.link(finished, changesetId, server);
            } else {
                ChangesetLinks.await(finished, changesetId, server);
            }
        }
        TaskStatusSender.fireSent(result.updated());
    }

    /**
     * Check if a task is still to be finished: it is still started, and the user has not picked another status
     *
     * @param status The Fixed status to send
     * @return {@code true} to send it
     */
    private static boolean stillFinishing(ModifiedTask status) {
        final var id = status.task().id();
        final var picked = ModifiedObjects.getModifiedTask(id);
        return ModifiedObjects.getLockedTask(id) != null && (picked == null || picked.status() == TaskStatus.CREATED);
    }

    private static void explainNotFinished(Task task, Exception exception) {
        if (exception instanceof MapRouletteLockHeldException lockHeld && lockHeld.heldTaskId().isEmpty()) {
            // locked_by_other: the lock expired and another mapper started the task
            final var message = tr("Your edits are uploaded, but MapRoulette did not mark task {0} as fixed: another "
                    + "mapper has started it. {1}", task.id(), lockHeld.serverMessage().orElse(""));
            Logging.warn(message);
            if (!GraphicsEnvironment.isHeadless()) {
                GuiHelper.runInEDT(() -> new Notification(message.strip()).setIcon(JOptionPane.WARNING_MESSAGE)
                        .setDuration(Notification.TIME_LONG).show());
            }
        } else if (exception instanceof IOException ioException) {
            ExceptionDialogUtil.explainException(ioException);
        }
    }

    /**
     * Check if a task's upload succeeded but its Fixed could not be sent
     *
     * @param taskId The task
     * @return {@code true} if Fixed can be sent again with {@link #retryFinish(long)}
     */
    public static boolean isUnfinished(long taskId) {
        final var unfinished = UNFINISHED.get(taskId);
        return unfinished != null && unfinished.server().equals(MapRouletteConfig.getBaseUrl());
    }

    /**
     * Send Fixed again for a task whose upload succeeded, then link the changeset
     *
     * @param taskId The task
     */
    public static void retryFinish(long taskId) {
        final var unfinished = UNFINISHED.get(taskId);
        if (unfinished != null) {
            MainApplication.worker.execute(() -> finish(List.of(unfinished.status()), unfinished.changesetId(),
                    unfinished.closed(), unfinished.server()));
        }
    }

    /**
     * Forget the tasks whose Fixed could not be sent, for example when the user switches servers
     *
     * @return The ids of the forgotten tasks
     */
    public static Set<Long> forgetUnfinished() {
        final var ids = Set.copyOf(UNFINISHED.keySet());
        ids.forEach(UNFINISHED::remove);
        return ids;
    }

    /**
     * Link the tasks waiting for a changeset once JOSM closes it. JOSM calls this on whichever thread updated the cache,
     * often the worker; the links are sent from a job of their own.
     */
    @Override
    public void changesetCacheUpdated(ChangesetCacheEvent event) {
        Stream.concat(event.getAddedChangesets().stream(), event.getUpdatedChangesets().stream())
                .filter(changeset -> !changeset.isOpen()).mapToLong(Changeset::getId).filter(ChangesetLinks::awaits)
                .forEach(changesetId -> MainApplication.worker.execute(() -> ChangesetLinks.retry(changesetId)));
    }

    /**
     * Check if an upload is for a task: it has at least one of the task's OSM objects. A task that names no OSM objects
     * (such as a missing feature) may belong to any upload.
     *
     * @param taskIds  The OSM objects of the task
     * @param uploaded The uploaded primitives
     * @return {@code true} if the upload may be for the task
     */
    static boolean inUpload(Collection<PrimitiveId> taskIds, Collection<? extends IPrimitive> uploaded) {
        if (taskIds.isEmpty()) {
            return true;
        }
        final var ids = taskIds.stream().map(id -> new SimplePrimitiveId(id.getUniqueId(), id.getType()))
                .collect(Collectors.toSet());
        return uploaded.stream().anyMatch(p -> ids.contains(new SimplePrimitiveId(p.getUniqueId(), p.getType())));
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
}
