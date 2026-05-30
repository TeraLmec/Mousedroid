package com.darusc.mousedroid.mkinput

import android.content.Context
import android.os.Looper
import android.view.*
import androidx.core.view.GestureDetectorCompat
import com.darusc.mousedroid.layouts.KeyboardLayout
import com.darusc.mousedroid.layouts.Keycode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Class that handles simples gestures (tap, long press, scroll) using
 * a GestureDetectorCompat
 */
class GestureHandler(
    context: Context,
    private val sendInputCallback: (InputEvent) -> (Unit)
) : View.OnTouchListener {

    private val TAG = "Mousedroid"
    private val EV_DELAY_MILLIS: Long = 150
    private val SCROLL_TRESHOLD = 2.0f
    private val MULTI_FINGER_TAP_THRESHOLD = 28.0f
    private val MULTI_FINGER_SWIPE_THRESHOLD = 120.0f
    private val TWO_FINGER_SCROLL_LOCK_THRESHOLD = 10.0f
    private val TWO_FINGER_ZOOM_LOCK_THRESHOLD = 28.0f
    private val ZOOM_PIXEL_THRESHOLD = 20.0f
    private val MOVE_FRAME_DELAY_MILLIS = 8L

    private enum class TwoFingerMode {
        NONE,
        PENDING,
        SCROLL,
        ZOOM
    }

    private data class State(
        var scrolling: Boolean,
        var lastScrolled: Long,
        var doublePress: Boolean,
        var lastDoublePress: Long,
        var dragging: Boolean,
        var activeMouseWhileDragging: InputEvent.MouseButton,
        var maxPointers: Int,
        var multiStartX: Float,
        var multiStartY: Float,
        var multiLastX: Float,
        var multiLastY: Float,
        var twoFingerMode: TwoFingerMode,
        var twoStartSpan: Float,
        var twoLastSpan: Float,
        var accumulatedZoom: Float,
        var multiGestureConsumed: Boolean,
        var suppressNextSingleTap: Boolean,
        var suppressNextPointerTap: Boolean,
        var suppressUntilAllPointersUp: Boolean,
        var hasSinglePointer: Boolean,
        var lastSingleX: Float,
        var lastSingleY: Float,
        var singleRemainderX: Float,
        var singleRemainderY: Float,
        var pendingMoveX: Float,
        var pendingMoveY: Float,
        var moveFlushScheduled: Boolean
    )

    private val state = State(
        false,
        0,
        false,
        0,
        false,
        InputEvent.MouseButton.NONE,
        0,
        0f,
        0f,
        0f,
        0f,
        TwoFingerMode.NONE,
        0f,
        0f,
        0f,
        false,
        false,
        false,
        false,
        false,
        0f,
        0f,
        0f,
        0f,
        0f,
        0f,
        false
    )
//    private val scaleDetector: ScaleGestureDetector =
//        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
//            override fun onScale(detector: ScaleGestureDetector): Boolean {
//                val scale = (ln(detector.scaleFactor) * 500).toInt().coerceIn(-128, 127).toByte()
//                if (!state.dragging) {
//                    connectionManager.send(InputEvent.Zoom(scale.toInt()), true)
//                }
//
//                return true
//            }
//        })

    /**
     * Detector used for gestures like: tap, double tap, move, drag and scroll
     */
    private val gestureDetector: GestureDetectorCompat =
        GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (state.suppressNextSingleTap) {
                    state.suppressNextSingleTap = false
                    return true
                }

                // Post a runnable that sends a click event after a set delay
                // onDoubleTap will cancel it when called
                singleTapRunnable = Runnable {
                    sendInputCallback(InputEvent.MouseClick(InputEvent.MouseButton.LEFT))
                }
                handler.postDelayed(singleTapRunnable!!, EV_DELAY_MILLIS)
                return super.onSingleTapUp(e)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Cancel the click event from the single tap
                singleTapRunnable?.let { handler.removeCallbacks(it) }

                state.doublePress = true
                state.lastDoublePress = System.currentTimeMillis()

                // Post a runnable that will send 2 click events after a set time
                // If before that a move event is detected by the onTouch, it will be canceled
                doubleTapRunnable = Runnable {
                    CoroutineScope(Dispatchers.IO).launch {
                        sendInputCallback(InputEvent.MouseClick(InputEvent.MouseButton.LEFT))
                        // Add delay so the 2 clicks are registered when sent via bluetooth
                        // TODO() might break over TCP/UDP
                        delay(75)
                        sendInputCallback(InputEvent.MouseClick(InputEvent.MouseButton.LEFT))

                        state.doublePress = false
                        state.dragging = false
                    }
                }
                handler.postDelayed(doubleTapRunnable!!, EV_DELAY_MILLIS)
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (state.maxPointers >= 3 || e2.pointerCount >= 3) {
                    return true
                }

                if ((e1?.pointerCount == 2 || e2.pointerCount == 2) || System.currentTimeMillis() - state.lastScrolled < EV_DELAY_MILLIS) {
                    if (state.twoFingerMode != TwoFingerMode.SCROLL) {
                        return true
                    }

                    if (abs(distanceX) < SCROLL_TRESHOLD && abs(distanceY) < SCROLL_TRESHOLD) {
                        return super.onScroll(e1, e2, distanceX, distanceY)
                    }

                    // If at least one event has 2 pointers and the time before last scroll is less than 500ms
                    // we continue to scroll
                    state.scrolling = true
                    state.lastScrolled = System.currentTimeMillis()

                    val type: Byte
                    val delta: Float
                    if (abs(distanceY) > abs(distanceX)) {
                        // Vertical scrolling
                        sendInputCallback(
                            InputEvent.MouseScroll(
                                0,
                                -distanceY.toInt().coerceIn(-128, 127)
                            )
                        )
                    } else {
                        // Horizontal scrolling
                        sendInputCallback(
                            InputEvent.MouseScroll(
                                -distanceX.toInt().coerceIn(-128, 127), 0
                            )
                        )
                    }
                } else {
                    return true
                }

                return super.onScroll(e1, e2, distanceX, distanceY)
            }
        })


    private val handler = android.os.Handler(Looper.getMainLooper())
    private var singleTapRunnable: Runnable? = null
    private var doubleTapRunnable: Runnable? = null

    override fun onTouch(p0: View?, p1: MotionEvent?): Boolean {
        val event = p1 ?: return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetSinglePointer(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                state.hasSinglePointer = false
                if (event.pointerCount >= 2) {
                    startMultiFingerGesture(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (state.maxPointers >= 2 && event.pointerCount >= 2) {
                    updateMultiFingerGesture(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val activeTwoFingerMode = state.twoFingerMode
                if (state.maxPointers >= 2 && finishMultiFingerGesture(event)) {
                    return true
                }

                if (state.scrolling || activeTwoFingerMode == TwoFingerMode.ZOOM) {
                    state.scrolling = false
                    state.suppressUntilAllPointersUp = true
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val shouldSuppress = state.suppressUntilAllPointersUp
                state.suppressNextPointerTap = false
                state.suppressUntilAllPointersUp = false
                state.hasSinglePointer = false
                state.singleRemainderX = 0f
                state.singleRemainderY = 0f
                flushPendingMove()
                resetMultiFingerGesture()
                if (shouldSuppress) {
                    return true
                }
            }
        }

        if (state.suppressUntilAllPointersUp) {
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.pointerCount == 2) {
            if (state.suppressNextPointerTap) {
                state.suppressNextPointerTap = false
                return true
            }

            if (state.scrolling) {
                // Cancel scrolling when pointer is lifted up
                state.scrolling = false
                return false
            } else if (System.currentTimeMillis() - state.lastScrolled > 500) {
                // Right click is detected when there are 2 pointers and 1 starts to lift
                // Can't be detected in onSingleTapUp because the 2 pointers are not lifted at the same time
                // so we don't get that event with 2 pointers but rather 2 different events with 1 pointer
                sendInputCallback(InputEvent.MouseClick(InputEvent.MouseButton.RIGHT))
            }
        }

        if (event.action == MotionEvent.ACTION_UP && state.dragging) {
            // Cancel dragging
            state.scrolling = false
            state.doublePress = false
            state.activeMouseWhileDragging = InputEvent.MouseButton.NONE
            sendInputCallback(InputEvent.MouseDragState(InputEvent.MouseButton.LEFT, false))
        }

        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && state.maxPointers == 0) {
                    maybeStartDragging(event)
                    sendSingleFingerMove(event)
                }
            }
        }

        return true
    }

    private fun resetSinglePointer(event: MotionEvent) {
        flushPendingMove()
        state.hasSinglePointer = true
        state.lastSingleX = event.x
        state.lastSingleY = event.y
        state.singleRemainderX = 0f
        state.singleRemainderY = 0f
    }

    private fun maybeStartDragging(event: MotionEvent) {
        if (!state.doublePress || System.currentTimeMillis() - state.lastDoublePress >= EV_DELAY_MILLIS) {
            return
        }

        doubleTapRunnable?.let { handler.removeCallbacks(it) }
        state.doublePress = false
        state.dragging = true
        state.activeMouseWhileDragging = InputEvent.MouseButton.LEFT
        sendInputCallback(
            InputEvent.MouseDragState(
                InputEvent.MouseButton.LEFT,
                true
            )
        )

        val cancelEvent = MotionEvent.obtain(event)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        gestureDetector.onTouchEvent(cancelEvent)
    }

    private fun sendSingleFingerMove(event: MotionEvent) {
        if (state.suppressUntilAllPointersUp) {
            return
        }

        if (!state.hasSinglePointer) {
            resetSinglePointer(event)
            return
        }

        state.pendingMoveX += state.lastSingleX - event.x
        state.pendingMoveY += state.lastSingleY - event.y
        state.lastSingleX = event.x
        state.lastSingleY = event.y

        scheduleMoveFlush()
    }

    private fun scheduleMoveFlush() {
        if (state.moveFlushScheduled) {
            return
        }

        state.moveFlushScheduled = true
        handler.postDelayed({
            state.moveFlushScheduled = false
            flushPendingMove()
        }, MOVE_FRAME_DELAY_MILLIS)
    }

    private fun flushPendingMove() {
        state.singleRemainderX += state.pendingMoveX
        state.singleRemainderY += state.pendingMoveY
        state.pendingMoveX = 0f
        state.pendingMoveY = 0f

        while (true) {
            val moveX = state.singleRemainderX.toInt().coerceIn(-128, 127)
            val moveY = state.singleRemainderY.toInt().coerceIn(-128, 127)

            if (moveX == 0 && moveY == 0) {
                return
            }

            state.singleRemainderX -= moveX
            state.singleRemainderY -= moveY
            sendMouseMove(moveX, moveY)
        }
    }

    private fun sendMouseMove(dx: Int, dy: Int) {
        sendInputCallback(
            InputEvent.MouseMove(
                dx,
                dy,
                state.activeMouseWhileDragging
            )
        )
    }

    private fun startMultiFingerGesture(event: MotionEvent) {
        val center = getPointerCenter(event)
        val span = getPointerSpan(event)

        if (state.maxPointers < 2 || event.pointerCount > state.maxPointers) {
            state.multiStartX = center.first
            state.multiStartY = center.second
        }

        state.maxPointers = maxOf(state.maxPointers, event.pointerCount)
        state.multiLastX = center.first
        state.multiLastY = center.second
        state.multiGestureConsumed = false

        if (event.pointerCount == 2 && state.maxPointers == 2) {
            state.twoFingerMode = TwoFingerMode.PENDING
            state.twoStartSpan = span
            state.twoLastSpan = span
            state.accumulatedZoom = 0f
        } else if (event.pointerCount >= 3) {
            state.twoFingerMode = TwoFingerMode.NONE
        }
    }

    private fun updateMultiFingerGesture(event: MotionEvent) {
        val center = getPointerCenter(event)
        val previousSpan = state.twoLastSpan
        val currentSpan = getPointerSpan(event)
        state.multiLastX = center.first
        state.multiLastY = center.second

        if (state.maxPointers == 2 && event.pointerCount == 2) {
            updateTwoFingerMode(currentSpan)
            if (state.twoFingerMode == TwoFingerMode.ZOOM) {
                sendZoomDelta(currentSpan - previousSpan)
            }
            state.twoLastSpan = currentSpan
        }
    }

    private fun updateTwoFingerMode(currentSpan: Float) {
        if (state.twoFingerMode != TwoFingerMode.PENDING) {
            return
        }

        val centerDx = state.multiLastX - state.multiStartX
        val centerDy = state.multiLastY - state.multiStartY
        val centerDistance = hypot(centerDx, centerDy)
        val spanDistance = abs(currentSpan - state.twoStartSpan)

        if (centerDistance >= TWO_FINGER_SCROLL_LOCK_THRESHOLD && centerDistance > spanDistance * 1.2f) {
            state.twoFingerMode = TwoFingerMode.SCROLL
            state.scrolling = true
            state.lastScrolled = System.currentTimeMillis()
        } else if (spanDistance >= TWO_FINGER_ZOOM_LOCK_THRESHOLD && spanDistance > centerDistance * 1.4f) {
            state.twoFingerMode = TwoFingerMode.ZOOM
            state.scrolling = false
        }
    }

    private fun sendZoomDelta(deltaPixels: Float) {
        state.accumulatedZoom += deltaPixels
        if (abs(state.accumulatedZoom) < ZOOM_PIXEL_THRESHOLD) {
            return
        }

        val ticks = (state.accumulatedZoom / ZOOM_PIXEL_THRESHOLD).toInt()
        if (ticks != 0) {
            sendInputCallback(InputEvent.Zoom(ticks))
            state.accumulatedZoom -= (ticks * ZOOM_PIXEL_THRESHOLD)
        }
    }

    private fun finishMultiFingerGesture(event: MotionEvent): Boolean {
        if (state.multiGestureConsumed) {
            resetMultiFingerGesture()
            return true
        }

        updateMultiFingerGesture(event)

        val dx = state.multiLastX - state.multiStartX
        val dy = state.multiLastY - state.multiStartY
        val absDx = abs(dx)
        val absDy = abs(dy)

        val consumed = when {
            state.maxPointers >= 3 && absDx < MULTI_FINGER_TAP_THRESHOLD && absDy < MULTI_FINGER_TAP_THRESHOLD -> {
                sendInputCallback(InputEvent.MouseClick(InputEvent.MouseButton.MIDDLE))
                true
            }

            state.maxPointers == 3 && absDx > MULTI_FINGER_SWIPE_THRESHOLD && absDx > absDy * 1.4f -> {
                sendShortcut(
                    if (dx < 0) Keycode.KEY_LEFT else Keycode.KEY_RIGHT,
                    Keycode.MOD_LEFT_ALT
                )
                true
            }

            else -> false
        }

        if (consumed) {
            singleTapRunnable?.let { handler.removeCallbacks(it) }
            doubleTapRunnable?.let { handler.removeCallbacks(it) }
            state.scrolling = false
            state.suppressNextSingleTap = true
            state.suppressNextPointerTap = true
            state.multiGestureConsumed = true
        }

        resetMultiFingerGesture()
        return consumed
    }

    private fun resetMultiFingerGesture() {
        state.maxPointers = 0
        state.multiStartX = 0f
        state.multiStartY = 0f
        state.multiLastX = 0f
        state.multiLastY = 0f
        state.twoFingerMode = TwoFingerMode.NONE
        state.twoStartSpan = 0f
        state.twoLastSpan = 0f
        state.accumulatedZoom = 0f
        state.multiGestureConsumed = false
    }

    private fun getPointerCenter(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f

        for (index in 0 until event.pointerCount) {
            x += event.getX(index)
            y += event.getY(index)
        }

        return Pair(x / event.pointerCount, y / event.pointerCount)
    }

    private fun getPointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) {
            return 0f
        }

        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun sendShortcut(keycode: Byte, modifier: Byte) {
        sendInputCallback(
            InputEvent.KeyPress(
                listOf(KeyboardLayout.Key(modifier, keycode))
            )
        )
    }

}
