package com.android.systemui.shade

import android.view.MotionEvent
import com.android.systemui.log.dagger.ShadeLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.plugins.log.LogMessage
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "systemui.shade"

/** Lightweight logging utility for the Shade. */
class ShadeLogger @Inject constructor(@ShadeLog private val buffer: LogBuffer) {
    fun v(@CompileTimeConstant msg: String) {
        buffer.log(TAG, LogLevel.VERBOSE, msg)
    }

    fun d(@CompileTimeConstant msg: String) {
        buffer.log(TAG, LogLevel.DEBUG, msg)
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }

    fun onQsInterceptMoveQsTrackingEnabled(h: Float) {
        log(
            LogLevel.VERBOSE,
            { double1 = h.toDouble() },
            { "onQsIntercept: move action, QS tracking enabled. h = $double1" }
        )
    }

    fun logQsTrackingNotStarted(
        initialTouchY: Float,
        y: Float,
        h: Float,
        touchSlop: Float,
        qsExpanded: Boolean,
        collapsedOnDown: Boolean,
        keyguardShowing: Boolean,
        qsExpansionEnabled: Boolean
    ) {
        log(
            LogLevel.VERBOSE,
            {
                int1 = initialTouchY.toInt()
                int2 = y.toInt()
                long1 = h.toLong()
                double1 = touchSlop.toDouble()
                bool1 = qsExpanded
                bool2 = collapsedOnDown
                bool3 = keyguardShowing
                bool4 = qsExpansionEnabled
            },
            {
                "QsTrackingNotStarted: initTouchY=$int1,y=$int2,h=$long1,slop=$double1,qsExpanded" +
                    "=$bool1,collapsedDown=$bool2,keyguardShowing=$bool3,qsExpansion=$bool4"
            }
        )
    }

    fun logMotionEvent(event: MotionEvent, message: String) {
        log(
            LogLevel.VERBOSE,
            {
                str1 = message
                long1 = event.eventTime
                long2 = event.downTime
                int1 = event.action
                int2 = event.classification
                double1 = event.y.toDouble()
            },
            {
                "$str1\neventTime=$long1,downTime=$long2,y=$double1,action=$int1,class=$int2"
            }
        )
    }

    fun logExpansionChanged(
            message: String,
            fraction: Float,
            expanded: Boolean,
            tracking: Boolean,
            dragDownPxAmount: Float,
    ) {
        log(LogLevel.VERBOSE, {
            str1 = message
            double1 = fraction.toDouble()
            bool1 = expanded
            bool2 = tracking
            long1 = dragDownPxAmount.toLong()
        }, {
            "$str1 fraction=$double1,expanded=$bool1," +
                    "tracking=$bool2," + "dragDownPxAmount=$dragDownPxAmount"
        })
    }

    fun logQsExpansionChanged(
            message: String,
            qsExpanded: Boolean,
            qsMinExpansionHeight: Int,
            qsMaxExpansionHeight: Int,
            stackScrollerOverscrolling: Boolean,
            dozing: Boolean,
            qsAnimatorExpand: Boolean,
            animatingQs: Boolean
    ) {
        log(LogLevel.VERBOSE, {
            str1 = message
            bool1 = qsExpanded
            int1 = qsMinExpansionHeight
            int2 = qsMaxExpansionHeight
            bool2 = stackScrollerOverscrolling
            bool3 = dozing
            bool4 = qsAnimatorExpand
            // 0 = false, 1 = true
            long1 = animatingQs.compareTo(false).toLong()
        }, {
            "$str1 qsExpanded=$bool1,qsMinExpansionHeight=$int1,qsMaxExpansionHeight=$int2," +
                    "stackScrollerOverscrolling=$bool2,dozing=$bool3,qsAnimatorExpand=$bool4," +
                    "animatingQs=$long1"
        })
    }

    fun logSingleTapUp(isDozing: Boolean, singleTapEnabled: Boolean, isNotDocked: Boolean) {
        log(LogLevel.DEBUG, {
            bool1 = isDozing
            bool2 = singleTapEnabled
            bool3 = isNotDocked
        }, {
            "PulsingGestureListener#onSingleTapUp all of this must true for single " +
              "tap to be detected: isDozing: $bool1, singleTapEnabled: $bool2, isNotDocked: $bool3"
        })
    }

    fun logSingleTapUpFalsingState(proximityIsNotNear: Boolean, isNotFalseTap: Boolean) {
        log(LogLevel.DEBUG, {
            bool1 = proximityIsNotNear
            bool2 = isNotFalseTap
        }, {
            "PulsingGestureListener#onSingleTapUp all of this must true for single " +
                    "tap to be detected: proximityIsNotNear: $bool1, isNotFalseTap: $bool2"
        })
    }
}
