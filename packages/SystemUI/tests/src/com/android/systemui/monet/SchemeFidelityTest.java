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
import com.android.systemui.monet.scheme.SchemeFidelity;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Ignore("b/279581953")
@SmallTest
@RunWith(JUnit4.class)
public final class SchemeFidelityTest extends SysuiTestCase {

    private final MaterialDynamicColors dynamicColors = new MaterialDynamicColors();

    @Test
    public void testKeyColors() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(dynamicColors.primaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff080CFF);
        assertThat(dynamicColors.secondaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff656DD3);
        assertThat(dynamicColors.tertiaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff9D0002);
        assertThat(dynamicColors.neutralPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff767684);
        assertThat(dynamicColors.neutralVariantPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff757589);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff1218ff);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff0001c3);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff000181);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff5660ff);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff2d36ff);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff0000e3);
    }

    @Test
    public void lightTheme_minContrast_tertiaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(0xffd93628);
    }

    @Test
    public void lightTheme_standardContrast_tertiaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(0xffb31910);
    }

    @Test
    public void lightTheme_maxContrast_tertiaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(0xff8c0002);
    }

    @Test
    public void lightTheme_minContrast_objectionableTertiaryContainerLightens() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff850096), false, -1.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(0xffbcac5a);
    }

    @Test
    public void lightTheme_standardContrast_objectionableTertiaryContainerLightens() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff850096), false, 0.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(0xffbcac5a);
    }

    @Test
    public void lightTheme_maxContrast_objectionableTertiaryContainerDarkens() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff850096), false, 1.0);
        assertThat(dynamicColors.tertiaryContainer().getArgb(scheme)).isSameColorAs(0xff4d4300);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xffcbcdff);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xffcecfff);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xffd6d6ff);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff5660ff);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xffbec2ff);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xfff6f4ff);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff0000e6);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff0000e6);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xffc4c6ff);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xff7a83ff);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xffa4aaff);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xff0001c6);
    }

    @Test
    public void darkTheme_minContrast_onTertiaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.onTertiaryContainer().getArgb(scheme)).isSameColorAs(0xfffe513e);
    }

    @Test
    public void darkTheme_standardContrast_onTertiaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.onTertiaryContainer().getArgb(scheme)).isSameColorAs(0xffFF9181);
    }

    @Test
    public void darkTheme_maxContrast_onTertiaryContainer() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.onTertiaryContainer().getArgb(scheme)).isSameColorAs(0xff790001);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff12121d);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff12121d);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeFidelity scheme = new SchemeFidelity(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff12121d);
    }

}
