// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadOsmTask;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.actions.downloadtasks.DownloadReferrersTask;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesTask;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesWithReferrersTask;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.OsmApi;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.spi.preferences.Config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Load the OSM data of a task into the edit layer and select its objects (#114)
 */
public final class TaskData {
    /**
     * Half the side of the area downloaded around a task with no OSM objects and a point location, in degrees (about
     * 100 m)
     */
    private static final double POINT_MARGIN = 0.001;

    private TaskData() {
        // Hide the constructor
    }

    /**
     * Download the task's objects with their referrers, or its area if it names no objects, into the edit layer, then
     * select the objects and zoom to them. Objects already in the edit layer are not downloaded again. Call on the EDT.
     *
     * @param task The task
     */
    public static void loadAndSelect(@Nonnull Task task) {
        loadAndSelect(task, null);
    }

    /**
     * Download the task's objects and select them, as {@link #loadAndSelect(Task)}
     *
     * @param task      The task
     * @param onMissing Called on the EDT with the objects the OSM server answered 404 for or has deleted, if none of
     *                  the task's objects could be loaded, or {@code null}
     */
    public static void loadAndSelect(@Nonnull Task task, @Nullable Consumer<Gone> onMissing) {
        // Finding the objects may need the challenge from the server
        Workers.execute(() -> {
            final var ids = List.copyOf(TaskPrimitives.getPrimitiveIds(task));
            GuiHelper.runInEDT(() -> load(task, ids, onMissing));
        });
    }

    /**
     * Download what is missing, then select. Call on the EDT.
     *
     * @param task The task
     * @param ids  The task's objects
     */
    static void load(@Nonnull Task task, @Nonnull List<PrimitiveId> ids) {
        load(task, ids, null);
    }

    /**
     * Download what is missing, then select. Call on the EDT.
     *
     * @param task      The task
     * @param ids       The task's objects
     * @param onMissing Called on the EDT with the objects the OSM server answered 404 for or has deleted, if none of
     *                  the task's objects could be loaded, or {@code null}
     */
    static void load(@Nonnull Task task, @Nonnull List<PrimitiveId> ids, @Nullable Consumer<Gone> onMissing) {
        final var dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (ids.isEmpty()) {
            loadArea(task, dataSet);
            return;
        }
        final var missing = missing(ids, dataSet);
        if (missing.isEmpty()) {
            select(ids);
            return;
        }
        // Merged into the edit layer, or a new layer if there is none
        MainApplication.worker.submit(new ObjectsDownload(missing, notFound -> {
            final var editData = MainApplication.getLayerManager().getEditDataSet();
            // Objects that don't exist on the server (404) stay missing, for example a task made for another server.
            // Objects deleted on the server come back invisible.
            final var deleted = ids.stream().filter(id -> Optional.ofNullable(editData)
                    .map(data -> data.getPrimitiveById(id)).map(p -> !p.isVisible()).orElse(false)).toList();
            if (missing(ids, editData).size() + deleted.size() >= ids.size()) {
                if (deleted.isEmpty()) {
                    CurrentTaskPanel.notDownloaded(task.id());
                }
                // Without a 404 or a deleted object, the download failed and JOSM explained why: the objects may well
                // exist
                if (onMissing != null && !(notFound.isEmpty() && deleted.isEmpty())) {
                    onMissing.accept(new Gone(notFound, deleted));
                }
            }
            select(ids);
        }));
    }

    /**
     * The objects of a task that could not be loaded
     *
     * @param notFound The objects the OSM server answered 404 for
     * @param deleted  The objects deleted on the OSM server
     */
    public record Gone(@Nonnull List<PrimitiveId> notFound, @Nonnull List<PrimitiveId> deleted) {
    }

