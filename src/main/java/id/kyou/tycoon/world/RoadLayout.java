package id.kyou.tycoon.world;

import id.kyou.tycoon.PlotLayout;

import java.util.*;

/** Pure layout of road blocks between plots, including centerlines at crossings. */
public final class RoadLayout {
    private RoadLayout() {}

    public enum Surface { BORDER, ROAD, CENTER }
    public record Cell(int x, int z, Surface surface) {}
    private record Band(int start, int size) {}

    public static List<Cell> create(List<PlotLayout.Bounds> plots, int roadSize) {
        Objects.requireNonNull(plots, "plots");
        if (plots.isEmpty()) return List.of();
        if (roadSize < 1) throw new IllegalArgumentException("roadSize must be positive");

        int minX = plots.stream().mapToInt(PlotLayout.Bounds::minX).min().orElseThrow();
        int maxX = plots.stream().mapToInt(PlotLayout.Bounds::maxX).max().orElseThrow();
        int minZ = plots.stream().mapToInt(PlotLayout.Bounds::minZ).min().orElseThrow();
        int maxZ = plots.stream().mapToInt(PlotLayout.Bounds::maxZ).max().orElseThrow();

        Set<Integer> columnEdges = new TreeSet<>();
        Set<Integer> rowEdges = new TreeSet<>();
        for (PlotLayout.Bounds plot : plots) {
            if (plot.maxX() < maxX) columnEdges.add(plot.maxX());
            if (plot.maxZ() < maxZ) rowEdges.add(plot.maxZ());
        }
        List<Band> columns = columnEdges.stream().map(edge -> new Band(edge + 1, roadSize)).toList();
        List<Band> rows = rowEdges.stream().map(edge -> new Band(edge + 1, roadSize)).toList();
        Map<Long, Cell> cells = new LinkedHashMap<>();

        for (Band row : rows) {
            for (int z = row.start(); z < row.start() + row.size(); z++) {
                Surface surface = lane(z - row.start(), roadSize);
                for (int x = minX; x <= maxX; x++) put(cells, new Cell(x, z, surface));
            }
        }
        for (Band column : columns) {
            for (int x = column.start(); x < column.start() + column.size(); x++) {
                Surface surface = lane(x - column.start(), roadSize);
                for (int z = minZ; z <= maxZ; z++) put(cells, new Cell(x, z, surface));
            }
        }
        // Intersections are explicit, ensuring the road centerline remains continuous.
        for (Band column : columns) {
            for (Band row : rows) {
                for (int x = column.start(); x < column.start() + column.size(); x++) {
                    for (int z = row.start(); z < row.start() + row.size(); z++) {
                        int laneX = x - column.start();
                        int laneZ = z - row.start();
                        Surface surface;
                        if (isEdge(laneX, roadSize) || isEdge(laneZ, roadSize)) surface = Surface.BORDER;
                        else if (laneX == roadSize / 2 || laneZ == roadSize / 2) surface = Surface.CENTER;
                        else surface = Surface.ROAD;
                        put(cells, new Cell(x, z, surface));
                    }
                }
            }
        }
        return List.copyOf(cells.values());
    }

    private static Surface lane(int lane, int width) {
        if (isEdge(lane, width)) return Surface.BORDER;
        return lane == width / 2 ? Surface.CENTER : Surface.ROAD;
    }

    private static boolean isEdge(int lane, int width) {
        return lane == 0 || lane == width - 1;
    }

    private static void put(Map<Long, Cell> cells, Cell cell) {
        long key = ((long) cell.x() << 32) ^ (cell.z() & 0xffffffffL);
        cells.put(key, cell);
    }
}
