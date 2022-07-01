package com.android.systemui.statusbar.notification.stack

import android.annotation.DimenRes
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.EmptyShadeView
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.BypassController
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@SmallTest
class StackScrollAlgorithmTest : SysuiTestCase() {

    private val hostView = FrameLayout(context)
    private val stackScrollAlgorithm = StackScrollAlgorithm(context, hostView)
    private val expandableViewState = ExpandableViewState()
    private val notificationRow = mock(ExpandableNotificationRow::class.java)
    private val dumpManager = mock(DumpManager::class.java)
    private val mStatusBarKeyguardViewManager = mock(StatusBarKeyguardViewManager::class.java)

    private val ambientState = AmbientState(
        context,
        dumpManager,
        SectionProvider { _, _ -> false },
        BypassController { false },
        mStatusBarKeyguardViewManager
    )

    private val testableResources = mContext.orCreateTestableResources

    private fun px(@DimenRes id: Int): Float =
            testableResources.resources.getDimensionPixelSize(id).toFloat()

    private val bigGap = px(R.dimen.notification_section_divider_height)
    private val smallGap = px(R.dimen.notification_section_divider_height_lockscreen)

    @Before
    fun setUp() {
        whenever(notificationRow.viewState).thenReturn(expandableViewState)
        hostView.addView(notificationRow)
    }

    @Test
    fun resetViewStates_defaultHun_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(expandableViewState.yTranslation).isEqualTo(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    fun resetViewStates_stackMargin_changesHunYTranslation() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        val minHeadsUpTranslation = context.resources
                .getDimensionPixelSize(R.dimen.notification_side_paddings)

        // split shade case with top margin introduced by shade's status bar
        ambientState.stackTopMargin = 100
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // top margin presence should decrease heads up translation up to minHeadsUpTranslation
        assertThat(expandableViewState.yTranslation).isEqualTo(minHeadsUpTranslation)
    }

    @Test
    fun resetViewStates_emptyShadeView_isCenteredVertically() {
        stackScrollAlgorithm.initView(context)
        val emptyShadeView = EmptyShadeView(context, /* attrs= */ null).apply {
            layout(/* l= */ 0, /* t= */ 0, /* r= */ 100, /* b= */ 100)
        }
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)
        ambientState.layoutMaxHeight = 1280

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val marginBottom =
            context.resources.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom)
        val fullHeight = ambientState.layoutMaxHeight + marginBottom - ambientState.stackY
        val centeredY = ambientState.stackY + fullHeight / 2f - emptyShadeView.height / 2f
        assertThat(emptyShadeView.viewState?.yTranslation).isEqualTo(centeredY)
    }

    @Test
    fun getGapForLocation_onLockscreen_returnsSmallGap() {
        val gap = stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f, /* onKeyguard= */ true)
        assertThat(gap).isEqualTo(smallGap)
    }

    @Test
    fun getGapForLocation_goingToShade_interpolatesGap() {
        val gap = stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0.5f, /* onKeyguard= */ true)
        assertThat(gap).isEqualTo(smallGap * 0.5f + bigGap * 0.5f)
    }

    @Test
    fun getGapForLocation_notOnLockscreen_returnsBigGap() {
        val gap = stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f, /* onKeyguard= */ false)
        assertThat(gap).isEqualTo(bigGap)
    }

    @Test
    fun updateViewWithShelf_viewAboveShelf_viewShown() {
        val viewStart = 0f
        val shelfStart = 1f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.isExpandAnimationRunning).thenReturn(false)
        whenever(expandableView.hasExpandingChild()).thenReturn(false)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertFalse(expandableViewState.hidden)
    }

    @Test
    fun updateViewWithShelf_viewBelowShelf_viewHidden() {
        val shelfStart = 0f
        val viewStart = 1f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.isExpandAnimationRunning).thenReturn(false)
        whenever(expandableView.hasExpandingChild()).thenReturn(false)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertTrue(expandableViewState.hidden)
    }

    @Test
    fun updateViewWithShelf_viewBelowShelfButIsExpanding_viewShown() {
        val shelfStart = 0f
        val viewStart = 1f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.isExpandAnimationRunning).thenReturn(true)
        whenever(expandableView.hasExpandingChild()).thenReturn(true)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertFalse(expandableViewState.hidden)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_endVisible_true() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = false

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 0f,
                /* maxHunY= */ 10f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_endHidden_false() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 0f)

        assertFalse(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_shadeClosed_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ false,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 1f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_notHUN_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ false,
                /* isViewEndVisible= */ true,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 1f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_topHidden_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(expandableViewState,
                /* isShadeExpanded= */ true,
                /* mustStayOnScreen= */ true,
                /* isViewEndVisible= */ false,
                /* viewEnd= */ 10f,
                /* maxHunY= */ 1f)

        assertTrue(expandableViewState.headsUpIsVisible)
    }

    @Test
    fun clampHunToTop_viewYGreaterThanQqs_viewYUnchanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = 50f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 1f, expandableViewState)

        // qqs (10 + 0) < viewY (50)
        assertEquals(50f, expandableViewState.yTranslation)
    }

    @Test
    fun clampHunToTop_viewYLessThanQqs_viewYChanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = -10f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 1f, expandableViewState)

        // qqs (10 + 0) > viewY (-10)
        assertEquals(10f, expandableViewState.yTranslation)
    }


    @Test
    fun clampHunToTop_viewYFarAboveVisibleStack_heightCollapsed() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.height = 20
        expandableViewState.yTranslation = -100f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 10f, expandableViewState)

        // newTranslation = max(10, -100) = 10
        // distToRealY = 10 - (-100f) = 110
        // height = max(20 - 110, 10f)
        assertEquals(10, expandableViewState.height)
    }

    @Test
    fun clampHunToTop_viewYNearVisibleStack_heightTallerThanCollapsed() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.height = 20
        expandableViewState.yTranslation = 5f

        stackScrollAlgorithm.clampHunToTop(/* quickQsOffsetHeight= */ 10f,
                /* stackTranslation= */ 0f,
                /* collapsedHeight= */ 10f, expandableViewState)

        // newTranslation = max(10, 5) = 10
        // distToRealY = 10 - 5 = 5
        // height = max(20 - 5, 10) = 15
        assertEquals(15, expandableViewState.height)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackBelowScreen_round() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 110f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f)
        assertEquals(1f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAboveScreenBelowPinPoint_halfRound() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 90f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f)
        assertEquals(0.5f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAbovePinPoint_notRound() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 0f)
        assertEquals(0f, currentRoundness)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_originallyRoundAndStackAbovePinPoint_round() {
        val currentRoundness = stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRoundness= */ 1f)
        assertEquals(1f, currentRoundness)
    }
}
