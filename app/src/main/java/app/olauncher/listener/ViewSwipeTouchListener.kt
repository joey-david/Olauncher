package app.olauncher.listener

import android.content.Context
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import kotlin.math.abs

internal open class ViewSwipeTouchListener(c: Context?, v: View) : OnTouchListener {
    private val gestureDetector: GestureDetector

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> view.isPressed = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
        }
        return gestureDetector.onTouchEvent(motionEvent)
    }

    private inner class GestureListener(private val c: Context?, private val view: View) : SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD: Int = 100
        private val SWIPE_VELOCITY_THRESHOLD: Int = 100

        // Fire once the finger has clearly travelled, instead of waiting for it to lift.
        private val earlySwipeDistance = 56 * (c?.resources?.displayMetrics?.density ?: 2.75f)
        private var swiped = false

        override fun onDown(e: MotionEvent): Boolean {
            swiped = false
            return true
        }

        override fun onScroll(event1: MotionEvent?, event2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (swiped || event1 == null) return false
            val diffX = event2.x - event1.x
            val diffY = event2.y - event1.y
            if (abs(diffX) > earlySwipeDistance && abs(diffX) > 2 * abs(diffY)) {
                swiped = true
                if (diffX > 0) onSwipeRight() else onSwipeLeft()
            } else if (abs(diffY) > earlySwipeDistance && abs(diffY) > 2 * abs(diffX)) {
                swiped = true
                if (diffY < 0) onSwipeUp() else onSwipeDown()
            }
            if (swiped) view.isPressed = false
            return swiped
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onClick(view)
            return super.onSingleTapUp(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleClick()
            return super.onDoubleTap(e)
        }

        override fun onLongPress(e: MotionEvent) {
            onLongClick(view)
            super.onLongPress(e)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (swiped) return false
            try {
                val diffY = event2.y - (event1?.y ?: 0F)
                val diffX = event2.x - (event1?.x ?: 0F)
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) onSwipeRight() else onSwipeLeft()
                    }
                } else {
                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY < 0) onSwipeUp() else onSwipeDown()
                    }
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
            return false
        }
    }

    open fun onSwipeRight() {}
    open fun onSwipeLeft() {}
    open fun onSwipeUp() {}
    open fun onSwipeDown() {}
    open fun onLongClick(view: View) {}
    private fun onDoubleClick() {}
    open fun onClick(view: View) {}

    init {
        gestureDetector = GestureDetector(c, GestureListener(c, v))
    }
}