package id.kyou.tycoon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfflineProductionCalculatorTest {
    @Test
    void onlineProductionIncludesPreviousProgressExactlyOnce() {
        var r = OfflineProductionCalculator.calculate(4, 1_000, 1_007, 0, true, false, 21_600, 10);
        assertEquals(7, r.elapsedSeconds());
        assertEquals(1, r.completeCycles());
        assertEquals(1.0, r.progressSeconds(), 0.0001);
    }

    @Test
    void playerOfflineFalseKeepsMachinesRunningButCapsOfflineTime() {
        long start = 10_000;
        var r = OfflineProductionCalculator.calculate(0, start, start + 36_000, start, false, false, 21_600, 10);
        assertEquals(21_600, r.elapsedSeconds());
        assertEquals(2_160, r.completeCycles());
        assertEquals(0.0, r.progressSeconds(), 0.0001);
    }

    @Test
    void playerOfflineTruePausesMachinesWithoutAccumulatingTime() {
        var r = OfflineProductionCalculator.calculate(3, 1_000, 2_000, 1_000, false, true, 21_600, 10);
        assertEquals(0, r.elapsedSeconds());
        assertEquals(0, r.completeCycles());
        assertEquals(3.0, r.progressSeconds(), 0.0001);
    }

    @Test
    void noOfflineTimeAccumulatesAfterTheConfiguredDeadline() {
        long start = 50_000;
        long deadline = start + 21_600;
        var r = OfflineProductionCalculator.calculate(0, deadline, deadline + 30, start, false, false, 21_600, 10);
        assertEquals(0, r.elapsedSeconds());
        assertEquals(0, r.completeCycles());
    }

    @Test
    void onlineProductionIsNotLimitedByTheOfflineCap() {
        var r = OfflineProductionCalculator.calculate(0, 1_000, 37_000, 0, true, false, 60, 100);
        assertEquals(36_000, r.elapsedSeconds());
        assertEquals(360, r.completeCycles());
    }
}
