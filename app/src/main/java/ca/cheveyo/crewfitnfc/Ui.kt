package ca.cheveyo.crewfitnfc

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Couleurs de l'appli. */
object C {
    val BG = Color.parseColor("#0F1012")
    val CARD = Color.parseColor("#1B1D21")
    val CARD2 = Color.parseColor("#2A2D33")
    val ACCENT = Color.parseColor("#FF5A1F")
    val TEXT = Color.parseColor("#F4F4F5")
    val MUTED = Color.parseColor("#A1A1AA")
    val DANGER = Color.parseColor("#DC2626")
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 14): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.card(): LinearLayout = vbox().apply {
    background = rounded(C.CARD, 16)
    val p = dp(16)
    setPadding(p, p, p, p)
}

fun Context.text(s: CharSequence, sizeSp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextColor(color)
        textSize = sizeSp
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

fun Context.button(label: String, primary: Boolean = true, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        setTextColor(if (primary) Color.WHITE else C.TEXT)
        background = rounded(if (primary) C.ACCENT else C.CARD2, 12)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

/** Petit bouton de choix (onglet, type, durée). */
fun Context.chip(label: String, selected: Boolean, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        setTextColor(if (selected) Color.WHITE else C.TEXT)
        background = rounded(if (selected) C.ACCENT else C.CARD2, 10)
        setOnClickListener { onClick() }
    }

fun Context.input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(this).apply {
        inputType = type
        this.hint = hint
        setText(value)
        setTextColor(C.TEXT)
        setHintTextColor(C.MUTED)
        textSize = 15f
        background = rounded(C.CARD2, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

/**
 * Ajoute une vue dans un LinearLayout.
 * Vertical : pleine largeur, marginDp = marge du haut.
 * Horizontal : marginDp = marge de gauche, weight > 0 = partage la largeur.
 */
fun LinearLayout.add(v: View, marginDp: Int = 0, weight: Float = 0f): View {
    val horizontal = orientation == LinearLayout.HORIZONTAL
    val params = if (horizontal) {
        LinearLayout.LayoutParams(if (weight > 0f) 0 else WRAP, WRAP, weight).apply {
            marginStart = context.dp(marginDp)
        }
    } else {
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(marginDp) }
    }
    addView(v, params)
    return v
}
