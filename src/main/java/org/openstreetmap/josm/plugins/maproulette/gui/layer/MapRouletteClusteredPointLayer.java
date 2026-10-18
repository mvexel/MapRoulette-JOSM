// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.layer;

import static org.openstreetmap.josm.gui.layer.OsmDataLayer.PROPERTY_HIDE_LABELS_WHILE_DRAGGING;
import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Point2D;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.INode;
import org.openstreetmap.josm.data.osm.IWay;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.QuadBuckets;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.osm.visitor.paint.StyledMapRenderer;
import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.preferences.CachingProperty;
import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.data.preferences.NamedColorProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.draw.SymbolShape;
import org.openstreetmap.josm.gui.layer.AbstractTileSourceLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.mappaint.mapcss.MapCSSStyleSource;
import org.openstreetmap.josm.gui.mappaint.styleelement.Symbol;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.model.Identifier;
import org.openstreetmap.josm.plugins.maproulette.api.model.Locatable;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.api_caching.TaskCache;
import org.openstreetmap.josm.plugins.maproulette.data.HiddenList;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskMarker;
import org.openstreetmap.josm.plugins.maproulette.io.upload.LateUploadHook;
import org.openstreetmap.josm.tools.ColorHelper;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.ListenerList;

/**
 * A layer for showing task locations
 */
public class MapRouletteClusteredPointLayer extends Layer implements MouseListener {
    /** The number of clicks for deselection */
    private static final CachingProperty<Integer> DESELECT_CLICK_COUNT = new IntegerProperty(
            "maproulette.task.deselect.mouse.click.count", 3).cached();

    /**
     * The style source, mostly used for preferences
     */
    private static final MapCSSStyleSource STYLE = new MapCSSStyleSource(
            "setting::maproulette{type:color;label:tr(\"MapRoulette task geometry\");default:maproulette#6495ed;}"
                    + "way::maproulette{casing-width:3;casing-color:setting(\"maproulette\");opacity:0.2;}"
                    + "node:tagged::maproulette{symbol-shape:circle;symbol-size:15;symbol-stroke-color:setting(\"maproulette\");"
                    + "symbol-fill-color:setting(\"maproulette\");symbol-fill-opacity:0.5;symbol-stroke-opacity:0.5;}");

    /**
     * The color for locked MR tasks
     */
    private static final NamedColorProperty LOCKED_TASK_COLOR = new NamedColorProperty(
            NamedColorProperty.COLOR_CATEGORY_MAPPAINT, STYLE.url, marktr("MapRoulette task geometry"),
            ColorHelper.html2color("#6495ed"));

    /**
     * Show the legend of the markers on the map
     */
    private static final BooleanProperty SHOW_LEGEND = new BooleanProperty("maproulette.map.legend", true);
    /** The gap between the legend and the edges of the map */
    static final int LEGEND_MARGIN = 12;
    /** The room below the legend over imagery: its attribution text, terms of use link and logo */
    static final int ATTRIBUTION_ROOM = 64;

    /**
     * Markers grouped into a count, as last painted
     *
     * @param x      The centre on the screen
     * @param y      The centre on the screen
     * @param points The tasks
     * @param bounds Their area
     */
    private record Group(double x, double y, List<TaskClusteredPoint> points, BBox bounds) {
    }

    /**
     * The groups of the last paint, for clicks
     */
    private volatile List<Group> groups = List.of();
    /**
     * The point bucket
     */
    private final QuadBuckets<TaskClusteredPoint> pointBucket = new QuadBuckets<>();
    /**
     * The id mapping
     */
    private final Set<TaskClusteredPoint> pointMap = new TreeSet<>(Comparator.comparingLong(Identifier::id));
    /**
     * The selected points
     */
    private final Collection<TaskClusteredPoint> selected = new HashSet<>();
    /**
     * The listener for selection updates
     */
    private final ListenerList<Consumer<Collection<TaskClusteredPoint>>> selectionListeners = ListenerList.create();
    /**
     * The listeners for notifying of updated data
     */
    private final ListenerList<Consumer<Map<Long, TaskClusteredPoint>>> updatedDataListeners = ListenerList.create();
    private final Consumer<Map<Long, Task>> taskUpdated;
    /**
     * Repaints when a challenge arrives in the background, since it may hide tasks
     */
    private final Runnable challengeLoaded = this::invalidate;
    /**
     * The bounds of the points
     */
    private final Bounds bounds;

