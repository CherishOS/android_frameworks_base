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

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.os.ServiceManager;
import android.service.games.GameService;
import android.service.games.GameSessionService;
import android.service.games.IGameService;
import android.service.games.IGameSessionService;

import com.android.internal.infra.ServiceConnector;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.app.GameServiceConfiguration.GameServiceComponentConfiguration;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerService;

final class GameServiceProviderInstanceFactoryImpl implements GameServiceProviderInstanceFactory {
    private final Context mContext;

    GameServiceProviderInstanceFactoryImpl(@NonNull Context context) {
        mContext = context;
    }

    @NonNull
    @Override
    public GameServiceProviderInstance create(
            @NonNull GameServiceComponentConfiguration configuration) {
        return new GameServiceProviderInstanceImpl(
                configuration.getUserHandle(),
                BackgroundThread.getExecutor(),
                mContext,
                new GameClassifierImpl(mContext.getPackageManager()),
                ActivityManager.getService(),
                ActivityTaskManager.getService(),
                (WindowManagerService) ServiceManager.getService(Context.WINDOW_SERVICE),
                LocalServices.getService(WindowManagerInternal.class),
                new GameServiceConnector(mContext, configuration),
                new GameSessionServiceConnector(mContext, configuration));
    }

    private static final class GameServiceConnector extends ServiceConnector.Impl<IGameService> {
        private static final int DISABLE_AUTOMATIC_DISCONNECT_TIMEOUT = 0;
        private static final int BINDING_FLAGS = Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS;

        GameServiceConnector(
                @NonNull Context context,
                @NonNull GameServiceComponentConfiguration configuration) {
            super(context, new Intent(GameService.ACTION_GAME_SERVICE)
                            .setComponent(configuration.getGameServiceComponentName()),
                    BINDING_FLAGS, configuration.getUserHandle().getIdentifier(),
                    IGameService.Stub::asInterface);
        }

        @Override
        protected long getAutoDisconnectTimeoutMs() {
            return DISABLE_AUTOMATIC_DISCONNECT_TIMEOUT;
        }
    }

    private static final class GameSessionServiceConnector extends
            ServiceConnector.Impl<IGameSessionService> {
        private static final int DISABLE_AUTOMATIC_DISCONNECT_TIMEOUT = 0;
        private static final int BINDING_FLAGS =
                Context.BIND_TREAT_LIKE_ACTIVITY
                        | Context.BIND_SCHEDULE_LIKE_TOP_APP
                        | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS;

        GameSessionServiceConnector(
                @NonNull Context context,
                @NonNull GameServiceComponentConfiguration configuration) {
            super(context, new Intent(GameSessionService.ACTION_GAME_SESSION_SERVICE)
                            .setComponent(configuration.getGameSessionServiceComponentName()),
                    BINDING_FLAGS, configuration.getUserHandle().getIdentifier(),
                    IGameSessionService.Stub::asInterface);
        }

        @Override
        protected long getAutoDisconnectTimeoutMs() {
            return DISABLE_AUTOMATIC_DISCONNECT_TIMEOUT;
        }
    }
}
