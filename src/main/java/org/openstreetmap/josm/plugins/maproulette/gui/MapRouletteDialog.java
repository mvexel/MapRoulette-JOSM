// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.Collections;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.openstreetmap.josm.data.preferences.DoubleProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.dialogs.DialogsPanel;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.plugins.maproulette.actions.HowItWorksAction;
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
     * The share of the height the task list gets while a task is shown (#132)
     */
    private static final DoubleProperty DIVIDER_RATIO = new DoubleProperty("maproulette.dialog.divider.ratio", 0.4);
    /**
     * How much bigger than the minimum the panel must get before the content comes back, so it doesn't flicker while
     * the user drags (#132)
     */
    private static final int HYSTERESIS = 24;
    private static final String TASK_CARD = "task";
    private static final String NORMAL_VIEW = "normal";
    private static final String SMALL_VIEW = "small";
    private final TaskListPanel taskList;
    private final CurrentTaskPanel currentTask;
    private final LoginControl login = new LoginControl(true);
    private final JLabel server = new JLabel();
    /**
     * The server and the login, shown in every view
     */
    private final JPanel header = new JPanel(new GridBagLayout());
    /**
     * Shows the server name again when the login changes, which also happens when the server changes
     */
    private final transient Runnable authListener = this::updateServer;
    /**
     * The lower part of the panel. The current task is its only card for now; a review mode can get its own.
     */
    private final JPanel lower = new JPanel(new CardLayout());
    /**
     * The task list and the current task, used while a task is shown
     */
    private final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true);
    /**
     * The task list alone with a notice, or the split
     */
    private final JPanel body = new JPanel(new BorderLayout());
    /**
     * Shown under the task list while there is no current task
     */
    private final JLabel noTask = new JLabel(tr("No current task. Select a task and press Start."));
    /**
     * The normal view, or the notice that the panel is too small
     */
    private final JPanel views = new JPanel(new CardLayout());
    private final JLabel tooSmallLabel = new JLabel();
    private final JButton undock = new JButton(tr("Undock"));
    /**
     * Saves the divider position once it stops moving: each preference change writes the preferences file
     */
    private final Timer saveDivider;
    /**
     * {@code true} while the split is in the panel, {@code null} before the first layout
     */
    private Boolean showsSplit;
    /**
     * {@code true} if the remembered divider position still has to be applied, once the split has a size
     */
    private boolean ratioPending;
    /**
     * {@code true} while the panel sets the divider, which is not the user dragging it
     */
    private boolean settingDivider;
    /**
     * {@code true} while the panel is too small and shows the notice
     */
    private boolean small;

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

        this.header.add(this.server, GBC.std().insets(4, 2, 4, 2));
        this.header.add(this.login.status(), GBC.std().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        this.header.add(this.login.button(), GBC.std().anchor(GBC.LINE_END).insets(0, 2, 2, 2));
        this.header.add(HowItWorksAction.link(), GBC.eol().anchor(GBC.LINE_END).insets(4, 2, 4, 2));

        // The list collapses with the arrows
        this.split.setOneTouchExpandable(true);
        this.split.setResizeWeight(DIVIDER_RATIO.get());
        this.saveDivider = new Timer(1000, e -> saveDivider());
        this.saveDivider.setRepeats(false);
        this.split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (!this.settingDivider && Boolean.TRUE.equals(this.showsSplit)) {
                this.saveDivider.restart();
            }
        });
        this.split.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyDivider();
            }
        });
        this.noTask.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        final var tooSmall = new JPanel(new GridBagLayout());
        tooSmall.add(this.tooSmallLabel, GBC.eol().anchor(GBC.CENTER).insets(8, 8, 8, 4));
        tooSmall.add(this.undock, GBC.eol().anchor(GBC.CENTER).insets(8, 0, 8, 8));
        this.undock.setToolTipText(tr("Open the MapRoulette panel in its own window"));
        this.undock.addActionListener(e -> undock());
        this.views.add(this.body, NORMAL_VIEW);
        this.views.add(tooSmall, SMALL_VIEW);
        this.views.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // After the layout has settled
                SwingUtilities.invokeLater(MapRouletteDialog.this::checkSize);
            }
        });

        final var content = new JPanel(new BorderLayout());
        content.add(this.header, BorderLayout.NORTH);
        content.add(this.views, BorderLayout.CENTER);
        createLayout(content, false, Collections.emptyList());
        updateLayout();
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
     * Show the task list alone while there is no current task, and the list and the task otherwise (#132)
     */
    public void updateLayout() {
        final var hasTask = this.currentTask.hasTask();
        if (!Boolean.valueOf(hasTask).equals(this.showsSplit)) {
            this.showsSplit = hasTask;
            this.body.removeAll();
            if (hasTask) {
                ((CardLayout) this.lower.getLayout()).show(this.lower, TASK_CARD);
                this.split.setTopComponent(this.taskList);
                this.split.setBottomComponent(this.lower);
                this.body.add(this.split, BorderLayout.CENTER);
                this.ratioPending = true;
            } else {
                this.body.add(this.taskList, BorderLayout.CENTER);
                this.body.add(this.noTask, BorderLayout.SOUTH);
            }
            this.body.revalidate();
            this.body.repaint();
            // The split may keep its old size, and then hears of no resize
            SwingUtilities.invokeLater(this::applyDivider);
        }
        checkSize();
    }

    /**
     * Get the size the content needs to be usable, without the header line (#132)
     *
     * @return The size
     */
    Dimension neededSize() {
        if (this.currentTask.hasTask()) {
            // The list may shrink to its title and challenge filter: the current task matters more, and the divider moves
            final var list = this.taskList.squeezedSize();
            final var task = this.currentTask.neededSize();
            return new Dimension(Math.max(list.width, task.width),
                    list.height + this.split.getDividerSize() + task.height);
        }
        final var list = this.taskList.neededSize(4);
        return new Dimension(list.width, list.height + this.noTask.getPreferredSize().height);
    }

    /**
     * Check if the content is too small to use
     *
     * @param size The size of the content
     * @return {@code true} to show the notice instead
     */
    boolean isTooSmall(Dimension size) {
        final var needed = neededSize();
        // Coming back needs some more room than going, so the view doesn't flip while the user drags
        final var slack = this.small ? HYSTERESIS : 0;
        return size.width < needed.width + slack || size.height < needed.height + slack;
    }

    /**
     * Show the notice instead of a cramped panel, or the content once there is room again
     */
    private void checkSize() {
        final var size = this.views.getSize();
        if (size.width <= 0 || size.height <= 0 || !isDialogShowing() || isDialogInCollapsedView()) {
            return;
        }
        final var tooSmall = isTooSmall(size);
        if (tooSmall) {
            this.tooSmallLabel.setText(this.isDocked ? tr("Make this panel bigger to use MapRoulette.")
                    : tr("Make this window bigger to use MapRoulette."));
            this.undock.setVisible(this.isDocked);
        }
        if (tooSmall != this.small) {
            this.small = tooSmall;
            ((CardLayout) this.views.getLayout()).show(this.views, tooSmall ? SMALL_VIEW : NORMAL_VIEW);
        }
    }

    /**
     * Open the panel in its own window, as the title bar's button does, at least at the size it needs
     */
    private void undock() {
        detach();
        if (this.dialogsPanel != null) {
            this.dialogsPanel.reconstruct(DialogsPanel.Action.ELEMENT_SHRINKS, null);
        }
        if (this.detachedDialog != null) {
            final var size = this.detachedDialog.getSize();
            final var needed = windowSize();
            if (size.width < needed.width || size.height < needed.height) {
                this.detachedDialog.setSize(Math.max(size.width, needed.width), Math.max(size.height, needed.height));
            }
        }
    }

    /**
     * Get the window size at which the content shows, with the header line and the window's border
     *
     * @return The size
     */
    private Dimension windowSize() {
        final var needed = neededSize();
        final var insets = this.detachedDialog == null ? new Insets(0, 0, 0, 0) : this.detachedDialog.getInsets();
        return new Dimension(needed.width + HYSTERESIS + insets.left + insets.right,
                needed.height + HYSTERESIS + this.header.getPreferredSize().height + insets.top + insets.bottom);
    }

    @Override
    protected void stateChanged() {
        super.stateChanged();
        // Shown, expanded, docked or undocked: the size may not change, so no resize event comes
        SwingUtilities.invokeLater(this::checkSize);
    }

    @Override
    protected Dimension getDefaultDetachedSize() {
        final var size = super.getDefaultDetachedSize();
        final var needed = windowSize();
        return new Dimension(Math.max(size.width, needed.width), Math.max(size.height, needed.height));
    }

    /**
     * Put the divider where the user left it, as a share of the height, once the split has a size
     */
    private void applyDivider() {
        if (!this.ratioPending || this.split.getHeight() <= 0 || !Boolean.TRUE.equals(this.showsSplit)) {
            return;
        }
        this.ratioPending = false;
        this.settingDivider = true;
        try {
            // The current task keeps the room it needs; the list gets the rest above its share
            final var room = this.split.getHeight() - this.split.getDividerSize();
            final var forTask = room - this.currentTask.neededSize().height;
            final var location = Math.min((int) (DIVIDER_RATIO.get() * room), forTask);
            this.split.setDividerLocation(Math.max(location, this.taskList.squeezedSize().height));
        } finally {
            this.settingDivider = false;
        }
    }

    /**
     * Remember the divider as a share of the height, so it fits any panel size
     */
    private void saveDivider() {
        final var room = this.split.getHeight() - this.split.getDividerSize();
        if (room > 0 && Boolean.TRUE.equals(this.showsSplit)) {
            DIVIDER_RATIO.put(Math.min(0.9, Math.max(0.1, this.split.getDividerLocation() / (double) room)));
        }
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
            saveDivider();
        }
        this.taskList.destroy();
        this.currentTask.destroy();
        this.login.detach();
        MapRouletteClient.getInstance().removeAuthListener(this.authListener);
        super.destroy();
    }
}
