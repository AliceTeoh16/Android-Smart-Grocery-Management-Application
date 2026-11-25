package com.xmum.smartgrocery.data.session

import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.utils.SuspendCall
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

@Suppress("UNCHECKED_CAST")
suspend fun <T> SessionManager.withInventoryDestination(target: DataScope, action: suspend () -> T): T =
    runInInventoryDestination(target, SuspendCall { action.startCoroutineUninterceptedOrReturn(it) }) as T
