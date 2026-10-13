package id.kyou.tycoon;

/**
 * Deterministic production-time calculation. Configuration semantics follow the
 * project specification: player_offline=false allows offline operation;
 * player_offline=true pauses a machine while its owner is offline.
 */
public final class OfflineProductionCalculator {
    private OfflineProductionCalculator() {}

    public record Result(long elapsedSeconds, long completeCycles, double progressSeconds) {}

    public static Result calculate(
            double previousProgressSeconds,
            long lastProcessedEpochSeconds,
            long nowEpochSeconds,
            long offlineStartedEpochSeconds,
            boolean playerOnline,
            boolean playerOfflineConfig,
            long maximumOfflineSeconds,
            long cycleSeconds) {
        if (cycleSeconds < 1) throw new IllegalArgumentException("cycleSeconds must be positive");
        if (maximumOfflineSeconds < 0) throw new IllegalArgumentException("maximumOfflineSeconds cannot be negative");

        double priorProgress = Math.max(0.0, Math.min(previousProgressSeconds, cycleSeconds));
        long elapsed = Math.max(0L, nowEpochSeconds - lastProcessedEpochSeconds);

        if (!playerOnline) {
            if (playerOfflineConfig) {
                elapsed = 0L;
            } else {
                long offlineStart = offlineStartedEpochSeconds > 0
                        ? offlineStartedEpochSeconds : lastProcessedEpochSeconds;
                long deadline = saturatingAdd(offlineStart, maximumOfflineSeconds);
                long eligibleNow = Math.min(nowEpochSeconds, deadline);
                elapsed = Math.max(0L, eligibleNow - lastProcessedEpochSeconds);
            }
        }

        double accumulated = priorProgress + elapsed;
        long cycles = (long) Math.floor(accumulated / cycleSeconds);
        double remaining = accumulated - ((double) cycles * cycleSeconds);
        return new Result(elapsed, cycles, Math.max(0.0, Math.min(remaining, cycleSeconds)));
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }
}
