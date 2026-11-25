package com.xmum.smartgrocery.data.local;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

final class HouseholdDetailsMigration extends Migration {
    static final HouseholdDetailsMigration INSTANCE = new HouseholdDetailsMigration();

    private HouseholdDetailsMigration() {
        super(10, 11);
    }

    @Override
    public void migrate(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("ALTER TABLE households ADD COLUMN description TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE household_members ADD COLUMN role TEXT NOT NULL DEFAULT 'Member'");
        db.execSQL("ALTER TABLE household_members ADD COLUMN status TEXT NOT NULL DEFAULT 'Active'");
        db.execSQL("ALTER TABLE household_members ADD COLUMN lastSeenAt INTEGER");
        // Earlier versions did not record ownership. Choose the earliest registered member.
        db.execSQL("UPDATE household_members SET role = 'Owner' WHERE userId = "
                + "(SELECT u.userId FROM users u JOIN household_members m ON m.userId = u.userId "
                + "WHERE m.householdId = household_members.householdId ORDER BY u.createdAt, u.userId LIMIT 1)");
        db.execSQL("CREATE TABLE household_settings (householdId TEXT NOT NULL PRIMARY KEY, "
                + "expiryWarningDays INTEGER NOT NULL DEFAULT 3, "
                + "FOREIGN KEY(householdId) REFERENCES households(householdId) ON DELETE CASCADE)");
        db.execSQL("INSERT INTO household_settings(householdId, expiryWarningDays) SELECT householdId, 3 FROM households");
        db.execSQL("ALTER TABLE local_session ADD COLUMN activeHouseholdId TEXT REFERENCES households(householdId) ON DELETE SET NULL");
        db.execSQL("CREATE INDEX index_local_session_activeHouseholdId ON local_session(activeHouseholdId)");
        db.execSQL("UPDATE local_session SET activeHouseholdId = COALESCE(householdId, "
                + "(SELECT m.householdId FROM household_members m WHERE m.userId = local_session.userId ORDER BY m.householdId LIMIT 1))");
    }
}
