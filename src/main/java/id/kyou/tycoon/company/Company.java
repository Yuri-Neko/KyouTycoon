package id.kyou.tycoon.company;

import java.util.UUID;

/** Immutable company data returned from SQLite. */
public record Company(
        long id,
        String name,
        UUID ownerUuid,
        double revenue,
        double assets,
        int reputation,
        long createdAt) {
    public Company {
        if (id < 1) throw new IllegalArgumentException("Company ID must be positive");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Company name cannot be blank");
        if (ownerUuid == null) throw new IllegalArgumentException("Company owner cannot be null");
        if (!Double.isFinite(revenue) || revenue < 0) throw new IllegalArgumentException("Revenue must be finite and non-negative");
        if (!Double.isFinite(assets) || assets < 0) throw new IllegalArgumentException("Assets must be finite and non-negative");
        reputation = Math.max(0, Math.min(100, reputation));
    }

    public double estimatedValue() {
        return Math.max(0.0, revenue + assets);
    }
}
