package com.labteto.dshmobile

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var hostsStore: HostsStore
    @Inject lateinit var notifications: DshNotifications
    @Inject lateinit var sessionStore: SessionStore

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — notifications degrade gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notifications.ensureChannels()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)

        // Apply the persisted in-app language (11 locales, incl. Thai/RTL).
        //
        // This only handles a *change* made while the app is running. Restoring the choice on
        // launch is AppCompat's job, via the `autoStoreLocales` service declared in the manifest:
        // it re-applies the stored locale in `attachBaseContext`, before anything is composed. That
        // ordering is the point. Below API 33 the per-app locale lives on the AppCompatActivity's
        // base context, and any window built from a context captured earlier keeps the device
        // language — which is how bottom sheets and dialogs ended up disagreeing with the rest of
        // the app. Setting it from here alone could never fix that; this call is deliberately after
        // onCreate, which is where the AppCompat contract requires it.
        //
        // Only set the locale when it actually differs, or the recreate it triggers loops.
        lifecycleScope.launch {
            hostsStore.settings.collect { settings ->
                applyNightMode(settings.themePreference)

                val desiredLocales = settings.localeOverride?.let { tag ->
                    LocaleListCompat.forLanguageTags(tag)
                } ?: LocaleListCompat.getEmptyLocaleList()
                
                val currentLocales = AppCompatDelegate.getApplicationLocales()
                
                // Only update if locales actually changed to prevent recreation loop
                if (desiredLocales.toLanguageTags() != currentLocales.toLanguageTags()) {
                    AppCompatDelegate.setApplicationLocales(desiredLocales)
                }
            }
        }

        revealFromIntent(intent)

        setContent {
            AppRoot()
        }
    }

    /**
     * A notification tapped while this activity is already running lands here, not in [onCreate].
     *
     * `launchMode="singleTask"` means the existing instance is reused for that tap, so without
     * this override the intent — and with it the session the notification was about — is dropped
     * on the floor.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        revealFromIntent(intent)
    }

    /**
     * The session a notification pointed at, in whichever of its two forms arrived.
     *
     * The extra is authoritative: the uri only exists so that two notifications about two sessions
     * are distinct pending intents, and a `dshmobile://` link from outside the app may carry no
     * extra at all.
     */
    private fun revealFromIntent(intent: Intent?) {
        val fromExtra = intent?.getStringExtra(DshNotifications.EXTRA_SESSION_ID)
        val fromUri = intent?.data
            ?.takeIf { it.scheme == "dshmobile" }
            ?.pathSegments
            ?.takeIf { it.size >= 2 && it[it.size - 2] == "session" }
            ?.last()
        val sessionId = fromExtra?.takeIf { it.isNotBlank() }
            ?: fromUri?.takeIf { it.isNotBlank() }
        if (sessionId != null) sessionStore.revealSession(sessionId)
    }

    /**
     * Keep the resource layer's scheme in step when the preference *changes* while running.
     *
     * [DshApplication.applyStoredNightMode] does the same thing at process start, which is where
     * the cost is zero; this only has to catch someone tapping Light or Dark. The guard is what
     * makes that true — without it, every settings emission would re-enter
     * [AppCompatDelegate.setDefaultNightMode] and recreate the activity.
     */
    private fun applyNightMode(themePreference: String) {
        val mode = DshApplication.nightModeFor(themePreference)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
