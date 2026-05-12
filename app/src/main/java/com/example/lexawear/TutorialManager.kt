package com.example.lexawear

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * TutorialManager — Lightroom-style tutorial.
 *
 * Layout:
 *  - Thin instruction banner pinned to the top (below status bar)
 *  - A highlight ring drawn around the target button
 *  - A small floating "Skip" pill bottom-right that doesn't block content
 *  - All non-target buttons disabled (alpha 0.35), target at full alpha
 *  - Info-only steps (no target) re-enable nothing; only the Skip pill works
 */
class TutorialManager(
    private val activity: AppCompatActivity,
    private val onComplete: () -> Unit
) {
    companion object {
        private const val PREFS  = "tutorial"
        private const val KEY_DONE = "completed"

        fun isCompleted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)

        fun reset(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, false).apply()
    }

    data class TutorialStep(
        val tab: Int,
        val title: String,
        val description: String,
        val targetViewId: Int?,           // null = info card only
        val nfcLive: Boolean = false,
        val suppressNavigation: Boolean = false,
        val loadFilterFragment: Boolean = false,
        val keepFilterFragment: Boolean = false  // don't reload tab fragment
    )

    private val steps = listOf(
        // ── Care tab ─────────────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_care,
            title = "1 of 15 — Care Tab",
            description = "This is the Care tab. Hold your phone to a clothing tag to read its care instructions. You can also tap the Scan Care Label button to read a label with the camera. Tap Got it to continue.",
            targetViewId = null,
            nfcLive = true
        ),
        TutorialStep(
            tab = R.id.tab_care,
            title = "2 of 15 — Add to Wardrobe",
            description = "After scanning a tag the Add to Wardrobe button appears at the bottom. Find it and tap it.",
            targetViewId = R.id.btn_add_to_wardrobe,
            nfcLive = true,
            suppressNavigation = true
        ),
        // ── Write tab ────────────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_nfc,
            title = "3 of 14 — Write Tab",
            description = "This is the Write tab. Answer questions about a clothing item then hold your phone to a blank NFC tag to save it. You can also tap the camera button to pre-fill fields automatically. Tap Got it to continue.",
            targetViewId = null
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            title = "4 of 14 — Microphone",
            description = "Find the microphone button and tap it to speak your answer instead of typing.",
            targetViewId = R.id.btn_mic,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            title = "5 of 14 — Camera",
            description = "Find the camera button and tap it to scan your clothing item and pre-fill fields automatically.",
            targetViewId = R.id.btn_camera,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            title = "6 of 14 — Next",
            description = "Find the Next button and tap it to confirm your answer and move to the next question.",
            targetViewId = R.id.btn_next,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            title = "7 of 14 — Skip",
            description = "Find the Skip button and tap it to leave a question blank and move on.",
            targetViewId = R.id.btn_skip,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            title = "8 of 14 — Back",
            description = "Find the Back button and tap it to return to the previous question.",
            targetViewId = R.id.btn_back,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            title = "9 of 15 — Editing Tags",
            description = "Tip: scan an existing LexaWEAR tag on this screen to load and edit it. Tap Got it to continue.",
            targetViewId = null
        ),
        // ── Wardrobe tab ─────────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_wardrobe,
            title = "10 of 15 — Wardrobe Tab",
            description = "This is your Wardrobe. Tagged clothing items appear here. Find the Scan Tag button at the bottom and tap it.",
            targetViewId = R.id.btn_wardrobe_scan,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            title = "11 of 15 — Filters",
            description = "Find the Filters button and tap it to open the filter screen.",
            targetViewId = R.id.btn_open_filters,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            title = "12 of 15 — Voice Filter",
            description = "Find the voice filter microphone and tap it to filter your wardrobe by speaking.",
            targetViewId = R.id.btn_voice_filter,
            suppressNavigation = true
        ),
        // ── Filter fragment (loaded directly, no tab switch) ─────────────────
        TutorialStep(
            tab = R.id.tab_wardrobe,
            title = "13 of 15 — Filter Arrows",
            description = "This is the Filter screen. Arrow buttons cycle through options. Find any Next arrow and tap it.",
            targetViewId = R.id.btn_type_next,
            loadFilterFragment = true,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            title = "14 of 15 — Apply Filters",
            description = "Find the Apply button and tap it to apply your filters.",
            targetViewId = R.id.btn_filter_apply,
            keepFilterFragment = true,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            title = "15 of 15 — Clear Filters",
            description = "Find the Clear button and tap it to reset all filters.",
            targetViewId = R.id.btn_filter_clear,
            keepFilterFragment = true,
            suppressNavigation = true
        )
    )

    var isActive = false
        private set
    var nfcLiveThisStep = false
        private set
    var suppressNavigationThisStep = false
        private set

    private var currentStepIndex = 0
    private var bannerView: View? = null
    private var highlightView: View? = null

    // Every interactive button ID across all fragments
    private val allButtonIds = listOf(
        R.id.btn_add_to_wardrobe,
        R.id.btn_camera_care,
        R.id.btn_mic, R.id.btn_camera, R.id.btn_next, R.id.btn_skip, R.id.btn_back, R.id.btn_write,
        R.id.btn_wardrobe_scan, R.id.btn_open_filters, R.id.btn_voice_filter,
        R.id.btn_type_prev, R.id.btn_type_next,
        R.id.btn_color_prev, R.id.btn_color_next,
        R.id.btn_season_prev, R.id.btn_season_next,
        R.id.btn_formality_prev, R.id.btn_formality_next,
        R.id.btn_filter_apply, R.id.btn_filter_clear
    )

    fun start() {
        isActive = true
        currentStepIndex = 0
        showStep(0)
    }

    fun onTargetTapped() {
        advance()
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) { complete(); return }

        val step = steps[index]
        nfcLiveThisStep = step.nfcLive
        suppressNavigationThisStep = step.suppressNavigation

        val main = activity as? MainActivity

        // Tab switch — only if not keeping the current fragment
        if (!step.keepFilterFragment) {
            main?.isTutorialNavigating = true
            main?.bottomNav?.selectedItemId = step.tab
            main?.isTutorialNavigating = false
        }

        // Load FilterFragment explicitly for filter steps
        if (step.loadFilterFragment) {
            main?.loadFragment(FilterFragment())
        }

        removeTutorialViews()

        activity.window.decorView.postDelayed({
            if (!isActive) return@postDelayed

            // Step index 1 = Add to Wardrobe — show button even without a scan
            if (index == 1) {
                val frag = main?.supportFragmentManager
                    ?.findFragmentById(R.id.fragment_container)
                if (frag is CareFragment) frag.showAddToWardrobeForTutorial()
            }

            // On Write tab steps, make Skip visible even on step 1 (Name)
            // so TalkBack can navigate to it. It stays disabled as a non-target.
            if (index in 3..7) {
                val skipBtn = activity.findViewById<View>(R.id.btn_skip)
                skipBtn?.visibility = View.VISIBLE
            }

            applyButtonStates(step)
            buildBanner(step)
            if (step.targetViewId != null) buildHighlight(step.targetViewId)
        }, 380)
    }

    // ── Button state management ───────────────────────────────────────────────

    private fun applyButtonStates(step: TutorialStep) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        allButtonIds.forEach { id ->
            val view = root.findViewById<View>(id) ?: return@forEach
            val isTarget = id == step.targetViewId
            // Info steps: all disabled except nothing (only skip pill works)
            view.isEnabled = isTarget
            view.alpha = if (isTarget) 1f else 0.35f
        }

        // Wire target
        if (step.targetViewId != null) {
            root.findViewById<View>(step.targetViewId)?.setOnClickListener {
                restoreButtonStates()
                advance()
            }
        }
    }

    private fun restoreButtonStates() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        allButtonIds.forEach { id ->
            val view = root.findViewById<View>(id) ?: return@forEach
            view.isEnabled = true
            view.alpha = 1f
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    /** Banner pinned just below the status bar, with Skip/Got it inline. */
    private fun buildBanner(step: TutorialStep) {
        val root = activity.findViewById<FrameLayout>(R.id.root_layout) ?: return

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 20, 20, 20))
            setPadding(32.dpToPx(), 20.dpToPx(), 32.dpToPx(), 16.dpToPx())
            elevation = 20f
            isClickable = false
            isFocusable = false
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            lp.setMargins(32.dpToPx(), 0, 32.dpToPx(), 0)
            layoutParams = lp
        }

        val tvTitle = TextView(activity).apply {
            text = step.title
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 4.dpToPx())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val tvDesc = TextView(activity).apply {
            text = step.description
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12.dpToPx())
            contentDescription = "${step.title}. ${step.description}"
            isFocusable = true
        }

        val skipLabel = if (step.targetViewId == null) "Got it" else "Skip"
        val skipDesc  = if (step.targetViewId == null)
            "Got it. Double tap to go to next step."
        else
            "Skip this step. Double tap to continue."

        val btnSkipInBanner = Button(activity).apply {
            text = skipLabel
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 70, 70, 70))
            elevation = 22f
            minHeight = 56.dpToPx()
            contentDescription = skipDesc
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.END
            layoutParams = lp
            setOnClickListener {
                restoreButtonStates()
                advance()
            }
        }

        container.addView(tvTitle)
        container.addView(tvDesc)
        container.addView(btnSkipInBanner)

        root.addView(container)
        bannerView = container

        container.postDelayed({
            tvDesc.announceForAccessibility("${step.title}. ${step.description}. $skipLabel button below.")
        }, 500)
    }

    /** Colored ring drawn around the target button. */
    private fun buildHighlight(targetId: Int) {
        val root = activity.findViewById<FrameLayout>(R.id.root_layout) ?: return
        val target = activity.findViewById<View>(targetId) ?: return

        // Get target position relative to root
        val targetRect = Rect()
        target.getGlobalVisibleRect(targetRect)
        val rootRect = Rect()
        root.getGlobalVisibleRect(rootRect)

        val left   = targetRect.left   - rootRect.left   - 8.dpToPx()
        val top    = targetRect.top    - rootRect.top    - 8.dpToPx()
        val width  = targetRect.width()  + 16.dpToPx()
        val height = targetRect.height() + 16.dpToPx()

        val ring = View(activity).apply {
            val border = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(4.dpToPx(), Color.parseColor("#FFD600"))
                cornerRadius = 12.dpToPx().toFloat()
            }
            background = border
            elevation = 18f
            isClickable = false
            isFocusable = false
            val lp = FrameLayout.LayoutParams(width, height)
            lp.leftMargin = left
            lp.topMargin  = top
            layoutParams = lp
        }

        root.addView(ring)
        highlightView = ring
    }

    private fun removeTutorialViews() {
        val root = activity.findViewById<FrameLayout>(R.id.root_layout)
        bannerView?.let    { root?.removeView(it) }; bannerView    = null
        highlightView?.let { root?.removeView(it) }; highlightView = null
    }

    private fun advance() {
        removeTutorialViews()
        currentStepIndex++
        if (currentStepIndex >= steps.size) complete() else showStep(currentStepIndex)
    }

    private fun complete() {
        isActive = false
        nfcLiveThisStep = false
        suppressNavigationThisStep = false
        removeTutorialViews()
        restoreButtonStates()

        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()

        // Return to Care tab
        val main = activity as? MainActivity
        main?.isTutorialNavigating = true
        main?.bottomNav?.selectedItemId = R.id.tab_care
        main?.isTutorialNavigating = false
        main?.loadFragment(CareFragment())

        // Show completion banner + done pill
        val root = activity.findViewById<FrameLayout>(R.id.root_layout) ?: run {
            onComplete(); return
        }

        val banner = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 20, 20, 20))
            setPadding(32.dpToPx(), 24.dpToPx(), 32.dpToPx(), 20.dpToPx())
            elevation = 20f
            isClickable = false
            isFocusable = false
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            lp.setMargins(32.dpToPx(), 0, 32.dpToPx(), 0)
            layoutParams = lp
        }

        val tvDone = TextView(activity).apply {
            text = "You're all set!"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8.dpToPx())
            isFocusable = true
            contentDescription = "Tutorial complete. You're all set!"
        }

        val tvSub = TextView(activity).apply {
            text = "You now know the full layout of LexaWEAR. Replay this tutorial anytime from the Settings button at the top."
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 0, 0, 12.dpToPx())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val btnDone = Button(activity).apply {
            text = "Start using LexaWEAR"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 70, 70, 70))
            elevation = 22f
            minHeight = 56.dpToPx()
            contentDescription = "Start using LexaWEAR. Double tap to close tutorial."
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.END
            layoutParams = lp
            setOnClickListener {
                root.removeView(banner)
                onComplete()
            }
        }

        banner.addView(tvDone)
        banner.addView(tvSub)
        banner.addView(btnDone)
        root.addView(banner)

        banner.postDelayed({
            tvDone.announceForAccessibility("Tutorial complete. You're all set! You can replay this tutorial anytime from the Settings button.")
        }, 500)
    }

    private fun Int.dpToPx(): Int =
        (this * activity.resources.displayMetrics.density).toInt()
}