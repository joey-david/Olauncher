package app.olauncher.listener

import android.content.Context
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import kotlin.math.abs

/*
Swipe, double tap and long press touch listener for a view
Source: https://www.tutorialspoint.com/how-to-handle-swipe-gestures-in-kotlin
*/

internal open class OnSwipeTouchListener(private val c: Context?) : OnTouchListener {

    //    private var doubleTapOn = false
    private val gestureDetector: GestureDetector

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(motionEvent)
    }

    private inner class GestureListener : SimpleOnGestureListener() {
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
            return swiped
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
//            if (doubleTapOn) {
//                doubleTapOn = false
//                onTripleClick()
//            }
            onClick()
            return super.onSingleTapUp(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
//            doubleTapOn = true
//            Timer().schedule(Constants.TRIPLE_TAP_DELAY_MS) {
//                if (doubleTapOn) {
//                    doubleTapOn = false
//                    onDoubleClick()
//                }
//            }
            onDoubleClick()
            return super.onDoubleTap(e)
        }

        override fun onLongPress(e: MotionEvent) {
            onLongClick()
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
    open fun onLongClick() {}
    open fun onDoubleClick() {}
    open fun onTripleClick() {}
    open fun onClick() {}

    init {
        gestureDetector = GestureDetector(c, GestureListener())
    }
}