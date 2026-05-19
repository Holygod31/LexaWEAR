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
 * TutorialManager — Lightroom-style step-by-step tutorial overlay.
 *
 * Layout per step:
 *  - Instruction banner (title + description + Skip/Got it pill) centred on screen.
 *  - Yellow highlight ring drawn around the target button, if any.
 *  - All non-target buttons dimmed (alpha 0.35) and disabled; target at full alpha.
 *  - Info-only steps (targetViewId = null) re-enable nothing; only the pill advances.
 *
 * Navigation contract with MainActivity:
 *  - [MainActivity.isTutorialNavigating] must be set true before and false after
 *    any programmatic tab switch here, to suppress the normal fragment-load callback.
 *  - [suppressNavigationThisStep] tells MainActivity to ignore the user's own tab taps.
 *
 * Total steps: 15. If the step list changes, update all tutorial_title_N strings.
 */
class TutorialManager(
    private val activity: AppCompatActivity,
    private val onComplete: () -> Unit   // called after the user dismisses the completion banner
) {
    companion object {
        private const val PREFS    = "tutorial"
        private const val KEY_DONE = "completed"

        /** Returns true if the tutorial has been completed on this device. */
        fun isCompleted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)

        /** Resets the completed flag — used for dev/testing to re-trigger the tutorial. */
        fun reset(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, false).apply()
    }

    /**
     * Describes a single tutorial step.
     *
     * @param tab                 Bottom-nav item ID to select before showing this step.
     * @param targetViewId        View to highlight and enable; null for info-only steps.
     * @param nfcLive             If true, NFC tag discovery is forwarded to the active fragment
     *                            (used during Care-tab steps so a real scan can be demonstrated).
     * @param suppressNavigation  If true, MainActivity ignores user tab taps this step.
     * @param loadFilterFragment  If true, FilterFragment is loaded into the container before
     *                            the step is shown (step 12 — first filter step).
     * @param keepFilterFragment  If true, the tab is not reloaded — FilterFragment stays on
     *                            screen (steps 13–14 stay inside the filter UI).
     */
    data class TutorialStep(
        val tab: Int,
        val titleRes: Int,
        val descriptionRes: Int,
        val targetViewId: Int?,
        val nfcLive: Boolean = false,
        val suppressNavigation: Boolean = false,
        val loadFilterFragment: Boolean = false,
        val keepFilterFragment: Boolean = false
    )

    /**
     * 15 tutorial steps across four sections: Care, Write, Wardrobe, Filter.
     * ⚠ Step count is 15 — update all tutorial_title_N / tutorial_desc_N strings
     * if steps are added or removed.
     */
    private val steps = listOf(
        // ── Care tab ──────────────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_care,
            titleRes = R.string.tutorial_title_1,
            descriptionRes = R.string.tutorial_desc_1,
            targetViewId = null,
            nfcLive = true          // allow real tag scan to demonstrate Care tab
        ),
        TutorialStep(
            tab = R.id.tab_care,
            titleRes = R.string.tutorial_title_2,
            descriptionRes = R.string.tutorial_desc_2,
            targetViewId = R.id.btn_add_to_wardrobe,
            nfcLive = true,
            suppressNavigation = true
        ),
        // ── Write tab ─────────────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_nfc,
            titleRes = R.string.tutorial_title_3,
            descriptionRes = R.string.tutorial_desc_3,
            targetViewId = null     // info-only intro to the Write tab
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            titleRes = R.string.tutorial_title_4,
            descriptionRes = R.string.tutorial_desc_4,
            targetViewId = R.id.btn_next,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            titleRes = R.string.tutorial_title_5,
            descriptionRes = R.string.tutorial_desc_5,
            targetViewId = R.id.btn_skip,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            titleRes = R.string.tutorial_title_6,
            descriptionRes = R.string.tutorial_desc_6,
            targetViewId = R.id.btn_back,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            titleRes = R.string.tutorial_title_7,
            descriptionRes = R.string.tutorial_desc_7,
            targetViewId = R.id.btn_camera,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_nfc,
            titleRes = R.string.tutorial_title_8,
            descriptionRes = R.string.tutorial_desc_8,
            targetViewId = null     // info-only NFC write prompt
        ),
        // ── Wardrobe tab ──────────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_9,
            descriptionRes = R.string.tutorial_desc_9,
            targetViewId = R.id.btn_wardrobe_scan,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_10,
            descriptionRes = R.string.tutorial_desc_10,
            targetViewId = R.id.btn_open_filters,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_11,
            descriptionRes = R.string.tutorial_desc_11,
            targetViewId = R.id.btn_voice_filter,
            suppressNavigation = true
        ),
        // ── Filter fragment ───────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_12,
            descriptionRes = R.string.tutorial_desc_12,
            targetViewId = R.id.btn_type_next,
            loadFilterFragment = true,  // load FilterFragment before this step is shown
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_13,
            descriptionRes = R.string.tutorial_desc_13,
            targetViewId = R.id.btn_filter_apply,
            keepFilterFragment = true,  // stay in FilterFragment — don't reload the tab
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_14,
            descriptionRes = R.string.tutorial_desc_14,
            targetViewId = R.id.btn_filter_clear,
            keepFilterFragment = true,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_15,
            descriptionRes = R.string.tutorial_desc_15,
            targetViewId = null     // info-only completion step
        )
    )

    /** True while the tutorial overlay is visible and active. */
    var isActive = false
        private set

    /**
     * Reflects [TutorialStep.nfcLive] for the current step.
     * Read by MainActivity to decide whether to forward NFC tag intents
     * to the active fragment during the tutorial.
     */
    var nfcLiveThisStep = false
        private set

    /**
     * Reflects [TutorialStep.suppressNavigation] for the current step.
     * Read by MainActivity to block user tab taps that would break the flow.
     */
    var suppressNavigationThisStep = false
        private set

    private var currentStepIndex = 0
    private var bannerView: View? = null
    private var highlightView: View? = null

    /**
     * All button IDs that the tutorial can enable/disable.
     * Buttons not in this list are unaffected by [applyButtonStates].
     * Add new buttons here if they appear in future tutorial steps.
     */
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

    /** Starts the tutorial from step 0. Safe to call if already complete — re-runs it. */
    fun start() {
        isActive = true
        currentStepIndex = 0
        showStep(0)
    }

    /**
     * Called by MainActivity when the user physically taps the highlighted target.
     * Advances to the next step. (Banner Skip pill uses the same [advance] path.)
     */
    fun onTargetTapped() { advance() }

    /**
     * Renders the given step: switches tabs (unless keepFilterFragment),
     * loads FilterFragment if requested, then posts a 380 ms delay before
     * building the overlay. The delay lets the fragment layout settle so
     * [buildHighlight] can obtain accurate view coordinates.
     */
    private fun showStep(index: Int) {
        if (index >= steps.size) { complete(); return }

        val step = steps[index]
        nfcLiveThisStep = step.nfcLive
        suppressNavigationThisStep = step.suppressNavigation

        val main = activity as? MainActivity

        if (!step.keepFilterFragment) {
            // ⚠ isTutorialNavigating must bracket every programmatic tab switch
            // to prevent MainActivity's onTabSelected from loading a fresh fragment.
            main?.isTutorialNavigating = true
            main?.bottomNav?.selectedItemId = step.tab
            main?.isTutorialNavigating = false
        }

        if (step.loadFilterFragment) main?.loadFragment(FilterFragment())

        removeTutorialViews()

        // 380 ms delay — fragment layout must be complete before view coordinates are read.
        activity.window.decorView.postDelayed({
            if (!isActive) return@postDelayed  // tutorial was cancelled during the delay

            // Step 1 (index=1): expose "Add to Wardrobe" button without a real NFC scan.
            if (index == 1) {
                val frag = main?.supportFragmentManager
                    ?.findFragmentById(R.id.fragment_container)
                if (frag is CareFragment) frag.showAddToWardrobeForTutorial()
            }

            // Steps 3–7 (Write tab): ensure Skip button is visible (hidden by default at step 0).
            if (index in 3..7) {
                activity.findViewById<View>(R.id.btn_skip)?.visibility = View.VISIBLE
            }

            applyButtonStates(step)
            buildBanner(step)
            if (step.targetViewId != null) buildHighlight(step.targetViewId)
        }, 380)
    }

    // ── Button state management ───────────────────────────────────────────────

    /**
     * Dims and disables all tutorial-managed buttons except the step's target.
     * Also wires the target's click listener to advance the tutorial.
     * [restoreButtonStates] must be called before advancing to prevent the
     * next step's buttons from starting dimmed.
     */
    private fun applyButtonStates(step: TutorialStep) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        allButtonIds.forEach { id ->
            val view = root.findViewById<View>(id) ?: return@forEach
            val isTarget = id == step.targetViewId
            view.isEnabled = isTarget
            view.alpha = if (isTarget) 1f else 0.35f
        }
        if (step.targetViewId != null) {
            // Override the target's normal click handler for the duration of this step.
            root.findViewById<View>(step.targetViewId)?.setOnClickListener {
                restoreButtonStates()
                advance()
            }
        }
    }

    /** Re-enables all tutorial-managed buttons at full alpha. Called before each step advance. */
    private fun restoreButtonStates() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        allButtonIds.forEach { id ->
            val view = root.findViewById<View>(id) ?: return@forEach
            view.isEnabled = true
            view.alpha = 1f
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    /**
     * Adds the instruction banner to [R.id.root_layout].
     * Banner contains: step title (small, muted), description (large, white),
     * and a Skip/Got-it pill. Info-only steps show "Got it"; target steps show "Skip".
     * TalkBack announces the full step text 500 ms after the banner is added,
     * giving the layout time to settle before the announcement fires.
     */
    private fun buildBanner(step: TutorialStep) {
        val root = activity.findViewById<FrameLayout>(R.id.root_layout) ?: return

        val title       = activity.getString(step.titleRes)
        val description = activity.getString(step.descriptionRes)
        // "Got it" for info-only steps (no target to tap); "Skip" when there's a target.
        val skipLabel   = if (step.targetViewId == null)
            activity.getString(R.string.tutorial_got_it)
        else
            activity.getString(R.string.tutorial_skip)
        val skipDesc    = if (step.targetViewId == null)
            activity.getString(R.string.tutorial_got_it_description)
        else
            activity.getString(R.string.tutorial_skip_description)

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 20, 20, 20))
            setPadding(32.dpToPx(), 20.dpToPx(), 32.dpToPx(), 16.dpToPx())
            elevation = 20f
            isClickable = false   // passthrough — don't block touches to the target behind it
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
            text = title
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 4.dpToPx())
            // Title is merged with the description in tvDesc's contentDescription — skip it.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val tvDesc = TextView(activity).apply {
            text = description
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12.dpToPx())
            // Combined title + description so TalkBack reads both in one announcement.
            contentDescription = "$title. $description"
            isFocusable = true
        }

        val btnSkipInBanner = Button(activity).apply {
            text = skipLabel
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 70, 70, 70))
            elevation = 22f        // above banner elevation so it receives touches reliably
            minHeight = 56.dpToPx()
            contentDescription = skipDesc
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.END
            layoutParams = lp
            setOnClickListener { restoreButtonStates(); advance() }
        }

        container.addView(tvTitle)
        container.addView(tvDesc)
        container.addView(btnSkipInBanner)
        root.addView(container)
        bannerView = container

        // 500 ms delay — ensures layout is complete and screen reader focus has settled.
        container.postDelayed({
            tvDesc.announceForAccessibility(
                "$title. $description. $skipLabel ${activity.getString(R.string.tutorial_button_below)}"
            )
        }, 500)
    }

    /**
     * Draws a yellow rounded-rectangle ring around [targetId] by reading its
     * screen coordinates and positioning a transparent [View] with a stroke drawable
     * over it in [R.id.root_layout]. The ring is 8 dp larger on each side than the target.
     *
     * ⚠ Must be called after the 380 ms layout delay in [showStep] — calling before
     * the fragment has laid out returns a zero-size rect and produces no visible ring.
     */
    private fun buildHighlight(targetId: Int) {
        val root   = activity.findViewById<FrameLayout>(R.id.root_layout) ?: return
        val target = activity.findViewById<View>(targetId) ?: return

        // Convert target screen coords to root-relative coords for FrameLayout positioning.
        val targetRect = Rect(); target.getGlobalVisibleRect(targetRect)
        val rootRect   = Rect(); root.getGlobalVisibleRect(rootRect)

        val left   = targetRect.left   - rootRect.left   - 8.dpToPx()
        val top    = targetRect.top    - rootRect.top    - 8.dpToPx()
        val width  = targetRect.width()  + 16.dpToPx()
        val height = targetRect.height() + 16.dpToPx()

        val ring = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(4.dpToPx(), Color.parseColor("#FFD600"))  // yellow ring
                cornerRadius = 12.dpToPx().toFloat()
            }
            elevation = 18f        // below banner (20f) but above normal UI
            isClickable = false    // ring must not intercept touches intended for the target
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = left
                topMargin  = top
            }
        }

        root.addView(ring)
        highlightView = ring
    }

    /** Removes both the banner and highlight ring from the layout. Safe to call if not present. */
    private fun removeTutorialViews() {
        val root = activity.findViewById<FrameLayout>(R.id.root_layout)
        bannerView?.let    { root?.removeView(it) }; bannerView    = null
        highlightView?.let { root?.removeView(it) }; highlightView = null
    }

    /** Removes overlay, increments step index, and either shows the next step or completes. */
    private fun advance() {
        removeTutorialViews()
        currentStepIndex++
        if (currentStepIndex >= steps.size) complete() else showStep(currentStepIndex)
    }

    /**
     * Called when all steps are done (or the last step is advanced past).
     * Persists the completed flag, navigates back to the Care tab, then shows
     * a completion banner. The [onComplete] callback fires when the user
     * dismisses the completion banner.
     */
    private fun complete() {
        isActive = false
        nfcLiveThisStep = false
        suppressNavigationThisStep = false
        removeTutorialViews()
        restoreButtonStates()

        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()

        // Navigate back to Care tab — isTutorialNavigating brackets required here too.
        val main = activity as? MainActivity
        main?.isTutorialNavigating = true
        main?.bottomNav?.selectedItemId = R.id.tab_care
        main?.isTutorialNavigating = false
        main?.loadFragment(CareFragment())

        val root = activity.findViewById<FrameLayout>(R.id.root_layout) ?: run {
            onComplete(); return
        }

        // Completion banner — same structure as step banners but with a single "Done" button.
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
            text = activity.getString(R.string.tutorial_complete_title)
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8.dpToPx())
            isFocusable = true
            contentDescription = activity.getString(R.string.tutorial_complete_title)
        }

        val tvSub = TextView(activity).apply {
            text = activity.getString(R.string.tutorial_complete_body)
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 0, 0, 12.dpToPx())
            // Title covers the full message — sub-text doesn't need separate announcement.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val btnDone = Button(activity).apply {
            text = activity.getString(R.string.tutorial_complete_btn)
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 70, 70, 70))
            elevation = 22f
            minHeight = 56.dpToPx()
            contentDescription = activity.getString(R.string.tutorial_complete_btn_description)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.END
            layoutParams = lp
            // Remove banner from root before calling onComplete to avoid a lingering overlay.
            setOnClickListener { root.removeView(banner); onComplete() }
        }

        banner.addView(tvDone)
        banner.addView(tvSub)
        banner.addView(btnDone)
        root.addView(banner)

        // 500 ms delay — matches step-banner announcement timing for consistency.
        banner.postDelayed({
            tvDone.announceForAccessibility(
                activity.getString(R.string.tutorial_complete_announcement)
            )
        }, 500)
    }

    /** Converts dp to pixels using the activity's display density. */
    private fun Int.dpToPx(): Int =
        (this * activity.resources.displayMetrics.density).toInt()
}