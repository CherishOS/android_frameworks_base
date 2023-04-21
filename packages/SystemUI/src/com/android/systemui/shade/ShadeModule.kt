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

package com.android.systemui.shade

import android.view.LayoutInflater
import com.android.keyguard.LockIconView
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.biometrics.AuthRippleController
import com.android.systemui.biometrics.AuthRippleView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Module for classes related to the notification shade. */
@Module
abstract class ShadeModule {

    @Binds
    @IntoMap
    @ClassKey(AuthRippleController::class)
    abstract fun bindAuthRippleController(controller: AuthRippleController): CoreStartable

    companion object {
        @Provides
        @SysUISingleton
        // TODO(b/277762009): Do something similar to
        //  {@link StatusBarWindowModule.InternalWindowView} so that only
        //  {@link NotificationShadeWindowViewController} can inject this view.
        fun providesNotificationShadeWindowView(
            layoutInflater: LayoutInflater,
        ): NotificationShadeWindowView {
            return layoutInflater.inflate(R.layout.super_notification_shade, /* root= */ null)
                as NotificationShadeWindowView?
                ?: throw IllegalStateException(
                    "R.layout.super_notification_shade could not be properly inflated"
                )
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationStackScrollLayout(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): NotificationStackScrollLayout {
            return notificationShadeWindowView.findViewById(R.id.notification_stack_scroller)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesNotificationPanelView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): NotificationPanelView {
            return notificationShadeWindowView.findViewById(R.id.notification_panel)
        }

        @Provides
        @SysUISingleton
        fun providesLightRevealScrim(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): LightRevealScrim {
            return notificationShadeWindowView.findViewById(R.id.light_reveal_scrim)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesAuthRippleView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): AuthRippleView? {
            return notificationShadeWindowView.findViewById(R.id.auth_ripple)
        }

        // TODO(b/277762009): Only allow this view's controller to inject the view. See above.
        @Provides
        @SysUISingleton
        fun providesLockIconView(
            notificationShadeWindowView: NotificationShadeWindowView,
        ): LockIconView {
            return notificationShadeWindowView.findViewById(R.id.lock_icon_view)
        }
    }
}
