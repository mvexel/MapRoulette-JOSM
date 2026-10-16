// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.osm.Changeset;
import org.openstreetmap.josm.data.osm.ChangesetCacheEvent;
import org.openstreetmap.josm.data.osm.ChangesetCacheListener;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.UploadDialog;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.io.TaskStatusSender;

/**
 * Links the tasks marked Fixed to the changeset of the upload that follows (D15)
 */
public class LateUploadHook implements UploadHook, ChangesetCacheListener {
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
                ChangesetLinks.link(fixed, changesetId);
            } else {
                ChangesetLinks.await(fixed, changesetId);
            }
        }));
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
