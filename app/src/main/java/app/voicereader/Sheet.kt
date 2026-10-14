package app.voicereader

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/**
 * Android breaks Korean between any two syllables ("만난 사 / 람들"). Glue each word with invisible word joiners so
 * lines break only at spaces, the way Korean is set, on every Android version (a word too long for a line still breaks).
 */
fun String.keepWords(): String = replace(Regex("""(?<=\S)(?=\S)"""), "\u2060")

fun MaterialAlertDialogBuilder.message(text: String): MaterialAlertDialogBuilder = apply { setMessage(text.keepWords()) }

/** A voice pack's folder name as people read it: "supertonic-2" → "Supertonic 2". */
fun voiceLabel(name: String): String = name.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * One look for every bottom sheet, built for e-ink: it appears fully open with no slide, rows invert while pressed,
 * choices are segmented buttons whose selected part is filled black, numbers move in steps (no sliders).
 */
class Sheet(private val context: Context, title: String, hint: String? = null) {

    private val dialog = BottomSheetDialog(context).apply {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(24))
    }

    init {
        column.addView(text(title, R.style.Text_Title).apply {
            setPadding(context.dp(16), 0, context.dp(16), context.dp(if (hint == null) 12 else 4))
        })
        hint?.let {
            column.addView(text(it, R.style.Text_Caption).apply {
                setPadding(context.dp(16), 0, context.dp(16), context.dp(12))
                setLineSpacing(0f, 1.25f)
            })
        }
    }

    /**
     * A tappable row that closes the sheet. [checked] marks the chosen option, [current] where the reader is, [more]
     * that it opens another sheet (a › at the end, so it reads as a way in rather than an action).
     */
    fun row(
        title: String,
        subtitle: String? = null,
        icon: Int? = null,
        checked: Boolean = false,
        current: Boolean = false,
        indent: Int = 0,
        more: Boolean = false,
        onClick: () -> Unit,
    ): Sheet = apply {
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(64)
            setPadding(context.dp(16 + indent * 18), context.dp(10), context.dp(16), context.dp(10))
            setBackgroundResource(R.drawable.row_bg)
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
            icon?.let {
                addView(ImageView(context).apply {
                    setImageResource(it)
                    imageTintList = context.getColorStateList(R.color.row_fg)
                    isDuplicateParentStateEnabled = true
                    layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply { marginEnd = context.dp(16) }
                })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(if (current) "▶  $title" else title, R.style.Text_Body).apply {
                    setTextColor(context.getColorStateList(R.color.row_fg))
                    isDuplicateParentStateEnabled = true
                    if (current || checked) setTypeface(typeface, Typeface.BOLD)
                })
                subtitle?.let {
                    addView(text(it, R.style.Text_Caption).apply {
                        setTextColor(context.getColorStateList(R.color.row_fg_muted))
                        isDuplicateParentStateEnabled = true
                    })
                }
            })
            if (checked) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = context.getColorStateList(R.color.row_fg)
                    isDuplicateParentStateEnabled = true
                    contentDescription = "선택됨"
                    layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28))
                })
            }
            if (more) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = context.getColorStateList(R.color.row_fg)
                    isDuplicateParentStateEnabled = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply { marginStart = context.dp(8) }
                })
            }
        })
    }

    /** A row with a check box, for picking several things at once. Toggles in place; the sheet stays open. */
    fun check(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): Sheet = apply {
        var on = checked
        val box = ImageView(context).apply {
            setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_blank)
            imageTintList = context.getColorStateList(R.color.row_fg)
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(context.dp(30), context.dp(30)).apply { marginEnd = context.dp(16) }
        }
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(64)
            setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
            setBackgroundResource(R.drawable.row_bg)
            setOnClickListener {
                on = !on
                box.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_blank)
                box.contentDescription = if (on) "선택됨" else "선택 안 됨"
                onChange(on)
            }
            addView(box)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(title, R.style.Text_Body).apply {
                    setTextColor(context.getColorStateList(R.color.row_fg))
                    isDuplicateParentStateEnabled = true
                })
                subtitle?.let {
                    addView(text(it, R.style.Text_Caption).apply {
                        setTextColor(context.getColorStateList(R.color.row_fg_muted))
                        isDuplicateParentStateEnabled = true
                    })
                }
            })
        })
    }

    /**
     * One choice out of a list, as rows with a check on the chosen one. Unlike [row] it leaves the sheet open, so the
     * listener can try several (e.g. hear each voice) before closing it.
     */
    fun pick(labels: List<String>, selected: Int, onPick: (Int) -> Unit): Sheet = apply {
        val names = labels.mapIndexed { i, label ->
            text(label, R.style.Text_Body).apply {
                setTextColor(context.getColorStateList(R.color.row_fg))
                isDuplicateParentStateEnabled = true
                setTypeface(typeface, if (i == selected) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        val checks = labels.indices.map { i ->
            ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = context.getColorStateList(R.color.row_fg)
                isDuplicateParentStateEnabled = true
                visibility = if (i == selected) View.VISIBLE else View.INVISIBLE
                contentDescription = "선택됨"
                layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28))
            }
        }
        labels.indices.forEach { i ->
            column.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(60)
                setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                setBackgroundResource(R.drawable.row_bg)
                setOnClickListener {
                    checks.forEachIndexed { j, check -> check.visibility = if (j == i) View.VISIBLE else View.INVISIBLE }
                    names.forEachIndexed { j, name -> name.setTypeface(Typeface.create(name.typeface, if (j == i) Typeface.BOLD else Typeface.NORMAL)) }
                    onPick(i)
                }
                addView(names[i])
                addView(checks[i])
            })
        }
    }

    fun onDismiss(block: () -> Unit): Sheet = apply { dialog.setOnDismissListener { block() } }

    /** A small heading with a rule above it, to group what follows. */
    fun section(title: String): Sheet = apply {
        column.addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.line))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1))
                .apply { setMargins(context.dp(16), context.dp(12), context.dp(16), context.dp(12)) }
        })
        column.addView(text(title, R.style.Text_Label).apply { setPadding(context.dp(16), 0, context.dp(16), context.dp(4)) })
    }

    /**
     * One choice out of a few, as segmented buttons (also used for on/off: "켬 | 끔"). The chosen part is filled
     * black, which reads at a glance on e-ink, unlike a sliding switch. Leaves the sheet open.
     */
    fun choice(title: String, subtitle: String?, labels: List<String>, selected: Int, onPick: (Int) -> Unit): Sheet = apply {
        val group = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = context.dp(8) }
        }
        val ids = labels.map { label ->
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                layoutParams = LinearLayout.LayoutParams(0, context.dp(56), 1f)
                group.addView(this)
            }.id
        }
        group.check(ids[selected])
        group.addOnButtonCheckedListener { _, id, isChecked -> if (isChecked) onPick(ids.indexOf(id)) }
        column.addView(block(title, subtitle, group))
    }

    /** A value changed in steps with large − / + buttons. [step] returns the new label, or null at a limit. */
    fun stepper(title: String, value: String, step: (Int) -> String?): Sheet = apply {
        val label = TextView(context).apply {
            setTextAppearance(R.style.Text_Title)
            text = value
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun button(sign: String, delta: Int) =
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = sign
                textSize = 24f
                contentDescription = "$title ${if (delta > 0) "크게" else "작게"}"
                layoutParams = LinearLayout.LayoutParams(context.dp(88), context.dp(56))
                setOnClickListener { step(delta)?.let { label.text = it } }
            }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = context.dp(8) }
            addView(button("−", -1))
            addView(label)
            addView(button("+", 1))
        }
        column.addView(block(title, null, row))
    }

    fun button(label: String, onClick: () -> Unit): Sheet = apply {
        column.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(60))
                .apply { setMargins(context.dp(16), context.dp(16), context.dp(16), 0) }
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        })
    }

    fun show() {
        dialog.setContentView(NestedScrollView(context).apply { addView(column) })
        dialog.window?.setWindowAnimations(0)
        dialog.show()
    }

    private fun block(title: String, subtitle: String?, content: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(6))
        addView(text(title, R.style.Text_Body))
        subtitle?.let { addView(text(it, R.style.Text_Caption)) }
        addView(content)
    }

    private fun text(value: String, style: Int) = TextView(context).apply {
        setTextAppearance(style)
        text = value.keepWords()
    }
}
