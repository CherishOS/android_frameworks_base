/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_FINISHED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_STARTED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_APPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_DISAPPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED;
import static android.view.contentcapture.ContentCaptureManager.DEBUG;
import static android.view.contentcapture.ContentCaptureManager.VERBOSE;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.TimeUtils;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main session associated with a context.
 *
 * <p>This session is created when the activity starts and finished when it stops; clients can use
 * it to create children activities.
 *
 * <p><b>NOTE: all methods in this class should return right away, or do the real work in a handler
 * thread. Hence, the only field that must be thread-safe is {@code mEnabled}, which is called at
 * the beginning of every method.
 *
 * @hide
 */
public final class MainContentCaptureSession extends ContentCaptureSession {

    private static final String TAG = MainContentCaptureSession.class.getSimpleName();

    /**
     * Handler message used to flush the buffer.
     */
    private static final int MSG_FLUSH = 1;

    /**
     * Maximum number of events that are buffered before sent to the app.
     */
    // TODO(b/121044064): use settings
    private static final int MAX_BUFFER_SIZE = 100;

    /**
     * Frequency the buffer is flushed if stale.
     */
    // TODO(b/121044064): use settings
    private static final int FLUSHING_FREQUENCY_MS = 5_000;

    /**
     * Name of the {@link IResultReceiver} extra used to pass the binder interface to the service.
     * @hide
     */
    public static final String EXTRA_BINDER = "binder";

    @NonNull
    private final AtomicBoolean mDisabled;

    @NonNull
    private final Context mContext;

    @NonNull
    private final Handler mHandler;

    /**
     * Interface to the system_server binder object - it's only used to start the session (and
     * notify when the session is finished).
     */
    @Nullable
    private final IContentCaptureManager mSystemServerInterface;

    /**
     * Direct interface to the service binder object - it's used to send the events, including the
     * last ones (when the session is finished)
     */
    @Nullable
    private IContentCaptureDirectManager mDirectServiceInterface;
    @Nullable
    private DeathRecipient mDirectServiceVulture;

    private int mState = STATE_UNKNOWN;

    @Nullable
    private IBinder mApplicationToken;

    @Nullable
    private ComponentName mComponentName;

    /**
     * List of events held to be sent as a batch.
     */
    @Nullable
    private ArrayList<ContentCaptureEvent> mEvents;

    // Used just for debugging purposes (on dump)
    private long mNextFlush;

    /** @hide */
    protected MainContentCaptureSession(@NonNull Context context, @NonNull Handler handler,
            @Nullable IContentCaptureManager systemServerInterface,
            @NonNull AtomicBoolean disabled) {
        mContext = context;
        mHandler = handler;
        mSystemServerInterface = systemServerInterface;
        mDisabled = disabled;
    }

    @Override
    ContentCaptureSession newChild(@NonNull ContentCaptureContext clientContext) {
        final ContentCaptureSession child = new ChildContentCaptureSession(this, clientContext);
        notifyChildSessionStarted(mId, child.mId, clientContext);
        return child;
    }

    /**
     * Starts this session.
     *
     * @hide
     */
    void start(@NonNull IBinder applicationToken, @NonNull ComponentName activityComponent) {
        if (!isContentCaptureEnabled()) return;

        if (VERBOSE) {
            Log.v(TAG, "start(): token=" + applicationToken + ", comp="
                    + ComponentName.flattenToShortString(activityComponent));
        }

        mHandler.sendMessage(obtainMessage(MainContentCaptureSession::handleStartSession, this,
                applicationToken, activityComponent));
    }

    @Override
    void flush() {
        mHandler.sendMessage(obtainMessage(MainContentCaptureSession::handleForceFlush, this));
    }

    @Override
    void onDestroy() {
        mHandler.sendMessage(
                obtainMessage(MainContentCaptureSession::handleDestroySession, this));
    }

