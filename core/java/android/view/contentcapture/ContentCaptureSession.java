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

import static android.view.contentcapture.ContentCaptureManager.DEBUG;
import static android.view.contentcapture.ContentCaptureManager.VERBOSE;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Session used to notify a system-provided Content Capture service about events associated with
 * views.
 */
public abstract class ContentCaptureSession implements AutoCloseable {

    private static final String TAG = ContentCaptureSession.class.getSimpleName();

    /**
     * Used on {@link #notifyViewTextChanged(AutofillId, CharSequence, int)} to indicate that the
     *
     * thext change was caused by user input (for example, through IME).
     */
    public static final int FLAG_USER_INPUT = 0x1;

    /**
     * Initial state, when there is no session.
     *
     * @hide
     */
    public static final int STATE_UNKNOWN = 0;

    /**
     * Service's startSession() was called, but server didn't confirm it was created yet.
     *
     * @hide
     */
    public static final int STATE_WAITING_FOR_SERVER = 1;

    /**
     * Session is active.
     *
     * @hide
     */
    public static final int STATE_ACTIVE = 2;

    /**
     * Session is disabled.
     *
     * @hide
     */
    public static final int STATE_DISABLED = 3;

    /**
     * Session is disabled because its id already existed on server.
     *
     * @hide
     */
    public static final int STATE_DISABLED_DUPLICATED_ID = 4;

    private static final int INITIAL_CHILDREN_CAPACITY = 5;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    /**
     * Guard use to ignore events after it's destroyed.
     */
    @NonNull
    private final AtomicBoolean mDestroyed = new AtomicBoolean();

    /** @hide */
    @Nullable
    protected final String mId = UUID.randomUUID().toString();

    private int mState = STATE_UNKNOWN;

    // Lazily created on demand.
    private ContentCaptureSessionId mContentCaptureSessionId;

    /**
     * List of children session.
     */
    // TODO(b/121033016): need to synchonize access, either by changing on handler or UI thread
    // (for now there's no handler on this class, so we need to wait for the next refactoring),
    // most likely the former (as we have no guarantee that createContentCaptureSession()
    // it will be called in the UiThread; for example, WebView most likely won't call on it)
    @Nullable
    private ArrayList<ContentCaptureSession> mChildren;

    /** @hide */
    protected ContentCaptureSession() {
        mCloseGuard.open("destroy");
    }

    /**
     * Gets the id used to identify this session.
     */
    public final ContentCaptureSessionId getContentCaptureSessionId() {
        if (mContentCaptureSessionId == null) {
            mContentCaptureSessionId = new ContentCaptureSessionId(mId);
        }
        return mContentCaptureSessionId;
    }

    /**
     * Creates a new {@link ContentCaptureSession}.
     *
     * <p>See {@link View#setContentCaptureSession(ContentCaptureSession)} for more info.
     */
    @NonNull
    public final ContentCaptureSession createContentCaptureSession(
            @NonNull ContentCaptureContext context) {
        final ContentCaptureSession child = newChild(context);
        if (DEBUG) {
            Log.d(TAG, "createContentCaptureSession(" + context + ": parent=" + mId + ", child="
                    + child.mId);
        }
        if (mChildren == null) {
            mChildren = new ArrayList<>(INITIAL_CHILDREN_CAPACITY);
        }
        mChildren.add(child);
        return child;
    }

    abstract ContentCaptureSession newChild(@NonNull ContentCaptureContext context);

    /**
     * Flushes the buffered events to the service.
     */
    abstract void flush();

    /**
     * Destroys this session, flushing out all pending notifications to the service.
     *
     * <p>Once destroyed, any new notification will be dropped.
     */
    public final void destroy() {
        if (!mDestroyed.compareAndSet(false, true)) {
            Log.e(TAG, "destroy(): already destroyed");
            return;
        }

        mCloseGuard.close();

        //TODO(b/111276913): check state (for example, how to handle if it's waiting for remote
        // id) and send it to the cache of batched commands
        if (VERBOSE) {
            Log.v(TAG, "destroy(): state=" + getStateAsString(mState) + ", mId=" + mId);
        }

        // Finish children first
        if (mChildren != null) {
            final int numberChildren = mChildren.size();
            if (VERBOSE) Log.v(TAG, "Destroying " + numberChildren + " children first");
            for (int i = 0; i < numberChildren; i++) {
                final ContentCaptureSession child = mChildren.get(i);
                try {
                    child.destroy();
                } catch (Exception e) {
                    Log.w(TAG, "exception destroying child session #" + i + ": " + e);
                }
            }
        }

        try {
            flush();
        } finally {
            onDestroy();
        }
    }

