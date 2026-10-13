package id.kyou.tycoon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlotLayoutTest {
    @Test
    void createsOneHundredPlotsWithOneHundredBlockFootprints() {
        var plots = PlotLayout.create(10, 10, 100, 7);
        assertEquals(100, plots.size());
        assertEquals(1, plots.getFirst().id());
        assertEquals(100, plots.getLast().id());
        assertTrue(plots.stream().allMatch(p -> p.width() == 100 && p.depth() == 100));
    }

    @Test
    void neighbouringPlotsAreSeparatedByExactlySevenBlocks() {
        var plots = PlotLayout.create(10, 10, 100, 7);
        assertEquals(7, PlotLayout.gapBetween(plots.get(0), plots.get(1)));
        assertEquals(7, PlotLayout.gapAlongZ(plots.get(0), plots.get(10)));
    }

    @Test
    void boundsDoNotOverlapAndGridIsCenteredAroundZero() {
        var plots = PlotLayout.create(4, 3, 20, 7);
        for (int i = 0; i < plots.size(); i++) {
            var a = plots.get(i);
            for (int j = i + 1; j < plots.size(); j++) {
                var b = plots.get(j);
                boolean overlapX = a.minX() <= b.maxX() && b.minX() <= a.maxX();
                boolean overlapZ = a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
                assertFalse(overlapX && overlapZ, "overlapping plot IDs " + a.id() + " and " + b.id());
            }
        }
        assertTrue(plots.stream().mapToInt(PlotLayout.Bounds::minX).min().orElseThrow() < 0);
        assertTrue(plots.stream().mapToInt(PlotLayout.Bounds::maxX).max().orElseThrow() > 0);
    }

    @Test
    void rejectsInvalidConfigurationInsteadOfOverflowingCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> PlotLayout.create(0, 10, 100, 7));
        assertThrows(IllegalArgumentException.class, () -> PlotLayout.create(10, 10, 100, 0));
    }
}
