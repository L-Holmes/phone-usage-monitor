package com.example.webtrafficmonitor

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView

// =====================================================================================
//  THE DESIGN SYSTEM
// =====================================================================================
//
//  READ THIS BEFORE ADDING A COLOUR, A CORNER RADIUS, OR A PADDING NUMBER.
//
//  This app's UI is built in code, not XML, and for a long time that meant every screen
//  invented its own greys. There were 500-odd raw 0xFF……. literals across the codebase:
//  eleven different "card backgrounds", four "muted text" greys within a few points of
//  each other, corner radii of 10, 12, 14, 16 and 18 on things that sit side by side.
//  Individually all defensible; together, visibly not one app.
//
//  So: ONE palette, ONE spacing scale, ONE type scale, ONE set of radii, ONE motion
//  vocabulary, and a small set of component builders that use them. If you need a colour
//  that isn't in Palette, the right move is almost always to use the closest one that is.
//  Add to the palette only for a genuinely new SEMANTIC role, and name it for the role
//  ("warning") rather than the colour ("orange").
//
//  ── THE LOOK ────────────────────────────────────────────────────────────────────────
//  Apple's, honestly: light, quiet, generously spaced, with the content doing the work
//  and the chrome getting out of the way.
//    • LIGHT. Near-white grouped background, white cards. The old palette was a dark
//      slate-teal that made a wellbeing app feel like a diagnostics console.
//    • GLASSY. Cards are translucent white over the tinted background with a hairline
//      border, not flat blocks of grey - see glassCard(). Depth comes from a whisper of
//      shadow and that hairline, never from a heavy drop shadow.
//    • ROUNDED. Generous, consistent radii (Radius), scaled to the element's size.
//    • SPACED. Everything lands on a 4pt grid (Space). Padding inside a card is 16 or 20,
//      never 13 or 17.
//    • QUICK. Transitions are 140-220ms with an ease-out curve (Motion). Fast enough to
//      feel instant, slow enough to be seen. Nothing bounces, nothing spins, nothing
//      makes the user wait for it.
//
//  ── WHAT DOESN'T CHANGE ─────────────────────────────────────────────────────────────
//  Nothing in here is allowed to soften a block. Cover screens are deliberately the one
//  place the design system gets LOUDER rather than quieter (Palette.danger and friends):
//  a block must read as a wall, not as a tasteful suggestion.
// =====================================================================================

object Palette {

    // ── Backgrounds ─────────────────────────────────────────────────────────────────
    /** The page behind everything. Near-white, a touch cool, never pure #FFF. */
    const val bg = 0xFFF7F8FA.toInt()
    /** A raised surface: cards, rows, sheets. */
    const val surface = 0xFFFFFFFF.toInt()
    /** A surface that needs to sit back a step (a well, a nested group, an input). */
    const val surfaceSunken = 0xFFF1F3F6.toInt()
    /** Translucent white for the glass treatment - let the tinted page show through. */
    const val glass = 0xF2FFFFFF.toInt()
    /** Hairline borders. One pixel of this reads as an edge without becoming a line. */
    const val hairline = 0xFFE4E7EC.toInt()
    /** A stronger divider, for genuine section breaks only. */
    const val divider = 0xFFD6DAE0.toInt()

    // ── Text ────────────────────────────────────────────────────────────────────────
    /** Primary text. Not pure black - pure black on near-white is harsher than it looks. */
    const val label = 0xFF12171D.toInt()
    /** Supporting text: subtitles, captions that still need to be read. */
    const val labelSecondary = 0xFF636C76.toInt()
    /** Metadata, timestamps, hints. Present, not competing. */
    const val labelTertiary = 0xFF929AA3.toInt()
    /** Chevrons, disabled glyphs, empty states. */
    const val labelQuaternary = 0xFFB9C0C8.toInt()
    /** Text on a filled/tinted surface. */
    const val onFill = 0xFFFFFFFF.toInt()

    // ── Brand ───────────────────────────────────────────────────────────────────────
    /** The app's teal, cleaned up: same identity, more light in it. */
    const val tint = 0xFF14A08F.toInt()
    /** Pressed / active state of the tint. */
    const val tintDeep = 0xFF0E8375.toInt()
    /** A wash of the tint, for selected rows and soft badges. */
    const val tintSoft = 0xFFE4F4F1.toInt()

    // ── Semantics ───────────────────────────────────────────────────────────────────
    // Each has a FILL (backgrounds, buttons, bars) and an ON-LIGHT text variant that
    // actually passes contrast on a white card. Reach for the text one for words.
    const val success = 0xFF30B85B.toInt()
    const val successText = 0xFF1E7A3C.toInt()
    const val successSoft = 0xFFE8F6EC.toInt()

    const val warning = 0xFFF5A524.toInt()
    const val warningText = 0xFF8A5A00.toInt()
    const val warningSoft = 0xFFFEF4E4.toInt()

    const val danger = 0xFFE5484D.toInt()
    const val dangerText = 0xFFB3261E.toInt()
    const val dangerSoft = 0xFFFCEBEB.toInt()

