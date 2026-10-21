package com.dpad.messaging.activities

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager

/**
 * Base activity that applies the user's chosen UI scale by overriding the
 * system font scale before inflation. All other activities extend this instead
 * of [AppCompatActivity] directly.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedScale: Float = 1.0f
    private var appliedAccent: String = Prefs.ACCENT_BLUE

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            setTheme(ThemeManager.activityThemeRes())
        } catch (_: Exception) {
            // Fallback to manifest theme during very early startup.
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        com.dpad.messaging.dumber.DumberSyncService.ensureStarted(this)
        val currentAccent = try { Prefs.get().appAccent } catch (_: Exception) { Prefs.ACCENT_BLUE }
        if (currentAccent != appliedAccent) {
            appliedAccent = currentAccent
            recreate()
            return
        }
        val currentScale = try { Prefs.get().uiScaleFactor } catch (_: Exception) { 1.0f }
        if (currentScale != appliedScale) {
            recreate()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Guard: Prefs may not yet be initialised during very early startup (SplashActivity).
        val scale = try { Prefs.get().uiScaleFactor } catch (_: Exception) { 1.0f }
        appliedScale = scale
        appliedAccent = try { Prefs.get().appAccent } catch (_: Exception) { Prefs.ACCENT_BLUE }
        if (scale == 1.0f) {
            super.attachBaseContext(newBase)
            return
        }
        val systemFontScale = Resources.getSystem().configuration.fontScale
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = systemFontScale * scale
        val scaledContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(scaledContext)
    }
}
