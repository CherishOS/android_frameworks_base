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

package com.android.systemui.flags;

import static com.android.systemui.flags.FlagManager.ACTION_GET_FLAGS;
import static com.android.systemui.flags.FlagManager.ACTION_SET_FLAG;
import static com.android.systemui.flags.FlagManager.EXTRA_FLAGS;
import static com.android.systemui.flags.FlagManager.EXTRA_ID;
import static com.android.systemui.flags.FlagManager.EXTRA_VALUE;

import static java.util.Objects.requireNonNull;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.settings.SecureSettings;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 * Concrete implementation of the a Flag manager that returns default values for debug builds
 *
 * Flags can be set (or unset) via the following adb command:
 *
 *   adb shell am broadcast -a com.android.systemui.action.SET_FLAG --ei id <id> [--ez value <0|1>]
 *
 * To restore a flag back to its default, leave the `--ez value <0|1>` off of the command.
 */
@SysUISingleton
public class FeatureFlagsDebug implements FeatureFlags, Dumpable {
    private static final String TAG = "SysUIFlags";

    private final FlagManager mFlagManager;
    private final SecureSettings mSecureSettings;
    private final Resources mResources;
    private final SystemPropertiesHelper mSystemProperties;
    private final Supplier<Map<Integer, Flag<?>>> mFlagsCollector;
    private final Map<Integer, Boolean> mBooleanFlagCache = new TreeMap<>();
    private final Map<Integer, String> mStringFlagCache = new TreeMap<>();
    private final IStatusBarService mBarService;