    /**
     * Name the OSM server the task's objects come from
     *
     * @return "OpenStreetMap" for the main server, its host otherwise
     */
    public static String osmServerName() {
        final var url = OsmApi.getOsmApi().getServerUrl();
        if (url.equals(Config.getUrls().getDefaultOsmApiUrl())) {
            return tr("OpenStreetMap");
        }
        try {
            return Optional.ofNullable(URI.create(url).getHost()).orElse(url);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /**
     * Download objects with their referrers into the edit layer, as {@link DownloadPrimitivesWithReferrersTask} does,
     * but without its dialog for objects the server doesn't have: the panel says so instead (#116). Unlike JOSM's
     * download, it always asks the OSM API, never Overpass: a task has only a few objects.
     */
    static final class ObjectsDownload extends PleaseWaitRunnable {
        private final List<PrimitiveId> ids;
        private final Consumer<List<PrimitiveId>> onDone;
        private final OsmDataLayer tmpLayer = new OsmDataLayer(new DataSet(), OsmDataLayer.createNewName(), null);
        private Set<PrimitiveId> notFound = Set.of();
        private PleaseWaitRunnable current;
        private boolean canceled;

        /**
         * Create a download
         *
         * @param ids    The objects
         * @param onDone Called on the EDT once the objects are merged, with the ones the server doesn't have
         */
        ObjectsDownload(List<PrimitiveId> ids, Consumer<List<PrimitiveId>> onDone) {
            super(tr("Download objects"), false);
            this.ids = List.copyOf(ids);
            this.onDone = onDone;
        }

        @Override
        protected synchronized void cancel() {
            this.canceled = true;
            if (this.current != null) {
                this.current.operationCanceled();
            }
        }

        @Override
        protected void realRun() {
            getProgressMonitor().setTicksCount(2);
            final var objects = new DownloadPrimitivesTask(this.tmpLayer, this.ids, true,
                    getProgressMonitor().createSubTaskMonitor(1, false));
            if (!this.start(objects)) {
                return;
            }
            objects.run();
            this.notFound = Optional.ofNullable(objects.getMissingPrimitives()).map(Set::copyOf).orElse(Set.of());
            // The referrers of the objects that exist, as JOSM's own download does
            final var visible = this.ids.stream().map(this.tmpLayer.data::getPrimitiveById)
                    .filter(p -> p != null && p.isVisible()).map(OsmPrimitive::getPrimitiveId).toList();
            if (!visible.isEmpty()) {
                final var referrers = new DownloadReferrersTask(this.tmpLayer, visible);
                if (this.start(referrers)) {
                    referrers.run();
                }
            }
        }

        private synchronized boolean start(PleaseWaitRunnable task) {
            this.current = this.canceled ? null : task;
            return !this.canceled;
        }

        @Override
        protected void finish() {
            synchronized (this) {
                if (this.canceled) {
                    return;
                }
            }
            GuiHelper.runInEDTAndWait(() -> {
                final var layer = MainApplication.getLayerManager().getEditLayer();
                if (layer == null || !layer.isDownloadable()) {
                    MainApplication.getLayerManager().addLayer(this.tmpLayer);
                } else {
                    layer.mergeFrom(this.tmpLayer);
                }
                this.onDone.accept(this.ids.stream().filter(this.notFound::contains).toList());
            });
        }
    }

    /**
     * Download the area of a task that names no OSM objects, unless the edit layer has it already
     *
     * @param task    The task
     * @param dataSet The edit data set, or {@code null}
     */
    private static void loadArea(@Nonnull Task task, @Nullable DataSet dataSet) {
        final var bounds = bounds(task);
        if (bounds == null) {
            return;
        }
        // Back to the task, whether or not its area is loaded (#145)
        Optional.ofNullable(MainApplication.getMap()).ifPresent(map -> map.mapView.zoomTo(bounds));
        if (dataSet != null && dataSet.getDataSourceBounds().stream().anyMatch(loaded -> loaded.contains(bounds))) {
            return;
        }
        final var maxArea = Config.getPref().getDouble("osm-server.max-request-area", 0.25);
        if (bounds.getArea() > maxArea) {
            new Notification(tr("Task {0} covers too large an area to download. Download the part you need.",
                    Long.toString(task.id()))).show();
            return;
        }
        new DownloadOsmTask().download(new DownloadParams(), bounds, null);
        MainApplication.worker.submit(() -> GuiHelper.runInEDT(CurrentTaskPanel::refreshData));
    }

    /**
     * Get the area to download for a task with no OSM objects
     *
     * @param task The task
     * @return The area, or {@code null} if the task has no geometry
     */
    @Nullable
    static Bounds bounds(@Nonnull Task task) {
        final var place = TaskArea.place(task);
        if (place == null) {
            return null;
        }
        return new Bounds(place.getBottomRightLat() - POINT_MARGIN, place.getTopLeftLon() - POINT_MARGIN,
                place.getTopLeftLat() + POINT_MARGIN, place.getBottomRightLon() + POINT_MARGIN);
    }

    /**
     * Get the task objects that are not in a data set, or not complete there
     *
     * @param ids     The task's objects
     * @param dataSet The data set, or {@code null}
     * @return The objects to download
     */
    @Nonnull
    public static List<PrimitiveId> missing(@Nonnull Collection<PrimitiveId> ids, @Nullable DataSet dataSet) {
        if (dataSet == null) {
            return List.copyOf(ids);
        }
        return ids.stream().filter(id -> Optional.ofNullable(dataSet.getPrimitiveById(id))
                .map(OsmPrimitive::isIncomplete).orElse(Boolean.TRUE)).toList();
    }

    /**
     * Select the task objects that are in the edit layer and zoom to them, then update the panel
     *
     * @param ids The task's objects
     */
    private static void select(@Nonnull List<PrimitiveId> ids) {
        final var dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet != null) {
            // Not deleted in the data, nor on the server
            final var present = ids.stream().map(dataSet::getPrimitiveById)
                    .filter(p -> p != null && !p.isDeleted() && p.isVisible()).toList();
            if (!present.isEmpty()) {
                dataSet.setSelected(present);
                AutoScaleAction.autoScale(AutoScaleAction.AutoScaleMode.SELECTION);
            } else if (!ids.isEmpty() && missing(ids, dataSet).isEmpty()) {
                // In the data but deleted there: nothing to select, and no download would help (#145)
                new Notification(tr("The task''s objects are deleted in your data.")).show();
            }
        }
        CurrentTaskPanel.refreshData();
    }
}
