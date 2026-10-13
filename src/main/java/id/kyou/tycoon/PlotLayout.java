package id.kyou.tycoon;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure coordinate model for the plot grid. Plot bounds are inclusive and
 * neighbouring plots are separated by exactly {@code roadSize} blocks.
 */
public final class PlotLayout {
    private PlotLayout() {}

    public record Bounds(int id, int minX, int maxX, int minZ, int maxZ) {
        public Bounds {
            if (id < 1 || minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Invalid plot bounds");
            }
        }
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
        public double centerX() { return (minX + maxX) / 2.0; }
        public double centerZ() { return (minZ + maxZ) / 2.0; }
        public int width() { return maxX - minX + 1; }
        public int depth() { return maxZ - minZ + 1; }
    }

    public static List<Bounds> create(int plotsX, int plotsZ, int plotSize, int roadSize) {
        if (plotsX < 1 || plotsZ < 1 || plotSize < 1 || roadSize < 1) {
            throw new IllegalArgumentException("Plot counts, size and road width must be positive");
        }
        long fullWidth = (long) plotsX * plotSize + (long) (plotsX - 1) * roadSize;
        long fullDepth = (long) plotsZ * plotSize + (long) (plotsZ - 1) * roadSize;
        if (fullWidth > Integer.MAX_VALUE || fullDepth > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Configured plot grid is too large");
        }
        int originX = (int) (-fullWidth / 2);
        int originZ = (int) (-fullDepth / 2);
        List<Bounds> result = new ArrayList<>(Math.multiplyExact(plotsX, plotsZ));
        int step = Math.addExact(plotSize, roadSize);
        for (int row = 0; row < plotsZ; row++) {
            int minZ = Math.addExact(originZ, Math.multiplyExact(row, step));
            for (int col = 0; col < plotsX; col++) {
                int minX = Math.addExact(originX, Math.multiplyExact(col, step));
                int id = Math.addExact(Math.multiplyExact(row, plotsX), col + 1);
                result.add(new Bounds(id, minX, Math.addExact(minX, plotSize - 1),
                        minZ, Math.addExact(minZ, plotSize - 1)));
            }
        }
        return List.copyOf(result);
    }

    public static int gapBetween(Bounds left, Bounds right) {
        if (left.maxZ() != right.maxZ() || left.minZ() != right.minZ()) {
            throw new IllegalArgumentException("Plots are not on the same row");
        }
        return right.minX() - left.maxX() - 1;
    }

    public static int gapAlongZ(Bounds north, Bounds south) {
        if (north.maxX() != south.maxX() || north.minX() != south.minX()) {
            throw new IllegalArgumentException("Plots are not in the same column");
        }
        return south.minZ() - north.maxZ() - 1;
    }
}