    /** Cover screens and other "this is a wall" surfaces - see the note at the top. */
    const val cover = 0xFF171C22.toInt()
    const val coverText = 0xFFF4F6F8.toInt()
    /**
     * The pause sweep's panel. A deep blue: it has to read as a solid object moving over
     * the near-black [cover], without the brand teal's glow - this is the one surface in
     * the app whose whole job is to be looked at for several seconds.
     */
    const val sweep = 0xFF17335E.toInt()

    // ── Data / charts ───────────────────────────────────────────────────────────────
    // Ordered so adjacent series stay distinguishable. Use in order; don't cherry-pick.
    val series = intArrayOf(
        0xFF14A08F.toInt(),   // teal (brand)
        0xFF4B8DF8.toInt(),   // blue
        0xFFF5A524.toInt(),   // amber
        0xFF9B6DE8.toInt(),   // violet
        0xFF30B85B.toInt(),   // green
        0xFFE5484D.toInt(),   // red
    )
}

/**
 * The 4pt grid. Every gap, margin and pad in the app comes from here.
 *
 * These are DP, not pixels - pass them through [dp] before handing them to a View.
 */
object Space {
    const val xxs = 4
    const val xs = 8
    const val sm = 12
    const val md = 16
    const val lg = 20
    const val xl = 24
    const val xxl = 32
    const val huge = 40
    /** The standard page gutter. Same on every screen, which is most of "cohesive". */
    const val page = 20
}

/** Corner radii, scaled to the element. Bigger elements get bigger corners. */
object Radius {
    const val chip = 10f
    const val control = 14f      // buttons, inputs, small rows
    const val card = 20f         // the standard card
    const val sheet = 28f        // full-width panels, modal-ish surfaces
    const val pill = 999f
}

/**
 * The type scale, in SP. Sizes only - weight is set at the call site, because the same
 * size is used bold for a heading and regular for body text.
 */
object Type {
    const val largeTitle = 32f
    const val title = 25f
    const val title2 = 21f
    const val headline = 17f
    const val body = 16f
    const val callout = 15f
    const val footnote = 13f
    const val caption = 12f

    /** Comfortable reading leading for anything longer than a line or two. */
    const val lineSpacing = 1.22f
}

/**
 * Motion. Short, eased-out, and never decorative.
 *
 * The curve is an ease-out with a long tail: things arrive quickly and settle, which is
 * what makes a transition feel responsive rather than animated. Do not add bounce, and do
 * not go above [slow] - a user who is fighting an urge should never be waiting on our UI.
 */
object Motion {
    const val fast = 140L        // press feedback, small state flips
    const val base = 200L        // page content arriving, cards appearing
    const val slow = 280L        // the largest thing that should ever animate

    /** Ease-out with a long settle. The house curve; use it unless you have a reason. */
    val easeOut: PathInterpolator get() = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    /** Symmetric ease, for things that move and come back (press in / press out). */
    val standard: PathInterpolator get() = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // ── The pause sweep ─────────────────────────────────────────────────────────────
    // The one exception to "never above [slow]": the pause gate's panel is not a
    // transition, it is the thing you are meant to watch, and it runs for seconds. Its
    // two curves live here so the gate and the in-app pages move identically.

    /** Up: away at once, easing off as it reaches the top. */
    val sweepUp: Interpolator get() = DecelerateInterpolator(1.6f)
    /**
     * Down: starts slow, drifts through the middle, and settles with only a slight
     * deceleration into the bottom. Deliberately NOT the mirror of [sweepUp] - coming
     * down is the part you are waiting out, and it should feel unhurried.
     */
    val sweepDown: Interpolator get() = PathInterpolator(0.45f, 0f, 0.75f, 1f)
}

// ── Unit helpers ────────────────────────────────────────────────────────────────────

/** DP -> px, the only conversion any of this needs. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density
fun View.dp(value: Int): Int = context.dp(value)
fun View.dpf(value: Float): Float = context.dpf(value)

// ── Surfaces ────────────────────────────────────────────────────────────────────────

/**
 * The house surface: a rounded fill with an optional hairline border.
 *
 * The hairline is what stops a white card on a near-white page from dissolving. It is
 * doing the job a drop shadow would do elsewhere, at a fraction of the visual weight.
 */
fun Context.surfaceBg(
    fill: Int = Palette.surface,
    radius: Float = Radius.card,
    stroke: Int? = Palette.hairline,
    strokeWidthDp: Float = 1f,
): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dpf(radius)
    setColor(fill)
    if (stroke != null) setStroke(maxOf(1, dpf(strokeWidthDp).toInt()), stroke)
}

/**
 * The glass treatment: translucent white over whatever is behind, plus a hairline.
 *
 * Android gives us no cheap backdrop blur below API 31, and a real blur on every card
 * would cost more than it is worth here. What actually reads as "glass" at this scale is
 * the translucency and the bright hairline edge, so that is what this does.
 */
