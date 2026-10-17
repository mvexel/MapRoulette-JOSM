// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.openstreetmap.josm.actions.downloadtasks.AbstractDownloadTask;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressTaskId;
import org.openstreetmap.josm.io.OsmApiException;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.tools.Logging;

/**
 * A download task for MapRoulette data
 */
public class MapRouletteDownloadTaskBox extends AbstractDownloadTask<TaskClusteredPoint[]> {
    private DownloadTask downloadTask;

    @Override
    public Future<?> download(DownloadParams settings, Bounds downloadArea, ProgressMonitor progressMonitor) {
        if (this.downloadTask != null) {
            this.downloadTask.cancel();
        }
        this.downloadTask = new DownloadTask(settings, downloadArea, progressMonitor);
        return MainApplication.worker.submit(this.downloadTask);
    }

    @Override
    public Future<?> loadUrl(DownloadParams settings, String url, ProgressMonitor progressMonitor) {
        return null; // TODO do we want to support this?
    }

    @Override
    public void cancel() {
        if (this.downloadTask != null) {
            this.downloadTask.cancel();
        }
    }

    @Override
    public String getConfirmationMessage(URL url) {
        return tr("Do you want to download {0}", url);
    }

    /**
     * Merge the rows MapRoulette sends for one task. A task can be listed once per lock: MapRoulette reports the holder
     * of a bundle lock or a reviewer claim in {@code lockedBy}. The row with someone else's lock wins, so the task list
     * shows the task as locked (#46, #112).
     *
     * @param points The tasks from MapRoulette
     * @return One row per task, in the order of first appearance
     */
    static ClusteredPoint[] mergeLockRows(ClusteredPoint[] points) {
        final var merged = new LinkedHashMap<Long, ClusteredPoint>();
        for (var point : points) {
            merged.merge(point.id(), point, (first, second) -> !ModifiedObjects.isLockedByOther(first)
                    && ModifiedObjects.isLockedByOther(second) ? second : first);
        }
        return merged.values().toArray(ClusteredPoint[]::new);
    }

    private static class DownloadTask extends PleaseWaitRunnable {
        private static final ProgressTaskId PROGRESS_TASK_ID = new ProgressTaskId("maproulette",
                "download tasks in bbox");
        private final Bounds bounds;
        private final DownloadParams settings;
        private TaskClusteredPoint[] tasks;
        private boolean cancelled;

        protected DownloadTask(DownloadParams settings, Bounds downloadArea, ProgressMonitor progressMonitor) {
            super(tr("Downloading MapRoulette Data"), progressMonitor, false);
            this.bounds = downloadArea;
            this.settings = settings;
        }

        @Override
        protected void cancel() {
            // We don't support cancelling right now
            this.getProgressMonitor().cancel();
            this.cancelled = true;
        }

        @Override
        protected void realRun() throws IOException, OsmTransferException {
            try {
                tasks = mergeLockRows(TaskAPI.box(bounds.getMinLon(), bounds.getMinLat(), bounds.getMaxLon(),
                        bounds.getMaxLat(), 1_000, 0, null, null, false));
                final var challenges = Arrays.stream(tasks).mapToLong(TaskClusteredPoint::parentId).distinct()
                        .toArray();
                for (var challenge : challenges) {
                    if (!this.cancelled && !this.getProgressMonitor().isCanceled()) {
                        // Cache the challenges so hidden ones are filtered out before the layer is shown
                        try {
                            ChallengeCache.challenge(challenge);
                        } catch (IOException ioException) {
                            // The tasks are shown; the layer fetches the challenge again later
                            Logging.trace(ioException);
                        }
                    }
                }
            } catch (MapRouletteAuthUnavailableException unauthorizedException) {
                ExceptionDialogUtil.explainException(unauthorizedException);
                // This is specifically so that user's don't get a bug report message
                final var transferException = new OsmApiException(unauthorizedException);
                transferException.setUrl(MapRouletteConfig.getBaseUrl());
                throw transferException;
            } catch (IOException e) {
                // This is specifically so that user's don't get a bug report message
                final var transferException = new OsmTransferException(e);
                transferException.setUrl(MapRouletteConfig.getBaseUrl());
                throw transferException;
            }
        }

        @Override
        protected void finish() {
            if (tasks != null && !this.cancelled && !this.getProgressMonitor().isCanceled()) {
                final var taskList = new ArrayList<>(Arrays.asList(tasks));
                final var tcMap = taskList.stream().collect(Collectors.toMap(TaskClusteredPoint::id, i -> i));
                final var currentLayers = MainApplication.getLayerManager()
                        .getLayersOfType(MapRouletteClusteredPointLayer.class);
                if (currentLayers.isEmpty()) {
                    final var layer = new MapRouletteClusteredPointLayer(bounds, taskList);
                    if (this.settings.getLayerName() != null) {
                        layer.setName(this.settings.getLayerName());
                    }
                    MainApplication.getLayerManager().addLayer(layer);
                    MainApplication.getMap().mapView.addMouseListener(layer);
                } else {
                    currentLayers.forEach(layer -> layer.refreshTasks(tcMap));
                    final var newTcMap = taskList.stream()
                            .collect(Collectors.toMap(TaskClusteredPoint::id, Function.identity()));
                    currentLayers.forEach(layer -> layer.refreshTasks(newTcMap));
                }
            }
        }

        @Override
        public ProgressTaskId canRunInBackground() {
            return PROGRESS_TASK_ID;
        }
    }
}
