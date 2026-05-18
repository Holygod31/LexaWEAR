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
 *  - Instruction banner centred on screen
 *  - A highlight ring drawn around the target button
 *  - A Skip/Got it pill inside the banner
 *  - All non-target buttons disabled (alpha 0.35), target at full alpha
 *  - Info-only steps (no target) re-enable nothing; only the Skip pill works
 */
class TutorialManager(
    private val activity: AppCompatActivity,
    private val onComplete: () -> Unit
) {
    companion object {
        private const val PREFS    = "tutorial"
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
        val titleRes: Int,
        val descriptionRes: Int,
        val targetViewId: Int?,
        val nfcLive: Boolean = false,
        val suppressNavigation: Boolean = false,
        val loadFilterFragment: Boolean = false,
        val keepFilterFragment: Boolean = false
    )

    private val steps = listOf(
        // ── Care tab ──────────────────────────────────────────────────────────
        TutorialStep(
            tab = R.id.tab_care,
            titleRes = R.string.tutorial_title_1,
            descriptionRes = R.string.tutorial_desc_1,
            targetViewId = null,
            nfcLive = true
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
            targetViewId = null
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
            targetViewId = null
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
            loadFilterFragment = true,
            suppressNavigation = true
        ),
        TutorialStep(
            tab = R.id.tab_wardrobe,
            titleRes = R.string.tutorial_title_13,
            descriptionRes = R.string.tutorial_desc_13,
            targetViewId = R.id.btn_filter_apply,
            keepFilterFragment = true,
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
            targetViewId = null
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

    fun onTargetTapped() { advance() }

    private fun showStep(index: Int) {
        if (index >= steps.size) { complete(); return }

        val step = steps[index]
        nfcLiveThisStep = step.nfcLive
        suppressNavigationThisStep = step.suppressNavigation

        val main = activity as? MainActivity

        if (!step.keepFilterFragment) {
            main?.isTutorialNavigating = true
            main?.bottomNav?.selectedItemId = step.tab
            main?.isTutorialNavigating = false
        }

        if (step.loadFilterFragment) main?.loadFragment(FilterFragment())

        removeTutorialViews()

        activity.window.decorView.postDelayed({
            if (!isActive) return@postDelayed

            if (index == 1) {
                val frag = main?.supportFragmentManager
                    ?.findFragmentById(R.id.fragment_container)
                if (frag is CareFragment) frag.showAddToWardrobeForTutorial()
            }

            if (index in 3..7) {
                activity.findViewById<View>(R.id.btn_skip)?.visibility = View.VISIBLE
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
            view.isEnabled = isTarget
            view.alpha = if (isTarget) 1f else 0.35f
        }
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

    private fun buildBanner(step: TutorialStep) {
        val root = activity.findViewById<FrameLayout>(R.id.root_layout) ?: return

        val title       = activity.getString(step.titleRes)
        val description = activity.getString(step.descriptionRes)
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
            text = title
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 4.dpToPx())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val tvDesc = TextView(activity).apply {
            text = description
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12.dpToPx())
            contentDescription = "$title. $description"
            isFocusable = true
        }

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
            setOnClickListener { restoreButtonStates(); advance() }
        }

        container.addView(tvTitle)
        container.addView(tvDesc)
        container.addView(btnSkipInBanner)
        root.addView(container)
        bannerView = container

        container.postDelayed({
            tvDesc.announceForAccessibility(
                "$title. $description. $skipLabel ${activity.getString(R.string.tutorial_button_below)}"
            )
        }, 500)
    }

    private fun buildHighlight(targetId: Int) {
        val root   = activity.findViewById<FrameLayout>(R.id.root_layout) ?: return
        val target = activity.findViewById<View>(targetId) ?: return

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
                setStroke(4.dpToPx(), Color.parseColor("#FFD600"))
                cornerRadius = 12.dpToPx().toFloat()
            }
            elevation = 18f
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = left
                topMargin  = top
            }
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

        val main = activity as? MainActivity
        main?.isTutorialNavigating = true
        main?.bottomNav?.selectedItemId = R.id.tab_care
        main?.isTutorialNavigating = false
        main?.loadFragment(CareFragment())

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
            setOnClickListener { root.removeView(banner); onComplete() }
        }

        banner.addView(tvDone)
        banner.addView(tvSub)
        banner.addView(btnDone)
        root.addView(banner)

        banner.postDelayed({
            tvDone.announceForAccessibility(
                activity.getString(R.string.tutorial_complete_announcement)
            )
        }, 500)
    }

    private fun Int.dpToPx(): Int =
        (this * activity.resources.displayMetrics.density).toInt()
}