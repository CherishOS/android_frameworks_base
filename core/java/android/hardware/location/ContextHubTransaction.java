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
package android.hardware.location;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A class describing a request sent to the Context Hub Service.
 *
 * This object is generated as a result of an asynchronous request sent to the Context Hub
 * through the ContextHubManager APIs. The caller can either retrieve the result
 * synchronously through a blocking call ({@link #waitForResponse(long, TimeUnit)}) or
 * asynchronously through a user-defined callback
 * ({@link #setCallbackOnComplete(ContextHubTransaction.Callback, Handler)}).
 *
 * @param <T> the type of the contents in the transaction response
 *
 * @hide
 */
public class ContextHubTransaction<T> {
    private static final String TAG = "ContextHubTransaction";

    /**
     * Constants describing the type of a transaction through the Context Hub Service.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_LOAD_NANOAPP,
            TYPE_UNLOAD_NANOAPP,
            TYPE_ENABLE_NANOAPP,
            TYPE_DISABLE_NANOAPP,
            TYPE_QUERY_NANOAPPS})
    public @interface Type {}
    public static final int TYPE_LOAD_NANOAPP = 0;
    public static final int TYPE_UNLOAD_NANOAPP = 1;
    public static final int TYPE_ENABLE_NANOAPP = 2;
    public static final int TYPE_DISABLE_NANOAPP = 3;
    public static final int TYPE_QUERY_NANOAPPS = 4;

    /**
     * Constants describing the result of a transaction or request through the Context Hub Service.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TRANSACTION_SUCCESS,
            TRANSACTION_FAILED_UNKNOWN,
            TRANSACTION_FAILED_BAD_PARAMS,
            TRANSACTION_FAILED_UNINITIALIZED,
            TRANSACTION_FAILED_PENDING,
            TRANSACTION_FAILED_AT_HUB,
            TRANSACTION_FAILED_TIMEOUT,
            TRANSACTION_FAILED_SERVICE_INTERNAL_FAILURE})
    public @interface Result {}
    public static final int TRANSACTION_SUCCESS = 0;
    /**
     * Generic failure mode.
     */
    public static final int TRANSACTION_FAILED_UNKNOWN = 1;
    /**
     * Failure mode when the request parameters were not valid.
     */
    public static final int TRANSACTION_FAILED_BAD_PARAMS = 2;
    /**
     * Failure mode when the Context Hub is not initialized.
     */
    public static final int TRANSACTION_FAILED_UNINITIALIZED = 3;
    /**
     * Failure mode when there are too many transactions pending.
     */
    public static final int TRANSACTION_FAILED_PENDING = 4;
    /**
     * Failure mode when the request went through, but failed asynchronously at the hub.
     */
    public static final int TRANSACTION_FAILED_AT_HUB = 5;
    /**
     * Failure mode when the transaction has timed out.
     */
    public static final int TRANSACTION_FAILED_TIMEOUT = 6;
    /**
     * Failure mode when the transaction has failed internally at the service.
     */
    public static final int TRANSACTION_FAILED_SERVICE_INTERNAL_FAILURE = 7;

    /**
     * A class describing the response for a ContextHubTransaction.
     *
     * @param <R> the type of the contents in the response
     */
    public static class Response<R> {
        /*
         * The result of the transaction.
         */
        @ContextHubTransaction.Result
        private int mResult;

        /*
         * The contents of the response from the Context Hub.
         */
        private R mContents;

        Response(@ContextHubTransaction.Result int result, R contents) {
            mResult = result;
            mContents = contents;
        }

        @ContextHubTransaction.Result
        public int getResult() {
            return mResult;
        }

        public R getContents() {
            return mContents;
        }
    }

    /**
     * An interface describing the callback to be invoked when a transaction completes.
     *
     * @param <C> the type of the contents in the transaction response
     */
    @FunctionalInterface
    public interface Callback<C> {
        /**
         * The callback to invoke when the transaction completes.
         *
         * @param transaction the transaction that this callback was attached to.
         * @param response the response of the transaction.
         */
        void onComplete(
                ContextHubTransaction<C> transaction, ContextHubTransaction.Response<C> response);
    }

    /*
     * The type of the transaction.
     */
    @Type
    private int mTransactionType;

    /*
     * The response of the transaction.
     */
    private ContextHubTransaction.Response<T> mResponse;

    /*
     * The handler to invoke the aynsc response supplied by onComplete.
     */
    private Handler mHandler = null;

    /*
     * The callback to invoke when the transaction completes.
     */
    private ContextHubTransaction.Callback<T> mCallback = null;

    /*
     * Synchronization latch used to block on response.
     */
    private final CountDownLatch mDoneSignal = new CountDownLatch(1);

    /*
     * true if the response has been set throught setResponse, false otherwise.
     */
    private boolean mIsResponseSet = false;

    ContextHubTransaction(@Type int type) {
        mTransactionType = type;
    }

    /**
     * @return the type of the transaction
     */
    @Type
    public int getType() {
        return mTransactionType;
    }

    /**
     * Waits to receive the asynchronous transaction result.
     *
     * This function blocks until the Context Hub Service has received a response
     * for the transaction represented by this object by the Context Hub, or a
     * specified timeout period has elapsed.
     *
     * If the specified timeout has passed, a TimeoutException will be thrown and the caller may
     * retry the invocation of this method at a later time.
     *
     * @param timeout the timeout duration
     * @param unit the unit of the timeout
     *
     * @return the transaction response
     *
     * @throws InterruptedException if the current thread is interrupted while waiting for response
     * @throws TimeoutException if the timeout period has passed
     */
    public ContextHubTransaction.Response<T> waitForResponse(
            long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        boolean success = mDoneSignal.await(timeout, unit);

        if (!success) {
            throw new TimeoutException("Timed out while waiting for transaction");
        }

        return mResponse;
    }

    /**
     * Sets a callback to be invoked when the transaction completes.
     *
     * This function provides an asynchronous approach to retrieve the result of the
     * transaction. When the transaction response has been provided by the Context Hub,
     * the given callback will be posted by the provided handler.
     *
     * If the transaction has already completed at the time of invocation, the callback
     * will be immediately posted by the handler. If the transaction has been invalidated,
     * the callback will never be invoked.
     *
     * A transaction can be invalidated if the process owning the transaction is no longer active
     * and the reference to this object is lost.
     *
     * This method or {@link #setCallbackOnCompletecan(ContextHubTransaction.Callback)} can only be
     * invoked once, or an IllegalStateException will be thrown.
     *
     * @param callback the callback to be invoked upon completion
     * @param handler the handler to post the callback
     *
     * @throws IllegalStateException if this method is called multiple times
     * @throws NullPointerException if the callback or handler is null
     */
    public void setCallbackOnComplete(
            @NonNull ContextHubTransaction.Callback<T> callback, @NonNull Handler handler) {
        synchronized (this) {
            if (callback == null) {
                throw new NullPointerException("Callback cannot be null");
            }
            if (handler == null) {
                throw new NullPointerException("Handler cannot be null");
            }
            if (mCallback != null) {
                throw new IllegalStateException(
                        "Cannot set ContextHubTransaction callback multiple times");
            }

            mCallback = callback;
            mHandler = handler;

            if (mDoneSignal.getCount() == 0) {
                boolean callbackPosted = mHandler.post(() -> {
                    mCallback.onComplete(this, mResponse);
                });

                if (!callbackPosted) {
                    Log.e(TAG, "Failed to post callback to Handler");
                }
            }
        }
    }

    /**
     * Sets a callback to be invoked when the transaction completes.
     *
     * Equivalent to {@link #setCallbackOnComplete(ContextHubTransaction.Callback, Handler)}
     * with the handler being that of the main thread's Looper.
     *
     * This method or {@link #setCallbackOnComplete(ContextHubTransaction.Callback, Handler)}
     * can only be invoked once, or an IllegalStateException will be thrown.
     *
     * @param callback the callback to be invoked upon completion
     *
     * @throws IllegalStateException if this method is called multiple times
     * @throws NullPointerException if the callback is null
     */
    public void setCallbackOnComplete(@NonNull ContextHubTransaction.Callback<T> callback) {
        setCallbackOnComplete(callback, new Handler(Looper.getMainLooper()));
    }

    /**
     * Sets the response of the transaction.
     *
     * This method should only be invoked by ContextHubManager as a result of a callback from
     * the Context Hub Service indicating the response from a transaction. This method should not be
     * invoked more than once.
     *
     * @param response the response to set
     *
     * @throws IllegalStateException if this method is invoked multiple times
     * @throws NullPointerException if the response is null
     */
    void setResponse(ContextHubTransaction.Response<T> response) {
        synchronized (this) {
            if (response == null) {
                throw new NullPointerException("Response cannot be null");
            }
            if (mIsResponseSet) {
                throw new IllegalStateException(
                        "Cannot set response of ContextHubTransaction multiple times");
            }

            mResponse = response;
            mIsResponseSet = true;

            mDoneSignal.countDown();
            if (mCallback != null) {
                boolean callbackPosted = mHandler.post(() -> {
                    mCallback.onComplete(this, mResponse);
                });

                if (!callbackPosted) {
                    Log.e(TAG, "Failed to post callback to Handler");
                }
            }
        }
    }
}
