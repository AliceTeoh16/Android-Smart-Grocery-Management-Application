package com.xmum.smartgrocery.ui.pantrypal

import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View

/** Preserve the same native widget state that Fragment saved before these Compose page shells. */
internal fun saveLegacyUiState(root: View, outState: Bundle) {
    val hierarchy = SparseArray<Parcelable>()
    root.saveHierarchyState(hierarchy)
    outState.putSparseParcelableArray("feature_widget_hierarchy", hierarchy)
}

@Suppress("DEPRECATION") // Supports the app's existing API 24 minimum without changing dependencies.
internal fun restoreLegacyUiState(root: View, savedState: Bundle?) {
    savedState?.getSparseParcelableArray<Parcelable>("feature_widget_hierarchy")?.let(root::restoreHierarchyState)
}
