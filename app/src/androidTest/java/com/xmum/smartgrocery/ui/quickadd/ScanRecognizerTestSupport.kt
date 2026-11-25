package com.xmum.smartgrocery.ui.quickadd

import android.net.Uri
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

internal fun IngredientScanViewModel.setFoodRecognizer(recognize: suspend (Uri) -> List<VisualFoodItem>) {
    useRecognizer { uri, continuation -> recognize.startCoroutineUninterceptedOrReturn(uri, continuation) }
}
