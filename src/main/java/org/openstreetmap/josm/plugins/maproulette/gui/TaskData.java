// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadOsmTask;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesWithReferrersTask;
import org.openstreetmap.josm.gui.util.GuiHelper;
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
     * @param onMissing Called on the EDT if none of the task's objects exist on the OSM server, or {@code null}
     */
    public static void loadAndSelect(@Nonnull Task task, @Nullable Runnable onMissing) {
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
     * @param onMissing Called on the EDT if none of the task's objects could be downloaded, or {@code null}
     */
    static void load(@Nonnull Task task, @Nonnull List<PrimitiveId> ids, @Nullable Runnable onMissing) {
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
        // Merged into the edit layer, or a new layer if there is none; the worker runs the jobs in order
        MainApplication.worker.submit(new DownloadPrimitivesWithReferrersTask(false, missing, true, true, null, null));
        MainApplication.worker.submit(() -> GuiHelper.runInEDT(() -> {
            // Objects that don't exist on the server (404) stay missing, for example a task made for another server
            if (onMissing != null
                    && missing(ids, MainApplication.getLayerManager().getEditDataSet()).size() == ids.size()) {
                onMissing.run();
            }
            select(ids);
        }));
    }

    /**
     * Download the area of a task that names no OSM objects, unless the edit layer has it already
     *
     * @param task    The task
     * @param dataSet The edit data set, or {@code null}
     */
    private static void loadArea(@Nonnull Task task, @Nullable DataSet dataSet) {
        final var bounds = bounds(task);
        if (bounds == null || dataSet != null
                && dataSet.getDataSourceBounds().stream().anyMatch(loaded -> loaded.contains(bounds))) {
            return;
        }
        final var maxArea = Config.getPref().getDouble("osm-server.max-request-area", 0.25);
        if (bounds.getArea() > maxArea) {
            new Notification(
                    tr("Task {0} covers too large an area to download. Download the part you need.", task.id())).show();
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
        Bounds bounds = null;
        for (var primitive : task.geometries().allNonDeletedPrimitives()) {
            final var bbox = primitive.getBBox();
            if (bbox.isValid()) {
                if (bounds == null) {
                    bounds = new Bounds(bbox.getBottomRight());
                }
                bounds.extend(bbox.getTopLeft());
                bounds.extend(bbox.getBottomRight());
            }
        }
        if (bounds == null && task.location() != null) {
            bounds = new Bounds(new LatLon(task.location().lat(), task.location().lon()));
        }
        if (bounds != null) {
            bounds.extend(bounds.getMinLat() - POINT_MARGIN, bounds.getMinLon() - POINT_MARGIN);
            bounds.extend(bounds.getMaxLat() + POINT_MARGIN, bounds.getMaxLon() + POINT_MARGIN);
        }
        return bounds;
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
            final var present = ids.stream().map(dataSet::getPrimitiveById).filter(p -> p != null && !p.isDeleted())
                    .toList();
            if (!present.isEmpty()) {
                dataSet.setSelected(present);
                AutoScaleAction.autoScale(AutoScaleAction.AutoScaleMode.SELECTION);
            }
        }
        CurrentTaskPanel.refreshData();
    }
}
