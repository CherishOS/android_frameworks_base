/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.testing.AndroidTestingRunner
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

private const val TEXT = "Hello, World."
private const val BMP_WIDTH = 400
private const val BMP_HEIGHT = 300

private val PAINT = TextPaint().apply {
    textSize = 32f
}

private val START_PAINT = arrayListOf<Paint>(TextPaint(PAINT).apply {
    fontVariationSettings = "'wght' 400"
})

private val END_PAINT = arrayListOf<Paint>(TextPaint(PAINT).apply {
    fontVariationSettings = "'wght' 700"
})

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TextInterpolatorTest : SysuiTestCase() {

    private fun makeLayout(text: String, paint: TextPaint): Layout {
        val width = ceil(Layout.getDesiredWidth(text, 0, text.length, paint)).toInt()
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
    }

    @Test
    fun testStartState() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        TextInterpolator.updatePaint(interp.basePaint, START_PAINT)
        interp.onBasePaintModified()

        TextInterpolator.updatePaint(interp.targetPaint, END_PAINT)
        interp.onTargetPaintModified()

        // Just after created TextInterpolator, it should have 0 progress.
        assertThat(interp.progress).isEqualTo(0f)
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(TEXT, START_PAINT[0] as TextPaint).toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testEndState() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        TextInterpolator.updatePaint(interp.basePaint, START_PAINT)
        interp.onBasePaintModified()

        TextInterpolator.updatePaint(interp.targetPaint, END_PAINT)
        interp.onTargetPaintModified()

        interp.progress = 1f
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        val expected = makeLayout(TEXT, END_PAINT[0] as TextPaint).toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }

    @Test
    fun testMiddleState() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        TextInterpolator.updatePaint(interp.basePaint, START_PAINT)
        interp.onBasePaintModified()

        TextInterpolator.updatePaint(interp.targetPaint, END_PAINT)
        interp.onTargetPaintModified()

        // We cannot expect exact text layout of the middle position since we don't use text shaping
        // result for the middle state for performance reason. Just check it is not equals to start
        // end state.
        interp.progress = 0.5f
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)
        assertThat(actual.sameAs(makeLayout(TEXT, START_PAINT[0] as TextPaint)
            .toBitmap(BMP_WIDTH, BMP_HEIGHT))).isFalse()
        assertThat(actual.sameAs(makeLayout(TEXT, END_PAINT[0] as TextPaint)
            .toBitmap(BMP_WIDTH, BMP_HEIGHT))).isFalse()
    }

    @Test
    fun testRebase() {
        val layout = makeLayout(TEXT, PAINT)

        val interp = TextInterpolator(layout)
        TextInterpolator.updatePaint(interp.basePaint, START_PAINT)
        interp.onBasePaintModified()

        TextInterpolator.updatePaint(interp.targetPaint, END_PAINT)
        interp.onTargetPaintModified()

        interp.progress = 0.5f
        val expected = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)

        // Rebase base state to the current state of progress 0.5.
        interp.rebase()
        assertThat(interp.progress).isEqualTo(0f)
        val actual = interp.toBitmap(BMP_WIDTH, BMP_HEIGHT)

        assertThat(expected.sameAs(actual)).isTrue()
    }
}

private fun Layout.toBitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also { draw(Canvas(it)) }!!

private fun TextInterpolator.toBitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also { draw(Canvas(it)) }