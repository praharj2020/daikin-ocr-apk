package pt.casa.daikinocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Mostra a foto e deixa desenhar (arrastando o dedo) a caixa à volta dos 2 dígitos. */
class RoiView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var bitmap: Bitmap? = null
        set(v) { field = v; requestLayout(); invalidate() }

    /** ROI normalizada 0..1 (x0,y0,x1,y1). */
    var roi: FloatArray? = null
        set(v) { field = v; invalidate() }

    var onRoiChanged: ((FloatArray) -> Unit)? = null

    private val paintRect = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val paintHint = Paint().apply { color = Color.GRAY; textSize = 40f }
    private var startX = 0f
    private var startY = 0f
    private val dst = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val b = bitmap
        val h = if (b != null) (w.toFloat() * b.height / b.width).toInt() else (w * 0.75f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap
        if (b == null) {
            canvas.drawColor(Color.parseColor("#222222"))
            canvas.drawText("Toque em \"Tirar foto\"", 30f, 60f, paintHint)
            return
        }
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, null, dst, null)
        roi?.let {
            canvas.drawRect(it[0] * width, it[1] * height, it[2] * width, it[3] * height, paintRect)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        val x = (e.x / width).coerceIn(0f, 1f)
        val y = (e.y / height).coerceIn(0f, 1f)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = x; startY = y
                roi = floatArrayOf(x, y, x, y)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                roi = floatArrayOf(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                if (e.action == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    roi?.let { r -> if (r[2] - r[0] > 0.01f && r[3] - r[1] > 0.01f) onRoiChanged?.invoke(r) }
                }
            }
        }
        return true
    }
}
