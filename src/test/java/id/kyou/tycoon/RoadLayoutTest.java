package id.kyou.tycoon;

import id.kyou.tycoon.world.RoadLayout;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoadLayoutTest {
    @Test
    void roadsAreSevenBlocksWideAndIntersectionsAreFilledWithoutDuplicates() {
        var plots = PlotLayout.create(2, 2, 10, 7);
        var cells = RoadLayout.create(plots, 7);
        assertEquals(329, cells.size());
        Set<String> coordinates = new HashSet<>();
        for (var cell : cells) {
            assertTrue(coordinates.add(cell.x() + ":" + cell.z()), "duplicate road cell " + cell);
        }
    }

    @Test
    void roadIntersectionsKeepCenterLinesAndBorders() {
        var plots = PlotLayout.create(2, 2, 10, 7);
        var first = plots.get(0);
        int roadX = first.maxX() + 1;
        int roadZ = first.maxZ() + 1;
        var cells = RoadLayout.create(plots, 7);
        assertEquals(RoadLayout.Surface.CENTER, cells.stream()
                .filter(c -> c.x() == roadX + 3 && c.z() == roadZ + 3)
                .findFirst().orElseThrow().surface());
        assertEquals(RoadLayout.Surface.BORDER, cells.stream()
                .filter(c -> c.x() == roadX && c.z() == roadZ + 3)
                .findFirst().orElseThrow().surface());
        assertEquals(RoadLayout.Surface.ROAD, cells.stream()
                .filter(c -> c.x() == roadX + 1 && c.z() == roadZ + 1)
                .findFirst().orElseThrow().surface());
    }

    @Test
    void emptyAndInvalidLayoutsAreHandled() {
        assertTrue(RoadLayout.create(java.util.List.of(), 7).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> RoadLayout.create(PlotLayout.create(1, 1, 10, 7), 0));
    }
}
