package com.xmum.smartgrocery.data.local.dao;

public final class HouseholdSql {
    public static final String ID = "(SELECT COALESCE(activeHouseholdId, householdId) FROM local_session WHERE id = 1)";
    public static final String ACCESS = "EXISTS (SELECT 1 FROM household_members access WHERE access.householdId = " + ID + " AND access.userId = " + ScopeSql.USER + " AND access.status = 'Active')";
    public static final String OWNER = "EXISTS (SELECT 1 FROM household_members owner WHERE owner.householdId = " + ID + " AND owner.userId = " + ScopeSql.USER + " AND owner.status = 'Active' AND owner.role = 'Owner')";

    private HouseholdSql() {
    }
}
