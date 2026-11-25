package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.Query;
import androidx.room.RoomDatabase;

import java.util.concurrent.Callable;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

/** Common scoped queries and coroutine dispatch for the Room DAOs. */
public abstract class ScopedDao {
    private final RoomDatabase database;

    protected ScopedDao(@NonNull RoomDatabase database) {
        this.database = database;
    }

    @Nullable
    @Query(ScopeSql.ACTIVE_SCOPE)
    protected abstract DataScope readScope();

    @NonNull
    protected final DataScope requireScopeNow() {
        DataScope scope = readScope();
        if (scope == null) {
            throw new IllegalStateException("Sign in and select a valid data scope first.");
        }
        return scope;
    }

    @Nullable
    protected final <T> Object execute(boolean transaction, Callable<T> operation, Continuation<? super T> continuation) {
        return CoroutinesRoom.execute(database, transaction, operation, continuation);
    }

    @NonNull
    protected final Object executeWrite(Runnable operation, Continuation<? super Unit> continuation) {
        return execute(true, () -> {
            operation.run();
            return Unit.INSTANCE;
        }, continuation);
    }
}
