package com.xmum.smartgrocery.data.local.dao;

public final class ScopeSql {
    public static final String ACTIVE_SCOPE = "SELECT s.userId, s.householdId FROM local_session s "
            + "JOIN users u ON u.userId = s.userId WHERE s.id = 1 AND (s.householdId IS NULL OR EXISTS "
            + "(SELECT 1 FROM household_members m WHERE m.householdId = s.householdId "
            + "AND m.userId = s.userId AND m.status = 'Active'))";
    public static final String USER = "(SELECT userId FROM local_session WHERE id = 1)";
    public static final String ACTOR_NAME = "(SELECT displayName FROM users WHERE userId = " + USER + ")";
    public static final String HOUSEHOLD = "(SELECT householdId FROM local_session WHERE id = 1)";
    public static final String PERSONAL = "userId = " + USER;
    public static final String ACCESSIBLE = "((householdId IS NULL AND userId = " + USER + ") OR householdId IN (SELECT m.householdId FROM household_members m WHERE m.userId = " + USER + " AND m.status = 'Active'))";
    public static final String MATCH = "((householdId IS NULL AND userId = " + USER + " AND " + HOUSEHOLD + " IS NULL) OR (householdId = " + HOUSEHOLD + " AND EXISTS (SELECT 1 FROM household_members m WHERE m.householdId = " + HOUSEHOLD + " AND m.userId = " + USER + " AND m.status = 'Active')))";

    private ScopeSql() {
    }
}