    /**
     * Create a new layer
     *
     * @param bounds The bounds of the layer
     * @param points The points for the layer
     */
    public MapRouletteClusteredPointLayer(Bounds bounds, Collection<TaskClusteredPoint> points) {
        super(tr("MapRoulette Task Layer"));
        // The server's statuses are newer than the ones sent before this load
        ModifiedObjects.clearSentStatuses(points.stream().map(Identifier::id).toList());
        this.pointBucket.addAll(points);
        this.bounds = bounds;
        this.taskUpdated = tasks -> {
            final var taskIds = tasks.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
            final Predicate<Locatable> filter = point -> Arrays.binarySearch(taskIds, point.id()) >= 0;
            this.pointBucket.removeIf(filter);
            this.pointMap.removeIf(filter);
            this.refreshTasks(tasks);
            this.updatedDataListeners.fireEvent(listener -> listener.accept(Collections.emptyMap()));
        };
        LateUploadHook.addUploadListener(this.taskUpdated);
        ChallengeCache.addListener(this.challengeLoaded);
        this.pointMap.addAll(points);
    }

    /**
     * Refresh tasks
     *
     * @param tcMap The map of task id to point
     */
    public synchronized void refreshTasks(Map<Long, ? extends TaskClusteredPoint> tcMap) {
        final var current = this.pointMap.stream().collect(Collectors.toMap(Identifier::id, c -> c));
        for (var entry : tcMap.entrySet()) {
            if (current.containsKey(entry.getKey())) {
                final var toRemove = current.get(entry.getKey());
                this.pointBucket.remove(toRemove);
                this.pointMap.remove(toRemove);
            }
            this.pointMap.add(entry.getValue());
            this.pointBucket.add(entry.getValue());
        }
        final Map<Long, TaskClusteredPoint> updateCollection = this.pointBucket.stream()
                .collect(Collectors.toMap(Identifier::id, c -> c));
        this.updatedDataListeners.fireEvent(consumer -> consumer.accept(updateCollection));
        GuiHelper.runInEDT(this::invalidate);
    }

    @Override
    public Icon getIcon() {
        return ImageProvider.get("dialogs", "user_no_image", ImageProvider.ImageSizes.LAYER);
    }

    @Override
    public String getToolTipText() {
        return tr("MapRoulette Task Layer: ");
    }

