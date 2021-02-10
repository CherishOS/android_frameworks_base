/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.app.GameManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class GameManagerServiceTests {

    private static final String TAG = "GameServiceTests";
    private static final String PACKAGE_NAME_0 = "com.android.app0";
    private static final String PACKAGE_NAME_1 = "com.android.app1";
    private static final int USER_ID_1 = 1001;
    private static final int USER_ID_2 = 1002;

    // Stolen from ConnectivityServiceTest.MockContext
    class MockContext extends ContextWrapper {
        private static final String TAG = "MockContext";

        // Map of permission name -> PermissionManager.Permission_{GRANTED|DENIED} constant
        private final HashMap<String, Integer> mMockedPermissions = new HashMap<>();

        MockContext(Context base) {
            super(base);
        }

        /**
         * Mock checks for the specified permission, and have them behave as per {@code granted}.
         *
         * <p>Passing null reverts to default behavior, which does a real permission check on the
         * test package.
         * @param granted One of {@link PackageManager#PERMISSION_GRANTED} or
         *                {@link PackageManager#PERMISSION_DENIED}.
         */
        public void setPermission(String permission, Integer granted) {
            mMockedPermissions.put(permission, granted);
        }

        private int checkMockedPermission(String permission, Supplier<Integer> ifAbsent) {
            final Integer granted = mMockedPermissions.get(permission);
            return granted != null ? granted : ifAbsent.get();
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return checkMockedPermission(
                    permission, () -> super.checkPermission(permission, pid, uid));
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return checkMockedPermission(
                    permission, () -> super.checkCallingOrSelfPermission(permission));
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            final Integer granted = mMockedPermissions.get(permission);
            if (granted == null) {
                super.enforceCallingOrSelfPermission(permission, message);
                return;
            }

            if (!granted.equals(PackageManager.PERMISSION_GRANTED)) {
                throw new SecurityException("[Test] permission denied: " + permission);
            }
        }
    }

    @Mock
    private MockContext mMockContext;

    @Before
    public void setUp() throws Exception {
        mMockContext = new MockContext(InstrumentationRegistry.getContext());
    }

    private void mockModifyGameModeGranted() {
        mMockContext.setPermission(Manifest.permission.MANAGE_GAME_MODE,
                PackageManager.PERMISSION_GRANTED);
    }

    private void mockModifyGameModeDenied() {
        mMockContext.setPermission(Manifest.permission.MANAGE_GAME_MODE,
                PackageManager.PERMISSION_DENIED);
    }

    /**
     * By default game mode is not supported.
     */
    @Test
    public void testGameModeDefaultValue() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        mockModifyGameModeGranted();

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(PACKAGE_NAME_0, USER_ID_1));
    }

    /**
     * Test the default behaviour for a nonexistent user.
     */
    @Test
    public void testDefaultValueForNonexistentUser() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        mockModifyGameModeGranted();

        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_STANDARD, USER_ID_2);
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_2));
    }

    /**
     * Test getter and setter of game modes.
     */
    @Test
    public void testGameMode() {
        GameManagerService gameManagerService =  new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        mockModifyGameModeGranted();

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
    }

    /**
     * Test permission.MANAGE_GAME_MODE is checked
     */
    @Test
    public void testGetGameModePermissionDenied() {
        GameManagerService gameManagerService =  new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        // Update the game mode so we can read back something valid.
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));

        // Deny permission.MANAGE_GAME_MODE and verify we get back GameManager.GAME_MODE_UNSUPPORTED
        mockModifyGameModeDenied();
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
    }

    /**
     * Test permission.MANAGE_GAME_MODE is checked
     */
    @Test
    public void testSetGameModePermissionDenied() {
        GameManagerService gameManagerService =  new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        // Update the game mode so we can read back something valid.
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));

        // Deny permission.MANAGE_GAME_MODE and verify the game mode is not updated.
        mockModifyGameModeDenied();
        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_1);

        mockModifyGameModeGranted();
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
    }
}
