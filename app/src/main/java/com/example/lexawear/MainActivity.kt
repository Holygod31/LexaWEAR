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

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    lateinit var bottomNav: BottomNavigationView

    private var tutorialManager: TutorialManager? = null
    var isTutorialNavigating = false

    private var preCameraTabId: Int = R.id.tab_care

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottom_navigation)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.tab_care
            loadFragment(CareFragment())
        }

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

        if (!TutorialManager.isCompleted(this)) {
            startTutorial()
        }
    }

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

    private fun applyLanguage(languageCode: String) {
        val locale = java.util.Locale(languageCode)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

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

    private fun startTutorial() {
        tutorialManager = TutorialManager(this) { tutorialManager = null }
        tutorialManager?.start()
    }

    fun shouldSuppressNfc(): Boolean {
        val tm = tutorialManager ?: return false
        return tm.isActive && !tm.nfcLiveThisStep
    }

    fun openCamera(source: CameraFragment.Source) {
        preCameraTabId = bottomNav.selectedItemId
        CameraFragment.source = source
        bottomNav.visibility = android.view.View.GONE
        loadFragment(CameraFragment())
    }

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

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

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

    fun loadFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

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

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}