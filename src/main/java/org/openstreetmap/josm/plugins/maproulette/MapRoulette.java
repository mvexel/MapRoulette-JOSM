// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette;

import java.io.IOException;
import java.util.List;

import org.openstreetmap.josm.actions.UploadAction;
import org.openstreetmap.josm.data.osm.ChangesetCache;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.download.OSMDownloadSource;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks.MapRouletteDownloadChallengeTask;
import org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks.MapRouletteDownloadTask;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.FirstStartNotice;
import org.openstreetmap.josm.plugins.maproulette.gui.LockRefresher;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteMenu;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.download.MapRouletteDownloadSource;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRoulettePreferences;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskEdits;
import org.openstreetmap.josm.plugins.maproulette.io.upload.EarlyUploadHook;
import org.openstreetmap.josm.plugins.maproulette.io.upload.LateUploadHook;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.tools.Logging;

/**
 * The POJO entry point
 */
public class MapRoulette extends Plugin {
    /**
     * Creates the plugin
     *
     * @param info the plugin information describing the plugin.
     */
    public MapRoulette(PluginInformation info) {
        super(info);
        MapRouletteAuth.deleteCachedApiKeys();
        this.getPreferenceSetting().ok();
        // The preferences already did this; spelled out because the stored login depends on it
        MapRouletteAuth.configure();
        LockRefresher.start();
        TaskEdits.start();
        UploadAction.registerUploadHook(new EarlyUploadHook());
        final var lateUploadHook = new LateUploadHook();
        UploadAction.registerUploadHook(lateUploadHook, true);
        ChangesetCache.getInstance().addChangesetCacheListener(lateUploadHook);
        OSMDownloadSource.addDownloadType(new MapRouletteDownloadSource());
        MainApplication.getMenu().openLocation.addDownloadTaskClass(MapRouletteDownloadTask.class);
        MainApplication.getMenu().openLocation.addDownloadTaskClass(MapRouletteDownloadChallengeTask.class);
        final var findChallenge = new FindChallengeAction();
        MapRouletteMenu.install(findChallenge);
        FirstStartNotice.showOnce(findChallenge);
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new MapRoulettePreferences();
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        super.mapFrameInitialized(oldFrame, newFrame);
        if (newFrame != null) {
            newFrame.addToggleDialog(new MapRouletteDialog());
        } else {
            releaseLocks(ModifiedObjects.getLockedTasks());
        }
    }

    /**
     * How long quitting JOSM waits for the started tasks to be released
     */
    static final long RELEASE_ON_QUIT_MILLIS = 3_000;

    /**
     * Release the tasks the user started, when the map closes
     *
     * @param lockedTasks The tasks to release
     */
    static void releaseLocks(List<Task> lockedTasks) {
        // JOSM may be shutting down, so log failures instead of showing a dialog
        final Runnable release = () -> {
            for (var task : lockedTasks) {
                try {
                    TaskAPI.release(task.id());
                } catch (IOException | RuntimeException e) {
                    Logging.warn(e);
                }
            }
        };
        // When JOSM quits, the worker is already shut down: try to release the locks before JOSM exits, but don't hold
        // up the quit for a slow server. MapRoulette lets locks expire anyway.
        if (!lockedTasks.isEmpty() && !Workers.tryExecute(release)) {
            final var thread = new Thread(release, "MapRoulette release locks");
            thread.setDaemon(true);
            thread.start();
            try {
                thread.join(RELEASE_ON_QUIT_MILLIS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                Logging.trace(interruptedException);
            }
        }
    }
}