    abstract void onDestroy();

    /** @hide */
    @Override
    public void close() {
        destroy();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            destroy();
        } finally {
            super.finalize();
        }
    }

    /**
     * Notifies the Content Capture Service that a node has been added to the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for views that return {@code true} on
     * {@link View#onProvideContentCaptureStructure(ViewStructure, int)}.
     *
     * @param node node that has been added.
     */
    public final void notifyViewAppeared(@NonNull ViewStructure node) {
        Preconditions.checkNotNull(node);
        if (!isContentCaptureEnabled()) return;

        if (!(node instanceof ViewNode.ViewStructureImpl)) {
            throw new IllegalArgumentException("Invalid node class: " + node.getClass());
        }

        internalNotifyViewAppeared((ViewStructureImpl) node);
    }

    abstract void internalNotifyViewAppeared(@NonNull ViewNode.ViewStructureImpl node);

    /**
     * Notifies the Content Capture Service that a node has been removed from the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for standard views.
     *
     * @param id id of the node that has been removed.
     */
    public final void notifyViewDisappeared(@NonNull AutofillId id) {
        Preconditions.checkNotNull(id);
        if (!isContentCaptureEnabled()) return;

        internalNotifyViewDisappeared(id);
    }

    abstract void internalNotifyViewDisappeared(@NonNull AutofillId id);

    /**
     * Notifies the Intelligence Service that the value of a text node has been changed.
     *
     * @param id of the node.
     * @param text new text.
     * @param flags either {@code 0} or {@link #FLAG_USER_INPUT} when the value was explicitly
     * changed by the user (for example, through the keyboard).
     */
    public final void notifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text,
            int flags) {
        Preconditions.checkNotNull(id);

        if (!isContentCaptureEnabled()) return;

        internalNotifyViewTextChanged(id, text, flags);
    }

    abstract void internalNotifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text,
            int flags);

    /**
     * Creates a {@link ViewStructure} for a "standard" view.
     *
     * @hide
     */
    @NonNull
    public final ViewStructure newViewStructure(@NonNull View view) {
        return new ViewNode.ViewStructureImpl(view);
    }

    /**
     * Creates a {@link ViewStructure} for a "virtual" view, so it can be passed to
     * {@link #notifyViewAppeared(ViewStructure)} by the view managing the virtual view hierarchy.
     *
     * @param parentId id of the virtual view parent (it can be obtained by calling
     * {@link ViewStructure#getAutofillId()} on the parent).
     * @param virtualId id of the virtual child, relative to the parent.
     *
     * @return a new {@link ViewStructure} that can be used for Content Capture purposes.
     *
     * @hide
     */
    @NonNull
    public final ViewStructure newVirtualViewStructure(@NonNull AutofillId parentId,
            int virtualId) {
        return new ViewNode.ViewStructureImpl(parentId, virtualId);
    }

    boolean isContentCaptureEnabled() {
        return !mDestroyed.get();
    }

    @CallSuper
    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("id: "); pw.println(mId);
        pw.print(prefix); pw.print("destroyed: "); pw.println(mDestroyed.get());
        if (mChildren != null && !mChildren.isEmpty()) {
            final String prefix2 = prefix + "  ";
            final int numberChildren = mChildren.size();
            pw.print(prefix); pw.print("number children: "); pw.println(numberChildren);
            for (int i = 0; i < numberChildren; i++) {
                final ContentCaptureSession child = mChildren.get(i);
                pw.print(prefix); pw.print(i); pw.println(": "); child.dump(prefix2, pw);
            }
        }

    }

    @Override
    public String toString() {
        return mId;
    }

    /**
     * @hide
     */
    @NonNull
    protected static String getStateAsString(int state) {
        switch (state) {
            case STATE_UNKNOWN:
                return "UNKNOWN";
            case STATE_WAITING_FOR_SERVER:
                return "WAITING_FOR_SERVER";
            case STATE_ACTIVE:
                return "ACTIVE";
            case STATE_DISABLED:
                return "DISABLED";
            case STATE_DISABLED_DUPLICATED_ID:
                return "DISABLED_DUPLICATED_ID";
            default:
                return "INVALID:" + state;
        }
    }
}
