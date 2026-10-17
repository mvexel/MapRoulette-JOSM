// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.preferences.CachingProperty;
import org.openstreetmap.josm.data.preferences.NamedColorProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.FilterField;
import org.openstreetmap.josm.plugins.maproulette.actions.DownloadTasks;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.plugins.maproulette.actions.GoToTaskLocation;
import org.openstreetmap.josm.plugins.maproulette.actions.IgnoreAction;
import org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks.MapRouletteDownloadTaskBox;
import org.openstreetmap.josm.plugins.maproulette.api.MRColors;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Identifier;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.api_caching.TaskCache;
import org.openstreetmap.josm.plugins.maproulette.data.HiddenList;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.widgets.DefaultPanelListCellRenderer;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;

import jakarta.annotation.Nullable;

/**
 * The task list section of the MapRoulette panel, for the tasks of the downloaded areas
 */
public final class TaskListPanel extends JPanel
        implements LayerManager.LayerChangeListener, Consumer<Collection<TaskClusteredPoint>> {
    /**
     * The serial UID for this component
     */
    @Serial
    private static final long serialVersionUID = -8983504332024481559L;
    private static final String TABLE_CARD = "table";
    private static final String EMPTY_CARD = "empty";
    /**
     * The underlying table
     */
    private final JTable table;
    /**
     * The underlying model
     */
    private final TaskTableModel model;
    /**
     * The test registered with {@link HiddenList}, kept so it can be removed again
     */
    private final LongPredicate hiddenTest = this::isHidden;
    /**
     * Re-evaluates Start/Stop when the MapRoulette login changes
     */
    private final Runnable authListener;
    /**
     * Re-sorts, re-filters and repaints the list when a challenge arrives in the background
     */
    private final Runnable challengeListener;
    /**
     * The actions of the section that are not on a side button, destroyed with it
     */
    private final List<JosmAction> actions;
    /**
     * The section title, with the task counts
     */
    private final JLabel title = new JLabel();
    /**
     * Shows the table, or the empty state while no tasks are loaded
     */
    private final JPanel cards = new JPanel(new CardLayout());

    /**
     * Create a new task list panel
     */
    public TaskListPanel() {
        super(new BorderLayout());
        model = new TaskTableModel();
        MainApplication.getLayerManager().addAndFireLayerChangeListener(model);
        table = new JTable(model);
        final var downloadAction = new DownloadDataAction();
        final var findAction = new FindChallengeAction();
        final var downloadButton = new SideButton(downloadAction);
        final var lockUnlockAction = new LockUnlockTaskAction(table);
        final var lockUnlockButton = new SideButton(lockUnlockAction);
        this.authListener = lockUnlockAction::updateEnabledState;
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        final var browseAction = new OpenInBrowserAction(table);
        final var tableRowSorter = new TableRowSorter<>(model);
        this.challengeListener = () -> {
            tableRowSorter.allRowsChanged();
            table.repaint();
        };
        ChallengeCache.addListener(this.challengeListener);
        final var menu = new JPopupMenu();
        final var filterField = new FilterField();
        final var panel = new JPanel(new GridBagLayout());
        panel.add(filterField, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(table.getTableHeader(), GBC.eol().anchor(GBC.LINE_START).fill(GBC.HORIZONTAL));
        panel.add(table, GBC.eol().anchor(GBC.LINE_START).fill(GBC.BOTH));
        final RowFilter<TaskTableModel, Integer> defaultFilter = new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends TaskTableModel, ? extends Integer> entry) {
                final var point = (TaskClusteredPoint) entry.getValue(0);
                return !TaskCache.isHidden(point);
            }
        };
        HiddenList.addListUpdater(this.hiddenTest);
        filterField.filter(expr -> {
            expr = expr.replace("+", "\\+");
            final ArrayList<RowFilter<? super TaskTableModel, ? super Integer>> andFilters = new ArrayList<>();
            andFilters.add(defaultFilter);
            // split search string on whitespace, do case-insensitive AND search
            for (var word : expr.split("\\s+", -1)) {
                andFilters.add(RowFilter.regexFilter("(?i)" + word));
            }
            tableRowSorter.setRowFilter(andFilters.size() > 1 ? RowFilter.andFilter(andFilters) : defaultFilter);
            final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
            final var ids = layers.stream().map(MapRouletteClusteredPointLayer::getTasks).flatMap(Collection::stream)
                    .mapToLong(Identifier::id).toArray();
            HiddenList.update(ids);
            layers.forEach(MapRouletteClusteredPointLayer::invalidate);
        });
        final var goTo = new GoToTaskLocation();
        final var downloadTasks = new DownloadTasks();
        final var ignoreTask = new IgnoreAction(IgnoreAction.IgnoreType.IGNORE_TASK);
        final var ignoreChallenge = new IgnoreAction(IgnoreAction.IgnoreType.IGNORE_CHALLENGE);
        menu.add(goTo);
        menu.add(downloadTasks);
        menu.add(ignoreTask);
        menu.add(ignoreChallenge);
        menu.add(browseAction);
        this.actions = List.of(findAction, browseAction, goTo, downloadTasks, ignoreTask, ignoreChallenge);
        table.setComponentPopupMenu(menu);
        tableRowSorter.setRowFilter(defaultFilter);
        tableRowSorter.setComparator(0,
                Comparator.comparing(TaskListPanel::getParentName).thenComparingInt(TaskClusteredPoint::priority)
                        .thenComparing(TaskListPanel::getTitle).thenComparingLong(TaskClusteredPoint::id));
        table.setRowSorter(tableRowSorter);
        table.setDefaultRenderer(ClusteredPoint.class, new TaskListCellRenderer());
        table.getSelectionModel().addListSelectionListener(l -> {
            lockUnlockAction.updateEnabledState();
            browseAction.updateEnabledState();
            final Task task;
            if (!l.getValueIsAdjusting() && this.table.getRowCount() > table.getSelectedRow()
                    && this.table.getSelectedRow() >= 0) {
                final var index = table.getRowSorter().convertRowIndexToModel(table.getSelectedRow());
                final var taskId = ((TaskClusteredPoint) table.getModel().getValueAt(index, -1)).id();
                task = ModifiedObjects.getLockedTask(taskId);
            } else {
                task = null;
            }
            if (!l.getValueIsAdjusting()) {
                if (task != null) {
                    CurrentTaskPanel.show(task, false);
                } else {
                    final var currentTaskPanel = CurrentTaskPanel.get();
                    // The window follows the user's locked task: filtering it out or selecting another row must not drop it
                    if (currentTaskPanel != null && !currentTaskPanel.showsLockedTask()) {
                        currentTaskPanel.refreshModel(null);
                    }
                }
            }
        });
        tableRowSorter.toggleSortOrder(0); // Start with an initial sort
        final var titleBar = new JPanel(new GridBagLayout());
        titleBar.add(this.title, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        this.add(titleBar, BorderLayout.NORTH);
        this.cards.add(GuiHelper.embedInVerticalScrollPane(panel), TABLE_CARD);
        this.cards.add(emptyState(findAction, downloadAction), EMPTY_CARD);
        this.add(this.cards, BorderLayout.CENTER);
        this.add(MRGuiHelper.buttonRows(List.of(List.of(downloadButton, lockUnlockButton))), BorderLayout.SOUTH);
        this.model.addTableModelListener(e -> updateTitle());
        tableRowSorter.addRowSorterListener(e -> updateTitle());
        updateTitle();
        // Existing MapRoulette layers call accept() as soon as we listen to them, which needs the table
        MainApplication.getLayerManager().addAndFireLayerChangeListener(this);
    }

    /**
     * Get the task list section of the MapRoulette panel
     *
     * @return The section, or {@code null} if there is no map
     */
    @Nullable
    public static TaskListPanel get() {
        final var dialog = MapRouletteDialog.get();
        return dialog == null ? null : dialog.taskList();
    }

    /**
     * Get the parent name
     *
     * @param point The point to get the parent name for
     * @return The parent name
     */
    private static String getParentName(TaskClusteredPoint point) {
        if (point instanceof ClusteredPoint clusteredPoint) {
            return clusteredPoint.parentName();
        } else if (point instanceof Task task) {
            // Used by the comparator and the renderer, so never block on the network
            return ChallengeCache.cached(task.parentId()).map(Challenge::name).orElseGet(() -> tr("Unknown"));
        } else {
            throw new IllegalArgumentException("Unknown class type: " + point.getClass());
        }
    }

    /**
     * Get the title for a task
     *
     * @param point The point to get the title for
     * @return The title
     */
    private static String getTitle(TaskClusteredPoint point) {
        if (point instanceof ClusteredPoint clusteredPoint) {
            return clusteredPoint.title();
        } else if (point instanceof Task task) {
            return task.name();
        } else {
            throw new IllegalArgumentException("Unknown class type: " + point.getClass());
        }
    }

    /**
     * Build the empty state, shown while no tasks are loaded
     *
     * @param findAction     The action that finds a challenge
     * @param downloadAction The action that loads the tasks in view
     * @return The empty state
     */
    private static JPanel emptyState(JosmAction findAction, JosmAction downloadAction) {
        final var empty = new JPanel(new GridBagLayout());
        empty.add(new JLabel(tr("No tasks loaded yet")), GBC.eol().insets(4));
        empty.add(new JButton(findAction), GBC.eol().insets(2).fill(GBC.HORIZONTAL));
        empty.add(new JButton(downloadAction), GBC.eol().insets(2).fill(GBC.HORIZONTAL));
        empty.add(new JLabel(), GBC.eol().fill(GBC.BOTH));
        return empty;
    }

    /**
     * Show the task counts in the title, and the empty state while nothing is loaded
     */
    private void updateTitle() {
        final var total = this.model.getRowCount();
        final var shown = this.table.getRowSorter() == null ? total : this.table.getRowSorter().getViewRowCount();
        final var hidden = total - shown;
        this.title.setText(
                hidden > 0 ? tr("Tasks · {0} in view · {1} hidden", shown, hidden) : tr("Tasks · {0} in view", shown));
        final var hasLayer = !MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                .isEmpty();
        ((CardLayout) this.cards.getLayout()).show(this.cards, total == 0 && !hasLayer ? EMPTY_CARD : TABLE_CARD);
    }

    /**
     * Remove the listeners and actions of the section. The MapRoulette panel calls this when it is destroyed.
     */
    public void destroy() {
        // The side buttons destroy their own actions, once
        GuiHelper.destroyComponents(this, false);
        this.actions.forEach(JosmAction::destroy);
        MainApplication.getLayerManager().removeAndFireLayerChangeListener(this.model);
        MainApplication.getLayerManager().removeAndFireLayerChangeListener(this);
        HiddenList.removeListUpdater(this.hiddenTest);
        MapRouletteClient.getInstance().removeAuthListener(this.authListener);
        ChallengeCache.removeListener(this.challengeListener);
    }

    /**
     * Get selected objects
     *
     * @return The selected objects
     */
    public Collection<TaskClusteredPoint> getSelected() {
        final var selected = this.table.getSelectedRows();
        final var set = new HashSet<TaskClusteredPoint>(selected.length);
        for (var i : selected) {
            set.add((TaskClusteredPoint) this.table.getValueAt(i, 0));
        }
        return set;
    }

    @Override
    public void accept(Collection<TaskClusteredPoint> selected) {
        final int[] toSelect = selected.stream().mapToInt(((TaskTableModel) table.getModel())::indexOf)
                .filter(i -> i >= 0).map(i -> table.getRowSorter().convertRowIndexToView(i)).sorted().distinct()
                .filter(i -> i >= 0).toArray();
        final var selModel = table.getSelectionModel();
        selModel.clearSelection();
        for (int i : toSelect) { // Not ideal. Probably won't be a perf issue though.
            selModel.addSelectionInterval(i, i);
        }
        if (toSelect.length > 0) {
            // Force an update of the row height
            table.getCellRenderer(toSelect[0], 0).getTableCellRendererComponent(table, table.getValueAt(toSelect[0], 0),
                    true, true, toSelect[0], 0);
            table.scrollRectToVisible(table.getCellRect(toSelect[0], 0, true));
        }
    }

    @Override
    public void layerAdded(LayerManager.LayerAddEvent e) {
        if (e.getAddedLayer()instanceof MapRouletteClusteredPointLayer layer) {
            layer.addSelectionListener(this);
            MapRouletteAuth.showUnavailableNoticeOnce();
            updateTitle();
        }
    }

    @Override
    public void layerRemoving(LayerManager.LayerRemoveEvent e) {
        if (e.getRemovedLayer()instanceof MapRouletteClusteredPointLayer layer) {
            layer.removeSelectionListener(this);
            GuiHelper.runInEDT(this::updateTitle);
        }
    }

    @Override
    public void layerOrderChanged(LayerManager.LayerOrderChangeEvent e) {
        // Ignore
    }

    /**
     * Check if a task is hidden
     * @param id The task to check
     * @return {@code true} if the task is hidden
     */
    private boolean isHidden(long id) {
        for (int i = 0; i < this.model.getRowCount(); i++) {
            if (id == this.model.get(i).id()) {
                return this.table.convertRowIndexToView(i) < 0;
            }
        }
        return true;
    }

    /**
     * Open task in MapRoulette
     */
    private static final class OpenInBrowserAction extends JosmAction {
        /**
         * The serial UID for this component
         */
        @Serial
        private static final long serialVersionUID = 6133894141191468929L;
        /**
         * The table to use
         */
        private final JTable table;

        /**
         * Open a task in the browser
         *
         * @param table The originating table
         */
        OpenInBrowserAction(JTable table) {
            super(tr("Open Task in MapRoulette"), "presets/misc/contact",
                    tr("Open MapRoulette Tasks on the MapRoulette website"),
                    Shortcut.registerShortcut("maproulette:open_tasks_in_browser",
                            tr("MapRoulette: Open Tasks in MapRoulette"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                    false);
            Objects.requireNonNull(table, "table");
            this.table = table;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            final var model = (TaskTableModel) this.table.getModel();
            if (this.table.getSelectedRows().length < 10) {
                for (int index : this.table.getSelectedRows()) {
                    final var i = this.table.getRowSorter().convertRowIndexToModel(index);
                    final var task = model.get(i);
                    MapRouletteServerPreference.getTaskUrl(task.parentId(), task.id())
                            .ifPresent(OpenBrowser::displayUrl);
                }
            }
        }

        @Override
        protected void updateEnabledState() {
            final var hasWebsite = MapRouletteServerPreference.getWebsiteUrl().isPresent();
            this.setEnabled(hasWebsite && this.table != null && this.table.getSelectedRowCount() < 10
                    && this.table.getSelectedRowCount() > 0);
            this.putValue(SHORT_DESCRIPTION, hasWebsite ? tr("Open MapRoulette Tasks on the MapRoulette website")
                    : tr("Set the MapRoulette website URL in the MapRoulette server preferences to open tasks there"));
        }
    }

    /**
     * Download data from MapRoulette
     */
    private static final class DownloadDataAction extends JosmAction {
        /**
         * The serial UID for this component
         */
        @Serial
        private static final long serialVersionUID = -4078764340309276574L;
        private MapRouletteDownloadTaskBox task;

        /**
         * Create a new action
         */
        DownloadDataAction() {
            super(tr("Load tasks here"), "download", tr("Load the MapRoulette tasks in the current map view"),
                    Shortcut.registerShortcut("maproulette:download_tasks", tr("MapRoulette: Download Tasks"),
                            KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                    false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            this.switchType(true);
        }

        /**
         * Perform the download in a background thread
         */
        private void download() {
            final var bounds = MainApplication.getMap().mapView.getState().getViewArea().getLatLonBoundsBox();
            final var task2 = new MapRouletteDownloadTaskBox();
            task2.download(new DownloadParams().withNewLayer(false), bounds, NullProgressMonitor.INSTANCE);
            synchronized (this) {
                if (this.task != null) {
                    this.task.cancel();
                }
                this.task = task2;
            }
            Workers.execute(() -> GuiHelper.runInEDT(() -> this.switchType(false)));
        }

        private void switchType(boolean performDownload) {
            if (performDownload && this.task == null) {
                Workers.execute(this::download);
                new ImageProvider("cancel").getResource().attachImageIcon(this);
                this.putValue(NAME, tr("Cancel"));
            } else {
                if (this.task != null) {
                    this.task.cancel();
                    this.task = null;
                }
                this.putValue(NAME, tr("Load tasks here"));
                new ImageProvider("download").getResource().attachImageIcon(this);
            }
        }
    }

    /**
     * A renderer for the task list
     */
    private static class TaskListCellRenderer extends DefaultPanelListCellRenderer<TaskClusteredPoint> {
        /**
         * The serial UID for this component
         */
        @Serial
        private static final long serialVersionUID = -4182177619455408415L;
        private static final CachingProperty<Color> COLOR_MODIFIED = new NamedColorProperty(
                marktr("MapRoulette: Task Modified"), Color.GREEN.brighter()).cached();
        private static final CachingProperty<Color> COLOR_LOCKED = new NamedColorProperty(
                marktr("MapRoulette: Task Locked"), Color.CYAN.brighter()).cached();

        private final JLabel singleLine = new JLabel();
        private final JLabel priority = new JLabel();
        private final JLabel difficulty = new JLabel();
        private final JLabel modified = new JLabel();
        private final JLabel type = new JLabel();
        private final JLabel status = new JLabel();

        /**
         * Create a new renderer
         */
        TaskListCellRenderer() {
            super(TaskClusteredPoint.class);
            this.setLayout(new GridBagLayout());
            final var gbc = GBC.eol().anchor(GBC.LINE_START).fill(GBC.HORIZONTAL);
            this.add(this.singleLine, gbc);
            this.add(this.priority, gbc);
            this.add(this.difficulty, gbc);
            this.add(this.modified, gbc);
            this.add(this.type, gbc);
            this.add(this.status, gbc);
        }

        @Override
        protected void addRenderComponents(TaskClusteredPoint value, boolean isSelected, boolean cellHasFocus) {
            this.singleLine.setText(TaskListPanel.getParentName(value) + ": " + TaskListPanel.getTitle(value));
            this.priority.setVisible(isSelected);
            this.difficulty.setVisible(isSelected);
            this.modified.setVisible(isSelected);
            this.type.setVisible(isSelected);
            this.status.setVisible(isSelected);
            if (isSelected) {
                this.priority.setText(tr("Priority: {0}", value.priority()));
                if (value instanceof ClusteredPoint point && point.difficulty() != null) {
                    this.difficulty.setText(tr("Difficulty: {0}", point.difficulty()));
                    this.difficulty.setVisible(true);
                } else {
                    this.difficulty.setVisible(false);
                }
                this.modified.setText(tr("Modified: {0}", value.modified()));
                if (value instanceof ClusteredPoint point) {
                    this.type.setText(tr("Type: {0}", point.type()));
                } else {
                    this.type.setVisible(false);
                }
                if (value.status() != null) {
                    this.status.setText(tr("Status: {0}", value.status()));
                }
                this.status.setVisible(value.status() != null);
            }
            if (!isSelected && !cellHasFocus) {
                final var defaultColor = MRColors.statusColor(value.status());
                if (defaultColor != null) {
                    this.setBackground(defaultColor);
                }
                if (ModifiedObjects.getModifiedTask(value.id()) != null) {
                    this.setBackground(COLOR_MODIFIED.get());
                }
                if (ModifiedObjects.getLockedTask(value.id()) != null) {
                    this.setBackground(COLOR_LOCKED.get());
                }
            }
            this.updateComponentColors();
            final var bounds = this.getBounds();
            this.setBounds(bounds.x, bounds.y, bounds.width, bounds.height * this.getComponentCount());
        }
    }
}
