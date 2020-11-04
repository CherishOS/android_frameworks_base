/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_ACTIVITY;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_SHORTCUT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_TASK;
import static android.view.DragEvent.ACTION_DRAG_STARTED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.platform.test.annotations.Presubmit;
import android.view.DragEvent;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for the {@link DragDropController} class.
 *
 * Build/Install/Run:
 *  atest WmTests:DragDropControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DragDropControllerTests extends WindowTestsBase {
    private static final int TIMEOUT_MS = 3000;
    private static final int TEST_UID = 12345;
    private static final int TEST_PID = 67890;
    private static final String TEST_PACKAGE = "com.test.package";

    private TestDragDropController mTarget;
    private WindowState mWindow;
    private IBinder mToken;

    static class TestDragDropController extends DragDropController {
        private Runnable mCloseCallback;
        boolean mDeferDragStateClosed;

        TestDragDropController(WindowManagerService service, Looper looper) {
            super(service, looper);
        }

        void setOnClosedCallbackLocked(Runnable runnable) {
            assertTrue(dragDropActiveLocked());
            mCloseCallback = runnable;
        }

        @Override
        void onDragStateClosedLocked(DragState dragState) {
            if (mDeferDragStateClosed) {
                return;
            }
            super.onDragStateClosedLocked(dragState);
            if (mCloseCallback != null) {
                mCloseCallback.run();
                mCloseCallback = null;
            }
        }
    }

    /**
     * Creates a window state which can be used as a drop target.
     */
    private WindowState createDropTargetWindow(String name, int ownerId) {
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        final Task stack = createTaskStackOnDisplay(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task task = createTaskInStack(stack, ownerId);
        task.addChild(activity, 0);

        // Use a new TestIWindow so we don't collect events for other windows
        final WindowState window = createWindow(
                null, TYPE_BASE_APPLICATION, activity, name, ownerId, false, new TestIWindow());
        window.mInputChannel = new InputChannel();
        window.mHasSurface = true;
        return window;
    }

    @BeforeClass
    public static void setUpOnce() {
        final UserManagerInternal userManager = mock(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, userManager);
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    @Before
    public void setUp() throws Exception {
        mTarget = new TestDragDropController(mWm, mWm.mH.getLooper());
        mWindow = createDropTargetWindow("Drag test window", 0);
        doReturn(mWindow).when(mDisplayContent).getTouchableWinAtPointLocked(0, 0);
        when(mWm.mInputManager.transferTouchFocus(any(InputChannel.class),
                any(InputChannel.class))).thenReturn(true);

        mWm.mWindowMap.put(mWindow.mClient.asBinder(), mWindow);
    }

    @After
    public void tearDown() throws Exception {
        final CountDownLatch latch;
        if (!mTarget.dragDropActiveLocked()) {
            return;
        }
        if (mToken != null) {
            mTarget.cancelDragAndDrop(mToken, false);
        }
        latch = new CountDownLatch(1);
        mTarget.setOnClosedCallbackLocked(latch::countDown);
        assertTrue(awaitInWmLock(() -> latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)));
    }

    @Test
    public void testDragFlow() {
        doDragAndDrop(0, ClipData.newPlainText("label", "Test"), 0, 0);
    }

    @Test
    public void testPerformDrag_NullDataWithGrantUri() {
        doDragAndDrop(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ, null, 0, 0);
    }

    @Test
    public void testPerformDrag_NullDataToOtherUser() {
        final WindowState otherUsersWindow =
                createDropTargetWindow("Other user's window", 1 * UserHandle.PER_USER_RANGE);
        doReturn(otherUsersWindow).when(mDisplayContent).getTouchableWinAtPointLocked(10, 10);

        doDragAndDrop(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ, null, 10, 10);
        mToken = otherUsersWindow.mClient.asBinder();
    }

    @Test
    public void testPrivateInterceptGlobalDragDropFlagChecksPermission() {
        spyOn(mWm.mContext);

        DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        attrs.privateFlags |= PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
        policy.validateAddingWindowLw(attrs, Binder.getCallingPid(), Binder.getCallingUid());

        verify(mWm.mAtmService).enforceTaskPermission(any());
    }

    @Test
    public void testPrivateInterceptGlobalDragDropFlagBehaviour() {
        mWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
        mWindow.setViewVisibility(View.GONE);

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);

        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                ClipData.newPlainText("label", "text"), () -> {
                    // Verify the start-drag event is sent for invisible windows
                    final DragEvent dragEvent = dragEvents.get(0);
                    assertTrue(dragEvent.getAction() == ACTION_DRAG_STARTED);

                    // Verify after consuming that the drag surface is relinquished
                    try {
                        mTarget.mDeferDragStateClosed = true;

                        // Verify the drop event includes the drag surface
                        mTarget.handleMotionEvent(false, 0, 0);
                        final DragEvent dropEvent = dragEvents.get(dragEvents.size() - 1);
                        assertTrue(dropEvent.getDragSurface() != null);

                        mTarget.reportDropResult(iwindow, true);
                    } finally {
                        mTarget.mDeferDragStateClosed = false;
                    }
                    assertTrue(mTarget.dragSurfaceRelinquished());
                });
    }

    @Test
    public void testValidateAppActivityArguments() {
        final Session session = new Session(mWm, new IWindowSessionCallback.Stub() {
            @Override
            public void onAnimatorScaleChanged(float scale) {}
        });
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForActivity(mock(PendingIntent.class), null), TEST_UID, TEST_PID,
                    TEST_PACKAGE);
            fail("Expected failure without user");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForActivity(null, mock(UserHandle.class)), TEST_UID, TEST_PID,
                    TEST_PACKAGE);
            fail("Expected failure without pending intent");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
    }

    private ClipData createClipDataForActivity(PendingIntent pi, UserHandle user) {
        final Intent data = new Intent();
        if (pi != null) {
            data.putExtra(ClipDescription.EXTRA_PENDING_INTENT, (Parcelable) pi);
        }
        if (user != null) {
            data.putExtra(Intent.EXTRA_USER, user);
        }
        final ClipData clipData = new ClipData(
                new ClipDescription("drag", new String[] {
                        MIMETYPE_APPLICATION_ACTIVITY}),
                new ClipData.Item(data));
        return clipData;
    }

    @Test
    public void testValidateAppShortcutArguments() {
        final Session session = new Session(mWm, new IWindowSessionCallback.Stub() {
            @Override
            public void onAnimatorScaleChanged(float scale) {}
        });
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForShortcut(null, "test_shortcut_id", mock(UserHandle.class)),
                    TEST_UID, TEST_PID, TEST_PACKAGE);
            fail("Expected failure without package name");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForShortcut("test_package", null, mock(UserHandle.class)),
                    TEST_UID, TEST_PID, TEST_PACKAGE);
            fail("Expected failure without shortcut id");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForShortcut("test_package", "test_shortcut_id", null),
                    TEST_UID, TEST_PID, TEST_PACKAGE);
            fail("Expected failure without package name");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
    }

    private ClipData createClipDataForShortcut(String packageName, String shortcutId,
            UserHandle user) {
        final Intent data = new Intent();
        if (packageName != null) {
            data.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
        }
        if (shortcutId != null) {
            data.putExtra(Intent.EXTRA_SHORTCUT_ID, shortcutId);
        }
        if (user != null) {
            data.putExtra(Intent.EXTRA_USER, user);
        }
        final ClipData clipData = new ClipData(
                new ClipDescription("drag", new String[] {
                        MIMETYPE_APPLICATION_SHORTCUT}),
                new ClipData.Item(data));
        return clipData;
    }

    @Test
    public void testValidateAppTaskArguments() {
        final Session session = new Session(mWm, new IWindowSessionCallback.Stub() {
            @Override
            public void onAnimatorScaleChanged(float scale) {}
        });
        try {
            final ClipData clipData = new ClipData(
                    new ClipDescription("drag", new String[] { MIMETYPE_APPLICATION_TASK }),
                    new ClipData.Item(new Intent()));

            session.validateAndResolveDragMimeTypeExtras(clipData, TEST_UID, TEST_PID,
                    TEST_PACKAGE);
            fail("Expected failure without task id");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
    }

    private void doDragAndDrop(int flags, ClipData data, float dropX, float dropY) {
        startDrag(flags, data, () -> {
            mTarget.handleMotionEvent(false, dropX, dropY);
            mToken = mWindow.mClient.asBinder();
        });
    }

    private void startDrag(int flag, ClipData data, Runnable r) {
        final SurfaceSession appSession = new SurfaceSession();
        try {
            final SurfaceControl surface = new SurfaceControl.Builder(appSession)
                    .setName("drag surface")
                    .setBufferSize(100, 100)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .build();

            assertTrue(mWm.mInputManager.transferTouchFocus(new InputChannel(),
                    new InputChannel()));
            mToken = mTarget.performDrag(0, 0, mWindow.mClient, flag, surface, 0, 0, 0, 0, 0, data);
            assertNotNull(mToken);

            r.run();
        } finally {
            appSession.kill();
        }
    }
}