    private void handleStartSession(@NonNull IBinder token, @NonNull ComponentName componentName) {
        if (mState != STATE_UNKNOWN) {
            // TODO(b/111276913): revisit this scenario
            Log.w(TAG, "ignoring handleStartSession(" + token + ") while on state "
                    + getStateAsString(mState));
            return;
        }
        mState = STATE_WAITING_FOR_SERVER;
        mApplicationToken = token;
        mComponentName = componentName;

        if (VERBOSE) {
            Log.v(TAG, "handleStartSession(): token=" + token + ", act="
                    + getActivityDebugName() + ", id=" + mId);
        }
        final int flags = 0; // TODO(b/111276913): get proper flags

        try {
            mSystemServerInterface.startSession(mContext.getUserId(), mApplicationToken,
                    componentName, mId, flags, new IResultReceiver.Stub() {
                        @Override
                        public void send(int resultCode, Bundle resultData) {
                            IBinder binder = null;
                            if (resultData != null) {
                                binder = resultData.getBinder(EXTRA_BINDER);
                                if (binder == null) {
                                    Log.wtf(TAG, "No " + EXTRA_BINDER + " extra result");
                                    handleResetState();
                                    return;
                                }
                            }
                            handleSessionStarted(resultCode, binder);
                        }
                    });
        } catch (RemoteException e) {
            Log.w(TAG, "Error starting session for " + componentName.flattenToShortString() + ": "
                    + e);
        }
    }

