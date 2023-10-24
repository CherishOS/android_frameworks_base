/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.footer.ui.view;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class FooterViewTest extends SysuiTestCase {

    FooterView mView;

    Context mSpyContext = spy(mContext);

    @Before
    public void setUp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATIONS_FOOTER_VIEW_REFACTOR);

        mView = (FooterView) LayoutInflater.from(mSpyContext).inflate(
                R.layout.status_bar_notification_footer, null, false);
        mView.setDuration(0);
    }

    @Test
    public void testViewsNotNull() {
        assertNotNull(mView.findContentView());
        assertNotNull(mView.findSecondaryView());
    }

    @Test
    public void setDismissOnClick() {
        mView.setClearAllButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findSecondaryView().hasOnClickListeners());
    }

    @Test
    public void setManageOnClick() {
        mView.setManageButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findViewById(R.id.manage_text).hasOnClickListeners());
    }

    @Test
    public void setHistoryShown() {
        mView.showHistory(true);
        assertTrue(mView.isHistoryShown());
        assertTrue(((TextView) mView.findViewById(R.id.manage_text))
                .getText().toString().contains("History"));
    }

    @Test
    public void setHistoryNotShown() {
        mView.showHistory(false);
        assertFalse(mView.isHistoryShown());
        assertTrue(((TextView) mView.findViewById(R.id.manage_text))
                .getText().toString().contains("Manage"));
    }

    @Test
    public void testPerformVisibilityAnimation() {
        mView.setVisible(false /* visible */, false /* animate */);
        assertFalse(mView.isVisible());

        mView.setVisible(true /* visible */, true /* animate */);
    }

    @Test
    public void testPerformSecondaryVisibilityAnimation() {
        mView.setSecondaryVisible(false /* visible */, false /* animate */);
        assertFalse(mView.isSecondaryVisible());

        mView.setSecondaryVisible(true /* visible */, true /* animate */);
    }

    @Test
    public void testSetMessageString_resourceOnlyFetchedOnce() {
        mView.setMessageString(R.string.unlock_to_see_notif_text);
        verify(mSpyContext).getString(eq(R.string.unlock_to_see_notif_text));

        clearInvocations(mSpyContext);

        assertThat(((TextView) mView.findViewById(R.id.unlock_prompt_footer))
                .getText().toString()).contains("Unlock");

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setMessageString(R.string.unlock_to_see_notif_text);
        mView.setMessageString(R.string.unlock_to_see_notif_text);

        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    public void testSetMessageIcon_resourceOnlyFetchedOnce() {
        mView.setMessageIcon(R.drawable.ic_friction_lock_closed);
        verify(mSpyContext).getDrawable(eq(R.drawable.ic_friction_lock_closed));

        clearInvocations(mSpyContext);

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setMessageIcon(R.drawable.ic_friction_lock_closed);
        mView.setMessageIcon(R.drawable.ic_friction_lock_closed);

        verify(mSpyContext, never()).getDrawable(anyInt());
    }

    @Test
    public void testSetFooterLabelVisible() {
        mView.setFooterLabelVisible(true);
        assertThat(mView.findViewById(R.id.manage_text).getVisibility()).isEqualTo(View.GONE);
        assertThat(mView.findSecondaryView().getVisibility()).isEqualTo(View.GONE);
        assertThat(mView.findViewById(R.id.unlock_prompt_footer).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void testSetFooterLabelInvisible() {
        mView.setFooterLabelVisible(false);
        assertThat(mView.findViewById(R.id.manage_text).getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mView.findSecondaryView().getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mView.findViewById(R.id.unlock_prompt_footer).getVisibility())
                .isEqualTo(View.GONE);
    }
}

