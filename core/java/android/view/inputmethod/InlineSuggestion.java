/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Size;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.View;
import android.view.inline.InlineContentView;
import android.view.inline.InlinePresentationSpec;

import com.android.internal.util.DataClass;
import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class represents an inline suggestion which is made by one app
 * and can be embedded into the UI of another. Suggestions may contain
 * sensitive information not known to the host app which needs to be
 * protected from spoofing. To address that the suggestion view inflated
 * on demand for embedding is created in such a way that the hosting app
 * cannot introspect its content and cannot interact with it.
 */
@DataClass(
        genEqualsHashCode = true,
        genToString = true,
        genHiddenConstDefs = true,
        genHiddenConstructor = true)
@DataClass.Suppress({"getContentProvider"})
public final class InlineSuggestion implements Parcelable {

    private static final String TAG = "InlineSuggestion";

    private final @NonNull InlineSuggestionInfo mInfo;

    private final @Nullable IInlineContentProvider mContentProvider;

    /**
     * Creates a new {@link InlineSuggestion}, for testing purpose.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public static InlineSuggestion newInlineSuggestion(@NonNull InlineSuggestionInfo info) {
        return new InlineSuggestion(info, null);
    }




    /**
     * Inflates a view with the content of this suggestion at a specific size.
     * The size must be between the {@link InlinePresentationSpec#getMinSize() min size}
     * and the {@link InlinePresentationSpec#getMaxSize() max size} of the presentation
     * spec returned by {@link InlineSuggestionInfo#getPresentationSpec()}. If an invalid
     * argument is passed an exception is thrown.
     *
     * @param context Context in which to inflate the view.
     * @param size The size at which to inflate the suggestion.
     * @param callback Callback for receiving the inflated view.
     */
    public void inflate(@NonNull Context context, @NonNull Size size,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull Consumer<View> callback) {
        final Size minSize = mInfo.getPresentationSpec().getMinSize();
        final Size maxSize = mInfo.getPresentationSpec().getMaxSize();
        if (size.getHeight() < minSize.getHeight() || size.getHeight() > maxSize.getHeight()
                || size.getWidth() < minSize.getWidth() || size.getWidth() > maxSize.getWidth()) {
            throw new IllegalArgumentException("size not between min:"
                    + minSize + " and max:" + maxSize);
        }
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            if (mContentProvider == null) {
                callback.accept(/* view */ null);
                return;
            }
            // TODO(b/137800469): keep a strong reference to the contentCallback so it doesn't
            //  get GC'd. Also add a isInflated() method and make sure the view can only be
            //  inflated once.
            try {
                InlineContentCallbackImpl contentCallback = new InlineContentCallbackImpl(context,
                        callbackExecutor, callback);
                mContentProvider.provideContent(size.getWidth(), size.getHeight(),
                        new InlineContentCallbackWrapper(contentCallback));
            } catch (RemoteException e) {
                Slog.w(TAG, "Error creating suggestion content surface: " + e);
                callback.accept(/* view */ null);
            }
        });
    }

    private static final class InlineContentCallbackWrapper extends IInlineContentCallback.Stub {

        private final WeakReference<InlineContentCallbackImpl> mCallbackImpl;

        InlineContentCallbackWrapper(InlineContentCallbackImpl callbackImpl) {
            mCallbackImpl = new WeakReference<>(callbackImpl);
        }

        @Override
        public void onContent(SurfaceControl content) {
            final InlineContentCallbackImpl callbackImpl = mCallbackImpl.get();
            if (callbackImpl != null) {
                callbackImpl.onContent(content);
            }
        }
    }

    private static final class InlineContentCallbackImpl {

        private final @NonNull Context mContext;
        private final @NonNull Executor mCallbackExecutor;
        private final @NonNull Consumer<View> mCallback;

        InlineContentCallbackImpl(@NonNull Context context,
                @NonNull @CallbackExecutor Executor callbackExecutor,
                @NonNull Consumer<View> callback) {
            mContext = context;
            mCallbackExecutor = callbackExecutor;
            mCallback = callback;
        }

        public void onContent(SurfaceControl content) {
            if (content == null) {
                mCallbackExecutor.execute(() -> mCallback.accept(/* view */null));
            } else {
                mCallbackExecutor.execute(
                        () -> mCallback.accept(new InlineContentView(mContext, content)));
            }
        }
    }



    // Code below generated by codegen v1.0.14.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/view/inputmethod/InlineSuggestion.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new InlineSuggestion.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public InlineSuggestion(
            @NonNull InlineSuggestionInfo info,
            @Nullable IInlineContentProvider contentProvider) {
        this.mInfo = info;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mInfo);
        this.mContentProvider = contentProvider;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public @NonNull InlineSuggestionInfo getInfo() {
        return mInfo;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "InlineSuggestion { " +
                "info = " + mInfo + ", " +
                "contentProvider = " + mContentProvider +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(InlineSuggestion other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        InlineSuggestion that = (InlineSuggestion) o;
        //noinspection PointlessBooleanExpression
        return true
                && java.util.Objects.equals(mInfo, that.mInfo)
                && java.util.Objects.equals(mContentProvider, that.mContentProvider);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + java.util.Objects.hashCode(mInfo);
        _hash = 31 * _hash + java.util.Objects.hashCode(mContentProvider);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mContentProvider != null) flg |= 0x2;
        dest.writeByte(flg);
        dest.writeTypedObject(mInfo, flags);
        if (mContentProvider != null) dest.writeStrongInterface(mContentProvider);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ InlineSuggestion(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        InlineSuggestionInfo info = (InlineSuggestionInfo) in.readTypedObject(InlineSuggestionInfo.CREATOR);
        IInlineContentProvider contentProvider = (flg & 0x2) == 0 ? null : IInlineContentProvider.Stub.asInterface(in.readStrongBinder());

        this.mInfo = info;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mInfo);
        this.mContentProvider = contentProvider;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<InlineSuggestion> CREATOR
            = new Parcelable.Creator<InlineSuggestion>() {
        @Override
        public InlineSuggestion[] newArray(int size) {
            return new InlineSuggestion[size];
        }

        @Override
        public InlineSuggestion createFromParcel(@NonNull android.os.Parcel in) {
            return new InlineSuggestion(in);
        }
    };

    @DataClass.Generated(
            time = 1581377984320L,
            codegenVersion = "1.0.14",
            sourceFile = "frameworks/base/core/java/android/view/inputmethod/InlineSuggestion.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate final @android.annotation.NonNull android.view.inputmethod.InlineSuggestionInfo mInfo\nprivate final @android.annotation.Nullable com.android.internal.view.inline.IInlineContentProvider mContentProvider\npublic static @android.annotation.TestApi @android.annotation.NonNull android.view.inputmethod.InlineSuggestion newInlineSuggestion(android.view.inputmethod.InlineSuggestionInfo)\npublic  void inflate(android.content.Context,android.util.Size,java.util.concurrent.Executor,java.util.function.Consumer<android.view.View>)\nclass InlineSuggestion extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genEqualsHashCode=true, genToString=true, genHiddenConstDefs=true, genHiddenConstructor=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
