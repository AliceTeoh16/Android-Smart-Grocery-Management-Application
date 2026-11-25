package com.xmum.smartgrocery.data.repository;

import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;

import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.dao.AccountDao;
import com.xmum.smartgrocery.data.local.dao.HouseholdDao;
import com.xmum.smartgrocery.data.local.dao.HouseholdMemberProfile;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberStatus;
import com.xmum.smartgrocery.data.local.entity.HouseholdRole;
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.local.entity.UserEntity;
import com.xmum.smartgrocery.data.sharing.SharingRuntime;
import com.xmum.smartgrocery.utils.AndroidCoroutines;

import java.util.List;
import java.util.Locale;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.flow.Flow;

public final class HouseholdRepository {
    private final AppDatabase database;
    private final AccountDao accounts;
    private final HouseholdDao dao;
    private final Flow<HouseholdEntity> household;
    private final Flow<List<HouseholdMemberProfile>> members;
    private final Flow<HouseholdSettingEntity> settings;

    public HouseholdRepository(@NonNull AppDatabase database) {
        this.database = database;
        this.accounts = database.accountDao();
        this.dao = database.householdDao();
        this.household = dao.observeHousehold();
        this.members = dao.observeMembers();
        this.settings = dao.observeSettings();
    }

    @NonNull
    public Flow<HouseholdEntity> getHousehold() { return household; }

    @NonNull
    public Flow<List<HouseholdMemberProfile>> getMembers() { return members; }

    @NonNull
    public Flow<HouseholdSettingEntity> getSettings() { return settings; }

    @NonNull
    public Object adjustWarningDays(@NonNull String householdId, int delta, @NonNull Continuation<? super Unit> completion) {
        if (delta != -1 && delta != 1) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        return execute(() -> {
            if (dao.adjustWarningDaysNow(householdId, delta) != 1) {
                throw new IllegalStateException("Household changed. Reopen Share & Connect.");
            }
        }, completion);
    }

    @NonNull
    public Object updateDetails(@NonNull String householdId, @NonNull String name, @NonNull String description,
            @NonNull Continuation<? super Unit> completion) {
        if (StringsKt.isBlank(name)) {
            throw new IllegalArgumentException("Enter a household name.");
        }
        return execute(() -> {
            if (dao.updateDetailsNow(householdId, trim(name), trim(description)) != 1) {
                throw new IllegalStateException("Only the household owner can edit these details.");
            }
        }, completion);
    }

    /** A local invitation is activated when the invited account joins by code. */
    @NonNull
    public Object inviteMember(@NonNull String householdId, @NonNull String email,
            @NonNull Continuation<? super Unit> completion) {
        SharingRuntime runtime = SharingRuntime.forDatabase(database);
        if (runtime != null) {
            return AndroidCoroutines.<String, Unit>then(next -> runtime.getService().invite(householdId, email, next),
                    (code, unused) -> Unit.INSTANCE, completion);
        }
        return execute(() -> database.runInTransaction(() -> inviteLocal(householdId, email)), completion);
    }

    private void inviteLocal(String householdId, String email) {
        LocalSessionEntity session = accounts.sessionNow();
        if (session == null || session.getUserId() == null) {
            throw new IllegalStateException("Sign in first.");
        }
        String active = session.getActiveHouseholdId() != null ? session.getActiveHouseholdId() : session.getHouseholdId();
        if (!householdId.equals(active)) {
            throw new IllegalStateException("Household changed. Reopen Share & Connect.");
        }
        HouseholdMemberEntity owner = dao.membershipNow(householdId, session.getUserId());
        if (owner == null || !HouseholdRole.OWNER.equals(owner.getRole())
                || !HouseholdMemberStatus.ACTIVE.equals(owner.getStatus())) {
            throw new IllegalStateException("Only the owner can invite members.");
        }
        UserEntity user = accounts.findByEmailNow(trim(email).toLowerCase(Locale.ROOT));
        if (user == null) {
            throw new IllegalArgumentException("This email has no local account yet. Share the invitation link instead.");
        }
        if (dao.membershipNow(householdId, user.getUserId()) != null) {
            throw new IllegalArgumentException("This account is already a member or invited.");
        }
        accounts.insertMemberNow(new HouseholdMemberEntity(householdId, user.getUserId(), HouseholdRole.MEMBER,
                HouseholdMemberStatus.INVITED, null));
    }

    private Object execute(Runnable operation, Continuation<? super Unit> completion) {
        return CoroutinesRoom.execute(database, true, () -> {
            operation.run();
            return Unit.INSTANCE;
        }, completion);
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }
}
