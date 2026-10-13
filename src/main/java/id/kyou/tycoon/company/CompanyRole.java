package id.kyou.tycoon.company;

/** Permission tier for the built-in company system. */
public enum CompanyRole {
    CEO(true, true, true),
    DIRECTOR(true, true, false),
    MANAGER(false, true, false),
    WORKER(false, false, false);

    private final boolean manageMembers;
    private final boolean manageEmployees;
    private final boolean transferOwnership;

    CompanyRole(boolean manageMembers, boolean manageEmployees, boolean transferOwnership) {
        this.manageMembers = manageMembers;
        this.manageEmployees = manageEmployees;
        this.transferOwnership = transferOwnership;
    }

    public boolean canManageMembers() { return manageMembers; }
    public boolean canManageEmployees() { return manageEmployees; }
    public boolean canTransferOwnership() { return transferOwnership; }

    public static CompanyRole parse(String value) {
        if (value == null || value.isBlank()) return WORKER;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return WORKER;
        }
    }
}
