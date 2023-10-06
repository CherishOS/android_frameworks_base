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
import com.android.systemui.monet.scheme.SchemeExpressive;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Ignore("b/279581953")
@SmallTest
@RunWith(JUnit4.class)
public final class SchemeExpressiveTest extends SysuiTestCase {

    private final MaterialDynamicColors dynamicColors = new MaterialDynamicColors();

    @Test
    public void testKeyColors() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(dynamicColors.primaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff35855F);
        assertThat(dynamicColors.secondaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff8C6D8C);
        assertThat(dynamicColors.tertiaryPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff806EA1);
        assertThat(dynamicColors.neutralPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff79757F);
        assertThat(dynamicColors.neutralVariantPaletteKeyColor().getArgb(scheme))
                .isSameColorAs(0xff7A7585);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xffad603c);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff924b28);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xff401400);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xffffdbcc);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xffffdbcc);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff6f3010);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xff99512e);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xff351000);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xffffd0bc);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xffad603c);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xffffb595);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.primary().getArgb(scheme)).isSameColorAs(0xfffff3ee);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff743413);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xff743413);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.primaryContainer().getArgb(scheme)).isSameColorAs(0xffffbb9e);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xfff99f75);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xffffdbcc);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.onPrimaryContainer().getArgb(scheme)).isSameColorAs(0xff622706);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff12131a);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff12131a);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(dynamicColors.surface().getArgb(scheme)).isSameColorAs(0xff12131a);
    }
}
