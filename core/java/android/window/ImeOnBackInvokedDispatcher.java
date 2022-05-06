/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.HashMap;

/**
 * A {@link OnBackInvokedDispatcher} for IME that forwards {@link OnBackInvokedCallback}
 * registrations from the IME process to the app process to be registered on the app window.
 * <p>
 * The app process creates and propagates an instance of {@link ImeOnBackInvokedDispatcher}
 * to the IME to be set on the IME window's {@link WindowOnBackInvokedDispatcher}.
 * <p>
 * @see WindowOnBackInvokedDispatcher#setImeOnBackInvokedDispatcher
 *
 * @hide
 */
public class ImeOnBackInvokedDispatcher implements OnBackInvokedDispatcher, Parcelable {

    private static final String TAG = "ImeBackDispatcher";
    static final String RESULT_KEY_ID = "id";
    static final String RESULT_KEY_CALLBACK = "callback";
    static final String RESULT_KEY_PRIORITY = "priority";
    static final int RESULT_CODE_REGISTER = 0;
    static final int RESULT_CODE_UNREGISTER = 1;
    @NonNull
    private final ResultReceiver mResultReceiver;

    public ImeOnBackInvokedDispatcher(Handler handler) {
        mResultReceiver = new ResultReceiver(handler) {
            @Override
            public void onReceiveResult(int resultCode, Bundle resultData) {
                WindowOnBackInvokedDispatcher dispatcher = getReceivingDispatcher();
                if (dispatcher != null) {
                    receive(resultCode, resultData, dispatcher);
                }
            }
        };
    }

    /**
     * Override this method to return the {@link WindowOnBackInvokedDispatcher} of the window
     * that should receive the forwarded callback.
     */
    @Nullable
    protected WindowOnBackInvokedDispatcher getReceivingDispatcher() {
        return null;
    }

    ImeOnBackInvokedDispatcher(Parcel in) {
        mResultReceiver = in.readTypedObject(ResultReceiver.CREATOR);
    }

    @Override
    public void registerOnBackInvokedCallback(
            @OnBackInvokedDispatcher.Priority int priority,
            @NonNull OnBackInvokedCallback callback) {
        final Bundle bundle = new Bundle();
        final IOnBackInvokedCallback iCallback =
                new WindowOnBackInvokedDispatcher.OnBackInvokedCallbackWrapper(callback);
        bundle.putBinder(RESULT_KEY_CALLBACK, iCallback.asBinder());
        bundle.putInt(RESULT_KEY_PRIORITY, priority);
        bundle.putInt(RESULT_KEY_ID, callback.hashCode());
        mResultReceiver.send(RESULT_CODE_REGISTER, bundle);
    }

    @Override
    public void unregisterOnBackInvokedCallback(
            @NonNull OnBackInvokedCallback callback) {
        Bundle bundle = new Bundle();
        bundle.putInt(RESULT_KEY_ID, callback.hashCode());
        mResultReceiver.send(RESULT_CODE_UNREGISTER, bundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mResultReceiver, flags);
    }

    @NonNull
    public static final Parcelable.Creator<ImeOnBackInvokedDispatcher> CREATOR =
            new Parcelable.Creator<ImeOnBackInvokedDispatcher>() {
                public ImeOnBackInvokedDispatcher createFromParcel(Parcel in) {
                    return new ImeOnBackInvokedDispatcher(in);
                }
                public ImeOnBackInvokedDispatcher[] newArray(int size) {
                    return new ImeOnBackInvokedDispatcher[size];
                }
            };

    private final HashMap<Integer, OnBackInvokedCallback> mImeCallbackMap = new HashMap<>();

    private void receive(
            int resultCode, Bundle resultData,
            @NonNull OnBackInvokedDispatcher receivingDispatcher) {
        final int callbackId = resultData.getInt(RESULT_KEY_ID);
        if (resultCode == RESULT_CODE_REGISTER) {
            int priority = resultData.getInt(RESULT_KEY_PRIORITY);
            final IOnBackInvokedCallback callback = IOnBackInvokedCallback.Stub.asInterface(
                    resultData.getBinder(RESULT_KEY_CALLBACK));
            registerReceivedCallback(
                    callback, priority, callbackId, receivingDispatcher);
        } else if (resultCode == RESULT_CODE_UNREGISTER) {
            unregisterReceivedCallback(callbackId, receivingDispatcher);
        }
    }

    private void registerReceivedCallback(
            @NonNull IOnBackInvokedCallback iCallback,
            @OnBackInvokedDispatcher.Priority int priority,
            int callbackId,
            @NonNull OnBackInvokedDispatcher receivingDispatcher) {
        final ImeOnBackInvokedCallback imeCallback =
                new ImeOnBackInvokedCallback(iCallback);
        mImeCallbackMap.put(callbackId, imeCallback);
        receivingDispatcher.registerOnBackInvokedCallback(priority, imeCallback);
    }

    private void unregisterReceivedCallback(
            int callbackId, OnBackInvokedDispatcher receivingDispatcher) {
        final OnBackInvokedCallback callback = mImeCallbackMap.get(callbackId);
        if (callback == null) {
            Log.e(TAG, "Ime callback not found. Ignoring unregisterReceivedCallback. "
                    + "callbackId: " + callbackId);
            return;
        }
        receivingDispatcher.unregisterOnBackInvokedCallback(callback);
    }

    /** Clears all registered callbacks on the instance. */
    public void clear() {
        mImeCallbackMap.clear();
    }

    static class ImeOnBackInvokedCallback implements OnBackInvokedCallback {
        @NonNull
        private final IOnBackInvokedCallback mIOnBackInvokedCallback;

        ImeOnBackInvokedCallback(@NonNull IOnBackInvokedCallback iCallback) {
            mIOnBackInvokedCallback = iCallback;
        }

        @Override
        public void onBackInvoked() {
            try {
                if (mIOnBackInvokedCallback != null) {
                    mIOnBackInvokedCallback.onBackInvoked();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception when invoking forwarded callback. e: ", e);
            }
        }

        IOnBackInvokedCallback getIOnBackInvokedCallback() {
            return mIOnBackInvokedCallback;
        }
    }
}
