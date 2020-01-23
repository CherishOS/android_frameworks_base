/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.internal.app.MatcherUtils.first;
import static com.android.internal.app.ResolverDataProvider.createPackageManagerMockedInfo;
import static com.android.internal.app.ResolverWrapperActivity.sOverrides;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.test.InstrumentationRegistry;
import androidx.test.espresso.Espresso;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.app.ResolverDataProvider.PackageManagerMockedInfo;
import com.android.internal.app.ResolverListAdapter.ActivityInfoPresentationGetter;
import com.android.internal.app.ResolverListAdapter.ResolveInfoPresentationGetter;
import com.android.internal.widget.ResolverDrawerLayout;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolver activity instrumentation tests
 */
@RunWith(AndroidJUnit4.class)
public class ResolverActivityTest {
    @Rule
    public ActivityTestRule<ResolverWrapperActivity> mActivityRule =
            new ActivityTestRule<>(ResolverWrapperActivity.class, false,
                    false);

    @Before
    public void cleanOverrideData() {
        sOverrides.reset();
    }

    @Test
    public void twoOptionsAndUserSelectsOne() throws InterruptedException {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);
        onView(withText(toChoose.activityInfo.name))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Ignore // Failing - b/144929805
    @Test
    public void setMaxHeight() throws Exception {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        waitForIdle();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        final View viewPager = activity.findViewById(R.id.profile_pager);
        final int initialResolverHeight = viewPager.getHeight();

        activity.runOnUiThread(() -> {
            ResolverDrawerLayout layout = (ResolverDrawerLayout)
                    activity.findViewById(
                            R.id.contentPanel);
            ((ResolverDrawerLayout.LayoutParams) viewPager.getLayoutParams()).maxHeight
                = initialResolverHeight - 1;
            // Force a relayout
            layout.invalidate();
            layout.requestLayout();
        });
        waitForIdle();
        assertThat("Drawer should be capped at maxHeight",
                viewPager.getHeight() == (initialResolverHeight - 1));

        activity.runOnUiThread(() -> {
            ResolverDrawerLayout layout = (ResolverDrawerLayout)
                    activity.findViewById(
                            R.id.contentPanel);
            ((ResolverDrawerLayout.LayoutParams) viewPager.getLayoutParams()).maxHeight
                = initialResolverHeight + 1;
            // Force a relayout
            layout.invalidate();
            layout.requestLayout();
        });
        waitForIdle();
        assertThat("Drawer should not change height if its height is less than maxHeight",
                viewPager.getHeight() == initialResolverHeight);
    }

    @Ignore // Failing - b/144929805
    @Test
    public void setShowAtTopToTrue() throws Exception {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        waitForIdle();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        final View viewPager = activity.findViewById(R.id.profile_pager);
        final View divider = activity.findViewById(R.id.divider);
        final RelativeLayout profileView =
            (RelativeLayout) activity.findViewById(R.id.profile_button).getParent();
        assertThat("Drawer should show at bottom by default",
                profileView.getBottom() + divider.getHeight() == viewPager.getTop()
                        && profileView.getTop() > 0);

        activity.runOnUiThread(() -> {
            ResolverDrawerLayout layout = (ResolverDrawerLayout)
                    activity.findViewById(
                            R.id.contentPanel);
            layout.setShowAtTop(true);
        });
        waitForIdle();
        assertThat("Drawer should show at top with new attribute",
            profileView.getBottom() + divider.getHeight() == viewPager.getTop()
                    && profileView.getTop() == 0);
    }

