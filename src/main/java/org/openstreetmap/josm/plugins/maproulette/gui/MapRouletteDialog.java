// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagLayout;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.Collections;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.Timer;

import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRoulettePreferences;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Shortcut;

import jakarta.annotation.Nullable;

/**
 * The MapRoulette panel: the server and login, the task list, and the current task. Use it on the EDT.
 */
public final class MapRouletteDialog extends ToggleDialog {
    @Serial
    private static final long serialVersionUID = -2424325245376358237L;
    /**
     * The position of the divider between the task list and the current task, {@code -1} for the default
     */
    private static final IntegerProperty DIVIDER_LOCATION = new IntegerProperty("maproulette.dialog.divider", -1);
    private static final String TASK_CARD = "task";
    private final TaskListPanel taskList;
    private final CurrentTaskPanel currentTask;
    private final LoginControl login = new LoginControl(true);
    private final JLabel server = new JLabel();
    /**
     * Shows the server name again when the login changes, which also happens when the server changes
     */
    private final transient Runnable authListener = this::updateServer;
    /**
     * The lower part of the panel. The current task is its only card for now; a review mode can get its own.
     */
    private final JPanel lower = new JPanel(new CardLayout());
    /**
     * Saves the divider position once it stops moving: each preference change writes the preferences file
     */
    private final Timer saveDivider;

    /**
     * Create a new MapRoulette panel
     */
    public MapRouletteDialog() {
        super(tr("MapRoulette"), "user_no_image", tr("MapRoulette tasks and the current task"), Shortcut
                .registerShortcut("maproulette:task_window", tr("MapRoulette"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                300, false, MapRoulettePreferences.class);
        this.taskList = new TaskListPanel();
        this.currentTask = new CurrentTaskPanel();
        this.lower.add(this.currentTask, TASK_CARD);

        final var header = new JPanel(new GridBagLayout());
        header.add(this.server, GBC.std().insets(4, 2, 4, 2));
        header.add(this.login.status(), GBC.std().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        header.add(this.login.button(), GBC.eol().anchor(GBC.LINE_END).insets(0, 2, 2, 2));

        final var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, this.taskList, this.lower);
        // The list collapses with the arrows, and the instructions get most of the height
        split.setOneTouchExpandable(true);
        split.setResizeWeight(0.35);
        if (DIVIDER_LOCATION.get() >= 0) {
            split.setDividerLocation(DIVIDER_LOCATION.get());
        }
        this.saveDivider = new Timer(1000, e -> DIVIDER_LOCATION.put(split.getDividerLocation()));
        this.saveDivider.setRepeats(false);
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> this.saveDivider.restart());

        final var content = new JPanel(new BorderLayout());
        content.add(header, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        createLayout(content, false, Collections.emptyList());
        this.login.attach();
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        updateServer();
    }

    /**
     * Get the MapRoulette panel of the map
     *
     * @return The panel, or {@code null} if there is no map
     */
    @Nullable
    public static MapRouletteDialog get() {
        final var map = MainApplication.getMap();
        return map == null ? null : map.getToggleDialog(MapRouletteDialog.class);
    }

    /**
     * Get the task list section
     *
     * @return The task list
     */
    public TaskListPanel taskList() {
        return this.taskList;
    }

    /**
     * Get the current task section
     *
     * @return The current task
     */
    public CurrentTaskPanel currentTask() {
        return this.currentTask;
    }

    /**
     * Show the current task in the lower part of the panel. This does not open a closed panel.
     */
    public void showTask() {
        ((CardLayout) this.lower.getLayout()).show(this.lower, TASK_CARD);
    }

    private void updateServer() {
        this.server.setText(tr("Server: {0}", MapRouletteServerPreference.getServerName()));
        this.server.setToolTipText(MapRouletteServerPreference.getServerTooltip());
    }

    @Override
    public String helpTopic() {
        return ht("/Dialog/MapRoulette");
    }

    @Override
    public void destroy() {
        if (this.saveDivider.isRunning()) {
            this.saveDivider.stop();
            this.saveDivider.getActionListeners()[0].actionPerformed(null);
        }
        this.taskList.destroy();
        this.currentTask.destroy();
        this.login.detach();
        MapRouletteClient.getInstance().removeAuthListener(this.authListener);
        super.destroy();
    }
}
