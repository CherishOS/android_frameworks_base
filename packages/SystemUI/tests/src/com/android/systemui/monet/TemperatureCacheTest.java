/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.monet;

import static com.android.systemui.monet.utils.ArgbSubject.assertThat;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.monet.hct.Hct;
import com.android.systemui.monet.temperature.TemperatureCache;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@SmallTest
@RunWith(JUnit4.class)
public final class TemperatureCacheTest extends SysuiTestCase {

    @Test
    public void testRawTemperature() {
        final Hct blueHct = Hct.fromInt(0xff0000ff);
        final double blueTemp = TemperatureCache.rawTemperature(blueHct);
        assertEquals(-1.393, blueTemp, 0.001);

        final Hct redHct = Hct.fromInt(0xffff0000);
        final double redTemp = TemperatureCache.rawTemperature(redHct);
        assertEquals(2.351, redTemp, 0.001);

        final Hct greenHct = Hct.fromInt(0xff00ff00);
        final double greenTemp = TemperatureCache.rawTemperature(greenHct);
        assertEquals(-0.267, greenTemp, 0.001);

        final Hct whiteHct = Hct.fromInt(0xffffffff);
        final double whiteTemp = TemperatureCache.rawTemperature(whiteHct);
        assertEquals(-0.5, whiteTemp, 0.001);

        final Hct blackHct = Hct.fromInt(0xff000000);
        final double blackTemp = TemperatureCache.rawTemperature(blackHct);
        assertEquals(-0.5, blackTemp, 0.001);
    }

    @Test
    public void testComplement() {
        final int blueComplement =
                new TemperatureCache(Hct.fromInt(0xff0000ff)).getComplement().toInt();
        assertThat(0xff9D0002).isSameColorAs(blueComplement);

        final int redComplement = new TemperatureCache(
                Hct.fromInt(0xffff0000)).getComplement().toInt();
        assertThat(0xff007BFC).isSameColorAs(redComplement);

        final int greenComplement =
                new TemperatureCache(Hct.fromInt(0xff00ff00)).getComplement().toInt();
        assertThat(0xffFFD2C9).isSameColorAs(greenComplement);

        final int whiteComplement =
                new TemperatureCache(Hct.fromInt(0xffffffff)).getComplement().toInt();
        assertThat(0xffffffff).isSameColorAs(whiteComplement);

        final int blackComplement =
                new TemperatureCache(Hct.fromInt(0xff000000)).getComplement().toInt();
        assertThat(0xff000000).isSameColorAs(blackComplement);
    }

    @Test
    public void testAnalogous() {
        final List<Hct> blueAnalogous =
                new TemperatureCache(Hct.fromInt(0xff0000ff)).getAnalogousColors();
        assertThat(0xff00590C).isSameColorAs(blueAnalogous.get(0).toInt());
        assertThat(0xff00564E).isSameColorAs(blueAnalogous.get(1).toInt());
        assertThat(0xff0000ff).isSameColorAs(blueAnalogous.get(2).toInt());
        assertThat(0xff6700CC).isSameColorAs(blueAnalogous.get(3).toInt());
        assertThat(0xff81009F).isSameColorAs(blueAnalogous.get(4).toInt());

        final List<Hct> redAnalogous =
                new TemperatureCache(Hct.fromInt(0xffff0000)).getAnalogousColors();
        assertThat(0xffF60082).isSameColorAs(redAnalogous.get(0).toInt());
        assertThat(0xffFC004C).isSameColorAs(redAnalogous.get(1).toInt());
        assertThat(0xffff0000).isSameColorAs(redAnalogous.get(2).toInt());
        assertThat(0xffD95500).isSameColorAs(redAnalogous.get(3).toInt());
        assertThat(0xffAF7200).isSameColorAs(redAnalogous.get(4).toInt());

        final List<Hct> greenAnalogous =
                new TemperatureCache(Hct.fromInt(0xff00ff00)).getAnalogousColors();
        assertThat(0xffCEE900).isSameColorAs(greenAnalogous.get(0).toInt());
        assertThat(0xff92F500).isSameColorAs(greenAnalogous.get(1).toInt());
        assertThat(0xff00ff00).isSameColorAs(greenAnalogous.get(2).toInt());
        assertThat(0xff00FD6F).isSameColorAs(greenAnalogous.get(3).toInt());
        assertThat(0xff00FAB3).isSameColorAs(greenAnalogous.get(4).toInt());

        final List<Hct> blackAnalogous =
                new TemperatureCache(Hct.fromInt(0xff000000)).getAnalogousColors();
        assertThat(0xff000000).isSameColorAs(blackAnalogous.get(0).toInt());
        assertThat(0xff000000).isSameColorAs(blackAnalogous.get(1).toInt());
        assertThat(0xff000000).isSameColorAs(blackAnalogous.get(2).toInt());
        assertThat(0xff000000).isSameColorAs(blackAnalogous.get(3).toInt());
        assertThat(0xff000000).isSameColorAs(blackAnalogous.get(4).toInt());

        final List<Hct> whiteAnalogous =
                new TemperatureCache(Hct.fromInt(0xffffffff)).getAnalogousColors();
        assertThat(0xffffffff).isSameColorAs(whiteAnalogous.get(0).toInt());
        assertThat(0xffffffff).isSameColorAs(whiteAnalogous.get(1).toInt());
        assertThat(0xffffffff).isSameColorAs(whiteAnalogous.get(2).toInt());
        assertThat(0xffffffff).isSameColorAs(whiteAnalogous.get(3).toInt());
        assertThat(0xffffffff).isSameColorAs(whiteAnalogous.get(4).toInt());
    }
}
