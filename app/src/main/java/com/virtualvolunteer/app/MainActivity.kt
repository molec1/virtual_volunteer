package com.virtualvolunteer.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.virtualvolunteer.app.databinding.ActivityMainBinding

/**
 * Single-activity host for Navigation Component destinations.
 *
 * Edge-to-edge, no shared AppBar: each destination draws its own large page title and (when it
 * isn't top-level) its own back button, styled to match. System back / up navigation is handled
 * automatically by [androidx.navigation.fragment.NavHostFragment]'s back stack integration.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
