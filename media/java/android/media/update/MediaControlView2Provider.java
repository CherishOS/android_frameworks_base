/*
 * Copyright 2017 The Android Open Source Project
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

package android.media.update;

import android.annotation.SystemApi;
import android.media.session.MediaController;
import android.view.View;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * Each instance object is connected to one corresponding updatable object which implements the
 * runtime behavior of that class. There should a corresponding provider method for all public
 * methods.
 *
 * All methods behave as per their namesake in the public API.
 *
 * @see android.widget.MediaControlView2
 *
 * @hide
 */
// TODO @SystemApi
public interface MediaControlView2Provider extends ViewProvider {
    void setController_impl(MediaController controller);
    void show_impl();
    void show_impl(int timeout);
    boolean isShowing_impl();
    void hide_impl();
    void showSubtitle_impl();
    void hideSubtitle_impl();
    void setPrevNextListeners_impl(View.OnClickListener next, View.OnClickListener prev);
    void setButtonVisibility_impl(int button, boolean visible);
}
