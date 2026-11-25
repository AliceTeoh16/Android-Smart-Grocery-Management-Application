package com.xmum.smartgrocery.utils

import android.content.SharedPreferences
import java.util.concurrent.Callable
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface SuspendCall<T> {
    fun invoke(continuation: Continuation<T>): Any?
}

fun interface SuspendTransform<T, R> {
    fun invoke(value: T, continuation: Continuation<R>): Any?
}

fun interface SuspendIndexedAction<T> {
    fun invoke(index: Int, value: T, continuation: Continuation<Unit>): Any?
}

/** Connects Java callers to the coroutine APIs used by Android lifecycle and Room. */
object AndroidCoroutines {
    /** Offload read-only presentation work; collectors retain their original UI context. */
    @JvmStatic
    fun <T> background(source: Flow<T>): Flow<T> = source.flowOn(Dispatchers.Default)

    suspend fun <T> await(call: SuspendCall<T>): T =
        suspendCoroutineUninterceptedOrReturn { call.invoke(it) }

    @JvmStatic
    fun <T> preferenceSnapshots(preferences: SharedPreferences, read: Supplier<T>): Flow<T> =
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read.get()) }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(read.get())
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()

    @JvmStatic
    suspend fun <T, R> then(call: SuspendCall<T>, transform: SuspendTransform<T, R>): R {
        val value = await(call)
        return suspendCoroutineUninterceptedOrReturn { transform.invoke(value, it) }
    }

    @JvmStatic
    suspend fun <T> onIo(operation: Callable<T>): T =
        withContext(Dispatchers.IO) { operation.call() }

    @JvmStatic
    suspend fun <T> onIoInterruptible(operation: Callable<T>): T =
        runInterruptible(Dispatchers.IO) { operation.call() }

    @JvmStatic
    fun ioScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun <T> launch(scope: CoroutineScope, call: SuspendCall<T>): Job =
        scope.launch { await(call) }

    @JvmStatic
    fun <T> launchWithError(scope: CoroutineScope, call: SuspendCall<T>, failure: Consumer<Throwable>): Job =
        scope.launch {
            try {
                await(call)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.accept(error)
            }
        }

    @JvmStatic
    fun newCompletion(): CompletableDeferred<Unit> = CompletableDeferred()

    @JvmStatic
    suspend fun <T> withLock(mutex: Mutex, call: SuspendCall<T>): T =
        mutex.withLock { await(call) }

    @JvmStatic
    suspend fun <T> withFinally(call: SuspendCall<T>, finish: Runnable): T {
        try {
            return await(call)
        } finally {
            finish.run()
        }
    }

    @JvmStatic
    fun <T> combineStates(sources: List<Flow<*>>, mapper: Function<Array<Any?>, T>): Flow<T> =
        combine(*sources.toTypedArray()) { mapper.apply(it) }

    @JvmStatic
    suspend fun <T> mapFailure(call: SuspendCall<T>, mapper: Function<Exception, Throwable>): T {
        return try {
            await(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw mapper.apply(error)
        }
    }

    @JvmStatic
    suspend fun <T> recoverWith(call: SuspendCall<T>, failure: SuspendTransform<Exception, T>): T {
        return try {
            await(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            suspendCoroutineUninterceptedOrReturn { failure.invoke(error, it) }
        }
    }

    @JvmStatic
    suspend fun <T> recoverCall(call: SuspendCall<T>, fallback: Function<Exception, T>): T =
        recoverWith(call) { error, _ -> fallback.apply(error) }

    @JvmStatic
    suspend fun <T> forEachAsync(values: Iterable<T>, action: SuspendTransform<T, Unit>) {
        for (value in values) {
            suspendCoroutineUninterceptedOrReturn<Unit> { action.invoke(value, it) }
        }
    }

    @JvmStatic
    suspend fun <T> forEachIndexedAsync(values: List<T>, action: SuspendIndexedAction<T>) {
        values.forEachIndexed { index, value ->
            suspendCoroutineUninterceptedOrReturn<Unit> { action.invoke(index, value, it) }
        }
    }

    @JvmStatic
    fun <T> observe(scope: CoroutineScope, source: Flow<T>, observer: Consumer<T>) {
        scope.launch { source.collect { observer.accept(it) } }
    }

    @JvmStatic
    suspend fun <T> collectValues(source: Flow<T>, observer: SuspendTransform<T, Unit>) {
        source.collect { value -> suspendCoroutineUninterceptedOrReturn<Unit> { observer.invoke(value, it) } }
    }

    @JvmStatic
    suspend fun <T> collectLatestValues(source: Flow<T>, observer: SuspendTransform<T, Unit>) {
        source.collectLatest { value -> suspendCoroutineUninterceptedOrReturn<Unit> { observer.invoke(value, it) } }
    }

    @JvmStatic
    fun <T> recoverFlow(source: Flow<T>, fallback: Function<Throwable, T>): Flow<T> =
        source.catch { emit(fallback.apply(it)) }

    @JvmStatic
    fun clock(intervalMillis: Long): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(intervalMillis)
        }
    }

    @JvmStatic
    fun <T> collectSilently(scope: CoroutineScope, updates: Flow<*>, call: SuspendCall<T>) {
        scope.launch { updates.collect { ignoreFailure(call) } }
    }

    @JvmStatic
    fun <T> repeatSilently(scope: CoroutineScope, intervalMillis: Long, call: SuspendCall<T>) {
        scope.launch {
            while (isActive) {
                ignoreFailure(call)
                delay(intervalMillis)
            }
        }
    }

    private suspend fun <T> ignoreFailure(call: SuspendCall<T>) {
        try {
            await(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The sharing service records the failure in its status before rethrowing.
        }
    }

    @JvmStatic
    fun <T> launchWithMessage(
        scope: CoroutineScope,
        messages: MutableSharedFlow<String>,
        call: SuspendCall<T>,
        success: Function<T, String?>,
        fallbackError: String,
    ) {
        scope.launch {
            val message = try {
                success.apply(await(call))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                error.message ?: fallbackError
            }
            if (message != null) messages.emit(message)
        }
    }

    @JvmStatic
    fun emit(scope: CoroutineScope, messages: MutableSharedFlow<String>, message: String) {
        scope.launch { messages.emit(message) }
    }
}
