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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.monet.hct.Cam16;
import com.android.systemui.monet.palettes.CorePalette;
import com.android.systemui.monet.palettes.TonalPalette;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
public final class PalettesTest extends SysuiTestCase {

    @Test
    public void tones_ofBlue() {
        int color = 0xff0000ff;
        Cam16 cam = Cam16.fromInt(color);
        TonalPalette tones = TonalPalette.fromHueAndChroma(cam.getHue(), cam.getChroma());
        assertThat(tones.tone(100)).isEqualTo(0xFFFFFFFF);
        assertThat(tones.tone(95)).isEqualTo(0xfff1efff);
        assertThat(tones.tone(90)).isEqualTo(0xffe0e0ff);
        assertThat(tones.tone(80)).isEqualTo(0xffbec2ff);
        assertThat(tones.tone(70)).isEqualTo(0xff9da3ff);
        assertThat(tones.tone(60)).isEqualTo(0xff7c84ff);
        assertThat(tones.tone(50)).isEqualTo(0xff5a64ff);
        assertThat(tones.tone(40)).isEqualTo(0xff343dff);
        assertThat(tones.tone(30)).isEqualTo(0xff0000ef);
        assertThat(tones.tone(20)).isEqualTo(0xff0001ac);
        assertThat(tones.tone(10)).isEqualTo(0xff00006e);
        assertThat(tones.tone(0)).isEqualTo(0xff000000);
    }

    @Test
    public void keyTones_ofBlue() {
        CorePalette core = CorePalette.of(0xff0000ff);

        assertThat(core.a1.tone(100)).isEqualTo(0xFFFFFFFF);
        assertThat(core.a1.tone(95)).isEqualTo(0xfff1efff);
        assertThat(core.a1.tone(90)).isEqualTo(0xffe0e0ff);
        assertThat(core.a1.tone(80)).isEqualTo(0xffbec2ff);
        assertThat(core.a1.tone(70)).isEqualTo(0xff9da3ff);
        assertThat(core.a1.tone(60)).isEqualTo(0xff7c84ff);
        assertThat(core.a1.tone(50)).isEqualTo(0xff5a64ff);
        assertThat(core.a1.tone(40)).isEqualTo(0xff343dff);
        assertThat(core.a1.tone(30)).isEqualTo(0xff0000ef);
        assertThat(core.a1.tone(20)).isEqualTo(0xff0001ac);
        assertThat(core.a1.tone(10)).isEqualTo(0xff00006e);
        assertThat(core.a1.tone(0)).isEqualTo(0xff000000);

        assertThat(core.a2.tone(100)).isEqualTo(0xffffffff);
        assertThat(core.a2.tone(95)).isEqualTo(0xfff1efff);
        assertThat(core.a2.tone(90)).isEqualTo(0xffe1e0f9);
        assertThat(core.a2.tone(80)).isEqualTo(0xffc5c4dd);
        assertThat(core.a2.tone(70)).isEqualTo(0xffa9a9c1);
        assertThat(core.a2.tone(60)).isEqualTo(0xff8f8fa6);
        assertThat(core.a2.tone(50)).isEqualTo(0xff75758b);
        assertThat(core.a2.tone(40)).isEqualTo(0xff5c5d72);
        assertThat(core.a2.tone(30)).isEqualTo(0xff444559);
        assertThat(core.a2.tone(20)).isEqualTo(0xff2e2f42);
        assertThat(core.a2.tone(10)).isEqualTo(0xff191a2c);
        assertThat(core.a2.tone(0)).isEqualTo(0xff000000);
    }

    @Test
    public void keyTones_contentOfBlue() {
        CorePalette core = CorePalette.contentOf(0xff0000ff);

        assertThat(core.a1.tone(100)).isEqualTo(0xffffffff);
        assertThat(core.a1.tone(95)).isEqualTo(0xfff1efff);
        assertThat(core.a1.tone(90)).isEqualTo(0xffe0e0ff);
        assertThat(core.a1.tone(80)).isEqualTo(0xffbec2ff);
        assertThat(core.a1.tone(70)).isEqualTo(0xff9da3ff);
        assertThat(core.a1.tone(60)).isEqualTo(0xff7c84ff);
        assertThat(core.a1.tone(50)).isEqualTo(0xff5a64ff);
        assertThat(core.a1.tone(40)).isEqualTo(0xff343dff);
        assertThat(core.a1.tone(30)).isEqualTo(0xff0000ef);
        assertThat(core.a1.tone(20)).isEqualTo(0xff0001ac);
        assertThat(core.a1.tone(10)).isEqualTo(0xff00006e);
        assertThat(core.a1.tone(0)).isEqualTo(0xff000000);

        assertThat(core.a2.tone(100)).isEqualTo(0xffffffff);
        assertThat(core.a2.tone(95)).isEqualTo(0xfff1efff);
        assertThat(core.a2.tone(90)).isEqualTo(0xffe0e0ff);
        assertThat(core.a2.tone(80)).isEqualTo(0xffc1c3f4);
        assertThat(core.a2.tone(70)).isEqualTo(0xffa5a7d7);
        assertThat(core.a2.tone(60)).isEqualTo(0xff8b8dbb);
        assertThat(core.a2.tone(50)).isEqualTo(0xff7173a0);
        assertThat(core.a2.tone(40)).isEqualTo(0xff585b86);
        assertThat(core.a2.tone(30)).isEqualTo(0xff40436d);
        assertThat(core.a2.tone(20)).isEqualTo(0xff2a2d55);
        assertThat(core.a2.tone(10)).isEqualTo(0xff14173f);
        assertThat(core.a2.tone(0)).isEqualTo(0xff000000);
    }
}
