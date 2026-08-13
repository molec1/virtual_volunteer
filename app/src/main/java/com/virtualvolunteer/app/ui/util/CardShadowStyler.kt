package com.virtualvolunteer.app.ui.util

import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.virtualvolunteer.app.R

/**
 * Applies the soft, brand-tinted ambient/spot shadow colors to a [MaterialCardView] in code.
 *
 * Setting `android:outlineAmbientShadowColor` / `outlineSpotShadowColor` purely via style XML
 * is not reliably honored on [MaterialCardView] — its own background/elevation setup can run
 * after attribute inflation and reset them. Applying them once, right after the view is
 * created, guarantees our color is the one actually used.
 */
object CardShadowStyler {
    fun applySoftShadow(card: MaterialCardView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ctx = card.context
            card.outlineAmbientShadowColor = ContextCompat.getColor(ctx, R.color.shadow_ambient_soft)
            card.outlineSpotShadowColor = ContextCompat.getColor(ctx, R.color.shadow_spot_soft)
        }
    }
}
