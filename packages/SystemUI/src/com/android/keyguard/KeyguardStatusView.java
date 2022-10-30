/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.GridLayout;
import android.graphics.Typeface;

import androidx.core.graphics.ColorUtils;

import com.android.systemui.R;
import com.android.systemui.corvus.qsweather.KGWeatherText;
import com.android.systemui.statusbar.CrossFadeHelper;


import java.io.PrintWriter;
import java.util.Set;

/**
 * View consisting of:
 * - keyguard clock
 * - media player (split shade mode only)
 */
public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private ViewGroup mStatusViewContainer;
    private KeyguardClockSwitch mClockView;
    private KeyguardSliceView mKeyguardSlice;
    private KGWeatherText mKeyguardWeather;
    private View mMediaHostContainer;

    private float mDarkAmount = 0;
    private int mTextColor;

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mStatusViewContainer = findViewById(R.id.status_view_container);

        mClockView = findViewById(R.id.keyguard_clock_container);
        if (KeyguardClockAccessibilityDelegate.isNeeded(mContext)) {
            mClockView.setAccessibilityDelegate(new KeyguardClockAccessibilityDelegate(mContext));
        }

        mKeyguardSlice = findViewById(R.id.keyguard_slice_view);
        mKeyguardWeather = findViewById(R.id.kg_weather_temp);
        
        onThemeChanged();

        mTextColor = mClockView.getCurrentTextColor();

        mMediaHostContainer = findViewById(R.id.status_view_media_container);

        updateDark();
    }

    public void onThemeChanged() {
        int customClockFont = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.KG_CUSTOM_DATE_FONT , 23, UserHandle.USER_CURRENT);
        
        switch (customClockFont) {
        	case 0:
        	Typeface sansSF = Typeface.create("sans-serif", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(sansSF);
        	mKeyguardWeather.setTypeface(sansSF);
        	break;
        	case 1:
        	Typeface accuratist = Typeface.create("accuratist", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(accuratist);
        	mKeyguardWeather.setTypeface(accuratist);
        	break;
        	case 2:
        	Typeface aclonica = Typeface.create("aclonica", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(aclonica);
        	mKeyguardWeather.setTypeface(aclonica);
        	break;
        	case 3:
        	Typeface amarante = Typeface.create("amarante", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(amarante);
        	mKeyguardWeather.setTypeface(amarante);
        	break;
        	case 4:
        	Typeface bariol = Typeface.create("bariol", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(bariol);
        	mKeyguardWeather.setTypeface(bariol);
        	break;
        	case 5:
        	Typeface cagliostro = Typeface.create("cagliostro", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(cagliostro);
        	mKeyguardWeather.setTypeface(cagliostro);
        	break;
        	case 6:
        	Typeface cocon = Typeface.create("cocon", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(cocon);
        	mKeyguardWeather.setTypeface(cocon);
        	break;
        	case 7:
        	Typeface comfortaa = Typeface.create("comfortaa", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(comfortaa);
        	mKeyguardWeather.setTypeface(comfortaa);
        	break;
        	case 8:
        	Typeface comicsans = Typeface.create("comicsans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(comicsans);
        	mKeyguardWeather.setTypeface(comicsans);
        	break;
        	case 9:
        	Typeface coolstory = Typeface.create("coolstory", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(coolstory);
        	mKeyguardWeather.setTypeface(coolstory);
        	break;
        	case 10:
        	Typeface exotwo = Typeface.create("exotwo", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(exotwo);
        	mKeyguardWeather.setTypeface(exotwo);
        	break;
        	case 11:
        	Typeface fifa2018 = Typeface.create("fifa2018", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(fifa2018);
        	mKeyguardWeather.setTypeface(fifa2018);
        	break;
        	case 12:
        	Typeface fluidsans = Typeface.create("fluid-sans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(fluidsans);
        	mKeyguardWeather.setTypeface(fluidsans);
        	break;
        	case 13:
        	Typeface googlesans = Typeface.create("googlesans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(googlesans);
        	mKeyguardWeather.setTypeface(googlesans);
        	break;
        	case 14:
        	Typeface grandhotel = Typeface.create("grandhotel", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(grandhotel);
        	mKeyguardWeather.setTypeface(grandhotel);
        	break;
        	case 15:
        	Typeface harmonyossans = Typeface.create("harmonyos-sans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(harmonyossans);
        	mKeyguardWeather.setTypeface(harmonyossans);
        	break;
        	case 16:
        	Typeface intercustom = Typeface.create("inter_custom", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(intercustom);
        	mKeyguardWeather.setTypeface(intercustom);
        	break;
        	case 17:
        	Typeface jtleonor = Typeface.create("jtleonor", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(jtleonor);
        	mKeyguardWeather.setTypeface(jtleonor);
        	break;
        	case 18:
        	Typeface latobold = Typeface.create("lato-bold", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(latobold);
        	mKeyguardWeather.setTypeface(latobold);
        	break;
        	case 19:
        	Typeface lgsmartgothic = Typeface.create("lgsmartgothic", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(lgsmartgothic);
        	mKeyguardWeather.setTypeface(lgsmartgothic);
        	break;
        	case 20:
        	Typeface linotte = Typeface.create("linotte", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(linotte);
        	mKeyguardWeather.setTypeface(linotte);
        	break;
        	case 21:
        	Typeface misans = Typeface.create("misans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(misans);
        	mKeyguardWeather.setTypeface(misans);
        	break;
        	case 22:
        	Typeface nokiapure = Typeface.create("nokiapure", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(nokiapure);
        	mKeyguardWeather.setTypeface(nokiapure);
        	break;
        	case 23:
        	Typeface nothingdot57 = Typeface.create("nothingdot57", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(nothingdot57);
        	mKeyguardWeather.setTypeface(nothingdot57);
        	break;
        	case 24:
        	Typeface nunitobold = Typeface.create("nunito-bold", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(nunitobold);
        	mKeyguardWeather.setTypeface(nunitobold);
        	break;
        	case 25:
        	Typeface opsans = Typeface.create("op-sans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(opsans);
        	mKeyguardWeather.setTypeface(opsans);
        	break;
        	case 26:
        	Typeface oneplusslate = Typeface.create("oneplusslate", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(oneplusslate);
        	mKeyguardWeather.setTypeface(oneplusslate);
        	break;
        	case 27:
        	Typeface opposans = Typeface.create("opposans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(opposans);
        	mKeyguardWeather.setTypeface(opposans);
        	break;
        	case 28:
        	Typeface oswaldbold = Typeface.create("oswald-bold", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(oswaldbold);
        	mKeyguardWeather.setTypeface(oswaldbold);
        	break;
        	case 29:
        	Typeface productsansvh = Typeface.create("productsansvh", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(productsansvh);
        	mKeyguardWeather.setTypeface(productsansvh);
        	break;
        	case 30:
        	Typeface quando = Typeface.create("quando", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(quando);
        	mKeyguardWeather.setTypeface(quando);
        	break;
        	case 31:
        	Typeface redressed = Typeface.create("redressed", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(redressed);
        	mKeyguardWeather.setTypeface(redressed);
        	break;
        	case 32:
        	Typeface reemkufi = Typeface.create("reemkufi", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(reemkufi);
        	mKeyguardWeather.setTypeface(reemkufi);
        	break;
        	case 33:
        	Typeface robotocondensed = Typeface.create("robotocondensed", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(robotocondensed);
        	mKeyguardWeather.setTypeface(robotocondensed);
        	break;
        	case 34:
        	Typeface rosemary = Typeface.create("rosemary", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(rosemary);
        	mKeyguardWeather.setTypeface(rosemary);
        	break;
        	case 35:
        	Typeface rubikbold = Typeface.create("rubik-bold", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(rubikbold);
        	mKeyguardWeather.setTypeface(rubikbold);
        	break;
        	case 36:
        	Typeface samsungone = Typeface.create("samsungone", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(samsungone);
        	mKeyguardWeather.setTypeface(samsungone);
        	break;
        	case 37:
        	Typeface sanfrancisco = Typeface.create("sanfrancisco", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(sanfrancisco);
        	mKeyguardWeather.setTypeface(sanfrancisco);
        	break;
        	case 38:
        	Typeface simpleday = Typeface.create("simpleday", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(simpleday);
        	mKeyguardWeather.setTypeface(simpleday);
        	break;
        	case 39:
        	Typeface sonysketch = Typeface.create("sonysketch", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(sonysketch);
        	mKeyguardWeather.setTypeface(sonysketch);
        	break;
        	case 40:
        	Typeface storopia = Typeface.create("storopia", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(storopia);
        	mKeyguardWeather.setTypeface(storopia);
        	break;
        	case 41:
        	Typeface surfer = Typeface.create("surfer", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(surfer);
        	mKeyguardWeather.setTypeface(surfer);
        	break;
        	case 42:
        	Typeface ubuntu = Typeface.create("ubuntu", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(ubuntu);
        	mKeyguardWeather.setTypeface(ubuntu);
        	break;
        	case 43:
        	Typeface manrope = Typeface.create("manrope", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(manrope);
        	mKeyguardWeather.setTypeface(manrope);
        	break;
        	case 44:
        	Typeface notosans = Typeface.create("noto-sans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(notosans);
        	mKeyguardWeather.setTypeface(notosans);
        	break;
        	case 45:
        	Typeface recursivecasual = Typeface.create("recursive-casual", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(recursivecasual);
        	mKeyguardWeather.setTypeface(recursivecasual);
        	break;
        	case 46:
        	Typeface recursive = Typeface.create("recursive", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(recursive);
        	mKeyguardWeather.setTypeface(recursive);
        	break;
        	case 47:
        	Typeface robotosystem = Typeface.create("roboto-system", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(robotosystem);
        	mKeyguardWeather.setTypeface(robotosystem);
        	break;
        	case 48:
        	Typeface sourcesans = Typeface.create("source-sans", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(sourcesans);
        	mKeyguardWeather.setTypeface(sourcesans);
        	break;
        	case 49:
        	Typeface serif = Typeface.create("serif", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(serif);
        	mKeyguardWeather.setTypeface(serif);
        	break;
        	case 50:
        	Typeface googlesansclock = Typeface.create("googlesansclock", Typeface.NORMAL);
        	mKeyguardSlice.setViewsTypeface(googlesansclock);
        	mKeyguardWeather.setTypeface(googlesansclock);
        	break;
        	default:
        	break;
        	
        }
    }
    

    void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        mClockView.setDarkAmount(darkAmount);
        CrossFadeHelper.fadeOut(mMediaHostContainer, darkAmount);
        updateDark();
    }

    void updateDark() {
        final int blendedTextColor = ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
        mKeyguardSlice.setDarkAmount(mDarkAmount);
        mClockView.setTextColor(blendedTextColor);
        onThemeChanged();
    }

    /** Sets a translationY value on every child view except for the media view. */
    public void setChildrenTranslationYExcludingMediaView(float translationY) {
        setChildrenTranslationYExcluding(translationY, Set.of(mMediaHostContainer));
    }

    /** Sets a translationY value on every view except for the views in the provided set. */
    private void setChildrenTranslationYExcluding(float translationY, Set<View> excludedViews) {
        for (int i = 0; i < mStatusViewContainer.getChildCount(); i++) {
            final View child = mStatusViewContainer.getChildAt(i);

            if (!excludedViews.contains(child)) {
                child.setTranslationY(translationY);
            }
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusView:");
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mTextColor: " + Integer.toHexString(mTextColor));
        if (mClockView != null) {
            mClockView.dump(pw, args);
        }
        if (mKeyguardSlice != null) {
            mKeyguardSlice.dump(pw, args);
        }
    }
}