fun Context.glassBg(radius: Float = Radius.card): GradientDrawable =
    surfaceBg(fill = Palette.glass, radius = radius, stroke = 0xFFEDF0F4.toInt())

/** A tappable surface with a proper ripple in the tint, clipped to the same radius. */
fun Context.tappableBg(
    fill: Int = Palette.surface,
    radius: Float = Radius.card,
    stroke: Int? = Palette.hairline,
    ripple: Int = 0x1F14A08F,
): RippleDrawable {
    val base = surfaceBg(fill, radius, stroke)
    val mask = GradientDrawable().apply { cornerRadius = dpf(radius); setColor(0xFF000000.toInt()) }
    return RippleDrawable(ColorStateList.valueOf(ripple), base, mask)
}

// ── Interaction ─────────────────────────────────────────────────────────────────────

/**
 * The press feel: a small, fast scale-down while the finger is down.
 *
 * Deliberately subtle (2.5%). You should not be able to describe what happened, only
 * notice that the thing responded. Applied on top of the ripple, not instead of it.
 */
fun View.pressable(): View {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.975f).scaleY(0.975f)
                    .setDuration(Motion.fast).setInterpolator(Motion.standard).start()
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(Motion.fast).setInterpolator(Motion.easeOut).start()
        }
        false        // never consume: the click listener and ripple still need this event
    }
    return this
}

/**
 * Content arriving: fade up over a few pixels.
 *
 * Used by the setContentView override so EVERY page gets the same entrance without a
 * single screen having to remember to ask for it. The rise is 8dp - enough to read as
 * motion, small enough that it never looks like the page is sliding in.
 */
fun View.enterFromBelow(distanceDp: Int = 8, duration: Long = Motion.base) {
    alpha = 0f
    translationY = dpf(distanceDp.toFloat())
    animate().alpha(1f).translationY(0f)
        .setDuration(duration).setInterpolator(Motion.easeOut).start()
}

// ── Components ──────────────────────────────────────────────────────────────────────

/** A section heading: small, upper-ish, quiet. Sits above a group of cards. */
fun Context.sectionHeader(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = Type.footnote
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(Palette.labelTertiary)
    letterSpacing = 0.06f
    setPadding(dp(Space.xxs), dp(Space.lg), dp(Space.xxs), dp(Space.xs))
}

/** A page title. One per screen, always the first thing in the content column. */
fun Context.pageTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = Type.title
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(Palette.label)
    letterSpacing = -0.02f              // tighter tracking at display sizes, as Apple does
    setPadding(0, 0, 0, dp(Space.sm))
}

/** Body copy, with reading leading already applied. */
fun Context.bodyText(text: CharSequence, colour: Int = Palette.labelSecondary): TextView =
    TextView(this).apply {
        this.text = text
        textSize = Type.callout
        setTextColor(colour)
        setLineSpacing(0f, Type.lineSpacing)
    }

/**
 * The standard glass card. Everything that groups content uses this, which is most of
 * why the app reads as one thing.
 */
fun Context.glassCard(padding: Int = Space.md, radius: Float = Radius.card): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glassBg(radius)
        elevation = dpf(1f)
        val p = dp(padding)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(Space.sm) }
    }

/**
 * The filled primary action. One per screen at most - if a screen has two of these, one
 * of them is not the primary action.
 */
fun Context.primaryButton(label: String, fill: Int = Palette.tint, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        textSize = Type.headline
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.onFill)
        gravity = Gravity.CENTER
        background = tappableBg(fill, Radius.control, stroke = null, ripple = 0x33FFFFFF)
        elevation = dpf(1.5f)
        setPadding(dp(Space.lg), dp(Space.md), dp(Space.lg), dp(Space.md))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(Space.xs) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        pressable()
    }

/** The quiet secondary action: tinted text on a soft wash, no fill, no shadow. */
fun Context.secondaryButton(label: String, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        textSize = Type.headline
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.tintDeep)
        gravity = Gravity.CENTER
        background = tappableBg(Palette.tintSoft, Radius.control, stroke = null)
        setPadding(dp(Space.lg), dp(Space.md), dp(Space.lg), dp(Space.md))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(Space.xs) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        pressable()
    }

/** A small status pill: soft background, strong text. For counts, states, scores. */
fun Context.badge(text: String, fill: Int = Palette.tintSoft, textColour: Int = Palette.tintDeep): TextView =
    TextView(this).apply {
        this.text = text
        textSize = Type.caption
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColour)
        gravity = Gravity.CENTER
        background = surfaceBg(fill, Radius.chip, stroke = null)
        setPadding(dp(Space.xs), dp(Space.xxs), dp(Space.xs), dp(Space.xxs))
    }

/** A hairline separator for use INSIDE a card, between rows. */
fun Context.separator(): View = View(this).apply {
    setBackgroundColor(Palette.hairline)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2),
    ).apply { topMargin = dp(Space.sm); bottomMargin = dp(Space.sm) }
}

/** A flexible gap that pushes whatever follows it to the bottom of a vertical layout. */
fun Context.spacer(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
    )
}
