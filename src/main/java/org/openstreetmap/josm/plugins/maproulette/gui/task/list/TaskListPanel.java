// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.table.TableStringConverter;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.NavigatableComponent;
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
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Identifier;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.api_caching.TaskCache;
import org.openstreetmap.josm.plugins.maproulette.data.HiddenList;
import org.openstreetmap.josm.plugins.maproulette.gui.LoginControl;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
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
     * Re-evaluates Start/Stop when the MapRoulette login or the started tasks change
     */
    private final Runnable authListener;
    /**
     * Re-evaluates Start/Stop and the empty state when the started tasks change
     */
    private final Runnable lockListener;
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
     * The buttons below the list, hidden with the empty state: it has its own buttons
     */
    private final JPanel buttons;
    /**
     * Says why Start is disabled without a login, with a Log in button (#116)
     */
    private final JPanel loginNotice = new JPanel(new GridBagLayout());
    /**
     * The text filter
     */
    private final FilterField filterField = new FilterField();
    /**
     * The challenge filter
     */
    private final JComboBox<ChallengeChoice> challengeFilter = new JComboBox<>();
    /**
     * Set while the challenge filter is rebuilt, so the rebuild does not filter
     */
    private boolean rebuildingChallenges;
    /**
     * The number of listed tasks of each challenge, for the challenge headers
     */
    private final Map<Long, Integer> challengeSizes = new HashMap<>();
    /**
     * The readable names, which can need a preset lookup, by task id. Cleared when the tasks change.
     */
    private final Map<Long, String> names = new HashMap<>();
    /**
     * Counts the tasks the filters or the ignore lists leave out
     */
    private final JLabel footerText = new JLabel();
    /**
     * The line below the list that counts the tasks left out
     */
    private final JPanel footer = new JPanel(new GridBagLayout());
    /**
     * Clears the filters
     */
    private final JButton showAll = new JButton(tr("Show all"));
    /**
     * The challenge and text filters, hidden with the empty state
     */
    private final JPanel filters = new JPanel(new GridBagLayout());
    /**
     * The title and the filters, above the list
     */
    private final JPanel titleBar = new JPanel(new GridBagLayout());
    /**
     * Repaints the distances when the map moves
     */
    private final NavigatableComponent.ZoomChangeListener zoomListener;
    /**
     * Hides tasks that are ignored or in hidden challenges
     */
    private final RowFilter<TaskTableModel, Integer> defaultFilter = new RowFilter<>() {
        @Override
        public boolean include(Entry<? extends TaskTableModel, ? extends Integer> entry) {
            final var point = (TaskClusteredPoint) entry.getValue(0);
            return !TaskCache.isHidden(point);
        }
    };
    /**
     * Sorts and filters the table
     */
    private final TableRowSorter<TaskTableModel> tableRowSorter;

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
        this.authListener = () -> {
            lockUnlockAction.updateEnabledState();
            this.loginNotice.setVisible(LoginControl.canLogIn());
            // Whose locks are the user's own depends on the login
            table.repaint();
        };
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        // A lock can also be dropped elsewhere (the current task's status, a server switch), which changes Stop
        this.lockListener = () -> {
            lockUnlockAction.updateEnabledState();
            updateTitle();
        };
        ModifiedObjects.addLockListener(this.lockListener);
        final var browseAction = new OpenInBrowserAction(table);
        this.tableRowSorter = new TableRowSorter<>(model);
        final var tableRowSorter = this.tableRowSorter;
        this.challengeListener = () -> {
            tableRowSorter.allRowsChanged();
            table.repaint();
        };
        ChallengeCache.addListener(this.challengeListener);
        final var menu = new JPopupMenu();
        final var filters = this.filters;
        final var challengeLabel = new JLabel(tr("Challenge"));
        challengeLabel.setLabelFor(this.challengeFilter);
        final var filterLabel = new JLabel(tr("Filter"));
        filterLabel.setLabelFor(this.filterField);
        this.filterField.setToolTipText(tr("Name, street, task id"));
        filters.add(challengeLabel, GBC.std().insets(4, 2, 4, 2));
        this.challengeFilter.setRenderer(new PlainTextRenderer());
        filters.add(this.challengeFilter, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 2, 4, 2));
        filters.add(filterLabel, GBC.std().insets(4, 2, 4, 2));
        filters.add(this.filterField, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 2, 4, 2));
        final var panel = new JPanel(new GridBagLayout());
        panel.add(table, GBC.eol().anchor(GBC.LINE_START).fill(GBC.BOTH));
        HiddenList.addListUpdater(this.hiddenTest);
        this.filterField.filter(expr -> applyFilters());
        this.challengeFilter.addActionListener(e -> {
            if (!this.rebuildingChallenges) {
                applyFilters();
            }
        });
        tableRowSorter.setStringConverter(new TableStringConverter() {
            @Override
            public String toString(TableModel tableModel, int row, int column) {
                final var point = model.get(row);
                return TaskRows.searchText(point, readableName(point));
            }
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
        tableRowSorter.setRowFilter(this.defaultFilter);
        // Grouped by challenge, for the challenge headers
        tableRowSorter.setComparator(0,
                Comparator.comparing(TaskRows::challengeName).thenComparingLong(TaskClusteredPoint::parentId)
                        .thenComparingInt(TaskClusteredPoint::priority).thenComparing(TaskRows::taskName)
                        .thenComparingLong(TaskClusteredPoint::id));
        table.setRowSorter(tableRowSorter);
        table.setTableHeader(null);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setDefaultRenderer(ClusteredPoint.class, new TaskRowRenderer(
                id -> this.challengeSizes.getOrDefault(id, 0), TaskListPanel::viewCentre, this::readableName));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // Double-clicking a task starts it; it never stops a started one
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && table.rowAtPoint(e.getPoint()) >= 0
                        && lockUnlockAction.isEnabled() && Boolean.TRUE.equals(lockUnlockAction.getValue("lock"))) {
                    lockUnlockAction.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "start"));
                }
            }
        });
        this.zoomListener = table::repaint;
        NavigatableComponent.addZoomChangeListener(this.zoomListener);
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
                showSelectedMarker();
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
        final var titleBar = this.titleBar;
        titleBar.add(this.title, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        titleBar.add(filters, GBC.eol().fill(GBC.HORIZONTAL));
        this.add(titleBar, BorderLayout.NORTH);
        this.showAll.addActionListener(e -> clearFilters());
        this.footer.add(this.footerText, GBC.std().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        this.footer.add(this.showAll, GBC.eol().insets(0, 2, 4, 2));
        final var listAndFooter = new JPanel(new BorderLayout());
        final var scrollPane = GuiHelper.embedInVerticalScrollPane(panel);
        // The scroll pane wraps a panel, so the table's own scroll step is not used: scroll about a row per step (#144)
        scrollPane.getVerticalScrollBar().setUnitIncrement(scrollStep(this.table));
        listAndFooter.add(scrollPane, BorderLayout.CENTER);
        listAndFooter.add(this.footer, BorderLayout.SOUTH);
        this.cards.add(listAndFooter, TABLE_CARD);
        this.cards.add(emptyState(findAction, downloadAction), EMPTY_CARD);
        this.add(this.cards, BorderLayout.CENTER);
        final var logIn = new JButton(tr("Log in…"));
        logIn.addActionListener(e -> {
            final var dialog = MapRouletteDialog.get();
            if (dialog != null) {
                // The panel header shows the login's progress and errors
                dialog.login().logIn();
            }
        });
        this.loginNotice.add(new JLabel(tr("Log in to start tasks:")), GBC.std().insets(4, 2, 4, 2));
        this.loginNotice.add(logIn, GBC.std().insets(0, 2, 4, 2));
        this.loginNotice.add(new JLabel(), GBC.eol().fill(GBC.HORIZONTAL));
        this.loginNotice.setVisible(LoginControl.canLogIn());
        this.buttons = new JPanel(new BorderLayout());
        this.buttons.add(this.loginNotice, BorderLayout.NORTH);
        this.buttons.add(MRGuiHelper.buttonRows(List.of(List.of(downloadButton, lockUnlockButton))),
                BorderLayout.CENTER);
        this.add(this.buttons, BorderLayout.SOUTH);
        // The sorter hears of model changes after this listener; it updates the title once it has sorted. This relies on
        // TaskTableModel firing only fireTableDataChanged: with row-range events, applyFilters here would re-sort before
        // the sorter has seen the change, and DefaultRowSorter could throw.
        this.model.addTableModelListener(e -> {
            this.names.clear();
            rebuildChallengeFilter();
        });
        tableRowSorter.addRowSorterListener(e -> updateTitle());
        rebuildChallengeFilter();
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
     * Get the centre of the map view, where the rows measure distances from
     *
     * @return The centre, or {@code null} without a map
     */
    @Nullable
    private static ILatLon viewCentre() {
        final var map = MainApplication.getMap();
        if (map == null || map.mapView == null || map.mapView.getWidth() == 0) {
            return null;
        }
        return map.mapView.getLatLon(map.mapView.getWidth() / 2.0, map.mapView.getHeight() / 2.0);
    }

    /**
     * Get the readable name of a task, cached until the tasks change
     *
     * @param point The task
     * @return The name
     */
    private String readableName(TaskClusteredPoint point) {
        return this.names.computeIfAbsent(point.id(), id -> TaskRows.readableName(point));
    }

    /**
     * Filter the list by the challenge filter, the text filter and the ignore lists
     */
    private void applyFilters() {
        final ArrayList<RowFilter<? super TaskTableModel, ? super Integer>> andFilters = new ArrayList<>();
        andFilters.add(this.defaultFilter);
        if (this.challengeFilter.getSelectedItem()instanceof ChallengeChoice choice && choice.id() >= 0) {
            andFilters.add(new RowFilter<TaskTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends TaskTableModel, ? extends Integer> entry) {
                    return ((TaskClusteredPoint) entry.getValue(0)).parentId() == choice.id();
                }
            });
        }
        final var expr = this.filterField.getText() == null ? "" : this.filterField.getText().trim();
        if (!expr.isEmpty()) {
            // split search string on whitespace, do case-insensitive AND search
            for (var word : expr.split("\\s+", -1)) {
                andFilters.add(RowFilter.regexFilter("(?i)" + Pattern.quote(word)));
            }
        }
        this.tableRowSorter.setRowFilter(andFilters.size() > 1 ? RowFilter.andFilter(andFilters) : this.defaultFilter);
        final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
        final var ids = layers.stream().map(MapRouletteClusteredPointLayer::getTasks).flatMap(Collection::stream)
                .mapToLong(Identifier::id).toArray();
        HiddenList.update(ids);
        layers.forEach(MapRouletteClusteredPointLayer::invalidate);
    }

    /**
     * Clear the challenge and text filters
     */
    private void clearFilters() {
        this.rebuildingChallenges = true;
        try {
            if (this.challengeFilter.getItemCount() > 0) {
                this.challengeFilter.setSelectedIndex(0);
            }
        } finally {
            this.rebuildingChallenges = false;
        }
        this.filterField.setText("");
        applyFilters();
    }

    /**
     * List the challenges of the loaded tasks in the challenge filter, keeping the selected one if it is still there
     */
    private void rebuildChallengeFilter() {
        final var counts = new LinkedHashMap<Long, Integer>();
        final var labels = new HashMap<Long, String>();
        for (var i = 0; i < this.model.getRowCount(); i++) {
            final var point = this.model.get(i);
            if (!TaskCache.isHidden(point)) {
                counts.merge(point.parentId(), 1, Integer::sum);
                labels.putIfAbsent(point.parentId(), TaskRows.challengeName(point));
            }
        }
        final var selected = this.challengeFilter.getSelectedItem()instanceof ChallengeChoice choice ? choice.id() : -1;
        final var choices = new ArrayList<ChallengeChoice>();
        choices.add(new ChallengeChoice(-1, tr("All challenges in view ({0})", counts.size())));
        counts.entrySet().stream().sorted(Comparator.comparing(e -> labels.get(e.getKey()))).forEach(e -> choices
                .add(new ChallengeChoice(e.getKey(), tr("{0} ({1})", labels.get(e.getKey()), e.getValue()))));
        this.rebuildingChallenges = true;
        try {
            this.challengeFilter.setModel(new DefaultComboBoxModel<>(choices.toArray(ChallengeChoice[]::new)));
            final var keep = choices.stream().filter(c -> c.id() == selected).findFirst();
            this.challengeFilter.setSelectedItem(keep.orElse(choices.get(0)));
        } finally {
            this.rebuildingChallenges = false;
        }
        if (selected >= 0 && choices.stream().noneMatch(c -> c.id() == selected)) {
            // The selected challenge is gone: show all again
            applyFilters();
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
        var ignored = 0;
        for (var i = 0; i < total; i++) {
            if (TaskCache.isHidden(this.model.get(i))) {
                ignored++;
            }
        }
        final var filtered = total - ignored - shown;
        this.title.setText(tr("Tasks · {0} in view", shown));
        this.challengeSizes.clear();
        for (var i = 0; i < shown; i++) {
            this.challengeSizes.merge(((TaskClusteredPoint) this.table.getValueAt(i, 0)).parentId(), 1, Integer::sum);
        }
        final var footerLines = new ArrayList<String>(2);
        if (filtered > 0) {
            footerLines.add(trn("{0} more task is hidden by the filter.", "{0} more tasks are hidden by the filter.",
                    filtered, filtered));
        }
        if (ignored > 0) {
            footerLines
                    .add(trn("{0} ignored task is not listed.", "{0} ignored tasks are not listed.", ignored, ignored));
        }
        this.footerText.setText(String.join(" ", footerLines));
        this.footer.setVisible(!footerLines.isEmpty());
        // Show all clears the filters; it can't bring back ignored tasks
        this.showAll.setVisible(filtered > 0);
        final var hasLayer = !MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                .isEmpty();
        // A started task keeps the list, even without tasks, so the panel never says nothing is loaded next to it
        final var empty = total == 0 && !hasLayer && ModifiedObjects.getLockedTasks().isEmpty();
        ((CardLayout) this.cards.getLayout()).show(this.cards, empty ? EMPTY_CARD : TABLE_CARD);
        this.buttons.setVisible(!empty);
        this.filters.setVisible(!empty);
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
        ModifiedObjects.removeLockListener(this.lockListener);
        ChallengeCache.removeListener(this.challengeListener);
        NavigatableComponent.removeZoomChangeListener(this.zoomListener);
    }

    /**
     * Highlight the markers of the selected rows, and pan to the marker of a single selected row that is off the map
     * (#113)
     */
    private void showSelectedMarker() {
        final var map = MainApplication.getMap();
        if (map == null || map.mapView == null) {
            return;
        }
        MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                .forEach(MapRouletteClusteredPointLayer::invalidate);
        final var selected = this.getSelected();
        if (selected.size() == 1) {
            final var location = selected.iterator().next().location();
            final var mapView = map.mapView;
            if (location != null && mapView.getWidth() > 0
                    && !mapView.getRealBounds().contains(new LatLon(location.lat(), location.lon()))) {
                mapView.zoomTo(new LatLon(location.lat(), location.lon()));
            }
        }
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
     * Get the size the list needs to show some rows, with its title, filters, footer and buttons (#132)
     *
     * @param rows The number of rows to show
     * @return The size
     */
    public Dimension neededSize(int rows) {
        final var footerHeight = this.footer.isVisible() ? this.footer.getPreferredSize().height : 0;
        return new Dimension(this.buttons.getPreferredSize().width, this.titleBar.getPreferredSize().height
                + rows * scrollStep(this.table) + footerHeight + this.buttons.getPreferredSize().height);
    }

    /**
     * Get the size the list needs while a task is started: its title and the challenge filter. The rest may be squeezed
     * out, since the user can drag the divider (#132).
     *
     * @return The size
     */
    public Dimension squeezedSize() {
        final var challengeRow = this.filters.isVisible() ? this.challengeFilter.getPreferredSize().height + 4 : 0;
        return new Dimension(this.buttons.getPreferredSize().width,
                this.title.getPreferredSize().height + 4 + challengeRow);
    }

    /**
     * Get the vertical scroll step of the task list: about one row, which shows a name and a details line
     *
     * @param table The task table
     * @return The step in pixels
     */
    static int scrollStep(JTable table) {
        return 2 * table.getFontMetrics(table.getFont()).getHeight() + 12;
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
                    : tr("This server has no MapRoulette website"));
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
     * Shows the challenge names, which come from challenge authors, as text, never as HTML
     */
    private static final class PlainTextRenderer extends DefaultListCellRenderer {
        @Serial
        private static final long serialVersionUID = 1L;

        PlainTextRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }
    }

    /**
     * An entry of the challenge filter
     *
     * @param id    The challenge id, or -1 for all challenges
     * @param label The text to show
     */
    private record ChallengeChoice(long id, String label) {
        @Override
        public String toString() {
            return this.label;
        }
    }
}