    /**
     * Callback from {@code system_server} after call to
     * {@link IContentCaptureManager#startSession(int, IBinder, ComponentName, String,
     * int, IResultReceiver)}.
     *
     * @param resultCode session state
     * @param binder handle to {@code IContentCaptureDirectManager}
     */
    private void handleSessionStarted(int resultCode, @Nullable IBinder binder) {
        mState = resultCode;
        if (binder != null) {
            mDirectServiceInterface = IContentCaptureDirectManager.Stub.asInterface(binder);
            mDirectServiceVulture = () -> {
                Log.w(TAG, "Destroying session " + mId + " because service died");
                destroy();
            };
            try {
                binder.linkToDeath(mDirectServiceVulture, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to link to death on " + binder + ": " + e);
            }
        }
        if (resultCode == STATE_DISABLED || resultCode == STATE_DISABLED_DUPLICATED_ID) {
            mDisabled.set(true);
            handleResetSession(/* resetState= */ false);
        } else {
            mDisabled.set(false);
        }
        if (VERBOSE) {
            Log.v(TAG, "handleSessionStarted() result: code=" + resultCode + ", id=" + mId
                    + ", state=" + getStateAsString(mState) + ", disabled=" + mDisabled.get()
                    + ", binder=" + binder);
        }
    }

    private void handleSendEvent(@NonNull ContentCaptureEvent event, boolean forceFlush) {
        if (mEvents == null) {
            if (VERBOSE) {
                Log.v(TAG, "Creating buffer for " + MAX_BUFFER_SIZE + " events");
            }
            mEvents = new ArrayList<>(MAX_BUFFER_SIZE);
        }

        if (!mEvents.isEmpty() && event.getType() == TYPE_VIEW_TEXT_CHANGED) {
            final ContentCaptureEvent lastEvent = mEvents.get(mEvents.size() - 1);

            // TODO(b/121045053): check if flags match
            if (lastEvent.getType() == TYPE_VIEW_TEXT_CHANGED
                    && lastEvent.getId().equals(event.getId())) {
                if (VERBOSE) {
                    Log.v(TAG, "Buffering VIEW_TEXT_CHANGED event, updated text = "
                            + event.getText());
                }
                lastEvent.setText(event.getText());
            } else {
                mEvents.add(event);
            }
        } else {
            mEvents.add(event);
        }

        final int numberEvents = mEvents.size();

        final boolean bufferEvent = numberEvents < MAX_BUFFER_SIZE;

        if (bufferEvent && !forceFlush) {
            handleScheduleFlush(/* checkExisting= */ true);
            return;
        }

        if (mState != STATE_ACTIVE) {
            // Callback from startSession hasn't been called yet - typically happens on system
            // apps that are started before the system service
            // TODO(b/111276913): try to ignore session while system is not ready / boot
            // not complete instead. Similarly, the manager service should return right away
            // when the user does not have a service set
            if (VERBOSE) {
                Log.v(TAG, "Closing session for " + getActivityDebugName()
                        + " after " + numberEvents + " delayed events and state "
                        + getStateAsString(mState));
            }
            handleResetState();
            // TODO(b/111276913): blacklist activity / use special flag to indicate that
            // when it's launched again
            return;
        }

        handleForceFlush();
    }

    private void handleScheduleFlush(boolean checkExisting) {
        if (checkExisting && mHandler.hasMessages(MSG_FLUSH)) {
            // "Renew" the flush message by removing the previous one
            mHandler.removeMessages(MSG_FLUSH);
        }
        mNextFlush = SystemClock.elapsedRealtime() + FLUSHING_FREQUENCY_MS;
        if (VERBOSE) {
            Log.v(TAG, "Scheduled to flush in " + FLUSHING_FREQUENCY_MS + "ms: " + mNextFlush);
        }
        mHandler.sendMessageDelayed(
                obtainMessage(MainContentCaptureSession::handleFlushIfNeeded, this)
                .setWhat(MSG_FLUSH), FLUSHING_FREQUENCY_MS);
    }

    private void handleFlushIfNeeded() {
        if (mEvents.isEmpty()) {
            if (VERBOSE) Log.v(TAG, "Nothing to flush");
            return;
        }
        handleForceFlush();
    }

    private void handleForceFlush() {
        if (mEvents == null) return;

        if (mDirectServiceInterface == null) {
            if (DEBUG) Log.d(TAG, "handleForceFlush(): hold your horses, client not ready yet!");
            if (!mHandler.hasMessages(MSG_FLUSH)) {
                handleScheduleFlush(/* checkExisting= */ false);
            }
            return;
        }

        final int numberEvents = mEvents.size();
        try {
            if (DEBUG) {
                Log.d(TAG, "Flushing " + numberEvents + " event(s) for " + getActivityDebugName());
            }
            mHandler.removeMessages(MSG_FLUSH);

            final ParceledListSlice<ContentCaptureEvent> events = handleClearEvents();
            mDirectServiceInterface.sendEvents(events);
        } catch (RemoteException e) {
            Log.w(TAG, "Error sending " + numberEvents + " for " + getActivityDebugName()
                    + ": " + e);
        }
    }

    /**
     * Resets the buffer and return a {@link ParceledListSlice} with the previous events.
     */
    @NonNull
    private ParceledListSlice<ContentCaptureEvent> handleClearEvents() {
        // NOTE: we must save a reference to the current mEvents and then set it to to null,
        // otherwise clearing it would clear it in the receiving side if the service is also local.
        final List<ContentCaptureEvent> events = mEvents == null
                ? Collections.emptyList()
                : mEvents;
        mEvents = null;
        return new ParceledListSlice<>(events);
    }

    private void handleDestroySession() {
        if (DEBUG) {
            Log.d(TAG, "Destroying session (ctx=" + mContext + ", id=" + mId + ") with "
                    + (mEvents == null ? 0 : mEvents.size()) + " event(s) for "
                    + getActivityDebugName());
        }

        try {
            mSystemServerInterface.finishSession(mContext.getUserId(), mId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error destroying system-service session " + mId + " for "
                    + getActivityDebugName() + ": " + e);
        }
    }

    private void handleResetState() {
        handleResetSession(/* resetState= */ true);
    }

    // TODO(b/121033016): once we support multiple sessions, we might need to move some of these
    // clearings out.
    private void handleResetSession(boolean resetState) {
        if (resetState) {
            mState = STATE_UNKNOWN;
        }

        // TODO(b/121033016): must reset children (which currently is owned by superclass)
        mApplicationToken = null;
        mComponentName = null;
        mEvents = null;
        if (mDirectServiceInterface != null) {
            mDirectServiceInterface.asBinder().unlinkToDeath(mDirectServiceVulture, 0);
        }
        mDirectServiceInterface = null;
        mHandler.removeMessages(MSG_FLUSH);
    }

    @Override
    void internalNotifyViewAppeared(@NonNull ViewStructureImpl node) {
        notifyViewAppeared(mId, node);
    }

    @Override
    void internalNotifyViewDisappeared(@NonNull AutofillId id) {
        notifyViewDisappeared(mId, id);
    }

    @Override
    void internalNotifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text,
            int flags) {
        notifyViewTextChanged(mId, id, text, flags);
    }

    @Override
    boolean isContentCaptureEnabled() {
        return super.isContentCaptureEnabled() && mSystemServerInterface != null
                && !mDisabled.get();
    }

