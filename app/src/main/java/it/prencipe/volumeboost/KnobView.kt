package it.prencipe.volumeboost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Manopola circolare: arco di 270° da 0 a maxValue dB, passo 0,5 dB.
 * Tocca o trascina lungo l'arco per regolare il guadagno.
 */
class KnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var maxValue = 20f
    var step = 0.5f
    var onValueChange: ((Float) -> Unit)? = null

    var value = 0f
        set(v) {
            val clamped = v.coerceIn(0f, maxValue)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** false = boost spento (es. dal riquadro rapido): la manopola appare attenuata */
    var active = true
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }

    private val startAngle = 135f
    private val sweepAngle = 270f
    private val density = resources.displayMetrics.density
    private val strokeW = 14f * density
    private val accent = Color.parseColor("#FF6D00")
    private val dimmed = Color.parseColor("#9E9E9E")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#40888888")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 40f * density
        isFakeBoldText = true
        color = Color.parseColor("#9E9E9E")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        letterSpacing = 0.2f
        color = Color.parseColor("#9E9E9E")
    }
    private val arcRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val on = active && value > 0f
        val color = if (on) accent else dimmed
        progressPaint.color = color
        thumbOuterPaint.color = color

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - strokeW * 1.5f
        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)
        val frac = value / maxValue
        if (frac > 0f) {
            canvas.drawArc(arcRect, startAngle, sweepAngle * frac, false, progressPaint)
        }

        // Cursore sulla posizione corrente
        val a = Math.toRadians((startAngle + sweepAngle * frac).toDouble())
        val tx = cx + r * cos(a).toFloat()
        val ty = cy + r * sin(a).toFloat()
        canvas.drawCircle(tx, ty, strokeW, thumbOuterPaint)
        canvas.drawCircle(tx, ty, strokeW * 0.55f, thumbInnerPaint)

        // Testo centrale
        val text = String.format(Locale.ITALY, "+%.1f dB", value)
        val textY = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText(text, cx, textY, valuePaint)
        canvas.drawText(if (on) "BOOST" else "OFF", cx, textY + 28f * density, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val newValue = valueFromTouch(event.x, event.y)
                // Evita salti bruschi 0 <-> max quando si trascina nella zona morta in basso
                if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                    abs(newValue - value) > maxValue / 2f
                ) return true
                // Toccare la manopola da spenta la riaccende anche senza cambiare valore
                if (newValue != value || (!active && newValue > 0f)) {
                    value = newValue
                    onValueChange?.invoke(value)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun valueFromTouch(x: Float, y: Float): Float {
        val deg = Math.toDegrees(
            atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble())
        ).toFloat()
        var rel = (deg - startAngle + 360f) % 360f
        if (rel > sweepAngle) {
            // Zona morta in basso: aggancia all'estremo più vicino
            rel = if (rel < sweepAngle + (360f - sweepAngle) / 2f) sweepAngle else 0f
        }
        val raw = rel / sweepAngle * maxValue
        return (raw / step).roundToInt() * step
    }
}
