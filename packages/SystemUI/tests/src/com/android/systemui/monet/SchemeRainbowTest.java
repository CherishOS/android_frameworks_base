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

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.monet.dynamiccolor.MaterialDynamicColors;
import com.android.systemui.monet.hct.Hct;
import com.android.systemui.monet.scheme.SchemeRainbow;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Ignore("b/279581953")
@SmallTest
@RunWith(JUnit4.class)
public final class SchemeRainbowTest extends SysuiTestCase {

    private final MaterialDynamicColors dynamicColors = new MaterialDynamicColors();

    @Test
    public void testKeyColors() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(dynamicColors.primaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff696FC4);
        assertThat(dynamicColors.secondaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff75758B);
        assertThat(dynamicColors.tertiaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff936B84);
        assertThat(dynamicColors.neutralPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff070707);
        assertThat(dynamicColors.neutralVariantPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff070707);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff676DC1);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff5056A9);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff0F136A);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(
                0xffE0E0FF);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(
                0xffE0E0FF);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(
                0xff34398B);
    }

    @Test
    public void lightTheme_minContrast_tertiaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(
                0xffffd8ee);
    }

    @Test
    public void lightTheme_standardContrast_tertiaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(
                0xffffd8ee);
    }

    @Test
    public void lightTheme_maxContrast_tertiaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(
                0xff5A384E);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(
                0xff565CB0);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(
                0xff050865);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(
                0xffd6d6ff);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfff9f9f9);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfff9f9f9);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfff9f9f9);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff676DC1);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xffbec2ff);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xfff6f4ff);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(
                0xff383E8F);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(
                0xff383E8F);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(
                0xffc4c6ff);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(
                0xffa9afff);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(
                0xffe0e0ff);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(
                0xff292f81);
    }

    @Test
    public void darkTheme_minContrast_onTertiaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.onTertiaryContainer().getArgb(scheme)).isSameColorAs(
                0xffd5a8c3);
    }

    @Test
    public void darkTheme_standardContrast_onTertiaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.onTertiaryContainer().getArgb(scheme)).isSameColorAs(
                0xffffd8ee);
    }

    @Test
    public void darkTheme_maxContrast_onTertiaryContainer() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.onTertiaryContainer().getArgb(scheme)).isSameColorAs(
                0xff4f2e44);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff131313);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff131313);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeRainbow scheme = new SchemeRainbow(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff131313);
    }
}
