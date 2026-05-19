package com.example.lexawear

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * MainActivity — single-activity host for all fragments.
 *
 * Responsibilities:
 *  - NFC foreground dispatch: intercepts all tag scans and routes them to the active fragment
 *  - Fragment routing: swaps fragments in response to bottom nav selection or internal navigation
 *  - Language: applies the saved locale on every cold start via [attachBaseContext]
 *  - Tutorial: launches [TutorialManager] on first run; exposes [isTutorialNavigating] so
 *    programmatic tab switches don't get blocked by the tutorial's nav guard
 *  - Settings: floating button (top-right) gives access to tutorial replay and language picker
 *
 * ⚠ Root layout must remain FrameLayout (R.id.root_layout) — TutorialManager overlays
 *   its banner and highlight ring onto this container. Do not revert to LinearLayout.
 */
class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    lateinit var bottomNav: BottomNavigationView

    /** Non-null while the tutorial is running; set to null on completion. */
    private var tutorialManager: TutorialManager? = null

    /**
     * Set to true before any programmatic tab switch inside TutorialManager,
     * and back to false immediately after. Prevents the nav listener from
     * blocking tutorial-driven navigation.
     */
    var isTutorialNavigating = false

    /** Tab that was active before the camera opened — restored on camera close/cancel. */
    private var preCameraTabId: Int = R.id.tab_care

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottom_navigation)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Only set initial fragment on fresh start, not on config change.
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.tab_care
            loadFragment(CareFragment())
        }

        // Block tab switches while tutorial is active unless the switch was triggered
        // programmatically by TutorialManager itself (isTutorialNavigating == true).
        bottomNav.setOnItemSelectedListener { item ->
            if (tutorialManager?.isActive == true && !isTutorialNavigating) {
                return@setOnItemSelectedListener false
            }
            val fragment = when (item.itemId) {
                R.id.tab_care     -> CareFragment()
                R.id.tab_nfc      -> NfcFragment()
                R.id.tab_wardrobe -> WardrobeFragment()
                else -> return@setOnItemSelectedListener false
            }
            loadFragment(fragment)
            true
        }

        setupSettingsButton()

        // Launch tutorial automatically on first ever run.
        if (!TutorialManager.isCompleted(this)) {
            startTutorial()
        }
    }

    /**
     * Adds the floating Settings button to the top-right of the root FrameLayout.
     * Created programmatically so it stays above all fragment content and the
     * tutorial overlay regardless of which fragment is loaded.
     */
    private fun setupSettingsButton() {
        val root = findViewById<FrameLayout>(R.id.root_layout)
        val btnSettings = Button(this).apply {
            text = getString(R.string.settings)
            textSize = 14f
            contentDescription = getString(R.string.settings_content_description)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            lp.setMargins(0, 16.dpToPx(), 16.dpToPx(), 0)
            layoutParams = lp
            elevation = 8f
            setOnClickListener { showSettingsDialog() }
        }
        root.addView(btnSettings)
    }

    /**
     * Shows the two-item settings menu: tutorial replay and language picker.
     */
    private fun showSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setItems(arrayOf(
                getString(R.string.settings_replay_tutorial),
                getString(R.string.settings_select_language)
            )) { _, which ->
                when (which) {
                    0 -> { TutorialManager.reset(this); startTutorial() }
                    1 -> showLanguageDialog()
                }
            }
            .setNegativeButton(getString(R.string.settings_close), null)
            .show()
    }

    /**
     * Shows a single-choice language picker.
     * Saves the selection to SharedPreferences, applies it immediately,
     * then calls [recreate] so all string resources reload in the new locale.
     *
     * To add a new language: add its display name to [languages] and its
     * BCP-47 code to [languageCodes] at the same index, then create
     * res/values-xx/strings.xml.
     */
    private fun showLanguageDialog() {
        val languages = arrayOf("English", "Français")
        val languageCodes = arrayOf("en", "fr")

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val current = prefs.getString("language", "en")
        val currentIndex = languageCodes.indexOf(current).takeIf { it >= 0 } ?: 0

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_select_language))
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selected = languageCodes[which]
                prefs.edit().putString("language", selected).apply()
                applyLanguage(selected)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.settings_close), null)
            .show()
    }

    /**
     * Applies [languageCode] to the current resources configuration immediately.
     * This covers the current activity instance; [attachBaseContext] covers restarts.
     */
    private fun applyLanguage(languageCode: String) {
        val locale = java.util.Locale(languageCode)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * Wraps the base context with the saved locale before any resources are loaded.
     * Called by the system before [onCreate] — ensures the correct language is
     * applied even after an app restart without needing an explicit [recreate] call.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("language", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    /**
     * Creates and starts a new [TutorialManager] instance.
     * The completion callback nulls out [tutorialManager] so NFC suppression
     * and nav blocking are lifted once the tutorial finishes.
     */
    private fun startTutorial() {
        tutorialManager = TutorialManager(this) { tutorialManager = null }
        tutorialManager?.start()
    }

    /**
     * Returns true when an NFC scan should be ignored.
     * NFC is suppressed during the tutorial except on steps where a live
     * scan is intentionally part of the tutorial flow (e.g. the Care tab step).
     */
    fun shouldSuppressNfc(): Boolean {
        val tm = tutorialManager ?: return false
        return tm.isActive && !tm.nfcLiveThisStep
    }

    /**
     * Opens the camera for clothing or care label scanning.
     * Hides the bottom nav while the camera is active so it doesn't overlap
     * the camera controls. Saves the current tab so it can be restored on close.
     *
     * @param source Whether the result should be routed to [NfcFragment] (WRITE)
     *               or [CareFragment] (CARE).
     */
    fun openCamera(source: CameraFragment.Source) {
        preCameraTabId = bottomNav.selectedItemId
        CameraFragment.source = source
        bottomNav.visibility = android.view.View.GONE
        loadFragment(CameraFragment())
    }

    /**
     * Called by [CameraFragment] when analysis is complete.
     * Restores the bottom nav and routes the result fields to the originating fragment.
     *
     * @param fields  Map of LexaWEAR field codes → values (e.g. "T" → "JK").
     * @param source  Determines which fragment receives the results.
     */
    fun onCameraResults(fields: Map<String, String>, source: CameraFragment.Source) {
        bottomNav.visibility = android.view.View.VISIBLE
        when (source) {
            CameraFragment.Source.WRITE -> {
                bottomNav.selectedItemId = R.id.tab_nfc
                val nfcFragment = NfcFragment().apply { pendingVisionResults = fields }
                loadFragment(nfcFragment)
            }
            CameraFragment.Source.CARE -> {
                bottomNav.selectedItemId = R.id.tab_care
                val careFragment = CareFragment().apply { pendingVisionResults = fields }
                loadFragment(careFragment)
            }
        }
    }

    /**
     * Called when the user cancels out of the camera without taking a shot.
     * Restores the bottom nav and returns to the tab that was active before.
     */
    fun onCameraCancel() {
        bottomNav.visibility = android.view.View.VISIBLE
        bottomNav.selectedItemId = preCameraTabId
        val fragment = when (preCameraTabId) {
            R.id.tab_care     -> CareFragment()
            R.id.tab_nfc      -> NfcFragment()
            R.id.tab_wardrobe -> WardrobeFragment()
            else              -> CareFragment()
        }
        loadFragment(fragment)
    }

    /** Enables NFC foreground dispatch so tag scans are delivered to [onNewIntent]. */
    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    /** Disables NFC foreground dispatch when the app is backgrounded. */
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    /**
     * Receives NFC tag scans and routes them to whichever fragment is currently active.
     * NFC is suppressed during tutorial steps that don't involve live scanning.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            if (shouldSuppressNfc()) return
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                when (fragment) {
                    is NfcFragment      -> fragment.onTagDiscovered(tag)
                    is CareFragment     -> fragment.onTagDiscovered(tag)
                    is WardrobeFragment -> fragment.onTagDiscovered(tag)
                }
            }
        }
    }

    /**
     * Replaces the fragment container with [fragment].
     * Used by all internal navigation — bottom nav, camera results, tutorial steps.
     */
    fun loadFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /**
     * Adds a clothing item to the wardrobe from a raw tag string.
     * If the tutorial is active on a suppressed-nav step, notifies the tutorial
     * instead of performing the actual navigation (the tap counts as the target action).
     * If WardrobeFragment is already loaded, adds directly; otherwise navigates to it.
     *
     * @param raw The raw encoded tag string (e.g. "N:Jacket|T:JK|CL:2196F3|…").
     */
    fun addToWardrobe(raw: String) {
        if (tutorialManager?.isActive == true &&
            tutorialManager?.suppressNavigationThisStep == true) {
            tutorialManager?.onTargetTapped()
            return
        }
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (fragment is WardrobeFragment) {
            fragment.addItemFromRaw(raw)
        } else {
            bottomNav.selectedItemId = R.id.tab_wardrobe
            val wardrobeFragment = WardrobeFragment().apply { pendingRaw = raw }
            loadFragment(wardrobeFragment)
        }
    }

    /**
     * Applies filter codes from FilterFragment to the wardrobe.
     * Same tutorial-intercept logic as [addToWardrobe].
     *
     * @param type      Clothing type code e.g. "JK", or "" for no filter.
     * @param color     Hex colour code e.g. "2196F3", or "" for no filter.
     * @param season    Season code e.g. "W", or "" for no filter.
     * @param formality Formality code e.g. "C", or "" for no filter.
     */
    fun applyFiltersFromFilter(type: String, color: String, season: String, formality: String) {
        if (tutorialManager?.isActive == true &&
            tutorialManager?.suppressNavigationThisStep == true) {
            tutorialManager?.onTargetTapped()
            return
        }
        val wardrobeFragment = WardrobeFragment().apply {
            pendingFilters = arrayOf(type, color, season, formality)
        }
        bottomNav.selectedItemId = R.id.tab_wardrobe
        loadFragment(wardrobeFragment)
    }

    /** Converts dp to pixels using the current display density. */
    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}