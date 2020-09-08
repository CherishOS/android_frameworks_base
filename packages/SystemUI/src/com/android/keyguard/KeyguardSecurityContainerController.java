/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link KeyguardSecurityContainer} */
public class KeyguardSecurityContainerController extends ViewController {

    private final KeyguardSecurityContainer mView;
    private final KeyguardSecurityViewController.Factory mKeyguardSecurityViewControllerFactory;

    @Inject
    KeyguardSecurityContainerController(KeyguardSecurityContainer view,
            KeyguardSecurityViewController.Factory keyguardSecurityViewControllerFactory) {
        super(view);
        mView = view;
        mKeyguardSecurityViewControllerFactory = keyguardSecurityViewControllerFactory;
    }

    @Override
    protected void onViewAttached() {

    }

    @Override
    protected void onViewDetached() {

    }

    /** */
    public void onPause() {
        mView.onPause();
    }
}
