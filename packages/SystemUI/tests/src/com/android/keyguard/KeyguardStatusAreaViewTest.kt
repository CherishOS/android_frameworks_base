package com.android.keyguard

import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class KeyguardStatusAreaViewTest : SysuiTestCase() {

    private lateinit var view: KeyguardStatusAreaView

    @Before
    fun setUp() {
        view = KeyguardStatusAreaView(context)
    }

    @Test
    fun checkTranslationX_AddedTotals() {
        view.translateXFromClockDesign = 10f
        assertEquals(10f, view.translationX)

        view.translateXFromAod = 20f
        assertEquals(30f, view.translationX)

        view.translateXFromUnfold = 30f
        assertEquals(60f, view.translationX)
    }

    @Test
    fun checkTranslationY_AddedTotals() {
        view.translateYFromClockSize = 10f
        assertEquals(10f, view.translationY)

        view.translateYFromClockDesign = 20f
        assertEquals(30f, view.translationY)
    }
}
