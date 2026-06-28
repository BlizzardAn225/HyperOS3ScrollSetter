package com.blizzard225.wallpapersetter

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewParent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var cropRect = RectF()
    private var imageRect = RectF()
    private var aspectRatio: Float? = null

    private var isDragging = false
    private var isResizing = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var cropStartRect = RectF()

    private enum class ResizeEdge {
        NONE, LEFT, TOP, RIGHT, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private var resizeEdge = ResizeEdge.NONE
    private val edgeTouchSlop = 48f
    private val minCropSize = 60f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (drawable == null) return

        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val scaleX = viewWidth / drawableWidth
        val scaleY = viewHeight / drawableHeight
        val scale = min(scaleX, scaleY)

        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale

        val left = (viewWidth - scaledWidth) / 2
        val top = (viewHeight - scaledHeight) / 2

        imageRect.set(left, top, left + scaledWidth, top + scaledHeight)

        if (cropRect.isEmpty) {
            resetCropRect()
        }

        drawOverlay(canvas)
        drawCropRect(canvas)
    }

    private fun drawOverlay(canvas: Canvas) {
        val paint = Paint().apply {
            color = Color.parseColor("#80000000")
            style = Paint.Style.FILL
        }

        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, paint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, paint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, paint)
    }

    private fun drawCropRect(canvas: Canvas) {
        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val gridPaint = Paint().apply {
            color = Color.parseColor("#80FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        canvas.drawRect(cropRect, borderPaint)

        val thirdWidth = cropRect.width() / 3
        val thirdHeight = cropRect.height() / 3

        for (i in 1..2) {
            canvas.drawLine(
                cropRect.left + thirdWidth * i, cropRect.top,
                cropRect.left + thirdWidth * i, cropRect.bottom, gridPaint
            )
            canvas.drawLine(
                cropRect.left, cropRect.top + thirdHeight * i,
                cropRect.right, cropRect.top + thirdHeight * i, gridPaint
            )
        }

        val handlePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val handleSize = 20f
        val handles = arrayOf(
            PointF(cropRect.left, cropRect.top),
            PointF(cropRect.right, cropRect.top),
            PointF(cropRect.left, cropRect.bottom),
            PointF(cropRect.right, cropRect.bottom)
        )

        handles.forEach { handle ->
            canvas.drawCircle(handle.x, handle.y, handleSize / 2, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val edge = detectEdge(event.x, event.y)
                if (edge != ResizeEdge.NONE) {
                    isResizing = true
                    resizeEdge = edge
                    dragStartX = event.x
                    dragStartY = event.y
                    cropStartRect.set(cropRect)
                } else if (isInsideCropRect(event.x, event.y)) {
                    isDragging = true
                    dragStartX = event.x
                    dragStartY = event.y
                    cropStartRect.set(cropRect)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY

                if (isDragging) {
                    val newLeft = cropStartRect.left + dx
                    val newTop = cropStartRect.top + dy
                    val clampedLeft = max(imageRect.left, min(newLeft, imageRect.right - cropRect.width()))
                    val clampedTop = max(imageRect.top, min(newTop, imageRect.bottom - cropRect.height()))
                    cropRect.offset(clampedLeft - cropRect.left, clampedTop - cropRect.top)
                    invalidate()
                } else if (isResizing) {
                    resizeCropRect(dx, dy)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isResizing = false
                resizeEdge = ResizeEdge.NONE
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun detectEdge(x: Float, y: Float): ResizeEdge {
        val s = edgeTouchSlop
        val onLeft = abs(x - cropRect.left) < s
        val onRight = abs(x - cropRect.right) < s
        val onTop = abs(y - cropRect.top) < s
        val onBottom = abs(y - cropRect.bottom) < s

        val inVerticalRange = y in (cropRect.top - s)..(cropRect.bottom + s)
        val inHorizontalRange = x in (cropRect.left - s)..(cropRect.right + s)

        if (onTop && onLeft) return ResizeEdge.TOP_LEFT
        if (onTop && onRight) return ResizeEdge.TOP_RIGHT
        if (onBottom && onLeft) return ResizeEdge.BOTTOM_LEFT
        if (onBottom && onRight) return ResizeEdge.BOTTOM_RIGHT
        if (onLeft && inVerticalRange) return ResizeEdge.LEFT
        if (onRight && inVerticalRange) return ResizeEdge.RIGHT
        if (onTop && inHorizontalRange) return ResizeEdge.TOP
        if (onBottom && inHorizontalRange) return ResizeEdge.BOTTOM

        return ResizeEdge.NONE
    }

    private fun resizeCropRect(dx: Float, dy: Float) {
        var newLeft = cropStartRect.left
        var newTop = cropStartRect.top
        var newRight = cropStartRect.right
        var newBottom = cropStartRect.bottom

        when (resizeEdge) {
            ResizeEdge.LEFT -> newLeft = cropStartRect.left + dx
            ResizeEdge.RIGHT -> newRight = cropStartRect.right + dx
            ResizeEdge.TOP -> newTop = cropStartRect.top + dy
            ResizeEdge.BOTTOM -> newBottom = cropStartRect.bottom + dy
            ResizeEdge.TOP_LEFT -> {
                newLeft = cropStartRect.left + dx
                newTop = cropStartRect.top + dy
            }
            ResizeEdge.TOP_RIGHT -> {
                newRight = cropStartRect.right + dx
                newTop = cropStartRect.top + dy
            }
            ResizeEdge.BOTTOM_LEFT -> {
                newLeft = cropStartRect.left + dx
                newBottom = cropStartRect.bottom + dy
            }
            ResizeEdge.BOTTOM_RIGHT -> {
                newRight = cropStartRect.right + dx
                newBottom = cropStartRect.bottom + dy
            }
            ResizeEdge.NONE -> return
        }

        newLeft = max(imageRect.left, newLeft)
        newTop = max(imageRect.top, newTop)
        newRight = min(imageRect.right, newRight)
        newBottom = min(imageRect.bottom, newBottom)

        if (newRight - newLeft < minCropSize) {
            if (resizeEdge == ResizeEdge.LEFT || resizeEdge == ResizeEdge.TOP_LEFT || resizeEdge == ResizeEdge.BOTTOM_LEFT) {
                newLeft = newRight - minCropSize
            } else {
                newRight = newLeft + minCropSize
            }
        }
        if (newBottom - newTop < minCropSize) {
            if (resizeEdge == ResizeEdge.TOP || resizeEdge == ResizeEdge.TOP_LEFT || resizeEdge == ResizeEdge.TOP_RIGHT) {
                newTop = newBottom - minCropSize
            } else {
                newBottom = newTop + minCropSize
            }
        }

        newLeft = max(imageRect.left, newLeft)
        newTop = max(imageRect.top, newTop)
        newRight = min(imageRect.right, newRight)
        newBottom = min(imageRect.bottom, newBottom)

        if (aspectRatio != null) {
            val ratio = aspectRatio!!
            val w = newRight - newLeft
            val h = newBottom - newTop
            val currentRatio = w / h

            when (resizeEdge) {
                ResizeEdge.LEFT, ResizeEdge.RIGHT -> {
                    val newH = w / ratio
                    val cy = cropStartRect.centerY()
                    newTop = cy - newH / 2
                    newBottom = cy + newH / 2
                    if (newTop < imageRect.top) {
                        newTop = imageRect.top
                        newBottom = newTop + newH
                    }
                    if (newBottom > imageRect.bottom) {
                        newBottom = imageRect.bottom
                        newTop = newBottom - newH
                    }
                }
                ResizeEdge.TOP, ResizeEdge.BOTTOM -> {
                    val newW = h * ratio
                    val cx = cropStartRect.centerX()
                    newLeft = cx - newW / 2
                    newRight = cx + newW / 2
                    if (newLeft < imageRect.left) {
                        newLeft = imageRect.left
                        newRight = newLeft + newW
                    }
                    if (newRight > imageRect.right) {
                        newRight = imageRect.right
                        newLeft = newRight - newW
                    }
                }
                ResizeEdge.TOP_LEFT -> {
                    val targetH = w / ratio
                    newTop = newBottom - targetH
                    if (newTop < imageRect.top) {
                        newTop = imageRect.top
                        val adjustedW = (newBottom - newTop) * ratio
                        newLeft = newRight - adjustedW
                    }
                }
                ResizeEdge.TOP_RIGHT -> {
                    val targetH = w / ratio
                    newTop = newBottom - targetH
                    if (newTop < imageRect.top) {
                        newTop = imageRect.top
                        val adjustedW = (newBottom - newTop) * ratio
                        newRight = newLeft + adjustedW
                    }
                }
                ResizeEdge.BOTTOM_LEFT -> {
                    val targetH = w / ratio
                    newBottom = newTop + targetH
                    if (newBottom > imageRect.bottom) {
                        newBottom = imageRect.bottom
                        val adjustedW = (newBottom - newTop) * ratio
                        newLeft = newRight - adjustedW
                    }
                }
                ResizeEdge.BOTTOM_RIGHT -> {
                    val targetH = w / ratio
                    newBottom = newTop + targetH
                    if (newBottom > imageRect.bottom) {
                        newBottom = imageRect.bottom
                        val adjustedW = (newBottom - newTop) * ratio
                        newRight = newLeft + adjustedW
                    }
                }
                ResizeEdge.NONE -> {}
            }
        }

        cropRect.set(newLeft, newTop, newRight, newBottom)
    }

    private fun isInsideCropRect(x: Float, y: Float): Boolean {
        return cropRect.contains(x, y)
    }

    fun setAspectRatio(ratio: Float?) {
        aspectRatio = ratio
        if (!imageRect.isEmpty) {
            resetCropRect()
            invalidate()
        }
    }

    private fun resetCropRect() {
        if (imageRect.isEmpty) return

        if (aspectRatio == null) {
            cropRect.set(imageRect)
        } else {
            val ratio = aspectRatio!!
            val imageWidth = imageRect.width()
            val imageHeight = imageRect.height()

            var cropWidth: Float
            var cropHeight: Float

            if (imageWidth / imageHeight > ratio) {
                cropHeight = imageHeight * 0.8f
                cropWidth = cropHeight * ratio
            } else {
                cropWidth = imageWidth * 0.8f
                cropHeight = cropWidth / ratio
            }

            val left = imageRect.left + (imageWidth - cropWidth) / 2
            val top = imageRect.top + (imageHeight - cropHeight) / 2

            cropRect.set(left, top, left + cropWidth, top + cropHeight)
        }
    }

    fun getCroppedBitmap(): Bitmap? {
        val drawable = drawable ?: return null
        if (imageRect.isEmpty || cropRect.isEmpty) return null

        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()

        val scaleX = drawableWidth / imageRect.width()
        val scaleY = drawableHeight / imageRect.height()

        val cropLeft = ((cropRect.left - imageRect.left) * scaleX).toInt()
        val cropTop = ((cropRect.top - imageRect.top) * scaleY).toInt()
        val cropRight = ((cropRect.right - imageRect.left) * scaleX).toInt()
        val cropBottom = ((cropRect.bottom - imageRect.top) * scaleY).toInt()

        val safeLeft = max(0, cropLeft)
        val safeTop = max(0, cropTop)
        val safeRight = min(drawableWidth.toInt(), cropRight)
        val safeBottom = min(drawableHeight.toInt(), cropBottom)

        if (safeRight <= safeLeft || safeBottom <= safeTop) return null

        val original = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null

        return Bitmap.createBitmap(original, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
    }

    fun reset() {
        cropRect.setEmpty()
        imageRect.setEmpty()
        invalidate()
    }
}
