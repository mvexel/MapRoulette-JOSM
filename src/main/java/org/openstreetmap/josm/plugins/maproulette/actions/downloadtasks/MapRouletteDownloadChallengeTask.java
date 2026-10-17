// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.downloadtasks.AbstractDownloadTask;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressTaskId;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.OsmApiException;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.plugins.maproulette.api.ChallengeAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.xml.sax.SAXException;

/**
 * A class for download all of a challenge's tasks
 */
public class MapRouletteDownloadChallengeTask extends AbstractDownloadTask<Collection<TaskClusteredPoint>> {
    private static final Pattern PATTERN_CHALLENGE = Pattern.compile(".*/challenges/(\\d+)/?$");
    private DownloadTask downloadTask;

    @Override
    public Future<?> download(DownloadParams settings, Bounds downloadArea, ProgressMonitor progressMonitor) {
        return null;
    }

    @Override
    public Future<?> loadUrl(DownloadParams settings, String url, ProgressMonitor progressMonitor) {
        final var challengeTaskMatcher = PATTERN_CHALLENGE.matcher(url);
        if (challengeTaskMatcher.matches()) {
            return loadChallenge(Long.parseLong(challengeTaskMatcher.group(1)), progressMonitor);
        }
        return null;
    }

    /**
     * Load all tasks of a challenge
     *
     * @param challengeId     The challenge to load
     * @param progressMonitor The progress monitor to use, or {@code null} for a new one
     * @return The download job
     */
    public Future<?> loadChallenge(long challengeId, ProgressMonitor progressMonitor) {
        this.downloadTask = new DownloadTask(progressMonitor, challengeId);
        return MainApplication.worker.submit(this.downloadTask);
    }

    @Override
    public void cancel() {
        this.downloadTask.cancel();
    }

    @Override
    public String getConfirmationMessage(URL url) {
        return tr("Download challenge {0}", url.toExternalForm());
    }

    @Override
    public String[] getPatterns() {
        return new String[] { "https?://.*" + PATTERN_CHALLENGE.pattern() };
    }

    @Override
    public String getTitle() {
        return tr("Download MapRoulette Challenge");
    }

    private static class DownloadTask extends PleaseWaitRunnable {
        private static final ProgressTaskId PROGRESS_TASK_ID = new ProgressTaskId("maproulette", "download task");
        private final long[] challengeIds;
        private Collection<TaskClusteredPoint> tasks;
        private boolean isCancelled;

        /**
         * Create a new download task
         *
         * @param progressMonitor The progress monitor to use
         * @param ids             The ids to download
         */
        DownloadTask(ProgressMonitor progressMonitor, long... ids) {
            super(tr("Downloading MapRoulette Challenge {0}", ids[0]), progressMonitor, false);
            this.challengeIds = ids;
        }

        @Override
        protected void cancel() {
            this.isCancelled = true;
        }

        @Override
        protected void realRun() throws SAXException, IOException, OsmTransferException {
            this.tasks = new ArrayList<>();
            for (var challengeId : this.challengeIds) {
                if (this.isCancelled) {
                    return;
                }
                try {
                    // Before merging: the user's own locks are not other mappers' locks
                    MapRouletteAuth.lookUpUser();
                    final var downloadedTasks = MapRouletteDownloadTaskBox
                            .mergeLockRows(ChallengeAPI.openTasks(challengeId));
                    if (downloadedTasks.length == 0) {
                        GuiHelper.runInEDT(() -> new Notification(
                                tr("MapRoulette challenge {0} has no open tasks.", Long.toString(challengeId)))
                                        .setIcon(JOptionPane.INFORMATION_MESSAGE).show());
                    }
                    this.tasks.addAll(Arrays.asList(downloadedTasks));
                    ChallengeCache.challenge(challengeId);
                } catch (MapRouletteAuthUnavailableException unauthorizedException) {
                    ExceptionDialogUtil.explainException(unauthorizedException);
                    // An OsmTransferException is shown as an error, not as a bug report
                    final var transferException = new OsmApiException(unauthorizedException);
                    transferException.setUrl(MapRouletteConfig.getBaseUrl());
                    throw transferException;
                } catch (IOException e) {
                    final var transferException = new OsmTransferException(e);
                    transferException.setUrl(MapRouletteConfig.getBaseUrl());
                    throw transferException;
                }
            }
        }

        @Override
        protected void finish() {
            if (tasks != null && !tasks.isEmpty()) {
                final var taskList = new ArrayList<>(tasks);
                final var tcMap = taskList.stream().collect(Collectors.toMap(TaskClusteredPoint::id, i -> i));
                final var currentLayers = MainApplication.getLayerManager()
                        .getLayersOfType(MapRouletteClusteredPointLayer.class);
                final var initialPoint = taskList.get(0).location();
                Objects.requireNonNull(initialPoint, "Task did not have location: " + taskList.get(0).id());
                final var bounds = new Bounds(initialPoint.lat(), initialPoint.lon(), initialPoint.lat(),
                        initialPoint.lon());
                if (currentLayers.isEmpty()) {
                    final var layer = new MapRouletteClusteredPointLayer(bounds, List.copyOf(taskList));
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
