/*
 * Copyright (C) 2018 crDroid Android Project
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

package com.android.systemui.statusbar.logo;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;

public class LogoImageView extends ImageView {

    private Context mContext;

    private boolean mAttached;
    private boolean mCherishLogo;
    private int mCherishLogoPosition;
    private int mCherishLogoStyle;
    private int mTintColor = Color.WHITE;
    private final Handler mHandler = new Handler();
    private ContentResolver mContentResolver;

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO_POSITION), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO_STYLE), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    public LogoImageView(Context context) {
        this(context, null);
    }

    public LogoImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LogoImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        mContext = context;
        mSettingsObserver.observe();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached) {
            return;
        }
        mAttached = true;
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached) {
            return;
        }
        mAttached = false;
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(area, this, tint);
        if (mCherishLogo && mCherishLogoPosition == 0) {
            updateCherishLogo();
        }
    }

    public void updateCherishLogo() {
        Drawable drawable = null;

        if (!mCherishLogo || mCherishLogoPosition == 1) {
            setImageDrawable(null);
            setVisibility(View.GONE);
            return;
        } else {
            setVisibility(View.VISIBLE);
        }

        if (mCherishLogoStyle == 0) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_cherish_logo);
        } else if (mCherishLogoStyle == 1) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_android_logo);
        } else if (mCherishLogoStyle == 2) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_apple_logo);
        } else if (mCherishLogoStyle == 3) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_beats);
        } else if (mCherishLogoStyle == 4) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_biohazard);
        } else if (mCherishLogoStyle == 5) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_blackberry);
        } else if (mCherishLogoStyle == 6) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_blogger);
        } else if (mCherishLogoStyle == 7) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_bomb);
        } else if (mCherishLogoStyle == 8) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_brain);
        } else if (mCherishLogoStyle == 9) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_cake);
        } else if (mCherishLogoStyle == 10) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_cannabis);
        } else if (mCherishLogoStyle == 11) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_death_star);
        } else if (mCherishLogoStyle == 12) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon);
        } else if (mCherishLogoStyle == 13) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_cool);
        } else if (mCherishLogoStyle == 14) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_dead);
        } else if (mCherishLogoStyle == 15) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_devil);
        } else if (mCherishLogoStyle == 16) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_happy);
        } else if (mCherishLogoStyle == 17) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_neutral);
        } else if (mCherishLogoStyle == 18) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_poop);
        } else if (mCherishLogoStyle == 19) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_sad);
        } else if (mCherishLogoStyle == 20) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_tongue);
        } else if (mCherishLogoStyle == 21) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_fire);
        } else if (mCherishLogoStyle == 22) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_flask);
        } else if (mCherishLogoStyle == 23) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_gender_female);
        } else if (mCherishLogoStyle == 24) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_gender_male);
        } else if (mCherishLogoStyle == 25) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_gender_male_female);
        } else if (mCherishLogoStyle == 26) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_ghost);
        } else if (mCherishLogoStyle == 27) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_google);
        } else if (mCherishLogoStyle == 28) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_guitar_acoustic);
        } else if (mCherishLogoStyle == 29) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_guitar_electric);
        } else if (mCherishLogoStyle == 30) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_heart);
        } else if (mCherishLogoStyle == 31) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_human_female);
        } else if (mCherishLogoStyle == 32) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_human_male);
        } else if (mCherishLogoStyle == 33) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_human_male_female);
        } else if (mCherishLogoStyle == 34) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_incognito);
        } else if (mCherishLogoStyle == 35) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_ios_logo);
        } else if (mCherishLogoStyle == 36) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_linux);
        } else if (mCherishLogoStyle == 37) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_lock);
        } else if (mCherishLogoStyle == 38) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_music);
        } else if (mCherishLogoStyle == 39) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_ninja);
        } else if (mCherishLogoStyle == 40) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_pac_man);
        } else if (mCherishLogoStyle == 41) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_peace);
        } else if (mCherishLogoStyle == 42) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_robot);
        } else if (mCherishLogoStyle == 43) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_skull);
        } else if (mCherishLogoStyle == 44) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_smoking);
        } else if (mCherishLogoStyle == 45) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_wallet);
        } else if (mCherishLogoStyle == 46) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_windows);
        } else if (mCherishLogoStyle == 47) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_xbox);
        } else if (mCherishLogoStyle == 48) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_xbox_controller);
        } else if (mCherishLogoStyle == 49) {
            drawable = mContext.getResources().getDrawable(R.drawable.ic_yin_yang);
        }

        setImageDrawable(null);

        clearColorFilter();

        drawable.setTint(mTintColor);
        setImageDrawable(drawable);
    }

    public void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mCherishLogo = Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_LOGO, 0) == 1;
        mCherishLogoPosition = Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_LOGO_POSITION, 0);
        mCherishLogoStyle = Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_LOGO_STYLE, 0);
        updateCherishLogo();
    }
}