    @Override
    public void mergeFrom(Layer from) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        v.visit(this.bounds);
    }

    @Override
    public Object getInfoComponent() {
        return null;
    }

    @Override
    public Action[] getMenuEntries() {
        return new Action[] { new LegendAction() };
    }

    /**
     * Show or hide the legend of the markers
     */
    private static final class LegendAction extends AbstractAction {
        @Serial
        private static final long serialVersionUID = -2546254166403371946L;

        LegendAction() {
            super(SHOW_LEGEND.get() ? tr("Hide the marker legend") : tr("Show the marker legend"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            SHOW_LEGEND.put(!SHOW_LEGEND.get());
            MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                    .forEach(Layer::invalidate);
        }
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bounds) {
        final var box = bounds.toBBox();
        final var painter = new StyledMapRenderer(g, mv, false);
        final var mrColor = LOCKED_TASK_COLOR.get();
        final var mrColorOpacity = new Color(mrColor.getRed(), mrColor.getGreen(), mrColor.getBlue(), 128);
        final var taskListPanel = TaskListPanel.get();

        final Collection<TaskClusteredPoint> listSelected = taskListPanel == null ? Collections.emptySet()
                : taskListPanel.getSelected();

        painter.enableSlowOperations(mv.getMapMover() == null || !mv.getMapMover().movementInProgress()
                || !PROPERTY_HIDE_LABELS_WHILE_DRAGGING.get());
        painter.getSettings(false);

        final var symbol = new Symbol(SymbolShape.CIRCLE, 10, null, mrColorOpacity, mrColorOpacity);
        final var stroke = new BasicStroke(8f);
        paintStartedGeometries(painter, box, symbol, stroke, mrColorOpacity);
        paintMarkers(g, mv, box, listSelected);
        if (SHOW_LEGEND.get()) {
            // Imagery draws its attribution, logo and terms of use at the bottom; some licences require them visible
            final var imagery = MainApplication.getLayerManager().getVisibleLayersInZOrder().stream()
                    .anyMatch(AbstractTileSourceLayer.class::isInstance);
            paintLegend(g, mv.getWidth(), mv.getHeight(), imagery ? ATTRIBUTION_ROOM : LEGEND_MARGIN);
        }
    }

    /**
     * Paint the task markers (#113). Markers too close to tell apart are grouped into a count; the started and the
     * selected tasks are never grouped.
     *
     * @param g            The graphics
     * @param mv           The map view
     * @param box          The area to paint
     * @param listSelected The tasks selected in the list
     */
    private void paintMarkers(Graphics2D g, MapView mv, BBox box, Collection<TaskClusteredPoint> listSelected) {
        final var single = new ArrayList<TaskClusteredPoint>();
        final var groupable = new ArrayList<TaskClusteredPoint>();
        for (var point : this.pointBucket.search(box)) {
            if (point.location() == null || TaskCache.isHidden(point) || HiddenList.isHidden(point.id())) {
                continue;
            }
            if (this.selected.contains(point) || listSelected.contains(point)
                    || ModifiedObjects.getLockedTask(point.id()) != null) {
                single.add(point);
            } else {
                groupable.add(point);
            }
        }
        final var newGroups = new ArrayList<Group>();
        for (var cell : cluster(groupable, mv::getPoint2D)) {
            if (cell.size() == 1) {
                final var point = cell.get(0);
                final var screen = mv.getPoint2D(point.location());
                final var kind = TaskMarker.kind(point);
                TaskMarker.paint(g, kind, screen.getX(), screen.getY(), TaskMarker.size(kind), false);
            } else {
                final var bounds = new BBox(cell.get(0).location());
                cell.forEach(point -> bounds.add(point.location().lon(), point.location().lat()));
                final var centre = mv.getPoint2D(bounds.getCenter());
                TaskMarker.paintGroup(g, centre.getX(), centre.getY(), cell.size());
                newGroups.add(new Group(centre.getX(), centre.getY(), List.copyOf(cell), bounds));
            }
        }
        this.groups = List.copyOf(newGroups);
        // Selected and started markers go on top, with their labels
        final var labelSelected = single.size() <= 3;
        for (var point : single) {
            final var screen = mv.getPoint2D(point.location());
            final var kind = TaskMarker.kind(point);
            final var isSelected = this.selected.contains(point) || listSelected.contains(point);
            final var size = TaskMarker.size(kind);
            TaskMarker.paint(g, kind, screen.getX(), screen.getY(), size, isSelected);
            final var labelX = screen.getX() + size / 2.0 + 10;
            if (kind == TaskMarker.Kind.STARTED) {
                TaskMarker.paintLabel(g, tr("Your task {0}", Long.toString(point.id())), labelX, screen.getY(),
                        TaskMarker.BLUE, Color.WHITE);
            } else if (isSelected && labelSelected) {
                TaskMarker.paintLabel(g, tr("Task {0} · selected", Long.toString(point.id())), labelX, screen.getY(),
                        TaskMarker.HIGHLIGHT, Color.BLACK);
            }
        }
    }

    /**
     * Group the tasks whose markers would overlap: those in the same {@value TaskMarker#GROUP_SIZE} px square of the
     * screen
     *
     * @param points The tasks
     * @param screen Where a place is on the screen
     * @return The groups, in the order of their first task; a group of one is a single marker
     */
    static List<List<TaskClusteredPoint>> cluster(Collection<TaskClusteredPoint> points,
            Function<ILatLon, Point2D> screen) {
        final var cells = new LinkedHashMap<Long, List<TaskClusteredPoint>>();
        final var cellSize = TaskMarker.GROUP_SIZE;
        for (var point : points) {
            final var position = screen.apply(point.location());
            final long key = (long) Math.floor(position.getX() / cellSize) * 1_000_003L
                    + (long) Math.floor(position.getY() / cellSize);
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(point);
        }
        return List.copyOf(cells.values());
    }

    /**
     * Paint the legend of the markers, at the bottom left of the map
     *
     * @param g      The graphics
     * @param width  The width of the map
     * @param height The height of the map
     * @param bottom The room to leave below the legend
     */
    static void paintLegend(Graphics2D g, int width, int height, int bottom) {
        final var g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(g2.getFont().deriveFont(12f));
            final var metrics = g2.getFontMetrics();
            final var items = new ArrayList<Map.Entry<Icon, String>>();
            for (var kind : TaskMarker.Kind.values()) {
                items.add(Map.entry(TaskMarker.icon(kind, false), kind.label()));
            }
            items.add(Map.entry(TaskMarker.icon(TaskMarker.Kind.OPEN, true), tr("Selected")));
            items.add(Map.entry(TaskMarker.groupIcon(), tr("Group, zoom in")));
            // Lay the items out in rows that fit the map
            final var maxWidth = Math.max(120, width - 2 * LEGEND_MARGIN);
            final var rows = new ArrayList<List<Map.Entry<Icon, String>>>();
            var row = new ArrayList<Map.Entry<Icon, String>>();
            var rowWidth = 0;
            var legendWidth = 0;
            for (var item : items) {
                final var itemWidth = item.getKey().getIconWidth() + 4 + metrics.stringWidth(item.getValue()) + 12;
                if (!row.isEmpty() && rowWidth + itemWidth > maxWidth - 16) {
                    rows.add(row);
                    legendWidth = Math.max(legendWidth, rowWidth);
                    row = new ArrayList<>();
                    rowWidth = 0;
                }
                row.add(item);
                rowWidth += itemWidth;
            }
            rows.add(row);
            legendWidth = Math.max(legendWidth, rowWidth);
            final var lineHeight = Math.max(18, metrics.getHeight());
            final var legendHeight = rows.size() * lineHeight + 8;
            final var left = LEGEND_MARGIN;
            final var top = height - bottom - legendHeight;
            g2.setColor(new Color(255, 255, 255, 235));
            g2.fillRoundRect(left, top, legendWidth + 16, legendHeight, 6, 6);
            g2.setColor(Color.DARK_GRAY);
            var y = top + 4;
            for (var line : rows) {
                var x = left + 8;
                for (var item : line) {
                    final var icon = item.getKey();
                    icon.paintIcon(null, g2, x, y + (lineHeight - icon.getIconHeight()) / 2);
                    x += icon.getIconWidth() + 4;
                    g2.setColor(Color.DARK_GRAY);
                    g2.drawString(item.getValue(), x,
                            y + (lineHeight + metrics.getAscent() - metrics.getDescent()) / 2);
                    x += metrics.stringWidth(item.getValue()) + 12;
                }
                y += lineHeight;
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paint the objects of the started tasks
     *
     * @param painter        The painter
     * @param box            The area to paint
     * @param symbol         The symbol of nodes
     * @param stroke         The stroke of ways
     * @param mrColorOpacity The colour
     */
    private static void paintStartedGeometries(StyledMapRenderer painter, BBox box, Symbol symbol, BasicStroke stroke,
            Color mrColorOpacity) {
        for (var task : ModifiedObjects.getLockedTasks()) {
            for (INode n : task.geometries().searchNodes(box)) {
                if (n.isTagged() || !n.isReferredByWays(1)) {
                    painter.drawNodeSymbol(n, symbol, mrColorOpacity, mrColorOpacity);
                }
            }
            for (IWay<?> w : task.geometries().searchWays(box)) {
                if (w.isTagged()) {
                    if (w.isClosed()) {
                        painter.drawArea(w, mrColorOpacity, null, 12.5f, 0.5f, false);
                    } else {
                        painter.drawWay(w, mrColorOpacity, stroke, stroke, null, 0, false, false, false, false);
                    }
                }
            }
            for (Relation r : task.geometries().searchRelations(box)) {
                if (r.isTagged() && r.isMultipolygon()) {
                    painter.drawArea(r, mrColorOpacity, null, null, null, false);
                }
            }
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        final var mapView = MainApplication.getMap().mapView;
        // A group zooms in on its tasks, or selects them if they share one place
        for (var group : this.groups) {
            if (e.getPoint().distance(group.x(), group.y()) <= TaskMarker.GROUP_SIZE / 2.0) {
                if (group.bounds().getTopLeftLat() - group.bounds().getBottomRightLat() < 1e-7
                        && group.bounds().getBottomRightLon() - group.bounds().getTopLeftLon() < 1e-7) {
                    this.setSelected(group.points());
                } else {
                    final var zoom = new Bounds(group.bounds().getBottomRightLat(), group.bounds().getTopLeftLon(),
                            group.bounds().getTopLeftLat(), group.bounds().getBottomRightLon());
                    mapView.zoomTo(zoom);
                }
                this.invalidate();
                return;
            }
        }
        final var clickLocation = mapView.getLatLon(e.getPoint().x, e.getPoint().y);
        final var bbox = new BBox(clickLocation);
        final ArrayList<TaskClusteredPoint> add = e.isShiftDown() ? new ArrayList<>(this.selected) : new ArrayList<>();
        add.addAll(this.pointBucket.search(bbox));
        add.removeIf(TaskCache::isHidden);
        if (!add.isEmpty() && !this.selected.containsAll(add) && !add.containsAll(this.selected)) {
            this.setSelected(add);
            return;
        }
        bbox.addLatLon(clickLocation, 0.000_000_8 * mapView.getDist100Pixel());
        add.addAll(this.pointBucket.search(bbox));
        add.removeIf(TaskCache::isHidden);
        if (add.isEmpty()) {
            final var tNode = new Node(clickLocation);
            for (var task : ModifiedObjects.getLockedTasks()) {
                if (!TaskCache.isHidden(task)) {
                    final var minDistance = task.geometries().searchPrimitives(bbox).stream()
                            .mapToDouble(prim -> Geometry.getDistance(tNode, prim)).min().orElse(Double.NaN);
                    if (!Double.isNaN(minDistance) && minDistance < mapView.getDist100Pixel() / 10) {
                        this.pointBucket.stream().filter(p -> p.id() == task.id()).findFirst().ifPresent(add::add);
                    }
                }
            }
        }
        if (add.isEmpty() && e.getClickCount() >= DESELECT_CLICK_COUNT.get()) {
            this.setSelected(Collections.emptyList());
        } else if (!add.isEmpty()) {
            this.setSelected(add);
        }
        this.invalidate();
    }

    /**
     * Set points as selected
     *
     * @param points The points to set as selected
     */
    @SuppressWarnings("UndefinedEquals")
    private void setSelected(Collection<TaskClusteredPoint> points) {
        final var lastSelected = new HashSet<>(this.selected);
        this.selected.clear();
        for (var p : points) {
            if (this.pointBucket.contains(p)) {
                this.selected.add(p);
            }
        }
        // Only fire listeners if the selection actually changed
        if (!this.selected.equals(lastSelected)) {
            this.selectionListeners.fireEvent(listener -> listener.accept(points));
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        // Do nothing
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        // Do nothing
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        // Do nothing
    }

    @Override
    public void mouseExited(MouseEvent e) {
        // Do nothing
    }

    @Override
    public synchronized void destroy() {
        super.destroy();
        this.selectionListeners.fireEvent(listener -> listener.accept(Collections.emptyList()));
        MainApplication.getMap().mapView.removeMouseListener(this);
        LateUploadHook.removeUploadListener(this.taskUpdated);
        ChallengeCache.removeListener(this.challengeLoaded);
    }

    /**
     * Add a listener for data updates
     *
     * @param updateConsumer The consumer for data updates
     */
    public void addListener(Consumer<Map<Long, TaskClusteredPoint>> updateConsumer) {
        this.updatedDataListeners.addListener(updateConsumer);
    }

    /**
     * Remove a listener for data updates
     *
     * @param updateConsumer The consumer to remove for data updates
     */
    public void removeListener(Consumer<Map<Long, TaskClusteredPoint>> updateConsumer) {
        this.updatedDataListeners.removeListener(updateConsumer);
    }

    /**
     * Add a selection listener
     *
     * @param listener The listener for updated selection events
     */
    public void addSelectionListener(Consumer<Collection<TaskClusteredPoint>> listener) {
        this.selectionListeners.addListener(listener);
        listener.accept(Collections.unmodifiableCollection(this.selected));
    }

    /**
     * Get the tasks from this layer
     *
     * @return The tasks for this layer
     */
    public Collection<TaskClusteredPoint> getTasks() {
        return Collections.unmodifiableCollection(this.pointBucket);
    }

    /**
     * Remove a selection listener
     *
     * @param listener The listener for updated selection events
     */
    public void removeSelectionListener(Consumer<Collection<TaskClusteredPoint>> listener) {
        this.selectionListeners.removeListener(listener);
    }
}