    @Inject
    public FeatureFlagsDebug(
            FlagManager flagManager,
            Context context,
            SecureSettings secureSettings,
            SystemPropertiesHelper systemProperties,
            @Main Resources resources,
            DumpManager dumpManager,
            @Nullable Supplier<Map<Integer, Flag<?>>> flagsCollector,
            IStatusBarService barService) {
        mFlagManager = flagManager;
        mSecureSettings = secureSettings;
        mResources = resources;
        mSystemProperties = systemProperties;
        mFlagsCollector = flagsCollector != null ? flagsCollector : Flags::collectFlags;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_FLAG);
        filter.addAction(ACTION_GET_FLAGS);
        flagManager.setOnSettingsChangedAction(this::restartSystemUI);
        flagManager.setClearCacheAction(this::removeFromCache);
        context.registerReceiver(mReceiver, filter, null, null,
                Context.RECEIVER_EXPORTED_UNAUDITED);
        dumpManager.registerDumpable(TAG, this);
        mBarService = barService;
    }

    @Override
    public boolean isEnabled(@NonNull BooleanFlag flag) {
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            mBooleanFlagCache.put(id,
                    readFlagValue(id, flag.getDefault(), BooleanFlagSerializer.INSTANCE));
        }

        return mBooleanFlagCache.get(id);
    }

    @Override
    public boolean isEnabled(@NonNull ResourceBooleanFlag flag) {
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            mBooleanFlagCache.put(id,
                    readFlagValue(id, mResources.getBoolean(flag.getResourceId()),
                            BooleanFlagSerializer.INSTANCE));
        }

        return mBooleanFlagCache.get(id);
    }

    @Override
    public boolean isEnabled(@NonNull SysPropBooleanFlag flag) {
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            mBooleanFlagCache.put(
                    id, mSystemProperties.getBoolean(flag.getName(), flag.getDefault()));
        }

        return mBooleanFlagCache.get(id);
    }

    @NonNull
    @Override
    public String getString(@NonNull StringFlag flag) {
        int id = flag.getId();
        if (!mStringFlagCache.containsKey(id)) {
            mStringFlagCache.put(id,
                    readFlagValue(id, flag.getDefault(), StringFlagSerializer.INSTANCE));
        }

        return mStringFlagCache.get(id);
    }

    @NonNull
    @Override
    public String getString(@NonNull ResourceStringFlag flag) {
        int id = flag.getId();
        if (!mStringFlagCache.containsKey(id)) {
            mStringFlagCache.put(id,
                    readFlagValue(id, mResources.getString(flag.getResourceId()),
                            StringFlagSerializer.INSTANCE));
        }

        return mStringFlagCache.get(id);
    }

    @NonNull
    private <T> T readFlagValue(int id, @NonNull T defaultValue, FlagSerializer<T> serializer) {
        requireNonNull(defaultValue, "defaultValue");
        T result = readFlagValueInternal(id, serializer);
        return result == null ? defaultValue : result;
    }


    /** Returns the stored value or null if not set. */
    @Nullable
    private <T> T readFlagValueInternal(int id, FlagSerializer<T> serializer) {
        try {
            return mFlagManager.readFlagValue(id, serializer);
        } catch (Exception e) {
            eraseInternal(id);
        }
        return null;
    }

    private <T> void setFlagValue(int id, @NonNull T value, FlagSerializer<T> serializer) {
        requireNonNull(value, "Cannot set a null value");
        T currentValue = readFlagValueInternal(id, serializer);
        if (Objects.equals(currentValue, value)) {
            Log.i(TAG, "Flag id " + id + " is already " + value);
            return;
        }
        final String data = serializer.toSettingsData(value);
        if (data == null) {
            Log.w(TAG, "Failed to set id " + id + " to " + value);
            return;
        }
        mSecureSettings.putString(mFlagManager.idToSettingsKey(id), data);
        Log.i(TAG, "Set id " + id + " to " + value);
        removeFromCache(id);
        mFlagManager.dispatchListenersAndMaybeRestart(id, this::restartSystemUI);
    }

    private <T> void eraseFlag(Flag<T> flag) {
        if (flag instanceof SysPropFlag) {
            mSystemProperties.erase(((SysPropFlag<T>) flag).getName());
            dispatchListenersAndMaybeRestart(flag.getId(), this::restartAndroid);
        } else {
            eraseFlag(flag.getId());
        }
    }

    /** Erase a flag's overridden value if there is one. */
    private void eraseFlag(int id) {
        eraseInternal(id);
        removeFromCache(id);
        dispatchListenersAndMaybeRestart(id, this::restartSystemUI);
    }

    private void dispatchListenersAndMaybeRestart(int id, Consumer<Boolean> restartAction) {
        mFlagManager.dispatchListenersAndMaybeRestart(id, restartAction);
    }
    /** Works just like {@link #eraseFlag(int)} except that it doesn't restart SystemUI. */
    private void eraseInternal(int id) {
        // We can't actually "erase" things from sysprops, but we can set them to empty!
        mSecureSettings.putString(mFlagManager.idToSettingsKey(id), "");
        Log.i(TAG, "Erase id " + id);
    }

    @Override
    public void addListener(@NonNull Flag<?> flag, @NonNull Listener listener) {
        mFlagManager.addListener(flag, listener);
    }

    @Override
    public void removeListener(@NonNull Listener listener) {
        mFlagManager.removeListener(listener);
    }

    private void restartSystemUI(boolean requestSuppress) {
        if (requestSuppress) {
            Log.i(TAG, "SystemUI Restart Suppressed");
            return;
        }
        Log.i(TAG, "Restarting SystemUI");
        // SysUI starts back when up exited. Is there a better way to do this?
        System.exit(0);
    }

    private void restartAndroid(boolean requestSuppress) {
        if (requestSuppress) {
            Log.i(TAG, "Android Restart Suppressed");
            return;
        }
        Log.i(TAG, "Restarting Android");
        try {
            mBarService.restart();
        } catch (RemoteException e) {
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) {
                return;
            }
            if (ACTION_SET_FLAG.equals(action)) {
                handleSetFlag(intent.getExtras());
            } else if (ACTION_GET_FLAGS.equals(action)) {
                Map<Integer, Flag<?>> knownFlagMap = mFlagsCollector.get();
                ArrayList<Flag<?>> flags = new ArrayList<>(knownFlagMap.values());

                // Convert all flags to parcelable flags.
                ArrayList<ParcelableFlag<?>> pFlags = new ArrayList<>();
                for (Flag<?> f : flags) {
                    ParcelableFlag<?> pf = toParcelableFlag(f);
                    if (pf != null) {
                        pFlags.add(pf);
                    }
                }

                Bundle extras =  getResultExtras(true);
                if (extras != null) {
                    extras.putParcelableArrayList(EXTRA_FLAGS, pFlags);
                }
            }
        }

        private void handleSetFlag(Bundle extras) {
            if (extras == null) {
                Log.w(TAG, "No extras");
                return;
            }
            int id = extras.getInt(EXTRA_ID);
            if (id <= 0) {
                Log.w(TAG, "ID not set or less than  or equal to 0: " + id);
                return;
            }

            Map<Integer, Flag<?>> flagMap = mFlagsCollector.get();
            if (!flagMap.containsKey(id)) {
                Log.w(TAG, "Tried to set unknown id: " + id);
                return;
            }
            Flag<?> flag = flagMap.get(id);

            if (!extras.containsKey(EXTRA_VALUE)) {
                eraseFlag(flag);
                return;
            }

            Object value = extras.get(EXTRA_VALUE);
            if (flag instanceof BooleanFlag && value instanceof Boolean) {
                setFlagValue(id, (Boolean) value, BooleanFlagSerializer.INSTANCE);
            } else  if (flag instanceof ResourceBooleanFlag && value instanceof Boolean) {
                setFlagValue(id, (Boolean) value, BooleanFlagSerializer.INSTANCE);
            } else  if (flag instanceof SysPropBooleanFlag && value instanceof Boolean) {
                // Store SysProp flags in SystemProperties where they can read by outside parties.
                mSystemProperties.setBoolean(
                        ((SysPropBooleanFlag) flag).getName(), (Boolean) value);
            } else if (flag instanceof StringFlag && value instanceof String) {
                setFlagValue(id, (String) value, StringFlagSerializer.INSTANCE);
            } else if (flag instanceof ResourceStringFlag && value instanceof String) {
                setFlagValue(id, (String) value, StringFlagSerializer.INSTANCE);
            } else {
                Log.w(TAG,
                        "Unable to set " + id + " of type " + flag.getClass() + " to value of type "
                                + (value == null ? null : value.getClass()));
            }
        }

        /**
         * Ensures that the data we send to the app reflects the current state of the flags.
         *
         * Also converts an non-parcelable versions of the flags to their parcelable versions.
         */
        @Nullable
        private ParcelableFlag<?> toParcelableFlag(Flag<?> f) {
            if (f instanceof BooleanFlag) {
                return new BooleanFlag(f.getId(), isEnabled((BooleanFlag) f));
            }
            if (f instanceof ResourceBooleanFlag) {
                return new BooleanFlag(f.getId(), isEnabled((ResourceBooleanFlag) f));
            }
            if (f instanceof SysPropBooleanFlag) {
                return new BooleanFlag(f.getId(), isEnabled((SysPropBooleanFlag) f));
            }

            // TODO: add support for other flag types.
            Log.w(TAG, "Unsupported Flag Type. Please file a bug.");
            return null;
        }
    };

    private void removeFromCache(int id) {
        mBooleanFlagCache.remove(id);
        mStringFlagCache.remove(id);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("can override: true");
        pw.println("booleans: " + mBooleanFlagCache.size());
        mBooleanFlagCache.forEach((key, value) -> pw.println("  sysui_flag_" + key + ": " + value));
        pw.println("Strings: " + mStringFlagCache.size());
        mStringFlagCache.forEach((key, value) -> pw.println("  sysui_flag_" + key
                + ": [length=" + value.length() + "] \"" + value + "\""));
    }
}
