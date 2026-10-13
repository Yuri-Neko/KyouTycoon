package id.kyou.tycoon.company;

/** Persisted employee payroll item. */
public record Employee(long id, long companyId, String type, double salary, boolean active) {
    public Employee {
        if (id < 1 || companyId < 1) throw new IllegalArgumentException("Employee and company IDs must be positive");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Employee type cannot be blank");
        if (!Double.isFinite(salary) || salary < 0) throw new IllegalArgumentException("Salary must be finite and non-negative");
    }
}
