package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon;

import java.util.concurrent.Executor;
import java.util.List;

import javax.inject.Inject;

public final class DataSwitchTile extends QSTileImpl<BooleanState> {

    private static final Intent sLongClickIntent = new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);

    private final BroadcastReceiver mSimReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "mSimReceiver:onReceive");
            refreshState();
        }
    };
    private final PhoneStateListener mPhoneStateListener;
    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyManager mTelephonyManager;
    private final Executor mBackgroundExecutor;

    private boolean mCanSwitch;
    private boolean mListening;
    private int mSimCount;

    @Inject
    public DataSwitchTile(
        QSHost host,
        @Background Looper backgroundLooper,
        @Main Handler mainHandler,
        FalsingManager falsingManager,
        MetricsLogger metricsLogger,
        StatusBarStateController statusBarStateController,
        ActivityStarter activityStarter,
        QSLogger qsLogger,
        @Background Executor backgroundExecutor
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mTelephonyManager = TelephonyManager.from(mContext);
        mBackgroundExecutor = backgroundExecutor;
        mPhoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String arg1) {
                mCanSwitch = mTelephonyManager.getCallState() == 0;
                refreshState();
            }
        };
    }

    @Override
    public boolean isAvailable() {
        final int count = TelephonyManager.getDefault().getPhoneCount();
        if (DEBUG) Log.d(TAG, "phoneCount: " + count);
        return count >= 2;
    }

    @Override
    public BooleanState newTileState() {
        final BooleanState state = new BooleanState();
        state.label = getTileLabel();
        return state;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (mListening) {
            final IntentFilter filter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            mContext.registerReceiver(mSimReceiver, filter);
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            refreshState();
        } else {
            mContext.unregisterReceiver(mSimReceiver);
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    private void updateSimCount() {
        final String simState = SystemProperties.get("gsm.sim.state");
        if (DEBUG) Log.d(TAG, "DataSwitchTile:updateSimCount:simState=" + simState);
        mSimCount = 0;
        if (simState == null) {
            Log.e(TAG, "Sim state is null");
            return;
        }
        final String[] sims = TextUtils.split(simState, ",");
        for (String sim : sims) {
            if (!sim.isEmpty()
                    && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_ABSENT)
                    && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_NOT_READY)) {
                mSimCount++;
            }
        }
        if (DEBUG) Log.d(TAG, "DataSwitchTile:updateSimCount:mSimCount=" + mSimCount);
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (!mCanSwitch) {
            if (DEBUG) Log.d(TAG, "Call state=" + mTelephonyManager.getCallState());
        } else if (mSimCount == 0) {
            if (DEBUG) Log.d(TAG, "handleClick:no sim card");
            Toast.makeText(mContext, R.string.qs_data_switch_toast_0,
                    Toast.LENGTH_LONG).show();
        } else if (mSimCount == 1) {
            if (DEBUG) Log.d(TAG, "handleClick:only one sim card");
            Toast.makeText(mContext, R.string.qs_data_switch_toast_1,
                    Toast.LENGTH_LONG).show();
        } else {
            mBackgroundExecutor.execute(() -> {
                toggleMobileDataEnabled();
                refreshState();
            });
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return sLongClickIntent;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_data_switch_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean activeSIMZero;
        if (arg == null) {
            final int defaultPhoneId = mSubscriptionManager.getDefaultDataPhoneId();
            if (DEBUG) Log.d(TAG, "default data phone id=" + defaultPhoneId);
            activeSIMZero = defaultPhoneId == 0;
        } else {
            activeSIMZero = (Boolean) arg;
        }
        updateSimCount();
        state.value = mSimCount == 2;
        if (mSimCount == 1 || mSimCount == 2) {
            state.icon = ResourceIcon.get(activeSIMZero
                    ? R.drawable.ic_qs_data_switch_1
                    : R.drawable.ic_qs_data_switch_2);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_data_switch_1);
        }
        if (mSimCount < 2 || !mCanSwitch) {
            state.state = 0;
            if (!mCanSwitch && DEBUG) Log.d(TAG, "call state isn't idle, set to unavailable.");
        } else {
            state.state = state.value ? 2 : 1;
        }

        state.contentDescription =
                mContext.getString(activeSIMZero
                        ? R.string.qs_data_switch_changed_1
                        : R.string.qs_data_switch_changed_2);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CHERISH_SETTINGS;
    }

    /**
     * Set whether to enable data for {@code subId}, also whether to disable data for other
     * subscription
     */
    private void toggleMobileDataEnabled() {
        // Get opposite slot 2 ^ 3 = 1, 1 ^ 3 = 2
        final int subId = SubscriptionManager.getDefaultDataSubscriptionId() ^ 3;
        final TelephonyManager telephonyManager =
                mTelephonyManager.createForSubscriptionId(subId);
        telephonyManager.setDataEnabled(true);
        mSubscriptionManager.setDefaultDataSubId(subId);
        if (DEBUG) Log.d(TAG, "Enabled subID: " + subId);

        final List<SubscriptionInfo> subInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList(true);
        if (subInfoList == null) return;
        // We never disable mobile data for opportunistic subscriptions.
        subInfoList.stream()
            .filter(subInfo -> !subInfo.isOpportunistic())
            .map(subInfo -> subInfo.getSubscriptionId())
            .filter(id -> id != subId)
            .forEach(id -> {
                mTelephonyManager.createForSubscriptionId(id).setDataEnabled(false);
                if (DEBUG) Log.d(TAG, "Disabled subID: " + id);
            });
    }
}
