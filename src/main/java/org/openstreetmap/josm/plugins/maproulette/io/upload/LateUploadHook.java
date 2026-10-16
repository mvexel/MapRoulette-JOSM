// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.UploadDialog;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteHttpException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteNotFoundException;
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
     * JOSM calls this on the EDT, then queues the upload on the worker. Statuses other than FIXED are sent from the
     * worker before the upload, as before. FIXED depends on the OSM upload, so it is sent after the upload, and only
     * if the upload succeeded (#45); otherwise it is kept for the next upload.
     */
    @Override
    public void modifyChangesetTags(Map<String, String> tags) {
        if (!MapRouletteAuth.isAvailable()) {
            return;
        }
        final var modifiedTasks = ModifiedObjects.getModifiedTasks();
        final var fixed = modifiedTasks.stream().filter(entry -> entry.status() == TaskStatus.FIXED).toList();
        final var others = modifiedTasks.stream().filter(entry -> entry.status() != TaskStatus.FIXED).toList();
        if (!others.isEmpty()) {
            MainApplication.worker.execute(() -> sendStatuses(others, true));
        }
        if (!fixed.isEmpty()) {
            final var primitives = this.uploading;
            final var linkChangeset = isChangesetClosedAfterUpload();
            // The upload is queued on the worker after this returns; queue the job from a later EDT event so that it
            // runs after the upload
            SwingUtilities.invokeLater(() -> MainApplication.worker.execute(() -> {
                if (Boolean.TRUE.equals(GuiHelper.runInEDTAndWaitAndReturn(() -> isUploaded(primitives)))) {
                    sendStatuses(fixed, linkChangeset);
                } else {
                    GuiHelper.runInEDT(LateUploadHook::explainHeld);
                }
            }));
        }
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

    private static void explainHeld() {
        ConditionalOptionPaneUtil.showMessageDialog("maproulette.status.held", MainApplication.getMainFrame(),
                tr("The upload did not complete, so the FIXED status of your MapRoulette tasks was not sent. It will be"
                        + " sent with your next upload, unless the task is resolved by someone else first."),
                tr("MapRoulette status not sent"), JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Send the status of modified tasks, release them and link them to the changeset. Must not be called on the EDT.
     *
     * @param modifiedTasks The modified tasks
     * @param linkChangeset {@code true} to ask MapRoulette to link the tasks to the changeset
     */
    private static void sendStatuses(List<ModifiedTask> modifiedTasks, boolean linkChangeset) {
        final var updatedTasks = new HashMap<Long, Task>(modifiedTasks.size());
        for (ModifiedTask entry : modifiedTasks) {
            // Skip a status dropped or changed since the upload started (Stop Task drops a held FIXED)
            if (entry.status() != TaskStatus.CREATED && ModifiedObjects.getModifiedTask(entry.task().id()) == entry) {
                try {
                    TaskAPI.updateStatus(entry.task().id(), entry.status(), entry.comment(), entry.tags(),
                            entry.reviewRequested(), entry.completionResponses());
                    updatedTasks.put(entry.task().id(), TaskAPI.release(entry.task().id()));
                    ModifiedObjects.removeLockedTask(entry.task());
                    ModifiedObjects.removeModifiedTask(entry);
                    if (linkChangeset) {
                        TaskAPI.changeset(entry.task().id());
                    }
                } catch (MapRouletteNotFoundException | MapRouletteHttpException e) {
                    if (e instanceof MapRouletteNotFoundException
                            || ((MapRouletteHttpException) e).status() == HttpURLConnection.HTTP_BAD_REQUEST) {
                        // MapRoulette will not take this status (an invalid status change, a paused challenge, or the
                        // task is gone), so sending it again with the next upload would fail again
                        ModifiedObjects.removeLockedTask(entry.task());
                        ModifiedObjects.removeModifiedTask(entry);
                    }
                    ExceptionDialogUtil.explainException(e);
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