    // TODO(b/121033016): refactor "notifyXXXX" methods below to a common "Buffer" object that is
    // shared between ActivityContentCaptureSession and ChildContentCaptureSession objects. Such
    // change should also get get rid of the "internalNotifyXXXX" methods above
    void notifyChildSessionStarted(@NonNull String parentSessionId,
            @NonNull String childSessionId, @NonNull ContentCaptureContext clientContext) {
        mHandler.sendMessage(obtainMessage(MainContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(childSessionId, TYPE_SESSION_STARTED)
                        .setParentSessionId(parentSessionId)
                        .setClientContext(clientContext),
                        /* forceFlush= */ false));
    }

    void notifyChildSessionFinished(@NonNull String parentSessionId,
            @NonNull String childSessionId) {
        mHandler.sendMessage(obtainMessage(MainContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(childSessionId, TYPE_SESSION_FINISHED)
                        .setParentSessionId(parentSessionId), /* forceFlush= */ false));
    }

    void notifyViewAppeared(@NonNull String sessionId, @NonNull ViewStructureImpl node) {
        mHandler.sendMessage(obtainMessage(MainContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(sessionId, TYPE_VIEW_APPEARED)
                        .setViewNode(node.mNode), /* forceFlush= */ false));
    }

    void notifyViewDisappeared(@NonNull String sessionId, @NonNull AutofillId id) {
        mHandler.sendMessage(obtainMessage(MainContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(sessionId, TYPE_VIEW_DISAPPEARED).setAutofillId(id),
                        /* forceFlush= */ false));
    }

    void notifyViewTextChanged(@NonNull String sessionId, @NonNull AutofillId id,
            @Nullable CharSequence text, int flags) {
        mHandler.sendMessage(obtainMessage(MainContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(sessionId, TYPE_VIEW_TEXT_CHANGED, flags).setAutofillId(id)
                        .setText(text), /* forceFlush= */ false));
    }

    @Override
    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("id: "); pw.println(mId);
        pw.print(prefix); pw.print("mContext: "); pw.println(mContext);
        pw.print(prefix); pw.print("user: "); pw.println(mContext.getUserId());
        if (mSystemServerInterface != null) {
            pw.print(prefix); pw.print("mSystemServerInterface: ");
            pw.println(mSystemServerInterface);
        }
        if (mDirectServiceInterface != null) {
            pw.print(prefix); pw.print("mDirectServiceInterface: ");
            pw.println(mDirectServiceInterface);
        }
        pw.print(prefix); pw.print("mDisabled: "); pw.println(mDisabled.get());
        pw.print(prefix); pw.print("isEnabled(): "); pw.println(isContentCaptureEnabled());
        pw.print(prefix); pw.print("state: "); pw.print(mState); pw.print(" (");
        pw.print(getStateAsString(mState)); pw.println(")");
        if (mApplicationToken != null) {
            pw.print(prefix); pw.print("app token: "); pw.println(mApplicationToken);
        }
        if (mComponentName != null) {
            pw.print(prefix); pw.print("component name: ");
            pw.println(mComponentName.flattenToShortString());
        }
        if (mEvents != null && !mEvents.isEmpty()) {
            final int numberEvents = mEvents.size();
            pw.print(prefix); pw.print("buffered events: "); pw.print(numberEvents);
            pw.print('/'); pw.println(MAX_BUFFER_SIZE);
            if (VERBOSE && numberEvents > 0) {
                final String prefix3 = prefix + "  ";
                for (int i = 0; i < numberEvents; i++) {
                    final ContentCaptureEvent event = mEvents.get(i);
                    pw.print(prefix3); pw.print(i); pw.print(": "); event.dump(pw);
                    pw.println();
                }
            }
            pw.print(prefix); pw.print("flush frequency: "); pw.println(FLUSHING_FREQUENCY_MS);
            pw.print(prefix); pw.print("next flush: ");
            TimeUtils.formatDuration(mNextFlush - SystemClock.elapsedRealtime(), pw); pw.println();
        }
        super.dump(prefix, pw);
    }

    /**
     * Gets a string that can be used to identify the activity on logging statements.
     */
    private String getActivityDebugName() {
        return mComponentName == null ? mContext.getPackageName()
                : mComponentName.flattenToShortString();
    }
}
