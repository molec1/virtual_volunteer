package com.virtualvolunteer.app.ui.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Edge-to-edge helper: adds system bar insets on top of a view's existing XML padding/margin so
 * screens keep their designed spacing while avoiding the status bar and gesture navigation bar.
 */
object EdgeToEdgeInsets {

    /** Adds the status bar inset to [view]'s current top padding. */
    fun applyStatusBarPadding(view: View) {
        val initialTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = initialTop + top)
            insets
        }
    }

    /** Adds the navigation bar inset to [view]'s current bottom padding. */
    fun applyNavigationBarPadding(view: View) {
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = initialBottom + bottom)
            insets
        }
    }

    /** Adds the navigation bar inset to [view]'s current bottom margin (for FAB-style anchors). */
    fun applyNavigationBarMargin(view: View) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val initialBottom = params.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = initialBottom + bottom }
            insets
        }
    }

    /**
     * Adds the status bar (and, where present, display cutout) inset to [view]'s current top
     * margin. For controls pinned to the very top-start/top-end of an edge-to-edge, full-bleed
     * screen (e.g. an overlay button on a camera viewfinder) — otherwise they can end up
     * underneath the status bar / camera cutout and become unreachable.
     */
    fun applyStatusBarMargin(view: View) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val initialTop = params.topMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val barTypes = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            val top = insets.getInsets(barTypes).top
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = initialTop + top }
            insets
        }
    }
}
