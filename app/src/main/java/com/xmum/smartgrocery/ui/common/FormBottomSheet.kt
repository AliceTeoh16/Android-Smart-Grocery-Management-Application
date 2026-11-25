package com.xmum.smartgrocery.ui.common

import com.google.android.material.bottomsheet.BottomSheetBehavior

/** Form content owns vertical gestures. Close/Cancel and system Back still dismiss normally. */
internal fun BottomSheetBehavior<*>.configureFormScrolling() {
    isDraggable = false
    skipCollapsed = true
    state = BottomSheetBehavior.STATE_EXPANDED
}