    @Test
    public void hasLastChosenActivity() throws Exception {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(0).getResolveInfoAt(0));

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        // The other entry is filtered to the last used slot
        assertThat(activity.getAdapter().getCount(), is(1));
        assertThat(activity.getAdapter().getPlaceholderCount(), is(1));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        onView(withId(R.id.button_once)).perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void hasOtherProfileOneOption() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;

        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        // The other entry is filtered to the last used slot
        assertThat(activity.getAdapter().getCount(), is(1));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2);
        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void hasOtherProfileTwoOptionsAndUserSelectsOne() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;

        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Confirm that the button bar is disabled by default
        onView(withId(R.id.button_once)).check(matches(not(isEnabled())));

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2);

        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        onView(withId(R.id.button_once)).perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }


    @Test
    public void hasLastChosenActivityAndOtherProfile() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;

        // In this case we prefer the other profile and don't display anything about the last
        // chosen activity.
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(1).getResolveInfoAt(0));

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Confirm that the button bar is disabled by default
        onView(withId(R.id.button_once)).check(matches(not(isEnabled())));

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2);

        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        onView(withId(R.id.button_once)).perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void getActivityLabelAndSubLabel() throws Exception {
        ActivityInfoPresentationGetter pg;
        PackageManagerMockedInfo info;

        info = createPackageManagerMockedInfo(false);
        pg = new ActivityInfoPresentationGetter(
                info.ctx, 0, info.activityInfo);
        assertThat("Label should match app label", pg.getLabel().equals(
                info.setAppLabel));
        assertThat("Sublabel should match activity label if set",
                pg.getSubLabel().equals(info.setActivityLabel));

        info = createPackageManagerMockedInfo(true);
        pg = new ActivityInfoPresentationGetter(
                info.ctx, 0, info.activityInfo);
        assertThat("With override permission label should match activity label if set",
                pg.getLabel().equals(info.setActivityLabel));
        assertThat("With override permission sublabel should be empty",
                TextUtils.isEmpty(pg.getSubLabel()));
    }

    @Test
    public void getResolveInfoLabelAndSubLabel() throws Exception {
        ResolveInfoPresentationGetter pg;
        PackageManagerMockedInfo info;

        info = createPackageManagerMockedInfo(false);
        pg = new ResolveInfoPresentationGetter(
                info.ctx, 0, info.resolveInfo);
        assertThat("Label should match app label", pg.getLabel().equals(
                info.setAppLabel));
        assertThat("Sublabel should match resolve info label if set",
                pg.getSubLabel().equals(info.setResolveInfoLabel));

        info = createPackageManagerMockedInfo(true);
        pg = new ResolveInfoPresentationGetter(
                info.ctx, 0, info.resolveInfo);
        assertThat("With override permission label should match resolve info label if set",
                pg.getLabel().equals(info.setResolveInfoLabel));
        assertThat("With override permission sublabel should be empty",
                TextUtils.isEmpty(pg.getSubLabel()));
    }

    @Test
    public void testWorkTab_displayedWhenWorkProfileUserAvailable() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        Intent sendIntent = createSendImageIntent();
        markWorkProfileUserAvailable();

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        onView(withId(R.id.tabs)).check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_hiddenWhenWorkProfileUserNotAvailable() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        Intent sendIntent = createSendImageIntent();

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        onView(withId(R.id.tabs)).check(matches(not(isDisplayed())));
    }

    @Test
    public void testWorkTab_workTabListEmptyBeforeGoingToTab() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(personalResolvedComponentInfos);
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        markWorkProfileUserAvailable();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertThat(activity.getCurrentUserHandle().getIdentifier(), is(0));
        // The work list adapter must only be filled when we open the work tab
        assertThat(activity.getWorkListAdapter().getCount(), is(0));
    }

    @Test
    public void testWorkTab_workTabUsesExpectedAdapter() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(personalResolvedComponentInfos);
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        markWorkProfileUserAvailable();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab)).perform(click());

        assertThat(activity.getCurrentUserHandle().getIdentifier(), is(10));
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
    }

    @Test
    public void testWorkTab_personalTabUsesExpectedAdapter() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(personalResolvedComponentInfos);
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        markWorkProfileUserAvailable();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab)).perform(click());

        assertThat(activity.getCurrentUserHandle().getIdentifier(), is(10));
        assertThat(activity.getPersonalListAdapter().getCount(), is(3));
    }

    @Test
    public void testWorkTab_workProfileHasExpectedNumberOfTargets() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(personalResolvedComponentInfos);
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());

        waitForIdle();
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
    }

    @Test
    public void testWorkTab_selectingWorkTabAppOpensAppInWorkProfile() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(personalResolvedComponentInfos);
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());
        waitForIdle();
        // wait for the share sheet to expand
        Thread.sleep(ChooserActivity.LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS);
        onView(first(allOf(withText(workResolvedComponentInfos.get(0)
                .getResolveInfoAt(0).activityInfo.applicationInfo.name), isCompletelyDisplayed())))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());

        waitForIdle();
        assertThat(chosen[0], is(workResolvedComponentInfos.get(0).getResolveInfoAt(0)));
    }

    @Test
    public void testWorkTab_noPersonalApps_workTabHasExpectedNumberOfTargets()
            throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());

        waitForIdle();
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
    }

    @Ignore // b/148156663
    @Test
    public void testWorkTab_noPersonalApps_canStartWorkApps()
            throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());
        waitForIdle();
        // wait for the share sheet to expand
        Thread.sleep(ChooserActivity.LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS);
        onView(first(allOf(withText(workResolvedComponentInfos.get(0)
                .getResolveInfoAt(0).activityInfo.applicationInfo.name), isCompletelyDisplayed())))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();

        assertThat(chosen[0], is(workResolvedComponentInfos.get(0).getResolveInfoAt(0)));
    }

    private Intent createSendImageIntent() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        sendIntent.setType("image/jpeg");
        return sendIntent;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTest(int numberOfResults) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfo(i));
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTestWithOtherProfile(
            int numberOfResults) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            if (i == 0) {
                infoList.add(ResolverDataProvider.createResolvedComponentInfoWithOtherId(i));
            } else {
                infoList.add(ResolverDataProvider.createResolvedComponentInfo(i));
            }
        }
        return infoList;
    }

    private void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void markWorkProfileUserAvailable() {
        ResolverWrapperActivity.sOverrides.workProfileUserHandle = UserHandle.of(10);
    }
}
