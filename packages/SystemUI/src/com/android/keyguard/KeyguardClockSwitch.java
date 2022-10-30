package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Typeface;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.database.ContentObserver;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.dagger.KeyguardStatusViewScope;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.ClockPlugin;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.TimeZone;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
@KeyguardStatusViewScope
public class KeyguardClockSwitch extends RelativeLayout {

    private static final String TAG = "KeyguardClockSwitch";

    private static final long CLOCK_OUT_MILLIS = 150;
    private static final long CLOCK_IN_MILLIS = 200;
    private static final long STATUS_AREA_MOVE_MILLIS = 350;

    @IntDef({LARGE, SMALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ClockSize { }

    public static final int LARGE = 0;
    public static final int SMALL = 1;

    /**
     * Optional/alternative clock injected via plugin.
     */
    private ClockPlugin mClockPlugin;

    /**
     * Frame for small/large clocks
     */
    private FrameLayout mClockFrame;
    private FrameLayout mLargeClockFrame;
    private AnimatableClockView mClockView;
    private AnimatableClockView mLargeClockView;

    private View mStatusArea;
    private int mSmartspaceTopOffset;

    /**
     * Maintain state so that a newly connected plugin can be initialized.
     */
    private float mDarkAmount;

    /**
     * Indicates which clock is currently displayed - should be one of {@link ClockSize}.
     * Use null to signify it is uninitialized.
     */
    @ClockSize private Integer mDisplayedClockSize = null;

    @VisibleForTesting AnimatorSet mClockInAnim = null;
    @VisibleForTesting AnimatorSet mClockOutAnim = null;
    private ObjectAnimator mStatusAreaAnim = null;

    /**
     * If the Keyguard Slice has a header (big center-aligned text.)
     */
    private boolean mSupportsDarkText;
    private int[] mColorPalette;

    private int mClockSwitchYAmount;
    @VisibleForTesting boolean mChildrenAreLaidOut = false;
    private Handler mHandler;
    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        public void onLogoutEnabledChanged() {
        }

        public void onUserSwitchComplete(int i) {
        }

        public void onTimeChanged() {
            if (mClockPlugin != null) {
                mClockPlugin.onTimeTick();
            }
        }

        public void onTimeZoneChanged(TimeZone timeZone) {
            if (mClockPlugin != null) {
                mClockPlugin.onTimeTick();
            }
        }

        public void onKeyguardVisibilityChanged(boolean z) {
            if (mClockPlugin != null) {
                mClockPlugin.setTextColor(mDarkAmount > 0.0f ? -1 : mContext.getResources().getColor(17170490));
            }
        }

        public void onStartedWakingUp() {
            if (mClockPlugin != null) {
                mClockPlugin.setTextColor(mDarkAmount > 0.0f ? -1 : mContext.getResources().getColor(17170490));
                mClockPlugin.onTimeTick();
            }
        }

        public void onFinishedGoingToSleep(int i) {
            if (mClockPlugin != null) {
                mClockPlugin.setTextColor(mDarkAmount > 0.0f ? -1 : mContext.getResources().getColor(17170490));
            }
        }

        public void onStartedGoingToSleep(int i) {
            if (mDisplayedClockSize != null) {
                KeyguardClockSwitch keyguardClockSwitch = KeyguardClockSwitch.this;
                setupFrames("startedGoingToSleep", mDisplayedClockSize.intValue() != 0);
            }
        }
    };


    public KeyguardClockSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mDisplayedClockSize != null) {
            boolean landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
            boolean useLargeClock = mDisplayedClockSize == LARGE && !landscape;
            updateClockViews(useLargeClock, /* animate */ true);
        }
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mLargeClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mContext.getResources()
                .getDimensionPixelSize(R.dimen.large_clock_text_size));
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mContext.getResources()
                .getDimensionPixelSize(R.dimen.clock_text_size));

        mClockSwitchYAmount = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_clock_switch_y_shift);

        mSmartspaceTopOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_smartspace_top_offset);
    }

    public void onThemeChanged() {
        int customClockFont = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.KG_CUSTOM_CLOCK_FONT , 23, UserHandle.USER_CURRENT);
        
        switch (customClockFont) {
        	case 0:
        	Typeface sansSF = Typeface.create("sans-serif", Typeface.NORMAL);
        	mClockView.setTypeface(sansSF);
        	mLargeClockView.setTypeface(sansSF);
        	break;
        	case 1:
        	Typeface accuratist = Typeface.create("accuratist", Typeface.NORMAL);
        	mClockView.setTypeface(accuratist);
        	mLargeClockView.setTypeface(accuratist);
        	break;
        	case 2:
        	Typeface aclonica = Typeface.create("aclonica", Typeface.NORMAL);
        	mClockView.setTypeface(aclonica);
        	mLargeClockView.setTypeface(aclonica);
        	break;
        	case 3:
        	Typeface amarante = Typeface.create("amarante", Typeface.NORMAL);
        	mClockView.setTypeface(amarante);
        	mLargeClockView.setTypeface(amarante);
        	break;
        	case 4:
        	Typeface bariol = Typeface.create("bariol", Typeface.NORMAL);
        	mClockView.setTypeface(bariol);
        	mLargeClockView.setTypeface(bariol);
        	break;
        	case 5:
        	Typeface cagliostro = Typeface.create("cagliostro", Typeface.NORMAL);
        	mClockView.setTypeface(cagliostro);
        	mLargeClockView.setTypeface(cagliostro);
        	break;
        	case 6:
        	Typeface cocon = Typeface.create("cocon", Typeface.NORMAL);
        	mClockView.setTypeface(cocon);
        	mLargeClockView.setTypeface(cocon);
        	break;
        	case 7:
        	Typeface comfortaa = Typeface.create("comfortaa", Typeface.NORMAL);
        	mClockView.setTypeface(comfortaa);
        	mLargeClockView.setTypeface(comfortaa);
        	break;
        	case 8:
        	Typeface comicsans = Typeface.create("comicsans", Typeface.NORMAL);
        	mClockView.setTypeface(comicsans);
        	mLargeClockView.setTypeface(comicsans);
        	break;
        	case 9:
        	Typeface coolstory = Typeface.create("coolstory", Typeface.NORMAL);
        	mClockView.setTypeface(coolstory);
        	mLargeClockView.setTypeface(coolstory);
        	break;
        	case 10:
        	Typeface exotwo = Typeface.create("exotwo", Typeface.NORMAL);
        	mClockView.setTypeface(exotwo);
        	mLargeClockView.setTypeface(exotwo);
        	break;
        	case 11:
        	Typeface fifa2018 = Typeface.create("fifa2018", Typeface.NORMAL);
        	mClockView.setTypeface(fifa2018);
        	mLargeClockView.setTypeface(fifa2018);
        	break;
        	case 12:
        	Typeface fluidsans = Typeface.create("fluid-sans", Typeface.NORMAL);
        	mClockView.setTypeface(fluidsans);
        	mLargeClockView.setTypeface(fluidsans);
        	break;
        	case 13:
        	Typeface googlesans = Typeface.create("googlesans", Typeface.NORMAL);
        	mClockView.setTypeface(googlesans);
        	mLargeClockView.setTypeface(googlesans);
        	break;
        	case 14:
        	Typeface grandhotel = Typeface.create("grandhotel", Typeface.NORMAL);
        	mClockView.setTypeface(grandhotel);
        	mLargeClockView.setTypeface(grandhotel);
        	break;
        	case 15:
        	Typeface harmonyossans = Typeface.create("harmonyos-sans", Typeface.NORMAL);
        	mClockView.setTypeface(harmonyossans);
        	mLargeClockView.setTypeface(harmonyossans);
        	break;
        	case 16:
        	Typeface intercustom = Typeface.create("inter_custom", Typeface.NORMAL);
        	mClockView.setTypeface(intercustom);
        	mLargeClockView.setTypeface(intercustom);
        	break;
        	case 17:
        	Typeface jtleonor = Typeface.create("jtleonor", Typeface.NORMAL);
        	mClockView.setTypeface(jtleonor);
        	mLargeClockView.setTypeface(jtleonor);
        	break;
        	case 18:
        	Typeface latobold = Typeface.create("lato-bold", Typeface.NORMAL);
        	mClockView.setTypeface(latobold);
        	mLargeClockView.setTypeface(latobold);
        	break;
        	case 19:
        	Typeface lgsmartgothic = Typeface.create("lgsmartgothic", Typeface.NORMAL);
        	mClockView.setTypeface(lgsmartgothic);
        	mLargeClockView.setTypeface(lgsmartgothic);
        	break;
        	case 20:
        	Typeface linotte = Typeface.create("linotte", Typeface.NORMAL);
        	mClockView.setTypeface(linotte);
        	mLargeClockView.setTypeface(linotte);
        	break;
        	case 21:
        	Typeface misans = Typeface.create("misans", Typeface.NORMAL);
        	mClockView.setTypeface(misans);
        	mLargeClockView.setTypeface(misans);
        	break;
        	case 22:
        	Typeface nokiapure = Typeface.create("nokiapure", Typeface.NORMAL);
        	mClockView.setTypeface(nokiapure);
        	mLargeClockView.setTypeface(nokiapure);
        	break;
        	case 23:
        	Typeface nothingdot57 = Typeface.create("nothingdot57", Typeface.NORMAL);
        	mClockView.setTypeface(nothingdot57);
        	mLargeClockView.setTypeface(nothingdot57);
        	break;
        	case 24:
        	Typeface nunitobold = Typeface.create("nunito-bold", Typeface.NORMAL);
        	mClockView.setTypeface(nunitobold);
        	mLargeClockView.setTypeface(nunitobold);
        	break;
        	case 25:
        	Typeface opsans = Typeface.create("op-sans", Typeface.NORMAL);
        	mClockView.setTypeface(opsans);
        	mLargeClockView.setTypeface(opsans);
        	break;
        	case 26:
        	Typeface oneplusslate = Typeface.create("oneplusslate", Typeface.NORMAL);
        	mClockView.setTypeface(oneplusslate);
        	mLargeClockView.setTypeface(oneplusslate);
        	break;
        	case 27:
        	Typeface opposans = Typeface.create("opposans", Typeface.NORMAL);
        	mClockView.setTypeface(opposans);
        	mLargeClockView.setTypeface(opposans);
        	break;
        	case 28:
        	Typeface oswaldbold = Typeface.create("oswald-bold", Typeface.NORMAL);
        	mClockView.setTypeface(oswaldbold);
        	mLargeClockView.setTypeface(oswaldbold);
        	break;
        	case 29:
        	Typeface productsansvh = Typeface.create("productsansvh", Typeface.NORMAL);
        	mClockView.setTypeface(productsansvh);
        	mLargeClockView.setTypeface(productsansvh);
        	break;
        	case 30:
        	Typeface quando = Typeface.create("quando", Typeface.NORMAL);
        	mClockView.setTypeface(quando);
        	mLargeClockView.setTypeface(quando);
        	break;
        	case 31:
        	Typeface redressed = Typeface.create("redressed", Typeface.NORMAL);
        	mClockView.setTypeface(redressed);
        	mLargeClockView.setTypeface(redressed);
        	break;
        	case 32:
        	Typeface reemkufi = Typeface.create("reemkufi", Typeface.NORMAL);
        	mClockView.setTypeface(reemkufi);
        	mLargeClockView.setTypeface(reemkufi);
        	break;
        	case 33:
        	Typeface robotocondensed = Typeface.create("robotocondensed", Typeface.NORMAL);
        	mClockView.setTypeface(robotocondensed);
        	mLargeClockView.setTypeface(robotocondensed);
        	break;
        	case 34:
        	Typeface rosemary = Typeface.create("rosemary", Typeface.NORMAL);
        	mClockView.setTypeface(rosemary);
        	mLargeClockView.setTypeface(rosemary);
        	break;
        	case 35:
        	Typeface rubikbold = Typeface.create("rubik-bold", Typeface.NORMAL);
        	mClockView.setTypeface(rubikbold);
        	mLargeClockView.setTypeface(rubikbold);
        	break;
        	case 36:
        	Typeface samsungone = Typeface.create("samsungone", Typeface.NORMAL);
        	mClockView.setTypeface(samsungone);
        	mLargeClockView.setTypeface(samsungone);
        	break;
        	case 37:
        	Typeface sanfrancisco = Typeface.create("sanfrancisco", Typeface.NORMAL);
        	mClockView.setTypeface(sanfrancisco);
        	mLargeClockView.setTypeface(sanfrancisco);
        	break;
        	case 38:
        	Typeface simpleday = Typeface.create("simpleday", Typeface.NORMAL);
        	mClockView.setTypeface(simpleday);
        	mLargeClockView.setTypeface(simpleday);
        	break;
        	case 39:
        	Typeface sonysketch = Typeface.create("sonysketch", Typeface.NORMAL);
        	mClockView.setTypeface(sonysketch);
        	mLargeClockView.setTypeface(sonysketch);
        	break;
        	case 40:
        	Typeface storopia = Typeface.create("storopia", Typeface.NORMAL);
        	mClockView.setTypeface(storopia);
        	mLargeClockView.setTypeface(storopia);
        	break;
        	case 41:
        	Typeface surfer = Typeface.create("surfer", Typeface.NORMAL);
        	mClockView.setTypeface(surfer);
        	mLargeClockView.setTypeface(surfer);
        	break;
        	case 42:
        	Typeface ubuntu = Typeface.create("ubuntu", Typeface.NORMAL);
        	mClockView.setTypeface(ubuntu);
        	mLargeClockView.setTypeface(ubuntu);
        	break;
        	case 43:
        	Typeface manrope = Typeface.create("manrope", Typeface.NORMAL);
        	mClockView.setTypeface(manrope);
        	mLargeClockView.setTypeface(manrope);
        	break;
        	case 44:
        	Typeface notosans = Typeface.create("noto-sans", Typeface.NORMAL);
        	mClockView.setTypeface(notosans);
        	mLargeClockView.setTypeface(notosans);
        	break;
        	case 45:
        	Typeface recursivecasual = Typeface.create("recursive-casual", Typeface.NORMAL);
        	mClockView.setTypeface(recursivecasual);
        	mLargeClockView.setTypeface(recursivecasual);
        	break;
        	case 46:
        	Typeface recursive = Typeface.create("recursive", Typeface.NORMAL);
        	mClockView.setTypeface(recursive);
        	mLargeClockView.setTypeface(recursive);
        	break;
        	case 47:
        	Typeface robotosystem = Typeface.create("roboto-system", Typeface.NORMAL);
        	mClockView.setTypeface(robotosystem);
        	mLargeClockView.setTypeface(robotosystem);
        	break;
        	case 48:
        	Typeface sourcesans = Typeface.create("source-sans", Typeface.NORMAL);
        	mClockView.setTypeface(sourcesans);
        	mLargeClockView.setTypeface(sourcesans);
        	break;
        	case 49:
        	Typeface serif = Typeface.create("serif", Typeface.NORMAL);
        	mClockView.setTypeface(serif);
        	mLargeClockView.setTypeface(serif);
        	break;
        	case 50:
        	Typeface googlesansclock = Typeface.create("googlesansclock", Typeface.NORMAL);
        	mClockView.setTypeface(googlesansclock);
        	mLargeClockView.setTypeface(googlesansclock);
        	break;
        	default:
        	break;
        	
        }
    }
    
    /**
     * Returns if this view is presenting a custom clock, or the default implementation.
     */
    public boolean hasCustomClock() {
        return mClockPlugin != null;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mClockFrame = findViewById(R.id.lockscreen_clock_view);
        mClockView = findViewById(R.id.animatable_clock_view);
        mLargeClockFrame = findViewById(R.id.lockscreen_clock_view_large);
        mLargeClockView = findViewById(R.id.animatable_clock_view_large);
        mStatusArea = findViewById(R.id.keyguard_status_area);

        onDensityOrFontScaleChanged();
        onThemeChanged();
    }

    void setClockPlugin(ClockPlugin plugin, int statusBarState) {
        // Disconnect from existing plugin.
        if (mClockPlugin != null) {
            View smallClockView = mClockPlugin.getView();
            if (smallClockView != null && smallClockView.getParent() == mClockFrame) {
                mClockFrame.removeView(smallClockView);
            }
            View bigClockView = mClockPlugin.getBigClockView();
            if (bigClockView != null && bigClockView.getParent() == mLargeClockFrame) {
                mLargeClockFrame.removeView(bigClockView);
            }
            mClockPlugin.onDestroyView();
            mClockPlugin = null;
        }
        boolean useLargeClock = false;
        if (plugin == null) {
            this.mStatusArea.setVisibility(View.VISIBLE);
            this.mClockView.setVisibility(View.VISIBLE);
            this.mLargeClockView.setVisibility(View.VISIBLE);
            this.mClockFrame.setVisibility(View.VISIBLE);
            setMargins(this.mLargeClockFrame, 0, 0, 0, 0);
            return;
        }
        // Attach small and big clock views to hierarchy.
        View smallClockView = plugin.getView();
        if (smallClockView != null) {
            mClockFrame.addView(smallClockView, -1,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            mClockView.setVisibility(View.GONE);
        }
        View bigClockView = plugin.getBigClockView();
        if (bigClockView != null) {
            mLargeClockFrame.addView(bigClockView);
            mLargeClockView.setVisibility(View.GONE);
        }
        mStatusArea.setVisibility(plugin.shouldShowStatusArea() ? View.VISIBLE : View.GONE);
        // Initialize plugin parameters.
        mClockPlugin = plugin;
        mClockPlugin.setStyle(getPaint().getStyle());
        mClockPlugin.setTextColor(getCurrentTextColor());
        mClockPlugin.setDarkAmount(mDarkAmount);
        Integer num = this.mDisplayedClockSize;
        if (num != null && num.intValue() == 0) {
            useLargeClock = true;
        }
        setupFrames("setPlugin", useLargeClock);
        if (mColorPalette != null) {
            mClockPlugin.setColorPalette(mSupportsDarkText, mColorPalette);
        }
    }

    /**
     * It will also update plugin setStyle if plugin is connected.
     */
    public void setStyle(Style style) {
        if (mClockPlugin != null) {
            mClockPlugin.setStyle(style);
        }
    }

    /**
     * It will also update plugin setTextColor if plugin is connected.
     */
    public void setTextColor(int color) {
        if (mClockPlugin != null) {
            mClockPlugin.setTextColor(color);
        }
    }

    private void updateClockViews(boolean useLargeClock, boolean animate) {
        if (mClockInAnim != null) mClockInAnim.cancel();
        if (mClockOutAnim != null) mClockOutAnim.cancel();
        if (mStatusAreaAnim != null) mStatusAreaAnim.cancel();

        mClockInAnim = null;
        mClockOutAnim = null;
        mStatusAreaAnim = null;

        View in, out;
        int direction = 1;
        float statusAreaYTranslation;
        if (useLargeClock) {
            out = mClockFrame;
            in = mLargeClockFrame;
            if (indexOfChild(in) == -1) addView(in);
            direction = -1;
            statusAreaYTranslation = mClockFrame.getTop() - mStatusArea.getTop()
                    + mSmartspaceTopOffset;
        } else {
            in = mClockFrame;
            out = mLargeClockFrame;
            statusAreaYTranslation = 0f;

            // Must remove in order for notifications to appear in the proper place
            removeView(out);
        }

        if (!animate) {
            out.setAlpha(0f);
            in.setAlpha(1f);
            in.setVisibility(VISIBLE);
            mStatusArea.setTranslationY(statusAreaYTranslation);
            return;
        }

        mClockOutAnim = new AnimatorSet();
        mClockOutAnim.setDuration(CLOCK_OUT_MILLIS);
        mClockOutAnim.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
        mClockOutAnim.playTogether(
                ObjectAnimator.ofFloat(out, View.ALPHA, 0f),
                ObjectAnimator.ofFloat(out, View.TRANSLATION_Y, 0,
                        direction * -mClockSwitchYAmount));
        mClockOutAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mClockOutAnim = null;
            }
        });

        in.setAlpha(0);
        in.setVisibility(View.VISIBLE);
        mClockInAnim = new AnimatorSet();
        mClockInAnim.setDuration(CLOCK_IN_MILLIS);
        mClockInAnim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        mClockInAnim.playTogether(ObjectAnimator.ofFloat(in, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(in, View.TRANSLATION_Y, direction * mClockSwitchYAmount, 0));
        mClockInAnim.setStartDelay(CLOCK_OUT_MILLIS / 2);
        mClockInAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mClockInAnim = null;
            }
        });

        mClockInAnim.start();
        mClockOutAnim.start();

        mStatusAreaAnim = ObjectAnimator.ofFloat(mStatusArea, View.TRANSLATION_Y,
                statusAreaYTranslation);
        mStatusAreaAnim.setDuration(STATUS_AREA_MOVE_MILLIS);
        mStatusAreaAnim.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mStatusAreaAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mStatusAreaAnim = null;
            }
        });
        mStatusAreaAnim.start();
        setupFrames("useLargeClock", !useLargeClock);
    }

    private void setPluginBelowKgArea() {
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(-1, -2);
        layoutParams.addRule(3, this.mStatusArea.getId());
        mLargeClockFrame.setLayoutParams(layoutParams);
    }

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
     *
     * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
     */
    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        if (mClockPlugin != null) {
            mClockPlugin.setDarkAmount(darkAmount);
        }
    }

    /**
     * Display the desired clock and hide the other one
     *
     * @return true if desired clock appeared and false if it was already visible
     */
    boolean switchToClock(@ClockSize int clockSize, boolean animate) {
        if (mDisplayedClockSize != null && clockSize == mDisplayedClockSize) {
            return false;
        }
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        boolean useLargeClock = clockSize == LARGE && !landscape;

        // let's make sure clock is changed only after all views were laid out so we can
        // translate them properly
        if (mChildrenAreLaidOut) {
            updateClockViews(useLargeClock, animate);
        }

        mDisplayedClockSize = clockSize;
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (mDisplayedClockSize != null && !mChildrenAreLaidOut) {
            post(() -> updateClockViews(mDisplayedClockSize == LARGE, /* animate */ true));
        }

        mChildrenAreLaidOut = true;
    }

    public Paint getPaint() {
        return mClockView.getPaint();
    }

    public int getCurrentTextColor() {
        return mClockView.getCurrentTextColor();
    }

    public float getTextSize() {
        return mClockView.getTextSize();
    }

    /**
     * Refresh the time of the clock, due to either time tick broadcast or doze time tick alarm.
     */
    public void refresh() {
        if (mClockPlugin != null) {
            mClockPlugin.onTimeTick();
        }
    }

    /**
     * Notifies that the time zone has changed.
     */
    public void onTimeZoneChanged(TimeZone timeZone) {
        if (mClockPlugin != null) {
            mClockPlugin.onTimeZoneChanged(timeZone);
        }
    }

    /**
     * Notifies that the time format has changed.
     *
     * @param timeFormat "12" for 12-hour format, "24" for 24-hour format
     */
    public void onTimeFormatChanged(String timeFormat) {
        if (mClockPlugin != null) {
            mClockPlugin.onTimeFormatChanged(timeFormat);
        }
    }

    void updateColors(ColorExtractor.GradientColors colors) {
        mSupportsDarkText = colors.supportsDarkText();
        mColorPalette = colors.getColorPalette();
        if (mClockPlugin != null) {
            mClockPlugin.setColorPalette(mSupportsDarkText, mColorPalette);
            this.mClockPlugin.setTextColor(this.mDarkAmount > 0.0f ? -1 : this.mContext.getResources().getColor(17170490));
        }
    }

    private void setupFrames(String str, boolean useLargeClock) {
        int i = 0;
        int largeClockTopMargin = getContext().getResources().getDimensionPixelSize(
            R.dimen.keyguard_large_clock_top_margin);
        if (useLargeClock) {
            this.mClockFrame.setVisibility(View.VISIBLE);
            if (mClockPlugin == null) {
                setMargins(this.mLargeClockFrame, 0, largeClockTopMargin, 0, 0);
            } else {
                setMargins(this.mLargeClockFrame, 0, 0, 0, 0);

            }
        } else if (hasCustomClock()) {
                int dimensionPixelSize = mContext.getResources().getDisplayMetrics().heightPixels - mContext.getResources().getDimensionPixelSize(R.dimen.status_bar_height);
                mClockFrame.setVisibility(!mClockPlugin.shouldShowClockFrame() ? View.GONE : View.VISIBLE);
            if (mClockPlugin.shouldShowStatusArea()) {
                setPluginBelowKgArea();
            } else {
                FrameLayout frameLayout = mLargeClockFrame;
                if (mClockPlugin.usesPreferredY()) {
                    i = mClockPlugin.getPreferredY(dimensionPixelSize);
                }
                setMargins(frameLayout, 0, i, 0, 0);
                }
            } else {
                mClockFrame.setVisibility(View.VISIBLE);
                if (mClockPlugin == null) {
                    setMargins(mLargeClockFrame, 0, largeClockTopMargin, 0, 0);
                } else {
                    setMargins(mLargeClockFrame, 0, 0, 0, 0);
                }
            }
            refresh();
    }

    public void setMargins(View view, int i, int i2, int i3, int i4) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).setMargins(i, i2, i3, i4);
            view.requestLayout(); 
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardClockSwitch:");
        pw.println("  mClockPlugin: " + mClockPlugin);
        pw.println("  mClockFrame: " + mClockFrame);
        pw.println("  mLargeClockFrame: " + mLargeClockFrame);
        pw.println("  mStatusArea: " + mStatusArea);
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mSupportsDarkText: " + mSupportsDarkText);
        pw.println("  mColorPalette: " + Arrays.toString(mColorPalette));
        pw.println("  mDisplayedClockSize: " + mDisplayedClockSize);
    }
}
