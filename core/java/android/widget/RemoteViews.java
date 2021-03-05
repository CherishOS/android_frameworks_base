/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.annotation.ColorInt;
import android.annotation.ColorRes;
import android.annotation.DimenRes;
import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.annotation.IntDef;
import android.annotation.LayoutRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.StringRes;
import android.annotation.StyleRes;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.Application;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.appwidget.AppWidgetHostView;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Outline;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.StrictMode;
import android.os.UserHandle;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.util.TypedValue.ComplexDimensionUnit;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.LayoutInflater.Filter;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewManager;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewStub;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.android.internal.R;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.util.Preconditions;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A class that describes a view hierarchy that can be displayed in
 * another process. The hierarchy is inflated from a layout resource
 * file, and this class provides some basic operations for modifying
 * the content of the inflated hierarchy.
 *
 * <p>{@code RemoteViews} is limited to support for the following layouts:</p>
 * <ul>
 *   <li>{@link android.widget.AdapterViewFlipper}</li>
 *   <li>{@link android.widget.FrameLayout}</li>
 *   <li>{@link android.widget.GridLayout}</li>
 *   <li>{@link android.widget.GridView}</li>
 *   <li>{@link android.widget.LinearLayout}</li>
 *   <li>{@link android.widget.ListView}</li>
 *   <li>{@link android.widget.RelativeLayout}</li>
 *   <li>{@link android.widget.StackView}</li>
 *   <li>{@link android.widget.ViewFlipper}</li>
 * </ul>
 * <p>And the following widgets:</p>
 * <ul>
 *   <li>{@link android.widget.AnalogClock}</li>
 *   <li>{@link android.widget.Button}</li>
 *   <li>{@link android.widget.Chronometer}</li>
 *   <li>{@link android.widget.ImageButton}</li>
 *   <li>{@link android.widget.ImageView}</li>
 *   <li>{@link android.widget.ProgressBar}</li>
 *   <li>{@link android.widget.TextClock}</li>
 *   <li>{@link android.widget.TextView}</li>
 * </ul>
 * <p>As of API 31, the following widgets and layouts may also be used:</p>
 * <ul>
 *     <li>{@link android.widget.CheckBox}</li>
 *     <li>{@link android.widget.RadioButton}</li>
 *     <li>{@link android.widget.RadioGroup}</li>
 *     <li>{@link android.widget.Switch}</li>
 * </ul>
 * <p>Descendants of these classes are not supported.</p>
 */
public class RemoteViews implements Parcelable, Filter {

    private static final String LOG_TAG = "RemoteViews";

    /** The intent extra for whether the view whose checked state changed is currently checked. */
    public static final String EXTRA_CHECKED = "android.widget.extra.CHECKED";

    /**
     * The intent extra that contains the appWidgetId.
     * @hide
     */
    static final String EXTRA_REMOTEADAPTER_APPWIDGET_ID = "remoteAdapterAppWidgetId";

    /**
     * The intent extra that contains {@code true} if inflating as dak text theme.
     * @hide
     */
    static final String EXTRA_REMOTEADAPTER_ON_LIGHT_BACKGROUND = "remoteAdapterOnLightBackground";

    /**
     * The intent extra that contains the bounds for all shared elements.
     */
    public static final String EXTRA_SHARED_ELEMENT_BOUNDS =
            "android.widget.extra.SHARED_ELEMENT_BOUNDS";

    /**
     * Maximum depth of nested views calls from {@link #addView(int, RemoteViews)} and
     * {@link #RemoteViews(RemoteViews, RemoteViews)}.
     */
    private static final int MAX_NESTED_VIEWS = 10;

    /**
     * Maximum number of RemoteViews that can be specified in constructor.
     */
    private static final int MAX_INIT_VIEW_COUNT = 16;

    // The unique identifiers for each custom {@link Action}.
    private static final int SET_ON_CLICK_RESPONSE_TAG = 1;
    private static final int REFLECTION_ACTION_TAG = 2;
    private static final int SET_DRAWABLE_TINT_TAG = 3;
    private static final int VIEW_GROUP_ACTION_ADD_TAG = 4;
    private static final int VIEW_CONTENT_NAVIGATION_TAG = 5;
    private static final int SET_EMPTY_VIEW_ACTION_TAG = 6;
    private static final int VIEW_GROUP_ACTION_REMOVE_TAG = 7;
    private static final int SET_PENDING_INTENT_TEMPLATE_TAG = 8;
    private static final int SET_REMOTE_VIEW_ADAPTER_INTENT_TAG = 10;
    private static final int TEXT_VIEW_DRAWABLE_ACTION_TAG = 11;
    private static final int BITMAP_REFLECTION_ACTION_TAG = 12;
    private static final int TEXT_VIEW_SIZE_ACTION_TAG = 13;
    private static final int VIEW_PADDING_ACTION_TAG = 14;
    private static final int SET_REMOTE_VIEW_ADAPTER_LIST_TAG = 15;
    private static final int SET_REMOTE_INPUTS_ACTION_TAG = 18;
    private static final int LAYOUT_PARAM_ACTION_TAG = 19;
    private static final int OVERRIDE_TEXT_COLORS_TAG = 20;
    private static final int SET_RIPPLE_DRAWABLE_COLOR_TAG = 21;
    private static final int SET_INT_TAG_TAG = 22;
    private static final int REMOVE_FROM_PARENT_ACTION_TAG = 23;
    private static final int RESOURCE_REFLECTION_ACTION_TAG = 24;
    private static final int COMPLEX_UNIT_DIMENSION_REFLECTION_ACTION_TAG = 25;
    private static final int SET_COMPOUND_BUTTON_CHECKED_TAG = 26;
    private static final int SET_RADIO_GROUP_CHECKED = 27;
    private static final int SET_VIEW_OUTLINE_RADIUS_TAG = 28;
    private static final int SET_ON_CHECKED_CHANGE_RESPONSE_TAG = 29;
    private static final int NIGHT_MODE_REFLECTION_ACTION_TAG = 30;

    /** @hide **/
    @IntDef(prefix = "MARGIN_", value = {
            MARGIN_LEFT,
            MARGIN_TOP,
            MARGIN_RIGHT,
            MARGIN_BOTTOM,
            MARGIN_START,
            MARGIN_END
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MarginType {}
    /** The value will apply to the marginLeft. */
    public static final int MARGIN_LEFT = 0;
    /** The value will apply to the marginTop. */
    public static final int MARGIN_TOP = 1;
    /** The value will apply to the marginRight. */
    public static final int MARGIN_RIGHT = 2;
    /** The value will apply to the marginBottom. */
    public static final int MARGIN_BOTTOM = 3;
    /** The value will apply to the marginStart. */
    public static final int MARGIN_START = 4;
    /** The value will apply to the marginEnd. */
    public static final int MARGIN_END = 5;

    /** @hide **/
    @IntDef(flag = true, value = {
            FLAG_REAPPLY_DISALLOWED,
            FLAG_WIDGET_IS_COLLECTION_CHILD,
            FLAG_USE_LIGHT_BACKGROUND_LAYOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplyFlags {}
    /**
     * Whether reapply is disallowed on this remoteview. This maybe be true if some actions modify
     * the layout in a way that isn't recoverable, since views are being removed.
     * @hide
     */
    public static final int FLAG_REAPPLY_DISALLOWED = 1;
    /**
     * This flag indicates whether this RemoteViews object is being created from a
     * RemoteViewsService for use as a child of a widget collection. This flag is used
     * to determine whether or not certain features are available, in particular,
     * setting on click extras and setting on click pending intents. The former is enabled,
     * and the latter disabled when this flag is true.
     * @hide
     */
    public static final int FLAG_WIDGET_IS_COLLECTION_CHILD = 2;
    /**
     * When this flag is set, the views is inflated with {@link #mLightBackgroundLayoutId} instead
     * of {link #mLayoutId}
     * @hide
     */
    public static final int FLAG_USE_LIGHT_BACKGROUND_LAYOUT = 4;

    /**
     * Used to restrict the views which can be inflated
     *
     * @see android.view.LayoutInflater.Filter#onLoadClass(java.lang.Class)
     */
    private static final LayoutInflater.Filter INFLATER_FILTER =
            (clazz) -> clazz.isAnnotationPresent(RemoteViews.RemoteView.class);

    /**
     * Application that hosts the remote views.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public ApplicationInfo mApplication;

    /**
     * The resource ID of the layout file. (Added to the parcel)
     */
    @UnsupportedAppUsage
    private int mLayoutId;

    /**
     * The resource ID of the layout file in dark text mode. (Added to the parcel)
     */
    private int mLightBackgroundLayoutId = 0;

    /**
     * An array of actions to perform on the view tree once it has been
     * inflated
     */
    @UnsupportedAppUsage
    private ArrayList<Action> mActions;

    /**
     * Maps bitmaps to unique indicies to avoid Bitmap duplication.
     */
    @UnsupportedAppUsage
    private BitmapCache mBitmapCache;

    /**
     * Indicates whether or not this RemoteViews object is contained as a child of any other
     * RemoteViews.
     */
    private boolean mIsRoot = true;

    /**
     * Constants to whether or not this RemoteViews is composed of a landscape and portrait
     * RemoteViews.
     */
    private static final int MODE_NORMAL = 0;
    private static final int MODE_HAS_LANDSCAPE_AND_PORTRAIT = 1;
    private static final int MODE_HAS_SIZED_REMOTEVIEWS = 2;

    /**
     * Used in conjunction with the special constructor
     * {@link #RemoteViews(RemoteViews, RemoteViews)} to keep track of the landscape and portrait
     * RemoteViews.
     */
    private RemoteViews mLandscape = null;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private RemoteViews mPortrait = null;
    /**
     * List of RemoteViews with their ideal size. There must be at least two if the map is not null.
     *
     * The smallest remote view is always the last element in the list.
     */
    private List<RemoteViews> mSizedRemoteViews = null;

    /**
     * Ideal size for this RemoteViews.
     *
     * Only to be used on children views used in a {@link RemoteViews} with
     * {@link RemoteViews#hasSizedRemoteViews()}.
     */
    private PointF mIdealSize = null;

    @ApplyFlags
    private int mApplyFlags = 0;

    /**
     * Id to use to override the ID of the top-level view in this RemoteViews.
     *
     * Only used if this RemoteViews is defined from a XML layout value.
     */
    private int mViewId = View.NO_ID;

    /** Class cookies of the Parcel this instance was read from. */
    private Map<Class, Object> mClassCookies;

    private static final InteractionHandler DEFAULT_INTERACTION_HANDLER =
            (view, pendingIntent, response) ->
                    startPendingIntent(view, pendingIntent, response.getLaunchOptions(view));

    private static final ArrayMap<MethodKey, MethodArgs> sMethods = new ArrayMap<>();

    /**
     * This key is used to perform lookups in sMethods without causing allocations.
     */
    private static final MethodKey sLookupKey = new MethodKey();

    /**
     * @hide
     */
    public void setRemoteInputs(@IdRes int viewId, RemoteInput[] remoteInputs) {
        mActions.add(new SetRemoteInputsAction(viewId, remoteInputs));
    }

    /**
     * Reduces all images and ensures that they are all below the given sizes.
     *
     * @param maxWidth the maximum width allowed
     * @param maxHeight the maximum height allowed
     *
     * @hide
     */
    public void reduceImageSizes(int maxWidth, int maxHeight) {
        ArrayList<Bitmap> cache = mBitmapCache.mBitmaps;
        for (int i = 0; i < cache.size(); i++) {
            Bitmap bitmap = cache.get(i);
            cache.set(i, Icon.scaleDownIfNecessary(bitmap, maxWidth, maxHeight));
        }
    }

    /**
     * Override all text colors in this layout and replace them by the given text color.
     *
     * @param textColor The color to use.
     *
     * @hide
     */
    public void overrideTextColors(int textColor) {
        addAction(new OverrideTextColorsAction(textColor));
    }

    /**
     * Sets an integer tag to the view.
     *
     * @hide
     */
    public void setIntTag(@IdRes int viewId, @IdRes int key, int tag) {
        addAction(new SetIntTagAction(viewId, key, tag));
    }

    /**
     * Set that it is disallowed to reapply another remoteview with the same layout as this view.
     * This should be done if an action is destroying the view tree of the base layout.
     *
     * @hide
     */
    public void addFlags(@ApplyFlags int flags) {
        mApplyFlags = mApplyFlags | flags;
    }

    /**
     * @hide
     */
    public boolean hasFlags(@ApplyFlags int flag) {
        return (mApplyFlags & flag) == flag;
    }

    /**
     * Stores information related to reflection method lookup.
     */
    static class MethodKey {
        public Class targetClass;
        public Class paramClass;
        public String methodName;

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof MethodKey)) {
                return false;
            }
            MethodKey p = (MethodKey) o;
            return Objects.equals(p.targetClass, targetClass)
                    && Objects.equals(p.paramClass, paramClass)
                    && Objects.equals(p.methodName, methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(targetClass) ^ Objects.hashCode(paramClass)
                    ^ Objects.hashCode(methodName);
        }

        public void set(Class targetClass, Class paramClass, String methodName) {
            this.targetClass = targetClass;
            this.paramClass = paramClass;
            this.methodName = methodName;
        }
    }


    /**
     * Stores information related to reflection method lookup result.
     */
    static class MethodArgs {
        public MethodHandle syncMethod;
        public MethodHandle asyncMethod;
        public String asyncMethodName;
    }

    /**
     * This annotation indicates that a subclass of View is allowed to be used
     * with the {@link RemoteViews} mechanism.
     */
    @Target({ ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RemoteView {
    }

    /**
     * Exception to send when something goes wrong executing an action
     *
     */
    public static class ActionException extends RuntimeException {
        public ActionException(Exception ex) {
            super(ex);
        }
        public ActionException(String message) {
            super(message);
        }
        /**
         * @hide
         */
        public ActionException(Throwable t) {
            super(t);
        }
    }

    /**
     * Handler for view interactions (such as clicks) within a RemoteViews.
     *
     * @hide
     */
    public interface InteractionHandler {
        /**
         * Invoked when the user performs an interaction on the View.
         *
         * @param view the View with which the user interacted
         * @param pendingIntent the base PendingIntent associated with the view
         * @param response the response to the interaction, which knows how to fill in the
         *                 attached PendingIntent
         *
         * @hide
         */
        boolean onInteraction(
                View view,
                PendingIntent pendingIntent,
                RemoteResponse response);
    }

    /**
     * Base class for all actions that can be performed on an
     * inflated view.
     *
     *  SUBCLASSES MUST BE IMMUTABLE SO CLONE WORKS!!!!!
     */
    private abstract static class Action implements Parcelable {
        public abstract void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) throws ActionException;

        public static final int MERGE_REPLACE = 0;
        public static final int MERGE_APPEND = 1;
        public static final int MERGE_IGNORE = 2;

        public int describeContents() {
            return 0;
        }

        public void setBitmapCache(BitmapCache bitmapCache) {
            // Do nothing
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int mergeBehavior() {
            return MERGE_REPLACE;
        }

        public abstract int getActionTag();

        public String getUniqueKey() {
            return (getActionTag() + "_" + viewId);
        }

        /**
         * This is called on the background thread. It should perform any non-ui computations
         * and return the final action which will run on the UI thread.
         * Override this if some of the tasks can be performed async.
         */
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                InteractionHandler handler, ColorResources colorResources) {
            return this;
        }

        public boolean prefersAsyncApply() {
            return false;
        }

        /**
         * Overridden by subclasses which have (or inherit) an ApplicationInfo instance
         * as member variable
         */
        public boolean hasSameAppInfo(ApplicationInfo parentInfo) {
            return true;
        }

        public void visitUris(@NonNull Consumer<Uri> visitor) {
            // Nothing to visit by default
        }

        @IdRes
        @UnsupportedAppUsage
        int viewId;
    }

    /**
     * Action class used during async inflation of RemoteViews. Subclasses are not parcelable.
     */
    private static abstract class RuntimeAction extends Action {
        @Override
        public final int getActionTag() {
            return 0;
        }

        @Override
        public final void writeToParcel(Parcel dest, int flags) {
            throw new UnsupportedOperationException();
        }
    }

    // Constant used during async execution. It is not parcelable.
    private static final Action ACTION_NOOP = new RuntimeAction() {
        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
        }
    };

    /**
     * Merges the passed RemoteViews actions with this RemoteViews actions according to
     * action-specific merge rules.
     *
     * @param newRv
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void mergeRemoteViews(RemoteViews newRv) {
        if (newRv == null) return;
        // We first copy the new RemoteViews, as the process of merging modifies the way the actions
        // reference the bitmap cache. We don't want to modify the object as it may need to
        // be merged and applied multiple times.
        RemoteViews copy = new RemoteViews(newRv);

        HashMap<String, Action> map = new HashMap<String, Action>();
        if (mActions == null) {
            mActions = new ArrayList<Action>();
        }

        int count = mActions.size();
        for (int i = 0; i < count; i++) {
            Action a = mActions.get(i);
            map.put(a.getUniqueKey(), a);
        }

        ArrayList<Action> newActions = copy.mActions;
        if (newActions == null) return;
        count = newActions.size();
        for (int i = 0; i < count; i++) {
            Action a = newActions.get(i);
            String key = newActions.get(i).getUniqueKey();
            int mergeBehavior = newActions.get(i).mergeBehavior();
            if (map.containsKey(key) && mergeBehavior == Action.MERGE_REPLACE) {
                mActions.remove(map.get(key));
                map.remove(key);
            }

            // If the merge behavior is ignore, we don't bother keeping the extra action
            if (mergeBehavior == Action.MERGE_REPLACE || mergeBehavior == Action.MERGE_APPEND) {
                mActions.add(a);
            }
        }

        // Because pruning can remove the need for bitmaps, we reconstruct the bitmap cache
        mBitmapCache = new BitmapCache();
        setBitmapCache(mBitmapCache);
    }

    /**
     * Note all {@link Uri} that are referenced internally, with the expectation
     * that Uri permission grants will need to be issued to ensure the recipient
     * of this object is able to render its contents.
     *
     * @hide
     */
    public void visitUris(@NonNull Consumer<Uri> visitor) {
        if (mActions != null) {
            for (int i = 0; i < mActions.size(); i++) {
                mActions.get(i).visitUris(visitor);
            }
        }
    }

    private static void visitIconUri(Icon icon, @NonNull Consumer<Uri> visitor) {
        if (icon != null && (icon.getType() == Icon.TYPE_URI
                || icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
            visitor.accept(icon.getUri());
        }
    }

    private static class RemoteViewsContextWrapper extends ContextWrapper {
        private final Context mContextForResources;

        RemoteViewsContextWrapper(Context context, Context contextForResources) {
            super(context);
            mContextForResources = contextForResources;
        }

        @Override
        public Resources getResources() {
            return mContextForResources.getResources();
        }

        @Override
        public Resources.Theme getTheme() {
            return mContextForResources.getTheme();
        }

        @Override
        public String getPackageName() {
            return mContextForResources.getPackageName();
        }

        @Override
        public boolean isRestricted() {
            // Override isRestricted and direct to resource's implementation. The isRestricted is
            // used for determining the risky resources loading, e.g. fonts, thus direct to context
            // for resource.
            return mContextForResources.isRestricted();
        }
    }

    private class SetEmptyView extends Action {
        int emptyViewId;

        SetEmptyView(@IdRes int viewId, @IdRes int emptyViewId) {
            this.viewId = viewId;
            this.emptyViewId = emptyViewId;
        }

        SetEmptyView(Parcel in) {
            this.viewId = in.readInt();
            this.emptyViewId = in.readInt();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(this.viewId);
            out.writeInt(this.emptyViewId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View view = root.findViewById(viewId);
            if (!(view instanceof AdapterView<?>)) return;

            AdapterView<?> adapterView = (AdapterView<?>) view;

            final View emptyView = root.findViewById(emptyViewId);
            if (emptyView == null) return;

            adapterView.setEmptyView(emptyView);
        }

        @Override
        public int getActionTag() {
            return SET_EMPTY_VIEW_ACTION_TAG;
        }
    }

    private class SetPendingIntentTemplate extends Action {
        public SetPendingIntentTemplate(@IdRes int id, PendingIntent pendingIntentTemplate) {
            this.viewId = id;
            this.pendingIntentTemplate = pendingIntentTemplate;
        }

        public SetPendingIntentTemplate(Parcel parcel) {
            viewId = parcel.readInt();
            pendingIntentTemplate = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            PendingIntent.writePendingIntentOrNullToParcel(pendingIntentTemplate, dest);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // If the view isn't an AdapterView, setting a PendingIntent template doesn't make sense
            if (target instanceof AdapterView<?>) {
                AdapterView<?> av = (AdapterView<?>) target;
                // The PendingIntent template is stored in the view's tag.
                OnItemClickListener listener = (parent, view, position, id) -> {
                    // The view should be a frame layout
                    if (view instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) view;

                        // AdapterViews contain their children in a frame
                        // so we need to go one layer deeper here.
                        if (parent instanceof AdapterViewAnimator) {
                            vg = (ViewGroup) vg.getChildAt(0);
                        }
                        if (vg == null) return;

                        RemoteResponse response = null;
                        int childCount = vg.getChildCount();
                        for (int i = 0; i < childCount; i++) {
                            Object tag = vg.getChildAt(i).getTag(R.id.fillInIntent);
                            if (tag instanceof RemoteResponse) {
                                response = (RemoteResponse) tag;
                                break;
                            }
                        }
                        if (response == null) return;
                        response.handleViewInteraction(view, handler);
                    }
                };
                av.setOnItemClickListener(listener);
                av.setTag(pendingIntentTemplate);
            } else {
                Log.e(LOG_TAG, "Cannot setPendingIntentTemplate on a view which is not" +
                        "an AdapterView (id: " + viewId + ")");
                return;
            }
        }

        @Override
        public int getActionTag() {
            return SET_PENDING_INTENT_TEMPLATE_TAG;
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        PendingIntent pendingIntentTemplate;
    }

    private class SetRemoteViewsAdapterList extends Action {
        public SetRemoteViewsAdapterList(@IdRes int id, ArrayList<RemoteViews> list,
                int viewTypeCount) {
            this.viewId = id;
            this.list = list;
            this.viewTypeCount = viewTypeCount;
        }

        public SetRemoteViewsAdapterList(Parcel parcel) {
            viewId = parcel.readInt();
            viewTypeCount = parcel.readInt();
            list = parcel.createTypedArrayList(RemoteViews.CREATOR);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(viewTypeCount);
            dest.writeTypedList(list, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Ensure that we are applying to an AppWidget root
            if (!(rootParent instanceof AppWidgetHostView)) {
                Log.e(LOG_TAG, "SetRemoteViewsAdapterIntent action can only be used for " +
                        "AppWidgets (root id: " + viewId + ")");
                return;
            }
            // Ensure that we are calling setRemoteAdapter on an AdapterView that supports it
            if (!(target instanceof AbsListView) && !(target instanceof AdapterViewAnimator)) {
                Log.e(LOG_TAG, "Cannot setRemoteViewsAdapter on a view which is not " +
                        "an AbsListView or AdapterViewAnimator (id: " + viewId + ")");
                return;
            }

            if (target instanceof AbsListView) {
                AbsListView v = (AbsListView) target;
                Adapter a = v.getAdapter();
                if (a instanceof RemoteViewsListAdapter && viewTypeCount <= a.getViewTypeCount()) {
                    ((RemoteViewsListAdapter) a).setViewsList(list);
                } else {
                    v.setAdapter(new RemoteViewsListAdapter(v.getContext(), list, viewTypeCount,
                            colorResources));
                }
            } else if (target instanceof AdapterViewAnimator) {
                AdapterViewAnimator v = (AdapterViewAnimator) target;
                Adapter a = v.getAdapter();
                if (a instanceof RemoteViewsListAdapter && viewTypeCount <= a.getViewTypeCount()) {
                    ((RemoteViewsListAdapter) a).setViewsList(list);
                } else {
                    v.setAdapter(new RemoteViewsListAdapter(v.getContext(), list, viewTypeCount,
                            colorResources));
                }
            }
        }

        @Override
        public int getActionTag() {
            return SET_REMOTE_VIEW_ADAPTER_LIST_TAG;
        }

        int viewTypeCount;
        ArrayList<RemoteViews> list;
    }

    private class SetRemoteViewsAdapterIntent extends Action {
        public SetRemoteViewsAdapterIntent(@IdRes int id, Intent intent) {
            this.viewId = id;
            this.intent = intent;
        }

        public SetRemoteViewsAdapterIntent(Parcel parcel) {
            viewId = parcel.readInt();
            intent = parcel.readTypedObject(Intent.CREATOR);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeTypedObject(intent, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Ensure that we are applying to an AppWidget root
            if (!(rootParent instanceof AppWidgetHostView)) {
                Log.e(LOG_TAG, "SetRemoteViewsAdapterIntent action can only be used for " +
                        "AppWidgets (root id: " + viewId + ")");
                return;
            }
            // Ensure that we are calling setRemoteAdapter on an AdapterView that supports it
            if (!(target instanceof AbsListView) && !(target instanceof AdapterViewAnimator)) {
                Log.e(LOG_TAG, "Cannot setRemoteViewsAdapter on a view which is not " +
                        "an AbsListView or AdapterViewAnimator (id: " + viewId + ")");
                return;
            }

            // Embed the AppWidget Id for use in RemoteViewsAdapter when connecting to the intent
            // RemoteViewsService
            AppWidgetHostView host = (AppWidgetHostView) rootParent;
            intent.putExtra(EXTRA_REMOTEADAPTER_APPWIDGET_ID, host.getAppWidgetId())
                    .putExtra(EXTRA_REMOTEADAPTER_ON_LIGHT_BACKGROUND,
                            hasFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT));

            if (target instanceof AbsListView) {
                AbsListView v = (AbsListView) target;
                v.setRemoteViewsAdapter(intent, isAsync);
                v.setRemoteViewsInteractionHandler(handler);
            } else if (target instanceof AdapterViewAnimator) {
                AdapterViewAnimator v = (AdapterViewAnimator) target;
                v.setRemoteViewsAdapter(intent, isAsync);
                v.setRemoteViewsOnClickHandler(handler);
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                InteractionHandler handler, ColorResources colorResources) {
            SetRemoteViewsAdapterIntent copy = new SetRemoteViewsAdapterIntent(viewId, intent);
            copy.isAsync = true;
            return copy;
        }

        @Override
        public int getActionTag() {
            return SET_REMOTE_VIEW_ADAPTER_INTENT_TAG;
        }

        Intent intent;
        boolean isAsync = false;
    }

    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}.
     */
    private class SetOnClickResponse extends Action {

        SetOnClickResponse(@IdRes int id, RemoteResponse response) {
            this.viewId = id;
            this.mResponse = response;
        }

        SetOnClickResponse(Parcel parcel) {
            viewId = parcel.readInt();
            mResponse = new RemoteResponse();
            mResponse.readFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            mResponse.writeToParcel(dest, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            if (mResponse.mPendingIntent != null) {
                // If the view is an AdapterView, setting a PendingIntent on click doesn't make
                // much sense, do they mean to set a PendingIntent template for the
                // AdapterView's children?
                if (hasFlags(FLAG_WIDGET_IS_COLLECTION_CHILD)) {
                    Log.w(LOG_TAG, "Cannot SetOnClickResponse for collection item "
                            + "(id: " + viewId + ")");
                    ApplicationInfo appInfo = root.getContext().getApplicationInfo();

                    // We let this slide for HC and ICS so as to not break compatibility. It should
                    // have been disabled from the outset, but was left open by accident.
                    if (appInfo != null
                            && appInfo.targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN) {
                        return;
                    }
                }
                target.setTagInternal(R.id.pending_intent_tag, mResponse.mPendingIntent);
            } else if (mResponse.mFillIntent != null) {
                if (!hasFlags(FLAG_WIDGET_IS_COLLECTION_CHILD)) {
                    Log.e(LOG_TAG, "The method setOnClickFillInIntent is available "
                            + "only from RemoteViewsFactory (ie. on collection items).");
                    return;
                }
                if (target == root) {
                    // Target is a root node of an AdapterView child. Set the response in the tag.
                    // Actual click handling is done by OnItemClickListener in
                    // SetPendingIntentTemplate, which uses this tag information.
                    target.setTagInternal(com.android.internal.R.id.fillInIntent, mResponse);
                    return;
                }
            } else {
                // No intent to apply, clear the listener and any tags that were previously set.
                target.setOnClickListener(null);
                target.setTagInternal(R.id.pending_intent_tag, null);
                target.setTagInternal(com.android.internal.R.id.fillInIntent, null);
                return;
            }
            target.setOnClickListener(v -> mResponse.handleViewInteraction(v, handler));
        }

        @Override
        public int getActionTag() {
            return SET_ON_CLICK_RESPONSE_TAG;
        }

        final RemoteResponse mResponse;
    }

    /**
     * Equivalent to calling
     * {@link android.widget.CompoundButton#setOnCheckedChangeListener(
     * android.widget.CompoundButton.OnCheckedChangeListener)}
     * to launch the provided {@link PendingIntent}.
     */
    private class SetOnCheckedChangeResponse extends Action {

        private final RemoteResponse mResponse;

        SetOnCheckedChangeResponse(@IdRes int id, RemoteResponse response) {
            this.viewId = id;
            this.mResponse = response;
        }

        SetOnCheckedChangeResponse(Parcel parcel) {
            viewId = parcel.readInt();
            mResponse = new RemoteResponse();
            mResponse.readFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            mResponse.writeToParcel(dest, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;
            if (!(target instanceof CompoundButton)) {
                Log.w(LOG_TAG, "setOnCheckedChange methods cannot be used on "
                        + "non-CompoundButton child (id: " + viewId + ")");
                return;
            }
            CompoundButton button = (CompoundButton) target;

            if (mResponse.mPendingIntent != null) {
                // setOnCheckedChangePendingIntent cannot be used with collection children, which
                // must use setOnCheckedChangeFillInIntent instead.
                if (hasFlags(FLAG_WIDGET_IS_COLLECTION_CHILD)) {
                    Log.w(LOG_TAG, "Cannot setOnCheckedChangePendingIntent for collection item "
                            + "(id: " + viewId + ")");
                    return;
                }
                target.setTagInternal(R.id.pending_intent_tag, mResponse.mPendingIntent);
            } else if (mResponse.mFillIntent != null) {
                if (!hasFlags(FLAG_WIDGET_IS_COLLECTION_CHILD)) {
                    Log.e(LOG_TAG, "The method setOnCheckedChangeFillInIntent is available "
                            + "only from RemoteViewsFactory (ie. on collection items).");
                    return;
                }
            } else {
                // No intent to apply, clear any existing listener or tag.
                button.setOnCheckedChangeListener(null);
                button.setTagInternal(R.id.remote_checked_change_listener_tag, null);
                return;
            }

            OnCheckedChangeListener onCheckedChangeListener =
                    (v, isChecked) -> mResponse.handleViewInteraction(v, handler);
            button.setTagInternal(R.id.remote_checked_change_listener_tag, onCheckedChangeListener);
            button.setOnCheckedChangeListener(onCheckedChangeListener);
        }

        @Override
        public int getActionTag() {
            return SET_ON_CHECKED_CHANGE_RESPONSE_TAG;
        }
    }

    /** @hide **/
    public static Rect getSourceBounds(View v) {
        final float appScale = v.getContext().getResources()
                .getCompatibilityInfo().applicationScale;
        final int[] pos = new int[2];
        v.getLocationOnScreen(pos);

        final Rect rect = new Rect();
        rect.left = (int) (pos[0] * appScale + 0.5f);
        rect.top = (int) (pos[1] * appScale + 0.5f);
        rect.right = (int) ((pos[0] + v.getWidth()) * appScale + 0.5f);
        rect.bottom = (int) ((pos[1] + v.getHeight()) * appScale + 0.5f);
        return rect;
    }

    private static Class<?> getParameterType(int type) {
        switch (type) {
            case BaseReflectionAction.BOOLEAN:
                return boolean.class;
            case BaseReflectionAction.BYTE:
                return byte.class;
            case BaseReflectionAction.SHORT:
                return short.class;
            case BaseReflectionAction.INT:
                return int.class;
            case BaseReflectionAction.LONG:
                return long.class;
            case BaseReflectionAction.FLOAT:
                return float.class;
            case BaseReflectionAction.DOUBLE:
                return double.class;
            case BaseReflectionAction.CHAR:
                return char.class;
            case BaseReflectionAction.STRING:
                return String.class;
            case BaseReflectionAction.CHAR_SEQUENCE:
                return CharSequence.class;
            case BaseReflectionAction.URI:
                return Uri.class;
            case BaseReflectionAction.BITMAP:
                return Bitmap.class;
            case BaseReflectionAction.BUNDLE:
                return Bundle.class;
            case BaseReflectionAction.INTENT:
                return Intent.class;
            case BaseReflectionAction.COLOR_STATE_LIST:
                return ColorStateList.class;
            case BaseReflectionAction.ICON:
                return Icon.class;
            case BaseReflectionAction.BLEND_MODE:
                return BlendMode.class;
            default:
                return null;
        }
    }

    private MethodHandle getMethod(View view, String methodName, Class<?> paramType,
            boolean async) {
        MethodArgs result;
        Class<? extends View> klass = view.getClass();

        synchronized (sMethods) {
            // The key is defined by the view class, param class and method name.
            sLookupKey.set(klass, paramType, methodName);
            result = sMethods.get(sLookupKey);

            if (result == null) {
                Method method;
                try {
                    if (paramType == null) {
                        method = klass.getMethod(methodName);
                    } else {
                        method = klass.getMethod(methodName, paramType);
                    }
                    if (!method.isAnnotationPresent(RemotableViewMethod.class)) {
                        throw new ActionException("view: " + klass.getName()
                                + " can't use method with RemoteViews: "
                                + methodName + getParameters(paramType));
                    }

                    result = new MethodArgs();
                    result.syncMethod = MethodHandles.publicLookup().unreflect(method);
                    result.asyncMethodName =
                            method.getAnnotation(RemotableViewMethod.class).asyncImpl();
                } catch (NoSuchMethodException | IllegalAccessException ex) {
                    throw new ActionException("view: " + klass.getName() + " doesn't have method: "
                            + methodName + getParameters(paramType));
                }

                MethodKey key = new MethodKey();
                key.set(klass, paramType, methodName);
                sMethods.put(key, result);
            }

            if (!async) {
                return result.syncMethod;
            }
            // Check this so see if async method is implemented or not.
            if (result.asyncMethodName.isEmpty()) {
                return null;
            }
            // Async method is lazily loaded. If it is not yet loaded, load now.
            if (result.asyncMethod == null) {
                MethodType asyncType = result.syncMethod.type()
                        .dropParameterTypes(0, 1).changeReturnType(Runnable.class);
                try {
                    result.asyncMethod = MethodHandles.publicLookup().findVirtual(
                            klass, result.asyncMethodName, asyncType);
                } catch (NoSuchMethodException | IllegalAccessException ex) {
                    throw new ActionException("Async implementation declared as "
                            + result.asyncMethodName + " but not defined for " + methodName
                            + ": public Runnable " + result.asyncMethodName + " ("
                            + TextUtils.join(",", asyncType.parameterArray()) + ")");
                }
            }
            return result.asyncMethod;
        }
    }

    private static String getParameters(Class<?> paramType) {
        if (paramType == null) return "()";
        return "(" + paramType + ")";
    }

    /**
     * Equivalent to calling
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * on the {@link Drawable} of a given view.
     * <p>
     * The operation will be performed on the {@link Drawable} returned by the
     * target {@link View#getBackground()} by default.  If targetBackground is false,
     * we assume the target is an {@link ImageView} and try applying the operations
     * to {@link ImageView#getDrawable()}.
     * <p>
     */
    private class SetDrawableTint extends Action {
        SetDrawableTint(@IdRes int id, boolean targetBackground,
                @ColorInt int colorFilter, @NonNull PorterDuff.Mode mode) {
            this.viewId = id;
            this.targetBackground = targetBackground;
            this.colorFilter = colorFilter;
            this.filterMode = mode;
        }

        SetDrawableTint(Parcel parcel) {
            viewId = parcel.readInt();
            targetBackground = parcel.readInt() != 0;
            colorFilter = parcel.readInt();
            filterMode = PorterDuff.intToMode(parcel.readInt());
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(targetBackground ? 1 : 0);
            dest.writeInt(colorFilter);
            dest.writeInt(PorterDuff.modeToInt(filterMode));
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Pick the correct drawable to modify for this view
            Drawable targetDrawable = null;
            if (targetBackground) {
                targetDrawable = target.getBackground();
            } else if (target instanceof ImageView) {
                ImageView imageView = (ImageView) target;
                targetDrawable = imageView.getDrawable();
            }

            if (targetDrawable != null) {
                targetDrawable.mutate().setColorFilter(colorFilter, filterMode);
            }
        }

        @Override
        public int getActionTag() {
            return SET_DRAWABLE_TINT_TAG;
        }

        boolean targetBackground;
        @ColorInt int colorFilter;
        PorterDuff.Mode filterMode;
    }

    /**
     * Equivalent to calling
     * {@link RippleDrawable#setColor(ColorStateList)},
     * on the {@link Drawable} of a given view.
     * <p>
     * The operation will be performed on the {@link Drawable} returned by the
     * target {@link View#getBackground()}.
     * <p>
     */
    private class SetRippleDrawableColor extends Action {

        ColorStateList mColorStateList;

        SetRippleDrawableColor(@IdRes int id, ColorStateList colorStateList) {
            this.viewId = id;
            this.mColorStateList = colorStateList;
        }

        SetRippleDrawableColor(Parcel parcel) {
            viewId = parcel.readInt();
            mColorStateList = parcel.readParcelable(null);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeParcelable(mColorStateList, 0);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Pick the correct drawable to modify for this view
            Drawable targetDrawable = target.getBackground();

            if (targetDrawable instanceof RippleDrawable) {
                ((RippleDrawable) targetDrawable.mutate()).setColor(mColorStateList);
            }
        }

        @Override
        public int getActionTag() {
            return SET_RIPPLE_DRAWABLE_COLOR_TAG;
        }
    }

    private final class ViewContentNavigation extends Action {
        final boolean mNext;

        ViewContentNavigation(@IdRes int viewId, boolean next) {
            this.viewId = viewId;
            this.mNext = next;
        }

        ViewContentNavigation(Parcel in) {
            this.viewId = in.readInt();
            this.mNext = in.readBoolean();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(this.viewId);
            out.writeBoolean(this.mNext);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

            try {
                getMethod(view,
                        mNext ? "showNext" : "showPrevious", null, false /* async */).invoke(view);
            } catch (Throwable ex) {
                throw new ActionException(ex);
            }
        }

        public int mergeBehavior() {
            return MERGE_IGNORE;
        }

        @Override
        public int getActionTag() {
            return VIEW_CONTENT_NAVIGATION_TAG;
        }
    }

    private static class BitmapCache {

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        ArrayList<Bitmap> mBitmaps;
        int mBitmapMemory = -1;

        public BitmapCache() {
            mBitmaps = new ArrayList<>();
        }

        public BitmapCache(Parcel source) {
            mBitmaps = source.createTypedArrayList(Bitmap.CREATOR);
        }

        public int getBitmapId(Bitmap b) {
            if (b == null) {
                return -1;
            } else {
                if (mBitmaps.contains(b)) {
                    return mBitmaps.indexOf(b);
                } else {
                    mBitmaps.add(b);
                    mBitmapMemory = -1;
                    return (mBitmaps.size() - 1);
                }
            }
        }

        public Bitmap getBitmapForId(int id) {
            if (id == -1 || id >= mBitmaps.size()) {
                return null;
            } else {
                return mBitmaps.get(id);
            }
        }

        public void writeBitmapsToParcel(Parcel dest, int flags) {
            dest.writeTypedList(mBitmaps, flags);
        }

        public int getBitmapMemory() {
            if (mBitmapMemory < 0) {
                mBitmapMemory = 0;
                int count = mBitmaps.size();
                for (int i = 0; i < count; i++) {
                    mBitmapMemory += mBitmaps.get(i).getAllocationByteCount();
                }
            }
            return mBitmapMemory;
        }
    }

    private class BitmapReflectionAction extends Action {
        int bitmapId;
        @UnsupportedAppUsage
        Bitmap bitmap;
        @UnsupportedAppUsage
        String methodName;

        BitmapReflectionAction(@IdRes int viewId, String methodName, Bitmap bitmap) {
            this.bitmap = bitmap;
            this.viewId = viewId;
            this.methodName = methodName;
            bitmapId = mBitmapCache.getBitmapId(bitmap);
        }

        BitmapReflectionAction(Parcel in) {
            viewId = in.readInt();
            methodName = in.readString8();
            bitmapId = in.readInt();
            bitmap = mBitmapCache.getBitmapForId(bitmapId);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeString8(methodName);
            dest.writeInt(bitmapId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) throws ActionException {
            ReflectionAction ra = new ReflectionAction(viewId, methodName,
                    BaseReflectionAction.BITMAP,
                    bitmap);
            ra.apply(root, rootParent, handler, colorResources);
        }

        @Override
        public void setBitmapCache(BitmapCache bitmapCache) {
            bitmapId = bitmapCache.getBitmapId(bitmap);
        }

        @Override
        public int getActionTag() {
            return BITMAP_REFLECTION_ACTION_TAG;
        }
    }

    /**
     * Base class for the reflection actions.
     */
    private abstract class BaseReflectionAction extends Action {
        static final int BOOLEAN = 1;
        static final int BYTE = 2;
        static final int SHORT = 3;
        static final int INT = 4;
        static final int LONG = 5;
        static final int FLOAT = 6;
        static final int DOUBLE = 7;
        static final int CHAR = 8;
        static final int STRING = 9;
        static final int CHAR_SEQUENCE = 10;
        static final int URI = 11;
        // BITMAP actions are never stored in the list of actions. They are only used locally
        // to implement BitmapReflectionAction, which eliminates duplicates using BitmapCache.
        static final int BITMAP = 12;
        static final int BUNDLE = 13;
        static final int INTENT = 14;
        static final int COLOR_STATE_LIST = 15;
        static final int ICON = 16;
        static final int BLEND_MODE = 17;

        @UnsupportedAppUsage
        String methodName;
        int type;

        BaseReflectionAction(@IdRes int viewId, String methodName, int type) {
            this.viewId = viewId;
            this.methodName = methodName;
            this.type = type;
        }

        BaseReflectionAction(Parcel in) {
            this.viewId = in.readInt();
            this.methodName = in.readString8();
            this.type = in.readInt();
            //noinspection ConstantIfStatement
            if (false) {
                Log.d(LOG_TAG, "read viewId=0x" + Integer.toHexString(this.viewId)
                        + " methodName=" + this.methodName + " type=" + this.type);
            }
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(this.viewId);
            out.writeString8(this.methodName);
            out.writeInt(this.type);
        }

        /**
         * Returns the value to use as parameter for the method.
         *
         * The view might be passed as {@code null} if the parameter value is requested outside of
         * inflation. If the parameter cannot be determined at that time, the method should return
         * {@code null} but not raise any exception.
         */
        @Nullable
        protected abstract Object getParameterValue(@Nullable View view) throws ActionException;

        @Override
        public final void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

            Class<?> param = getParameterType(this.type);
            if (param == null) {
                throw new ActionException("bad type: " + this.type);
            }
            Object value = getParameterValue(view);
            try {
                getMethod(view, this.methodName, param, false /* async */).invoke(view, value);
            } catch (Throwable ex) {
                throw new ActionException(ex);
            }
        }

        @Override
        public final Action initActionAsync(ViewTree root, ViewGroup rootParent,
                InteractionHandler handler, ColorResources colorResources) {
            final View view = root.findViewById(viewId);
            if (view == null) return ACTION_NOOP;

            Class<?> param = getParameterType(this.type);
            if (param == null) {
                throw new ActionException("bad type: " + this.type);
            }

            Object value = getParameterValue(view);
            try {
                MethodHandle method = getMethod(view, this.methodName, param, true /* async */);

                if (method != null) {
                    Runnable endAction = (Runnable) method.invoke(view, value);
                    if (endAction == null) {
                        return ACTION_NOOP;
                    }
                    // Special case view stub
                    if (endAction instanceof ViewStub.ViewReplaceRunnable) {
                        root.createTree();
                        // Replace child tree
                        root.findViewTreeById(viewId).replaceView(
                                ((ViewStub.ViewReplaceRunnable) endAction).view);
                    }
                    return new RunnableAction(endAction);
                }
            } catch (Throwable ex) {
                throw new ActionException(ex);
            }

            return this;
        }

        public final int mergeBehavior() {
            // smoothScrollBy is cumulative, everything else overwites.
            if (methodName.equals("smoothScrollBy")) {
                return MERGE_APPEND;
            } else {
                return MERGE_REPLACE;
            }
        }

        @Override
        public final String getUniqueKey() {
            // Each type of reflection action corresponds to a setter, so each should be seen as
            // unique from the standpoint of merging.
            return super.getUniqueKey() + this.methodName + this.type;
        }

        @Override
        public final boolean prefersAsyncApply() {
            return this.type == URI || this.type == ICON;
        }

        @Override
        public final void visitUris(@NonNull Consumer<Uri> visitor) {
            switch (this.type) {
                case URI:
                    final Uri uri = (Uri) getParameterValue(null);
                    if (uri != null) visitor.accept(uri);
                    break;
                case ICON:
                    final Icon icon = (Icon) getParameterValue(null);
                    if (icon != null) visitIconUri(icon, visitor);
                    break;
            }
        }
    }

    /** Class for the reflection actions. */
    private final class ReflectionAction extends BaseReflectionAction {
        @UnsupportedAppUsage
        Object value;

        ReflectionAction(@IdRes int viewId, String methodName, int type, Object value) {
            super(viewId, methodName, type);
            this.value = value;
        }

        ReflectionAction(Parcel in) {
            super(in);
            // For some values that may have been null, we first check a flag to see if they were
            // written to the parcel.
            switch (this.type) {
                case BOOLEAN:
                    this.value = in.readBoolean();
                    break;
                case BYTE:
                    this.value = in.readByte();
                    break;
                case SHORT:
                    this.value = (short) in.readInt();
                    break;
                case INT:
                    this.value = in.readInt();
                    break;
                case LONG:
                    this.value = in.readLong();
                    break;
                case FLOAT:
                    this.value = in.readFloat();
                    break;
                case DOUBLE:
                    this.value = in.readDouble();
                    break;
                case CHAR:
                    this.value = (char) in.readInt();
                    break;
                case STRING:
                    this.value = in.readString8();
                    break;
                case CHAR_SEQUENCE:
                    this.value = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
                    break;
                case URI:
                    this.value = in.readTypedObject(Uri.CREATOR);
                    break;
                case BITMAP:
                    this.value = in.readTypedObject(Bitmap.CREATOR);
                    break;
                case BUNDLE:
                    this.value = in.readBundle();
                    break;
                case INTENT:
                    this.value = in.readTypedObject(Intent.CREATOR);
                    break;
                case COLOR_STATE_LIST:
                    this.value = in.readTypedObject(ColorStateList.CREATOR);
                    break;
                case ICON:
                    this.value = in.readTypedObject(Icon.CREATOR);
                    break;
                case BLEND_MODE:
                    this.value = BlendMode.fromValue(in.readInt());
                    break;
                default:
                    break;
            }
        }

        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            // For some values which are null, we record an integer flag to indicate whether
            // we have written a valid value to the parcel.
            switch (this.type) {
                case BOOLEAN:
                    out.writeBoolean((Boolean) this.value);
                    break;
                case BYTE:
                    out.writeByte((Byte) this.value);
                    break;
                case SHORT:
                    out.writeInt((Short) this.value);
                    break;
                case INT:
                    out.writeInt((Integer) this.value);
                    break;
                case LONG:
                    out.writeLong((Long) this.value);
                    break;
                case FLOAT:
                    out.writeFloat((Float) this.value);
                    break;
                case DOUBLE:
                    out.writeDouble((Double) this.value);
                    break;
                case CHAR:
                    out.writeInt((int) ((Character) this.value).charValue());
                    break;
                case STRING:
                    out.writeString8((String) this.value);
                    break;
                case CHAR_SEQUENCE:
                    TextUtils.writeToParcel((CharSequence) this.value, out, flags);
                    break;
                case BUNDLE:
                    out.writeBundle((Bundle) this.value);
                    break;
                case BLEND_MODE:
                    out.writeInt(BlendMode.toValue((BlendMode) this.value));
                    break;
                case URI:
                case BITMAP:
                case INTENT:
                case COLOR_STATE_LIST:
                case ICON:
                    out.writeTypedObject((Parcelable) this.value, flags);
                    break;
                default:
                    break;
            }
        }

        @Override
        protected Object getParameterValue(View view) throws ActionException {
            return this.value;
        }

        @Override
        public int getActionTag() {
            return REFLECTION_ACTION_TAG;
        }
    }

    private final class ResourceReflectionAction extends BaseReflectionAction {

        static final int DIMEN_RESOURCE = 1;
        static final int COLOR_RESOURCE = 2;
        static final int STRING_RESOURCE = 3;

        private final int mResourceType;
        private final int mResId;

        ResourceReflectionAction(@IdRes int viewId, String methodName, int parameterType,
                int resourceType, int resId) {
            super(viewId, methodName, parameterType);
            this.mResourceType = resourceType;
            this.mResId = resId;
        }

        ResourceReflectionAction(Parcel in) {
            super(in);
            this.mResourceType = in.readInt();
            this.mResId = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(this.mResourceType);
            dest.writeInt(this.mResId);
        }

        @Override
        protected @NonNull Object getParameterValue(View view) throws ActionException {
            Resources resources = view.getContext().getResources();
            try {
                switch (this.mResourceType) {
                    case DIMEN_RESOURCE:
                        if (this.type == BaseReflectionAction.INT) {
                            return resources.getDimensionPixelSize(this.mResId);
                        }
                        return resources.getDimension(this.mResId);
                    case COLOR_RESOURCE:
                        switch(this.type) {
                            case BaseReflectionAction.INT:
                                return view.getContext().getColor(this.mResId);
                            case BaseReflectionAction.COLOR_STATE_LIST:
                                return view.getContext().getColorStateList(this.mResId);
                            default:
                                throw new ActionException(
                                        "color resources must be used as int or ColorStateList, "
                                                + "not " + this.type);
                        }
                    case STRING_RESOURCE:
                        return resources.getText(this.mResId);
                    default:
                        throw new ActionException("unknown resource type: " + this.mResourceType);
                }
            } catch (Throwable t) {
                throw new ActionException(t);
            }
        }

        @Override
        public int getActionTag() {
            return RESOURCE_REFLECTION_ACTION_TAG;
        }
    }

    private final class ComplexUnitDimensionReflectionAction extends BaseReflectionAction {

        private final float mValue;
        @ComplexDimensionUnit
        private final int mUnit;

        ComplexUnitDimensionReflectionAction(int viewId, String methodName, int parameterType,
                float value, @ComplexDimensionUnit int unit) {
            super(viewId, methodName, parameterType);
            this.mValue = value;
            this.mUnit = unit;
        }

        ComplexUnitDimensionReflectionAction(Parcel in) {
            super(in);
            this.mValue = in.readFloat();
            this.mUnit = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeFloat(this.mValue);
            dest.writeInt(this.mUnit);
        }

        @Override
        protected Object getParameterValue(View view) throws ActionException {
            DisplayMetrics dm = view.getContext().getResources().getDisplayMetrics();
            try {
                int data = TypedValue.createComplexDimension(this.mValue, this.mUnit);
                switch (this.type) {
                    case ReflectionAction.INT:
                        return TypedValue.complexToDimensionPixelSize(data, dm);
                    case ReflectionAction.FLOAT:
                        return TypedValue.complexToDimension(data, dm);
                    default:
                        throw new ActionException(
                                "parameter type must be INT or FLOAT, not " + this.type);
                }
            } catch (ActionException ex) {
                throw ex;
            } catch (Throwable t) {
                throw new ActionException(t);
            }
        }

        @Override
        public int getActionTag() {
            return COMPLEX_UNIT_DIMENSION_REFLECTION_ACTION_TAG;
        }
    }

    private final class NightModeReflectionAction extends BaseReflectionAction {

        private final Object mLightValue;
        private final Object mDarkValue;

        NightModeReflectionAction(
                @IdRes int viewId,
                String methodName,
                int type,
                Object lightValue,
                Object darkValue) {
            super(viewId, methodName, type);
            mLightValue = lightValue;
            mDarkValue = darkValue;
        }

        NightModeReflectionAction(Parcel in) {
            super(in);
            switch (this.type) {
                case ICON:
                    mLightValue = in.readTypedObject(Icon.CREATOR);
                    mDarkValue = in.readTypedObject(Icon.CREATOR);
                    break;
                case COLOR_STATE_LIST:
                    mLightValue = in.readTypedObject(ColorStateList.CREATOR);
                    mDarkValue = in.readTypedObject(ColorStateList.CREATOR);
                    break;
                case INT:
                    mLightValue = in.readInt();
                    mDarkValue = in.readInt();
                    break;
                default:
                    throw new ActionException("Unexpected night mode action type: " + this.type);
            }
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            switch (this.type) {
                case ICON:
                case COLOR_STATE_LIST:
                    out.writeTypedObject((Parcelable) mLightValue, flags);
                    out.writeTypedObject((Parcelable) mDarkValue, flags);
                    break;
                case INT:
                    out.writeInt((int) mLightValue);
                    out.writeInt((int) mDarkValue);
                    break;
            }
        }

        @Nullable
        @Override
        protected Object getParameterValue(@Nullable View view) throws ActionException {
            if (view == null) return null;

            Configuration configuration = view.getResources().getConfiguration();
            return configuration.isNightModeActive() ? mDarkValue : mLightValue;
        }

        @Override
        public int getActionTag() {
            return NIGHT_MODE_REFLECTION_ACTION_TAG;
        }
    }

    /**
     * This is only used for async execution of actions and it not parcelable.
     */
    private static final class RunnableAction extends RuntimeAction {
        private final Runnable mRunnable;

        RunnableAction(Runnable r) {
            mRunnable = r;
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            mRunnable.run();
        }
    }

    private void configureRemoteViewsAsChild(RemoteViews rv) {
        rv.setBitmapCache(mBitmapCache);
        rv.setNotRoot();
    }

    void setNotRoot() {
        mIsRoot = false;
    }

    /**
     * ViewGroup methods that are related to adding Views.
     */
    private class ViewGroupActionAdd extends Action {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private RemoteViews mNestedViews;
        private int mIndex;

        ViewGroupActionAdd(@IdRes int viewId, RemoteViews nestedViews) {
            this(viewId, nestedViews, -1 /* index */);
        }

        ViewGroupActionAdd(@IdRes int viewId, RemoteViews nestedViews, int index) {
            this.viewId = viewId;
            mNestedViews = nestedViews;
            mIndex = index;
            if (nestedViews != null) {
                configureRemoteViewsAsChild(nestedViews);
            }
        }

        ViewGroupActionAdd(Parcel parcel, BitmapCache bitmapCache, ApplicationInfo info,
                int depth, Map<Class, Object> classCookies) {
            viewId = parcel.readInt();
            mIndex = parcel.readInt();
            mNestedViews = new RemoteViews(parcel, bitmapCache, info, depth, classCookies);
            mNestedViews.addFlags(mApplyFlags);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(mIndex);
            mNestedViews.writeToParcel(dest, flags);
        }

        @Override
        public boolean hasSameAppInfo(ApplicationInfo parentInfo) {
            return mNestedViews.hasSameAppInfo(parentInfo);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final Context context = root.getContext();
            final ViewGroup target = root.findViewById(viewId);

            if (target == null) {
                return;
            }

            // Inflate nested views and add as children
            target.addView(
                    mNestedViews.apply(context, target, handler, null /* size */, colorResources),
                    mIndex);
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                InteractionHandler handler, ColorResources colorResources) {
            // In the async implementation, update the view tree so that subsequent calls to
            // findViewById return the current view.
            root.createTree();
            ViewTree target = root.findViewTreeById(viewId);
            if ((target == null) || !(target.mRoot instanceof ViewGroup)) {
                return ACTION_NOOP;
            }
            final ViewGroup targetVg = (ViewGroup) target.mRoot;

            // Inflate nested views and perform all the async tasks for the child remoteView.
            final Context context = root.mRoot.getContext();
            final AsyncApplyTask task = mNestedViews.getAsyncApplyTask(context, targetVg,
                    null /* listener */, handler, null /* size */, colorResources);
            final ViewTree tree = task.doInBackground();

            if (tree == null) {
                throw new ActionException(task.mError);
            }

            // Update the global view tree, so that next call to findViewTreeById
            // goes through the subtree as well.
            target.addChild(tree, mIndex);

            return new RuntimeAction() {
                @Override
                public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                        ColorResources colorResources) throws ActionException {
                    task.onPostExecute(tree);
                    targetVg.addView(task.mResult, mIndex);
                }
            };
        }

        @Override
        public void setBitmapCache(BitmapCache bitmapCache) {
            mNestedViews.setBitmapCache(bitmapCache);
        }

        @Override
        public int mergeBehavior() {
            return MERGE_APPEND;
        }

        @Override
        public boolean prefersAsyncApply() {
            return mNestedViews.prefersAsyncApply();
        }

        @Override
        public int getActionTag() {
            return VIEW_GROUP_ACTION_ADD_TAG;
        }
    }

    /**
     * ViewGroup methods related to removing child views.
     */
    private class ViewGroupActionRemove extends Action {
        /**
         * Id that indicates that all child views of the affected ViewGroup should be removed.
         *
         * <p>Using -2 because the default id is -1. This avoids accidentally matching that.
         */
        private static final int REMOVE_ALL_VIEWS_ID = -2;

        private int mViewIdToKeep;

        ViewGroupActionRemove(@IdRes int viewId) {
            this(viewId, REMOVE_ALL_VIEWS_ID);
        }

        ViewGroupActionRemove(@IdRes int viewId, @IdRes int viewIdToKeep) {
            this.viewId = viewId;
            mViewIdToKeep = viewIdToKeep;
        }

        ViewGroupActionRemove(Parcel parcel) {
            viewId = parcel.readInt();
            mViewIdToKeep = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(mViewIdToKeep);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final ViewGroup target = root.findViewById(viewId);

            if (target == null) {
                return;
            }

            if (mViewIdToKeep == REMOVE_ALL_VIEWS_ID) {
                target.removeAllViews();
                return;
            }

            removeAllViewsExceptIdToKeep(target);
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                InteractionHandler handler, ColorResources colorResources) {
            // In the async implementation, update the view tree so that subsequent calls to
            // findViewById return the current view.
            root.createTree();
            ViewTree target = root.findViewTreeById(viewId);

            if ((target == null) || !(target.mRoot instanceof ViewGroup)) {
                return ACTION_NOOP;
            }

            final ViewGroup targetVg = (ViewGroup) target.mRoot;

            if (mViewIdToKeep == REMOVE_ALL_VIEWS_ID) {
                // Clear all children when there's no excepted view
                target.mChildren = null;
            } else {
                // Remove just the children which don't match the excepted view
                target.mChildren.removeIf(childTree -> childTree.mRoot.getId() != mViewIdToKeep);
                if (target.mChildren.isEmpty()) {
                    target.mChildren = null;
                }
            }
            return new RuntimeAction() {
                @Override
                public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                        ColorResources colorResources) throws ActionException {
                    if (mViewIdToKeep == REMOVE_ALL_VIEWS_ID) {
                        targetVg.removeAllViews();
                        return;
                    }

                    removeAllViewsExceptIdToKeep(targetVg);
                }
            };
        }

        /**
         * Iterates through the children in the given ViewGroup and removes all the views that
         * do not have an id of {@link #mViewIdToKeep}.
         */
        private void removeAllViewsExceptIdToKeep(ViewGroup viewGroup) {
            // Otherwise, remove all the views that do not match the id to keep.
            int index = viewGroup.getChildCount() - 1;
            while (index >= 0) {
                if (viewGroup.getChildAt(index).getId() != mViewIdToKeep) {
                    viewGroup.removeViewAt(index);
                }
                index--;
            }
        }

        @Override
        public int getActionTag() {
            return VIEW_GROUP_ACTION_REMOVE_TAG;
        }

        @Override
        public int mergeBehavior() {
            return MERGE_APPEND;
        }
    }

    /**
     * Action to remove a view from its parent.
     */
    private class RemoveFromParentAction extends Action {

        RemoveFromParentAction(@IdRes int viewId) {
            this.viewId = viewId;
        }

        RemoveFromParentAction(Parcel parcel) {
            viewId = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);

            if (target == null || target == root) {
                return;
            }

            ViewParent parent = target.getParent();
            if (parent instanceof ViewManager) {
                ((ViewManager) parent).removeView(target);
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                InteractionHandler handler, ColorResources colorResources) {
            // In the async implementation, update the view tree so that subsequent calls to
            // findViewById return the correct view.
            root.createTree();
            ViewTree target = root.findViewTreeById(viewId);

            if (target == null || target == root) {
                return ACTION_NOOP;
            }

            ViewTree parent = root.findViewTreeParentOf(target);
            if (parent == null || !(parent.mRoot instanceof ViewManager)) {
                return ACTION_NOOP;
            }
            final ViewManager parentVg = (ViewManager) parent.mRoot;

            parent.mChildren.remove(target);
            return new RuntimeAction() {
                @Override
                public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                        ColorResources colorResources) throws ActionException {
                    parentVg.removeView(target.mRoot);
                }
            };
        }

        @Override
        public int getActionTag() {
            return REMOVE_FROM_PARENT_ACTION_TAG;
        }

        @Override
        public int mergeBehavior() {
            return MERGE_APPEND;
        }
    }

    /**
     * Helper action to set compound drawables on a TextView. Supports relative
     * (s/t/e/b) or cardinal (l/t/r/b) arrangement.
     */
    private class TextViewDrawableAction extends Action {
        public TextViewDrawableAction(@IdRes int viewId, boolean isRelative, @DrawableRes int d1,
                @DrawableRes int d2, @DrawableRes int d3, @DrawableRes int d4) {
            this.viewId = viewId;
            this.isRelative = isRelative;
            this.useIcons = false;
            this.d1 = d1;
            this.d2 = d2;
            this.d3 = d3;
            this.d4 = d4;
        }

        public TextViewDrawableAction(@IdRes int viewId, boolean isRelative,
                Icon i1, Icon i2, Icon i3, Icon i4) {
            this.viewId = viewId;
            this.isRelative = isRelative;
            this.useIcons = true;
            this.i1 = i1;
            this.i2 = i2;
            this.i3 = i3;
            this.i4 = i4;
        }

        public TextViewDrawableAction(Parcel parcel) {
            viewId = parcel.readInt();
            isRelative = (parcel.readInt() != 0);
            useIcons = (parcel.readInt() != 0);
            if (useIcons) {
                i1 = parcel.readTypedObject(Icon.CREATOR);
                i2 = parcel.readTypedObject(Icon.CREATOR);
                i3 = parcel.readTypedObject(Icon.CREATOR);
                i4 = parcel.readTypedObject(Icon.CREATOR);
            } else {
                d1 = parcel.readInt();
                d2 = parcel.readInt();
                d3 = parcel.readInt();
                d4 = parcel.readInt();
            }
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(isRelative ? 1 : 0);
            dest.writeInt(useIcons ? 1 : 0);
            if (useIcons) {
                dest.writeTypedObject(i1, 0);
                dest.writeTypedObject(i2, 0);
                dest.writeTypedObject(i3, 0);
                dest.writeTypedObject(i4, 0);
            } else {
                dest.writeInt(d1);
                dest.writeInt(d2);
                dest.writeInt(d3);
                dest.writeInt(d4);
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return;
            if (drawablesLoaded) {
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(id1, id2, id3, id4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(id1, id2, id3, id4);
                }
            } else if (useIcons) {
                final Context ctx = target.getContext();
                final Drawable id1 = i1 == null ? null : i1.loadDrawable(ctx);
                final Drawable id2 = i2 == null ? null : i2.loadDrawable(ctx);
                final Drawable id3 = i3 == null ? null : i3.loadDrawable(ctx);
                final Drawable id4 = i4 == null ? null : i4.loadDrawable(ctx);
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(id1, id2, id3, id4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(id1, id2, id3, id4);
                }
            } else {
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(d1, d2, d3, d4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(d1, d2, d3, d4);
                }
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                InteractionHandler handler, ColorResources colorResources) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return ACTION_NOOP;

            TextViewDrawableAction copy = useIcons ?
                    new TextViewDrawableAction(viewId, isRelative, i1, i2, i3, i4) :
                    new TextViewDrawableAction(viewId, isRelative, d1, d2, d3, d4);

            // Load the drawables on the background thread.
            copy.drawablesLoaded = true;
            final Context ctx = target.getContext();

            if (useIcons) {
                copy.id1 = i1 == null ? null : i1.loadDrawable(ctx);
                copy.id2 = i2 == null ? null : i2.loadDrawable(ctx);
                copy.id3 = i3 == null ? null : i3.loadDrawable(ctx);
                copy.id4 = i4 == null ? null : i4.loadDrawable(ctx);
            } else {
                copy.id1 = d1 == 0 ? null : ctx.getDrawable(d1);
                copy.id2 = d2 == 0 ? null : ctx.getDrawable(d2);
                copy.id3 = d3 == 0 ? null : ctx.getDrawable(d3);
                copy.id4 = d4 == 0 ? null : ctx.getDrawable(d4);
            }
            return copy;
        }

        @Override
        public boolean prefersAsyncApply() {
            return useIcons;
        }

        @Override
        public int getActionTag() {
            return TEXT_VIEW_DRAWABLE_ACTION_TAG;
        }

        @Override
        public void visitUris(@NonNull Consumer<Uri> visitor) {
            if (useIcons) {
                visitIconUri(i1, visitor);
                visitIconUri(i2, visitor);
                visitIconUri(i3, visitor);
                visitIconUri(i4, visitor);
            }
        }

        boolean isRelative = false;
        boolean useIcons = false;
        int d1, d2, d3, d4;
        Icon i1, i2, i3, i4;

        boolean drawablesLoaded = false;
        Drawable id1, id2, id3, id4;
    }

    /**
     * Helper action to set text size on a TextView in any supported units.
     */
    private class TextViewSizeAction extends Action {
        TextViewSizeAction(@IdRes int viewId, @ComplexDimensionUnit int units, float size) {
            this.viewId = viewId;
            this.units = units;
            this.size = size;
        }

        TextViewSizeAction(Parcel parcel) {
            viewId = parcel.readInt();
            units = parcel.readInt();
            size  = parcel.readFloat();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(units);
            dest.writeFloat(size);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return;
            target.setTextSize(units, size);
        }

        @Override
        public int getActionTag() {
            return TEXT_VIEW_SIZE_ACTION_TAG;
        }

        int units;
        float size;
    }

    /**
     * Helper action to set padding on a View.
     */
    private class ViewPaddingAction extends Action {
        public ViewPaddingAction(@IdRes int viewId, @Px int left, @Px int top,
                @Px int right, @Px int bottom) {
            this.viewId = viewId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public ViewPaddingAction(Parcel parcel) {
            viewId = parcel.readInt();
            left = parcel.readInt();
            top = parcel.readInt();
            right = parcel.readInt();
            bottom = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(left);
            dest.writeInt(top);
            dest.writeInt(right);
            dest.writeInt(bottom);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;
            target.setPadding(left, top, right, bottom);
        }

        @Override
        public int getActionTag() {
            return VIEW_PADDING_ACTION_TAG;
        }

        @Px int left, top, right, bottom;
    }

    /**
     * Helper action to set layout params on a View.
     */
    private static class LayoutParamAction extends Action {

        static final int LAYOUT_MARGIN_LEFT = MARGIN_LEFT;
        static final int LAYOUT_MARGIN_TOP = MARGIN_TOP;
        static final int LAYOUT_MARGIN_RIGHT = MARGIN_RIGHT;
        static final int LAYOUT_MARGIN_BOTTOM = MARGIN_BOTTOM;
        static final int LAYOUT_MARGIN_START = MARGIN_START;
        static final int LAYOUT_MARGIN_END = MARGIN_END;
        static final int LAYOUT_WIDTH = 8;
        static final int LAYOUT_HEIGHT = 9;

        final int mProperty;
        final boolean mIsDimen;
        final int mValue;

        /**
         * @param viewId ID of the view alter
         * @param property which layout parameter to alter
         * @param value new value of the layout parameter
         * @param units the units of the given value
         */
        LayoutParamAction(@IdRes int viewId, int property, float value,
                @ComplexDimensionUnit int units) {
            this.viewId = viewId;
            this.mProperty = property;
            this.mIsDimen = false;
            this.mValue = TypedValue.createComplexDimension(value, units);
        }

        /**
         * @param viewId ID of the view alter
         * @param property which layout parameter to alter
         * @param dimen new dimension with the value of the layout parameter
         */
        LayoutParamAction(@IdRes int viewId, int property, @DimenRes int dimen) {
            this.viewId = viewId;
            this.mProperty = property;
            this.mIsDimen = true;
            this.mValue = dimen;
        }

        public LayoutParamAction(Parcel parcel) {
            viewId = parcel.readInt();
            mProperty = parcel.readInt();
            mIsDimen = parcel.readBoolean();
            mValue = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(mProperty);
            dest.writeBoolean(mIsDimen);
            dest.writeInt(mValue);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) {
                return;
            }
            ViewGroup.LayoutParams layoutParams = target.getLayoutParams();
            if (layoutParams == null) {
                return;
            }
            switch (mProperty) {
                case LAYOUT_MARGIN_LEFT:
                    if (layoutParams instanceof MarginLayoutParams) {
                        ((MarginLayoutParams) layoutParams).leftMargin = getPixelOffset(target);
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_MARGIN_TOP:
                    if (layoutParams instanceof MarginLayoutParams) {
                        ((MarginLayoutParams) layoutParams).topMargin = getPixelOffset(target);
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_MARGIN_RIGHT:
                    if (layoutParams instanceof MarginLayoutParams) {
                        ((MarginLayoutParams) layoutParams).rightMargin = getPixelOffset(target);
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_MARGIN_BOTTOM:
                    if (layoutParams instanceof MarginLayoutParams) {
                        ((MarginLayoutParams) layoutParams).bottomMargin = getPixelOffset(target);
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_MARGIN_START:
                    if (layoutParams instanceof MarginLayoutParams) {
                        ((MarginLayoutParams) layoutParams).setMarginStart(getPixelOffset(target));
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_MARGIN_END:
                    if (layoutParams instanceof MarginLayoutParams) {
                        ((MarginLayoutParams) layoutParams).setMarginEnd(getPixelOffset(target));
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_WIDTH:
                    layoutParams.width = getPixelSize(target);
                    target.setLayoutParams(layoutParams);
                    break;
                case LAYOUT_HEIGHT:
                    layoutParams.height = getPixelSize(target);
                    target.setLayoutParams(layoutParams);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown property " + mProperty);
            }
        }

        private int getPixelOffset(View target) {
            if (mIsDimen) {
                if (mValue == 0) {
                    return 0;
                }
                return target.getResources().getDimensionPixelOffset(mValue);
            }
            return TypedValue.complexToDimensionPixelOffset(mValue,
                    target.getResources().getDisplayMetrics());
        }

        private int getPixelSize(View target) {
            if (mIsDimen) {
                if (mValue == 0) {
                    return 0;
                }
                return target.getResources().getDimensionPixelSize(mValue);
            }
            return TypedValue.complexToDimensionPixelSize(mValue,
                    target.getResources().getDisplayMetrics());
        }

        @Override
        public int getActionTag() {
            return LAYOUT_PARAM_ACTION_TAG;
        }

        @Override
        public String getUniqueKey() {
            return super.getUniqueKey() + mProperty;
        }
    }

    /**
     * Helper action to add a view tag with RemoteInputs.
     */
    private class SetRemoteInputsAction extends Action {

        public SetRemoteInputsAction(@IdRes int viewId, RemoteInput[] remoteInputs) {
            this.viewId = viewId;
            this.remoteInputs = remoteInputs;
        }

        public SetRemoteInputsAction(Parcel parcel) {
            viewId = parcel.readInt();
            remoteInputs = parcel.createTypedArray(RemoteInput.CREATOR);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeTypedArray(remoteInputs, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            target.setTagInternal(R.id.remote_input_tag, remoteInputs);
        }

        @Override
        public int getActionTag() {
            return SET_REMOTE_INPUTS_ACTION_TAG;
        }

        final Parcelable[] remoteInputs;
    }

    /**
     * Helper action to override all textViewColors
     */
    private class OverrideTextColorsAction extends Action {

        private final int textColor;

        public OverrideTextColorsAction(int textColor) {
            this.textColor = textColor;
        }

        public OverrideTextColorsAction(Parcel parcel) {
            textColor = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(textColor);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            // Let's traverse the viewtree and override all textColors!
            Stack<View> viewsToProcess = new Stack<>();
            viewsToProcess.add(root);
            while (!viewsToProcess.isEmpty()) {
                View v = viewsToProcess.pop();
                if (v instanceof TextView) {
                    TextView textView = (TextView) v;
                    textView.setText(ContrastColorUtil.clearColorSpans(textView.getText()));
                    textView.setTextColor(textColor);
                }
                if (v instanceof ViewGroup) {
                    ViewGroup viewGroup = (ViewGroup) v;
                    for (int i = 0; i < viewGroup.getChildCount(); i++) {
                        viewsToProcess.push(viewGroup.getChildAt(i));
                    }
                }
            }
        }

        @Override
        public int getActionTag() {
            return OVERRIDE_TEXT_COLORS_TAG;
        }
    }

    private class SetIntTagAction extends Action {
        @IdRes private final int mViewId;
        @IdRes private final int mKey;
        private final int mTag;

        SetIntTagAction(@IdRes int viewId, @IdRes int key, int tag) {
            mViewId = viewId;
            mKey = key;
            mTag = tag;
        }

        SetIntTagAction(Parcel parcel) {
            mViewId = parcel.readInt();
            mKey = parcel.readInt();
            mTag = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mViewId);
            dest.writeInt(mKey);
            dest.writeInt(mTag);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) {
            final View target = root.findViewById(mViewId);
            if (target == null) return;

            target.setTagInternal(mKey, mTag);
        }

        @Override
        public int getActionTag() {
            return SET_INT_TAG_TAG;
        }
    }

    private static class SetCompoundButtonCheckedAction extends Action {

        private final boolean mChecked;

        SetCompoundButtonCheckedAction(@IdRes int viewId, boolean checked) {
            this.viewId = viewId;
            mChecked = checked;
        }

        SetCompoundButtonCheckedAction(Parcel in) {
            viewId = in.readInt();
            mChecked = in.readBoolean();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeBoolean(mChecked);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources)
                throws ActionException {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            if (!(target instanceof CompoundButton)) {
                Log.w(LOG_TAG, "Cannot set checked to view "
                        + viewId + " because it is not a CompoundButton");
                return;
            }

            CompoundButton button = (CompoundButton) target;
            Object tag = button.getTag(R.id.remote_checked_change_listener_tag);
            // Temporarily unset the checked change listener so calling setChecked doesn't launch
            // the intent.
            if (tag instanceof OnCheckedChangeListener) {
                button.setOnCheckedChangeListener(null);
                button.setChecked(mChecked);
                button.setOnCheckedChangeListener((OnCheckedChangeListener) tag);
            } else {
                button.setChecked(mChecked);
            }
        }

        @Override
        public int getActionTag() {
            return SET_COMPOUND_BUTTON_CHECKED_TAG;
        }
    }

    private static class SetRadioGroupCheckedAction extends Action {

        @IdRes private final int mCheckedId;

        SetRadioGroupCheckedAction(@IdRes int viewId, @IdRes int checkedId) {
            this.viewId = viewId;
            mCheckedId = checkedId;
        }

        SetRadioGroupCheckedAction(Parcel in) {
            viewId = in.readInt();
            mCheckedId = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(mCheckedId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) throws ActionException {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            if (!(target instanceof RadioGroup)) {
                Log.w(LOG_TAG, "Cannot check " + viewId + " because it's not a RadioGroup");
                return;
            }

            RadioGroup group = (RadioGroup) target;

            // Temporarily unset all the checked change listeners while we check the group.
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof CompoundButton)) continue;

                Object tag = child.getTag(R.id.remote_checked_change_listener_tag);
                if (!(tag instanceof OnCheckedChangeListener)) continue;

                // Clear the checked change listener, we'll restore it after the check.
                ((CompoundButton) child).setOnCheckedChangeListener(null);
            }

            group.check(mCheckedId);

            // Loop through the children again and restore the checked change listeners.
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof CompoundButton)) continue;

                Object tag = child.getTag(R.id.remote_checked_change_listener_tag);
                if (!(tag instanceof OnCheckedChangeListener)) continue;

                ((CompoundButton) child).setOnCheckedChangeListener((OnCheckedChangeListener) tag);
            }
        }

        @Override
        public int getActionTag() {
            return SET_RADIO_GROUP_CHECKED;
        }
    }

    private static class SetViewOutlinePreferredRadiusAction extends Action {

        private final boolean mIsDimen;
        private final int mValue;

        SetViewOutlinePreferredRadiusAction(@IdRes int viewId, @DimenRes int dimenResId) {
            this.viewId = viewId;
            this.mIsDimen = true;
            this.mValue = dimenResId;
        }

        SetViewOutlinePreferredRadiusAction(
                @IdRes int viewId, float radius, @ComplexDimensionUnit int units) {
            this.viewId = viewId;
            this.mIsDimen = false;
            this.mValue = TypedValue.createComplexDimension(radius, units);

        }

        SetViewOutlinePreferredRadiusAction(Parcel in) {
            viewId = in.readInt();
            mIsDimen = in.readBoolean();
            mValue = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeBoolean(mIsDimen);
            dest.writeInt(mValue);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, InteractionHandler handler,
                ColorResources colorResources) throws ActionException {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            float radius;
            if (mIsDimen) {
                radius = mValue == 0 ? 0 : target.getResources().getDimension(mValue);
            } else {
                radius = TypedValue.complexToDimensionPixelSize(mValue,
                        target.getResources().getDisplayMetrics());
            }
            target.setOutlineProvider(new RemoteViewOutlineProvider(radius));
        }

        @Override
        public int getActionTag() {
            return SET_VIEW_OUTLINE_RADIUS_TAG;
        }
    }

    /**
     * OutlineProvider for a view with a radius set by
     * {@link #setViewOutlinePreferredRadius(int, float, int)}.
     */
    public static final class RemoteViewOutlineProvider extends ViewOutlineProvider {

        private final float mRadius;

        public RemoteViewOutlineProvider(float radius) {
            mRadius = radius;
        }

        /** Returns the corner radius used when providing the view outline. */
        public float getRadius() {
            return mRadius;
        }

        @Override
        public void getOutline(@NonNull View view, @NonNull Outline outline) {
            outline.setRoundRect(
                    0 /*left*/,
                    0 /* top */,
                    view.getWidth() /* right */,
                    view.getHeight() /* bottom */,
                    mRadius);
        }
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param packageName Name of the package that contains the layout resource
     * @param layoutId The id of the layout resource
     */
    public RemoteViews(String packageName, int layoutId) {
        this(getApplicationInfo(packageName, UserHandle.myUserId()), layoutId);
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param packageName Name of the package that contains the layout resource.
     * @param userId The user under which the package is running.
     * @param layoutId The id of the layout resource.
     *
     * @hide
     */
    public RemoteViews(String packageName, int userId, @LayoutRes int layoutId) {
        this(getApplicationInfo(packageName, userId), layoutId);
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param application The application whose content is shown by the views.
     * @param layoutId The id of the layout resource.
     *
     * @hide
     */
    protected RemoteViews(ApplicationInfo application, @LayoutRes int layoutId) {
        mApplication = application;
        mLayoutId = layoutId;
        mBitmapCache = new BitmapCache();
        mClassCookies = null;
    }

    private boolean hasMultipleLayouts() {
        return hasLandscapeAndPortraitLayouts() || hasSizedRemoteViews();
    }

    private boolean hasLandscapeAndPortraitLayouts() {
        return (mLandscape != null) && (mPortrait != null);
    }

    private boolean hasSizedRemoteViews() {
        return mSizedRemoteViews != null;
    }

    private @Nullable PointF getIdealSize() {
        return mIdealSize;
    }

    private void setIdealSize(@Nullable PointF size) {
        mIdealSize = size;
    }

    /**
     * Finds the smallest view in {@code mSizedRemoteViews}.
     * This method must not be called if {@code mSizedRemoteViews} is null.
     */
    private RemoteViews findSmallestRemoteView() {
        return mSizedRemoteViews.get(mSizedRemoteViews.size() - 1);
    }

    /**
     * Create a new RemoteViews object that will inflate as the specified
     * landspace or portrait RemoteViews, depending on the current configuration.
     *
     * @param landscape The RemoteViews to inflate in landscape configuration
     * @param portrait The RemoteViews to inflate in portrait configuration
     * @throws IllegalArgumentException if either landscape or portrait are null or if they are
     *   not from the same application
     */
    public RemoteViews(RemoteViews landscape, RemoteViews portrait) {
        if (landscape == null || portrait == null) {
            throw new IllegalArgumentException("Both RemoteViews must be non-null");
        }
        if (!landscape.hasSameAppInfo(portrait.mApplication)) {
            throw new IllegalArgumentException(
                    "Both RemoteViews must share the same package and user");
        }
        mApplication = portrait.mApplication;
        mLayoutId = portrait.mLayoutId;
        mLightBackgroundLayoutId = portrait.mLightBackgroundLayoutId;

        mLandscape = landscape;
        mPortrait = portrait;

        mBitmapCache = new BitmapCache();
        configureRemoteViewsAsChild(landscape);
        configureRemoteViewsAsChild(portrait);

        mClassCookies = (portrait.mClassCookies != null)
                ? portrait.mClassCookies : landscape.mClassCookies;
    }

    /**
     * Create a new RemoteViews object that will inflate the layout with the closest size
     * specification.
     *
     * The default remote views in that case is always the smallest one provided.
     *
     * @param remoteViews Mapping of size to layout.
     * @throws IllegalArgumentException if the map is empty, there are more than
     *   MAX_INIT_VIEW_COUNT layouts or the remote views are not all from the same application.
     */
    public RemoteViews(@NonNull Map<PointF, RemoteViews> remoteViews) {
        if (remoteViews.isEmpty()) {
            throw new IllegalArgumentException("The set of RemoteViews cannot be empty");
        }
        if (remoteViews.size() > MAX_INIT_VIEW_COUNT) {
            throw new IllegalArgumentException("Too many RemoteViews in constructor");
        }
        if (remoteViews.size() == 1) {
            initializeFrom(remoteViews.values().iterator().next());
            return;
        }
        mBitmapCache = new BitmapCache();
        mClassCookies = initializeSizedRemoteViews(
                remoteViews.entrySet().stream().map(
                        entry -> {
                            entry.getValue().setIdealSize(entry.getKey());
                            return entry.getValue();
                        }
                ).iterator()
        );

        RemoteViews smallestView = findSmallestRemoteView();
        mApplication = smallestView.mApplication;
        mLayoutId = smallestView.mLayoutId;
        mLightBackgroundLayoutId = smallestView.mLightBackgroundLayoutId;
    }

    // Initialize mSizedRemoteViews and return the class cookies.
    private Map<Class, Object> initializeSizedRemoteViews(Iterator<RemoteViews> remoteViews) {
        List<RemoteViews> sizedRemoteViews = new ArrayList<>();
        Map<Class, Object> classCookies = null;
        float viewArea = Float.MAX_VALUE;
        RemoteViews smallestView = null;
        while (remoteViews.hasNext()) {
            RemoteViews view = remoteViews.next();
            PointF size = view.getIdealSize();
            float newViewArea = size.x * size.y;
            if (smallestView != null && !view.hasSameAppInfo(smallestView.mApplication)) {
                throw new IllegalArgumentException(
                        "All RemoteViews must share the same package and user");
            }
            if (smallestView == null || newViewArea < viewArea) {
                if (smallestView != null) {
                    sizedRemoteViews.add(smallestView);
                }
                viewArea = newViewArea;
                smallestView = view;
            } else {
                sizedRemoteViews.add(view);
            }
            configureRemoteViewsAsChild(view);
            view.setIdealSize(size);
            if (classCookies == null) {
                classCookies = view.mClassCookies;
            }
        }
        sizedRemoteViews.add(smallestView);
        mSizedRemoteViews = sizedRemoteViews;
        return classCookies;
    }

    /**
     * Creates a copy of another RemoteViews.
     */
    public RemoteViews(RemoteViews src) {
        initializeFrom(src);
    }

    private void initializeFrom(RemoteViews src) {
        mBitmapCache = src.mBitmapCache;
        mApplication = src.mApplication;
        mIsRoot = src.mIsRoot;
        mLayoutId = src.mLayoutId;
        mLightBackgroundLayoutId = src.mLightBackgroundLayoutId;
        mApplyFlags = src.mApplyFlags;
        mClassCookies = src.mClassCookies;
        mIdealSize = src.mIdealSize;

        if (src.hasLandscapeAndPortraitLayouts()) {
            mLandscape = new RemoteViews(src.mLandscape);
            mPortrait = new RemoteViews(src.mPortrait);
        }

        if (src.hasSizedRemoteViews()) {
            mSizedRemoteViews = new ArrayList<>(src.mSizedRemoteViews.size());
            for (RemoteViews srcView : src.mSizedRemoteViews) {
                mSizedRemoteViews.add(new RemoteViews(srcView));
            }
        }

        if (src.mActions != null) {
            Parcel p = Parcel.obtain();
            p.putClassCookies(mClassCookies);
            src.writeActionsToParcel(p);
            p.setDataPosition(0);
            // Since src is already in memory, we do not care about stack overflow as it has
            // already been read once.
            readActionsFromParcel(p, 0);
            p.recycle();
        }

        // Now that everything is initialized and duplicated, setting a new BitmapCache will
        // re-initialize the cache.
        setBitmapCache(new BitmapCache());
    }

    /**
     * Reads a RemoteViews object from a parcel.
     *
     * @param parcel
     */
    public RemoteViews(Parcel parcel) {
        this(parcel, null, null, 0, null);
    }

    private RemoteViews(Parcel parcel, BitmapCache bitmapCache, ApplicationInfo info, int depth,
            Map<Class, Object> classCookies) {
        if (depth > MAX_NESTED_VIEWS
                && (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID)) {
            throw new IllegalArgumentException("Too many nested views.");
        }
        depth++;

        int mode = parcel.readInt();

        // We only store a bitmap cache in the root of the RemoteViews.
        if (bitmapCache == null) {
            mBitmapCache = new BitmapCache(parcel);
            // Store the class cookies such that they are available when we clone this RemoteView.
            mClassCookies = parcel.copyClassCookies();
        } else {
            setBitmapCache(bitmapCache);
            mClassCookies = classCookies;
            setNotRoot();
        }

        if (mode == MODE_NORMAL) {
            mApplication = parcel.readInt() == 0 ? info :
                    ApplicationInfo.CREATOR.createFromParcel(parcel);
            mIdealSize = parcel.readInt() == 0 ? null : PointF.CREATOR.createFromParcel(parcel);
            mLayoutId = parcel.readInt();
            mLightBackgroundLayoutId = parcel.readInt();

            readActionsFromParcel(parcel, depth);
        } else if (mode == MODE_HAS_SIZED_REMOTEVIEWS) {
            int numViews = parcel.readInt();
            if (numViews > MAX_INIT_VIEW_COUNT) {
                throw new IllegalArgumentException(
                        "Too many views in mapping from size to RemoteViews.");
            }
            List<RemoteViews> remoteViews = new ArrayList<>(numViews);
            for (int i = 0; i < numViews; i++) {
                RemoteViews view = new RemoteViews(parcel, mBitmapCache, info, depth,
                        mClassCookies);
                info = view.mApplication;
                remoteViews.add(view);
            }
            initializeSizedRemoteViews(remoteViews.iterator());
            RemoteViews smallestView = findSmallestRemoteView();
            mApplication = smallestView.mApplication;
            mLayoutId = smallestView.mLayoutId;
            mLightBackgroundLayoutId = smallestView.mLightBackgroundLayoutId;
        } else {
            // MODE_HAS_LANDSCAPE_AND_PORTRAIT
            mLandscape = new RemoteViews(parcel, mBitmapCache, info, depth, mClassCookies);
            mPortrait = new RemoteViews(parcel, mBitmapCache, mLandscape.mApplication, depth,
                    mClassCookies);
            mApplication = mPortrait.mApplication;
            mLayoutId = mPortrait.mLayoutId;
            mLightBackgroundLayoutId = mPortrait.mLightBackgroundLayoutId;
        }
        mApplyFlags = parcel.readInt();
    }

    private void readActionsFromParcel(Parcel parcel, int depth) {
        int count = parcel.readInt();
        if (count > 0) {
            mActions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                mActions.add(getActionFromParcel(parcel, depth));
            }
        }
    }

    private Action getActionFromParcel(Parcel parcel, int depth) {
        int tag = parcel.readInt();
        switch (tag) {
            case SET_ON_CLICK_RESPONSE_TAG:
                return new SetOnClickResponse(parcel);
            case SET_DRAWABLE_TINT_TAG:
                return new SetDrawableTint(parcel);
            case REFLECTION_ACTION_TAG:
                return new ReflectionAction(parcel);
            case VIEW_GROUP_ACTION_ADD_TAG:
                return new ViewGroupActionAdd(parcel, mBitmapCache, mApplication, depth,
                        mClassCookies);
            case VIEW_GROUP_ACTION_REMOVE_TAG:
                return new ViewGroupActionRemove(parcel);
            case VIEW_CONTENT_NAVIGATION_TAG:
                return new ViewContentNavigation(parcel);
            case SET_EMPTY_VIEW_ACTION_TAG:
                return new SetEmptyView(parcel);
            case SET_PENDING_INTENT_TEMPLATE_TAG:
                return new SetPendingIntentTemplate(parcel);
            case SET_REMOTE_VIEW_ADAPTER_INTENT_TAG:
                return new SetRemoteViewsAdapterIntent(parcel);
            case TEXT_VIEW_DRAWABLE_ACTION_TAG:
                return new TextViewDrawableAction(parcel);
            case TEXT_VIEW_SIZE_ACTION_TAG:
                return new TextViewSizeAction(parcel);
            case VIEW_PADDING_ACTION_TAG:
                return new ViewPaddingAction(parcel);
            case BITMAP_REFLECTION_ACTION_TAG:
                return new BitmapReflectionAction(parcel);
            case SET_REMOTE_VIEW_ADAPTER_LIST_TAG:
                return new SetRemoteViewsAdapterList(parcel);
            case SET_REMOTE_INPUTS_ACTION_TAG:
                return new SetRemoteInputsAction(parcel);
            case LAYOUT_PARAM_ACTION_TAG:
                return new LayoutParamAction(parcel);
            case OVERRIDE_TEXT_COLORS_TAG:
                return new OverrideTextColorsAction(parcel);
            case SET_RIPPLE_DRAWABLE_COLOR_TAG:
                return new SetRippleDrawableColor(parcel);
            case SET_INT_TAG_TAG:
                return new SetIntTagAction(parcel);
            case REMOVE_FROM_PARENT_ACTION_TAG:
                return new RemoveFromParentAction(parcel);
            case RESOURCE_REFLECTION_ACTION_TAG:
                return new ResourceReflectionAction(parcel);
            case COMPLEX_UNIT_DIMENSION_REFLECTION_ACTION_TAG:
                return new ComplexUnitDimensionReflectionAction(parcel);
            case SET_COMPOUND_BUTTON_CHECKED_TAG:
                return new SetCompoundButtonCheckedAction(parcel);
            case SET_RADIO_GROUP_CHECKED:
                return new SetRadioGroupCheckedAction(parcel);
            case SET_VIEW_OUTLINE_RADIUS_TAG:
                return new SetViewOutlinePreferredRadiusAction(parcel);
            case SET_ON_CHECKED_CHANGE_RESPONSE_TAG:
                return new SetOnCheckedChangeResponse(parcel);
            case NIGHT_MODE_REFLECTION_ACTION_TAG:
                return new NightModeReflectionAction(parcel);
            default:
                throw new ActionException("Tag " + tag + " not found");
        }
    };

    /**
     * Returns a deep copy of the RemoteViews object. The RemoteView may not be
     * attached to another RemoteView -- it must be the root of a hierarchy.
     *
     * @deprecated use {@link #RemoteViews(RemoteViews)} instead.
     * @throws IllegalStateException if this is not the root of a RemoteView
     *         hierarchy
     */
    @Override
    @Deprecated
    public RemoteViews clone() {
        Preconditions.checkState(mIsRoot, "RemoteView has been attached to another RemoteView. "
                + "May only clone the root of a RemoteView hierarchy.");

        return new RemoteViews(this);
    }

    public String getPackage() {
        return (mApplication != null) ? mApplication.packageName : null;
    }

    /**
     * Returns the layout id of the root layout associated with this RemoteViews. In the case
     * that the RemoteViews has both a landscape and portrait root, this will return the layout
     * id associated with the portrait layout.
     *
     * @return the layout id.
     */
    public int getLayoutId() {
        return hasFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT) && (mLightBackgroundLayoutId != 0)
                ? mLightBackgroundLayoutId : mLayoutId;
    }

    /**
     * Recursively sets BitmapCache in the hierarchy and update the bitmap ids.
     */
    private void setBitmapCache(BitmapCache bitmapCache) {
        mBitmapCache = bitmapCache;
        if (hasSizedRemoteViews()) {
            for (RemoteViews remoteView : mSizedRemoteViews) {
                remoteView.setBitmapCache(bitmapCache);
            }
        } else if (hasLandscapeAndPortraitLayouts()) {
            mLandscape.setBitmapCache(bitmapCache);
            mPortrait.setBitmapCache(bitmapCache);
        } else {
            if (mActions != null) {
                final int count = mActions.size();
                for (int i = 0; i < count; ++i) {
                    mActions.get(i).setBitmapCache(bitmapCache);
                }
            }
        }
    }

    /**
     * Returns an estimate of the bitmap heap memory usage for this RemoteViews.
     */
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int estimateMemoryUsage() {
        return mBitmapCache.getBitmapMemory();
    }

    /**
     * Add an action to be executed on the remote side when apply is called.
     *
     * @param a The action to add
     */
    private void addAction(Action a) {
        if (hasMultipleLayouts()) {
            throw new RuntimeException("RemoteViews specifying separate layouts for orientation"
                    + " or size cannot be modified. Instead, fully configure each layouts"
                    + " individually before constructing the combined layout.");
        }
        if (mActions == null) {
            mActions = new ArrayList<>();
        }
        mActions.add(a);
    }

    /**
     * Equivalent to calling {@link ViewGroup#addView(View)} after inflating the
     * given {@link RemoteViews}. This allows users to build "nested"
     * {@link RemoteViews}. In cases where consumers of {@link RemoteViews} may
     * recycle layouts, use {@link #removeAllViews(int)} to clear any existing
     * children.
     *
     * @param viewId The id of the parent {@link ViewGroup} to add child into.
     * @param nestedView {@link RemoteViews} that describes the child.
     */
    public void addView(@IdRes int viewId, RemoteViews nestedView) {
        // Clear all children when nested views omitted
        addAction(nestedView == null
                ? new ViewGroupActionRemove(viewId)
                : new ViewGroupActionAdd(viewId, nestedView));
    }

    /**
     * Equivalent to calling {@link ViewGroup#addView(View, int)} after inflating the
     * given {@link RemoteViews}.
     *
     * @param viewId The id of the parent {@link ViewGroup} to add the child into.
     * @param nestedView {@link RemoteViews} of the child to add.
     * @param index The position at which to add the child.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void addView(@IdRes int viewId, RemoteViews nestedView, int index) {
        addAction(new ViewGroupActionAdd(viewId, nestedView, index));
    }

    /**
     * Equivalent to calling {@link ViewGroup#removeAllViews()}.
     *
     * @param viewId The id of the parent {@link ViewGroup} to remove all
     *            children from.
     */
    public void removeAllViews(@IdRes int viewId) {
        addAction(new ViewGroupActionRemove(viewId));
    }

    /**
     * Removes all views in the {@link ViewGroup} specified by the {@code viewId} except for any
     * child that has the {@code viewIdToKeep} as its id.
     *
     * @param viewId The id of the parent {@link ViewGroup} to remove children from.
     * @param viewIdToKeep The id of a child that should not be removed.
     *
     * @hide
     */
    public void removeAllViewsExceptId(@IdRes int viewId, @IdRes int viewIdToKeep) {
        addAction(new ViewGroupActionRemove(viewId, viewIdToKeep));
    }

    /**
     * Removes the {@link View} specified by the {@code viewId} from its parent {@link ViewManager}.
     * This will do nothing if the viewId specifies the root view of this RemoteViews.
     *
     * @param viewId The id of the {@link View} to remove from its parent.
     *
     * @hide
     */
    public void removeFromParent(@IdRes int viewId) {
        addAction(new RemoveFromParentAction(viewId));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#showNext()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showNext()}
     */
    public void showNext(@IdRes int viewId) {
        addAction(new ViewContentNavigation(viewId, true /* next */));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#showPrevious()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showPrevious()}
     */
    public void showPrevious(@IdRes int viewId) {
        addAction(new ViewContentNavigation(viewId, false /* next */));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#setDisplayedChild(int)}
     *
     * @param viewId The id of the view on which to call
     *               {@link AdapterViewAnimator#setDisplayedChild(int)}
     */
    public void setDisplayedChild(@IdRes int viewId, int childIndex) {
        setInt(viewId, "setDisplayedChild", childIndex);
    }

    /**
     * Equivalent to calling {@link View#setVisibility(int)}
     *
     * @param viewId The id of the view whose visibility should change
     * @param visibility The new visibility for the view
     */
    public void setViewVisibility(@IdRes int viewId, @View.Visibility int visibility) {
        setInt(viewId, "setVisibility", visibility);
    }

    /**
     * Equivalent to calling {@link TextView#setText(CharSequence)}
     *
     * @param viewId The id of the view whose text should change
     * @param text The new text for the view
     */
    public void setTextViewText(@IdRes int viewId, CharSequence text) {
        setCharSequence(viewId, "setText", text);
    }

    /**
     * Equivalent to calling {@link TextView#setTextSize(int, float)}
     *
     * @param viewId The id of the view whose text size should change
     * @param units The units of size (e.g. COMPLEX_UNIT_SP)
     * @param size The size of the text
     */
    public void setTextViewTextSize(@IdRes int viewId, int units, float size) {
        addAction(new TextViewSizeAction(viewId, units, size));
    }

    /**
     * Equivalent to calling
     * {@link TextView#setCompoundDrawablesWithIntrinsicBounds(int, int, int, int)}.
     *
     * @param viewId The id of the view whose text should change
     * @param left The id of a drawable to place to the left of the text, or 0
     * @param top The id of a drawable to place above the text, or 0
     * @param right The id of a drawable to place to the right of the text, or 0
     * @param bottom The id of a drawable to place below the text, or 0
     */
    public void setTextViewCompoundDrawables(@IdRes int viewId, @DrawableRes int left,
            @DrawableRes int top, @DrawableRes int right, @DrawableRes int bottom) {
        addAction(new TextViewDrawableAction(viewId, false, left, top, right, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesRelativeWithIntrinsicBounds(int, int, int, int)}.
     *
     * @param viewId The id of the view whose text should change
     * @param start The id of a drawable to place before the text (relative to the
     * layout direction), or 0
     * @param top The id of a drawable to place above the text, or 0
     * @param end The id of a drawable to place after the text, or 0
     * @param bottom The id of a drawable to place below the text, or 0
     */
    public void setTextViewCompoundDrawablesRelative(@IdRes int viewId, @DrawableRes int start,
            @DrawableRes int top, @DrawableRes int end, @DrawableRes int bottom) {
        addAction(new TextViewDrawableAction(viewId, true, start, top, end, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesWithIntrinsicBounds(Drawable, Drawable, Drawable, Drawable)}
     * using the drawables yielded by {@link Icon#loadDrawable(Context)}.
     *
     * @param viewId The id of the view whose text should change
     * @param left an Icon to place to the left of the text, or 0
     * @param top an Icon to place above the text, or 0
     * @param right an Icon to place to the right of the text, or 0
     * @param bottom an Icon to place below the text, or 0
     *
     * @hide
     */
    public void setTextViewCompoundDrawables(@IdRes int viewId,
            Icon left, Icon top, Icon right, Icon bottom) {
        addAction(new TextViewDrawableAction(viewId, false, left, top, right, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable, Drawable, Drawable, Drawable)}
     * using the drawables yielded by {@link Icon#loadDrawable(Context)}.
     *
     * @param viewId The id of the view whose text should change
     * @param start an Icon to place before the text (relative to the
     * layout direction), or 0
     * @param top an Icon to place above the text, or 0
     * @param end an Icon to place after the text, or 0
     * @param bottom an Icon to place below the text, or 0
     *
     * @hide
     */
    public void setTextViewCompoundDrawablesRelative(@IdRes int viewId,
            Icon start, Icon top, Icon end, Icon bottom) {
        addAction(new TextViewDrawableAction(viewId, true, start, top, end, bottom));
    }

    /**
     * Equivalent to calling {@link ImageView#setImageResource(int)}
     *
     * @param viewId The id of the view whose drawable should change
     * @param srcId The new resource id for the drawable
     */
    public void setImageViewResource(@IdRes int viewId, @DrawableRes int srcId) {
        setInt(viewId, "setImageResource", srcId);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageURI(Uri)}
     *
     * @param viewId The id of the view whose drawable should change
     * @param uri The Uri for the image
     */
    public void setImageViewUri(@IdRes int viewId, Uri uri) {
        setUri(viewId, "setImageURI", uri);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageBitmap(Bitmap)}
     *
     * @param viewId The id of the view whose bitmap should change
     * @param bitmap The new Bitmap for the drawable
     */
    public void setImageViewBitmap(@IdRes int viewId, Bitmap bitmap) {
        setBitmap(viewId, "setImageBitmap", bitmap);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageIcon(Icon)}
     *
     * @param viewId The id of the view whose bitmap should change
     * @param icon The new Icon for the ImageView
     */
    public void setImageViewIcon(@IdRes int viewId, Icon icon) {
        setIcon(viewId, "setImageIcon", icon);
    }

    /**
     * Equivalent to calling {@link AdapterView#setEmptyView(View)}
     *
     * @param viewId The id of the view on which to set the empty view
     * @param emptyViewId The view id of the empty view
     */
    public void setEmptyView(@IdRes int viewId, @IdRes int emptyViewId) {
        addAction(new SetEmptyView(viewId, emptyViewId));
    }

    /**
     * Equivalent to calling {@link Chronometer#setBase Chronometer.setBase},
     * {@link Chronometer#setFormat Chronometer.setFormat},
     * and {@link Chronometer#start Chronometer.start()} or
     * {@link Chronometer#stop Chronometer.stop()}.
     *
     * @param viewId The id of the {@link Chronometer} to change
     * @param base The time at which the timer would have read 0:00.  This
     *             time should be based off of
     *             {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()}.
     * @param format The Chronometer format string, or null to
     *               simply display the timer value.
     * @param started True if you want the clock to be started, false if not.
     *
     * @see #setChronometerCountDown(int, boolean)
     */
    public void setChronometer(@IdRes int viewId, long base, String format, boolean started) {
        setLong(viewId, "setBase", base);
        setString(viewId, "setFormat", format);
        setBoolean(viewId, "setStarted", started);
    }

    /**
     * Equivalent to calling {@link Chronometer#setCountDown(boolean) Chronometer.setCountDown} on
     * the chronometer with the given viewId.
     *
     * @param viewId The id of the {@link Chronometer} to change
     * @param isCountDown True if you want the chronometer to count down to base instead of
     *                    counting up.
     */
    public void setChronometerCountDown(@IdRes int viewId, boolean isCountDown) {
        setBoolean(viewId, "setCountDown", isCountDown);
    }

    /**
     * Equivalent to calling {@link ProgressBar#setMax ProgressBar.setMax},
     * {@link ProgressBar#setProgress ProgressBar.setProgress}, and
     * {@link ProgressBar#setIndeterminate ProgressBar.setIndeterminate}
     *
     * If indeterminate is true, then the values for max and progress are ignored.
     *
     * @param viewId The id of the {@link ProgressBar} to change
     * @param max The 100% value for the progress bar
     * @param progress The current value of the progress bar.
     * @param indeterminate True if the progress bar is indeterminate,
     *                false if not.
     */
    public void setProgressBar(@IdRes int viewId, int max, int progress,
            boolean indeterminate) {
        setBoolean(viewId, "setIndeterminate", indeterminate);
        if (!indeterminate) {
            setInt(viewId, "setMax", max);
            setInt(viewId, "setProgress", progress);
        }
    }

    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}. The source bounds
     * ({@link Intent#getSourceBounds()}) of the intent will be set to the bounds of the clicked
     * view in screen space.
     * Note that any activity options associated with the mPendingIntent may get overridden
     * before starting the intent.
     *
     * When setting the on-click action of items within collections (eg. {@link ListView},
     * {@link StackView} etc.), this method will not work. Instead, use {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent)} in conjunction with
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     *
     * @param viewId The id of the view that will trigger the {@link PendingIntent} when clicked
     * @param pendingIntent The {@link PendingIntent} to send when user clicks
     */
    public void setOnClickPendingIntent(@IdRes int viewId, PendingIntent pendingIntent) {
        setOnClickResponse(viewId, RemoteResponse.fromPendingIntent(pendingIntent));
    }

    /**
     * Equivalent of calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link RemoteResponse}.
     *
     * @param viewId The id of the view that will trigger the {@link RemoteResponse} when clicked
     * @param response The {@link RemoteResponse} to send when user clicks
     */
    public void setOnClickResponse(@IdRes int viewId, @NonNull RemoteResponse response) {
        addAction(new SetOnClickResponse(viewId, response));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not recommended. Instead
     * this method should be used to set a single PendingIntent template on the collection, and
     * individual items can differentiate their on-click behavior using
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     *
     * @param viewId The id of the collection who's children will use this PendingIntent template
     *          when clicked
     * @param pendingIntentTemplate The {@link PendingIntent} to be combined with extras specified
     *          by a child of viewId and executed when that child is clicked
     */
    public void setPendingIntentTemplate(@IdRes int viewId, PendingIntent pendingIntentTemplate) {
        addAction(new SetPendingIntentTemplate(viewId, pendingIntentTemplate));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not recommended. Instead
     * a single PendingIntent template can be set on the collection, see {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent)}, and the individual on-click
     * action of a given item can be distinguished by setting a fillInIntent on that item. The
     * fillInIntent is then combined with the PendingIntent template in order to determine the final
     * intent which will be executed when the item is clicked. This works as follows: any fields
     * which are left blank in the PendingIntent template, but are provided by the fillInIntent
     * will be overwritten, and the resulting PendingIntent will be used. The rest
     * of the PendingIntent template will then be filled in with the associated fields that are
     * set in fillInIntent. See {@link Intent#fillIn(Intent, int)} for more details.
     *
     * @param viewId The id of the view on which to set the fillInIntent
     * @param fillInIntent The intent which will be combined with the parent's PendingIntent
     *        in order to determine the on-click behavior of the view specified by viewId
     */
    public void setOnClickFillInIntent(@IdRes int viewId, Intent fillInIntent) {
        setOnClickResponse(viewId, RemoteResponse.fromFillInIntent(fillInIntent));
    }

    /**
     * Equivalent to calling
     * {@link android.widget.CompoundButton#setOnCheckedChangeListener(
     * android.widget.CompoundButton.OnCheckedChangeListener)}
     * to launch the provided {@link RemoteResponse}.
     *
     * The intent will be filled with the current checked state of the view at the key
     * {@link #EXTRA_CHECKED}.
     *
     * The {@link RemoteResponse} will not be launched in response to check changes arising from
     * {@link #setCompoundButtonChecked(int, boolean)} or {@link #setRadioGroupChecked(int, int)}
     * usages.
     *
     * The {@link RemoteResponse} must be created using
     * {@link RemoteResponse#fromFillInIntent(Intent)} in conjunction with
     * {@link RemoteViews#setPendingIntentTemplate(int, PendingIntent)} for items inside
     * collections (eg. {@link ListView}, {@link StackView} etc.).
     *
     * Otherwise, create the {@link RemoteResponse} using
     * {@link RemoteResponse#fromPendingIntent(PendingIntent)}.
     *
     * @param viewId The id of the view that will trigger the {@link PendingIntent} when checked
     *               state changes.
     * @param response The {@link RemoteResponse} to send when the checked state changes.
     */
    public void setOnCheckedChangeResponse(
            @IdRes int viewId,
            @NonNull RemoteResponse response) {
        addAction(
                new SetOnCheckedChangeResponse(
                        viewId,
                        response.setInteractionType(
                                RemoteResponse.INTERACTION_TYPE_CHECKED_CHANGE)));
    }

    /**
     * @hide
     * Equivalent to calling
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * on the {@link Drawable} of a given view.
     * <p>
     *
     * @param viewId The id of the view that contains the target
     *            {@link Drawable}
     * @param targetBackground If true, apply these parameters to the
     *            {@link Drawable} returned by
     *            {@link android.view.View#getBackground()}. Otherwise, assume
     *            the target view is an {@link ImageView} and apply them to
     *            {@link ImageView#getDrawable()}.
     * @param colorFilter Specify a color for a
     *            {@link android.graphics.ColorFilter} for this drawable. This will be ignored if
     *            {@code mode} is {@code null}.
     * @param mode Specify a PorterDuff mode for this drawable, or null to leave
     *            unchanged.
     */
    public void setDrawableTint(@IdRes int viewId, boolean targetBackground,
            @ColorInt int colorFilter, @NonNull PorterDuff.Mode mode) {
        addAction(new SetDrawableTint(viewId, targetBackground, colorFilter, mode));
    }

    /**
     * @hide
     * Equivalent to calling
     * {@link RippleDrawable#setColor(ColorStateList)} on the {@link Drawable} of a given view,
     * assuming it's a {@link RippleDrawable}.
     * <p>
     *
     * @param viewId The id of the view that contains the target
     *            {@link RippleDrawable}
     * @param colorStateList Specify a color for a
     *            {@link ColorStateList} for this drawable.
     */
    public void setRippleDrawableColor(@IdRes int viewId, ColorStateList colorStateList) {
        addAction(new SetRippleDrawableColor(viewId, colorStateList));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setProgressTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressTintList(@IdRes int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setProgressTintList",
                BaseReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setProgressBackgroundTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressBackgroundTintList(@IdRes int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setProgressBackgroundTintList",
                BaseReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setIndeterminateTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressIndeterminateTintList(@IdRes int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setIndeterminateTintList",
                BaseReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * Equivalent to calling {@link android.widget.TextView#setTextColor(int)}.
     *
     * @param viewId The id of the view whose text color should change
     * @param color Sets the text color for all the states (normal, selected,
     *            focused) to be this color.
     */
    public void setTextColor(@IdRes int viewId, @ColorInt int color) {
        setInt(viewId, "setTextColor", color);
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.TextView#setTextColor(ColorStateList)}.
     *
     * @param viewId The id of the view whose text color should change
     * @param colors the text colors to set
     */
    public void setTextColor(@IdRes int viewId, ColorStateList colors) {
        addAction(new ReflectionAction(viewId, "setTextColor",
                BaseReflectionAction.COLOR_STATE_LIST, colors));
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     *
     * @param appWidgetId The id of the app widget which contains the specified view. (This
     *      parameter is ignored in this deprecated method)
     * @param viewId The id of the {@link AdapterView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     * @deprecated This method has been deprecated. See
     *      {@link android.widget.RemoteViews#setRemoteAdapter(int, Intent)}
     */
    @Deprecated
    public void setRemoteAdapter(int appWidgetId, @IdRes int viewId, Intent intent) {
        setRemoteAdapter(viewId, intent);
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     * Can only be used for App Widgets.
     *
     * @param viewId The id of the {@link AdapterView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     */
    public void setRemoteAdapter(@IdRes int viewId, Intent intent) {
        addAction(new SetRemoteViewsAdapterIntent(viewId, intent));
    }

    /**
     * Creates a simple Adapter for the viewId specified. The viewId must point to an AdapterView,
     * ie. {@link ListView}, {@link GridView}, {@link StackView} or {@link AdapterViewAnimator}.
     * This is a simpler but less flexible approach to populating collection widgets. Its use is
     * encouraged for most scenarios, as long as the total memory within the list of RemoteViews
     * is relatively small (ie. doesn't contain large or numerous Bitmaps, see {@link
     * RemoteViews#setImageViewBitmap}). In the case of numerous images, the use of API is still
     * possible by setting image URIs instead of Bitmaps, see {@link RemoteViews#setImageViewUri}.
     *
     * This API is supported in the compatibility library for previous API levels, see
     * RemoteViewsCompat.
     *
     * @param viewId The id of the {@link AdapterView}
     * @param list The list of RemoteViews which will populate the view specified by viewId.
     * @param viewTypeCount The maximum number of unique layout id's used to construct the list of
     *      RemoteViews. This count cannot change during the life-cycle of a given widget, so this
     *      parameter should account for the maximum possible number of types that may appear in the
     *      See {@link Adapter#getViewTypeCount()}.
     *
     * @hide
     * @deprecated this appears to have no users outside of UnsupportedAppUsage?
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Deprecated
    public void setRemoteAdapter(@IdRes int viewId, ArrayList<RemoteViews> list,
            int viewTypeCount) {
        addAction(new SetRemoteViewsAdapterList(viewId, list, viewTypeCount));
    }

    /**
     * Equivalent to calling {@link ListView#smoothScrollToPosition(int)}.
     *
     * @param viewId The id of the view to change
     * @param position Scroll to this adapter position
     */
    public void setScrollPosition(@IdRes int viewId, int position) {
        setInt(viewId, "smoothScrollToPosition", position);
    }

    /**
     * Equivalent to calling {@link ListView#smoothScrollByOffset(int)}.
     *
     * @param viewId The id of the view to change
     * @param offset Scroll by this adapter position offset
     */
    public void setRelativeScrollPosition(@IdRes int viewId, int offset) {
        setInt(viewId, "smoothScrollByOffset", offset);
    }

    /**
     * Equivalent to calling {@link android.view.View#setPadding(int, int, int, int)}.
     *
     * @param viewId The id of the view to change
     * @param left the left padding in pixels
     * @param top the top padding in pixels
     * @param right the right padding in pixels
     * @param bottom the bottom padding in pixels
     */
    public void setViewPadding(@IdRes int viewId,
            @Px int left, @Px int top, @Px int right, @Px int bottom) {
        addAction(new ViewPaddingAction(viewId, left, top, right, bottom));
    }

    /**
     * Equivalent to calling {@link MarginLayoutParams#setMarginEnd}.
     * Only works if the {@link View#getLayoutParams()} supports margins.
     *
     * @param viewId The id of the view to change
     * @param type The margin being set e.g. {@link #MARGIN_END}
     * @param dimen a dimension resource to apply to the margin, or 0 to clear the margin.
     */
    public void setViewLayoutMarginDimen(@IdRes int viewId, @MarginType int type,
            @DimenRes int dimen) {
        addAction(new LayoutParamAction(viewId, type, dimen));
    }

    /**
     * Equivalent to calling {@link MarginLayoutParams#setMarginEnd}.
     * Only works if the {@link View#getLayoutParams()} supports margins.
     *
     * <p>NOTE: It is recommended to use {@link TypedValue#COMPLEX_UNIT_PX} only for 0.
     * Setting margins in pixels will behave poorly when the RemoteViews object is used on a
     * display with a different density.
     *
     * @param viewId The id of the view to change
     * @param type The margin being set e.g. {@link #MARGIN_END}
     * @param value a value for the margin the given units.
     * @param units The unit type of the value e.g. {@link TypedValue#COMPLEX_UNIT_DIP}
     */
    public void setViewLayoutMargin(@IdRes int viewId, @MarginType int type, float value,
            @ComplexDimensionUnit int units) {
        addAction(new LayoutParamAction(viewId, type, value, units));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.LayoutParams#width} except that you may
     * provide the value in any dimension units.
     *
     * <p>NOTE: It is recommended to use {@link TypedValue#COMPLEX_UNIT_PX} only for 0,
     * {@link ViewGroup.LayoutParams#WRAP_CONTENT}, or {@link ViewGroup.LayoutParams#MATCH_PARENT}.
     * Setting actual sizes in pixels will behave poorly when the RemoteViews object is used on a
     * display with a different density.
     *
     * @param width Width of the view in the given units
     * @param units The unit type of the value e.g. {@link TypedValue#COMPLEX_UNIT_DIP}
     */
    public void setViewLayoutWidth(@IdRes int viewId, float width,
            @ComplexDimensionUnit int units) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_WIDTH, width, units));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.LayoutParams#width} with
     * the result of {@link Resources#getDimensionPixelSize(int)}.
     *
     * @param widthDimen the dimension resource for the view's width
     */
    public void setViewLayoutWidthDimen(@IdRes int viewId, @DimenRes int widthDimen) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_WIDTH, widthDimen));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.LayoutParams#height} except that you may
     * provide the value in any dimension units.
     *
     * <p>NOTE: It is recommended to use {@link TypedValue#COMPLEX_UNIT_PX} only for 0,
     * {@link ViewGroup.LayoutParams#WRAP_CONTENT}, or {@link ViewGroup.LayoutParams#MATCH_PARENT}.
     * Setting actual sizes in pixels will behave poorly when the RemoteViews object is used on a
     * display with a different density.
     *
     * @param height height of the view in the given units
     * @param units The unit type of the value e.g. {@link TypedValue#COMPLEX_UNIT_DIP}
     */
    public void setViewLayoutHeight(@IdRes int viewId, float height,
            @ComplexDimensionUnit int units) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_HEIGHT, height, units));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.LayoutParams#height} with
     * the result of {@link Resources#getDimensionPixelSize(int)}.
     *
     * @param heightDimen a dimen resource to read the height from.
     */
    public void setViewLayoutHeightDimen(@IdRes int viewId, @DimenRes int heightDimen) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_HEIGHT, heightDimen));
    }

    /**
     * Sets an OutlineProvider on the view whose corner radius is a dimension calculated using
     * {@link TypedValue#applyDimension(int, float, DisplayMetrics)}. This outline may change shape
     * during system transitions.
     *
     * <p>NOTE: It is recommended to use {@link TypedValue#COMPLEX_UNIT_PX} only for 0.
     * Setting margins in pixels will behave poorly when the RemoteViews object is used on a
     * display with a different density.
     */
    public void setViewOutlinePreferredRadius(
            @IdRes int viewId, float radius, @ComplexDimensionUnit int units) {
        addAction(new SetViewOutlinePreferredRadiusAction(viewId, radius, units));
    }

    /**
     * Sets an OutlineProvider on the view whose corner radius is a dimension resource with
     * {@code resId}. This outline may change shape during system transitions.
     */
    public void setViewOutlinePreferredRadiusDimen(@IdRes int viewId, @DimenRes int resId) {
        addAction(new SetViewOutlinePreferredRadiusAction(viewId, resId));
    }

    /**
     * Call a method taking one boolean on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBoolean(@IdRes int viewId, String methodName, boolean value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.BOOLEAN, value));
    }

    /**
     * Call a method taking one byte on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setByte(@IdRes int viewId, String methodName, byte value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.BYTE, value));
    }

    /**
     * Call a method taking one short on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setShort(@IdRes int viewId, String methodName, short value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.SHORT, value));
    }

    /**
     * Call a method taking one int on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setInt(@IdRes int viewId, String methodName, int value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.INT, value));
    }

    /**
     * Call a method taking one int, a size in pixels, on a view in the layout for this
     * RemoteViews.
     *
     * The dimension will be resolved from the resources at the time of inflation.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param dimenResource The resource to resolve and pass as argument to the method.
     */
    public void setIntDimen(@IdRes int viewId, @NonNull String methodName,
            @DimenRes int dimenResource) {
        addAction(new ResourceReflectionAction(viewId, methodName, BaseReflectionAction.INT,
                ResourceReflectionAction.DIMEN_RESOURCE, dimenResource));
    }

    /**
     * Call a method taking one int, a size in pixels, on a view in the layout for this
     * RemoteViews.
     *
     * The dimension will be resolved from the specified dimension at the time of inflation.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value of the dimension.
     * @param unit The unit in which the value is specified.
     */
    public void setIntDimen(@IdRes int viewId, @NonNull String methodName,
            float value, @ComplexDimensionUnit int unit) {
        addAction(new ComplexUnitDimensionReflectionAction(viewId, methodName, ReflectionAction.INT,
                value, unit));
    }

    /**
     * Call a method taking one int, a color, on a view in the layout for this RemoteViews.
     *
     * The ColorStateList will be resolved from the resources at the time of inflation.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param colorResource The resource to resolve and pass as argument to the method.
     */
    public void setColor(@IdRes int viewId, @NonNull String methodName,
            @ColorRes int colorResource) {
        addAction(new ResourceReflectionAction(viewId, methodName, BaseReflectionAction.INT,
                ResourceReflectionAction.COLOR_RESOURCE, colorResource));
    }

    /**
     * Call a method taking one int, a color, on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param notNight The value to pass to the method when the view's configuration is set to
     *                 {@link Configuration#UI_MODE_NIGHT_NO}
     * @param night The value to pass to the method when the view's configuration is set to
     *                 {@link Configuration#UI_MODE_NIGHT_YES}
     */
    public void setColorInt(
            @IdRes int viewId,
            @NonNull String methodName,
            @ColorInt int notNight,
            @ColorInt int night) {
        addAction(
                new NightModeReflectionAction(
                        viewId,
                        methodName,
                        BaseReflectionAction.INT,
                        notNight,
                        night));
    }


    /**
     * Call a method taking one ColorStateList on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setColorStateList(@IdRes int viewId, @NonNull String methodName,
            @Nullable ColorStateList value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.COLOR_STATE_LIST,
                value));
    }

    /**
     * Call a method taking one ColorStateList on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param notNight The value to pass to the method when the view's configuration is set to
     *                 {@link Configuration#UI_MODE_NIGHT_NO}
     * @param night The value to pass to the method when the view's configuration is set to
     *                 {@link Configuration#UI_MODE_NIGHT_YES}
     */
    public void setColorStateList(
            @IdRes int viewId,
            @NonNull String methodName,
            @Nullable ColorStateList notNight,
            @Nullable ColorStateList night) {
        addAction(
                new NightModeReflectionAction(
                        viewId,
                        methodName,
                        BaseReflectionAction.COLOR_STATE_LIST,
                        notNight,
                        night));
    }

    /**
     * Call a method taking one ColorStateList on a view in the layout for this RemoteViews.
     *
     * The ColorStateList will be resolved from the resources at the time of inflation.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param colorResource The resource to resolve and pass as argument to the method.
     */
    public void setColorStateList(@IdRes int viewId, @NonNull String methodName,
            @ColorRes int colorResource) {
        addAction(new ResourceReflectionAction(viewId, methodName,
                BaseReflectionAction.COLOR_STATE_LIST, ResourceReflectionAction.COLOR_RESOURCE,
                colorResource));
    }

    /**
     * Call a method taking one long on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setLong(@IdRes int viewId, String methodName, long value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.LONG, value));
    }

    /**
     * Call a method taking one float on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setFloat(@IdRes int viewId, String methodName, float value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.FLOAT, value));
    }

    /**
     * Call a method taking one float, a size in pixels, on a view in the layout for this
     * RemoteViews.
     *
     * The dimension will be resolved from the resources at the time of inflation.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param dimenResource The resource to resolve and pass as argument to the method.
     */
    public void setFloatDimen(@IdRes int viewId, @NonNull String methodName,
            @DimenRes int dimenResource) {
        addAction(new ResourceReflectionAction(viewId, methodName, BaseReflectionAction.FLOAT,
                ResourceReflectionAction.DIMEN_RESOURCE, dimenResource));
    }

    /**
     * Call a method taking one float, a size in pixels, on a view in the layout for this
     * RemoteViews.
     *
     * The dimension will be resolved from the specified dimension at the time of inflation.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value of the dimension.
     * @param unit The unit in which the value is specified.
     */
    public void setFloatDimen(@IdRes int viewId, @NonNull String methodName,
            float value, @ComplexDimensionUnit int unit) {
        addAction(
                new ComplexUnitDimensionReflectionAction(viewId, methodName, ReflectionAction.FLOAT,
                        value, unit));
    }

    /**
     * Call a method taking one double on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setDouble(@IdRes int viewId, String methodName, double value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.DOUBLE, value));
    }

    /**
     * Call a method taking one char on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setChar(@IdRes int viewId, String methodName, char value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.CHAR, value));
    }

    /**
     * Call a method taking one String on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setString(@IdRes int viewId, String methodName, String value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.STRING, value));
    }

    /**
     * Call a method taking one CharSequence on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setCharSequence(@IdRes int viewId, String methodName, CharSequence value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.CHAR_SEQUENCE,
                value));
    }

    /**
     * Call a method taking one CharSequence on a view in the layout for this RemoteViews.
     *
     * The CharSequence will be resolved from the resources at the time of inflation.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param stringResource The resource to resolve and pass as argument to the method.
     */
    public void setCharSequence(@IdRes int viewId, @NonNull String methodName,
            @StringRes int stringResource) {
        addAction(
                new ResourceReflectionAction(viewId, methodName, BaseReflectionAction.CHAR_SEQUENCE,
                        ResourceReflectionAction.STRING_RESOURCE, stringResource));
    }

    /**
     * Call a method taking one Uri on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setUri(@IdRes int viewId, String methodName, Uri value) {
        if (value != null) {
            // Resolve any filesystem path before sending remotely
            value = value.getCanonicalUri();
            if (StrictMode.vmFileUriExposureEnabled()) {
                value.checkFileUriExposed("RemoteViews.setUri()");
            }
        }
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.URI, value));
    }

    /**
     * Call a method taking one Bitmap on a view in the layout for this RemoteViews.
     * @more
     * <p class="note">The bitmap will be flattened into the parcel if this object is
     * sent across processes, so it may end up using a lot of memory, and may be fairly slow.</p>
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBitmap(@IdRes int viewId, String methodName, Bitmap value) {
        addAction(new BitmapReflectionAction(viewId, methodName, value));
    }

    /**
     * Call a method taking one BlendMode on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBlendMode(@IdRes int viewId, @NonNull String methodName,
            @Nullable BlendMode value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.BLEND_MODE, value));
    }

    /**
     * Call a method taking one Bundle on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBundle(@IdRes int viewId, String methodName, Bundle value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.BUNDLE, value));
    }

    /**
     * Call a method taking one Intent on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The {@link android.content.Intent} to pass the method.
     */
    public void setIntent(@IdRes int viewId, String methodName, Intent value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.INTENT, value));
    }

    /**
     * Call a method taking one Icon on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The {@link android.graphics.drawable.Icon} to pass the method.
     */
    public void setIcon(@IdRes int viewId, String methodName, Icon value) {
        addAction(new ReflectionAction(viewId, methodName, BaseReflectionAction.ICON, value));
    }

    /**
     * Call a method taking one Icon on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param notNight The value to pass to the method when the view's configuration is set to
     *                 {@link Configuration#UI_MODE_NIGHT_NO}
     * @param night The value to pass to the method when the view's configuration is set to
     *                 {@link Configuration#UI_MODE_NIGHT_YES}
     */
    public void setIcon(
            @IdRes int viewId,
            @NonNull String methodName,
            @Nullable Icon notNight,
            @Nullable Icon night) {
        addAction(
                new NightModeReflectionAction(
                        viewId,
                        methodName,
                        BaseReflectionAction.ICON,
                        notNight,
                        night));
    }

    /**
     * Equivalent to calling View.setContentDescription(CharSequence).
     *
     * @param viewId The id of the view whose content description should change.
     * @param contentDescription The new content description for the view.
     */
    public void setContentDescription(@IdRes int viewId, CharSequence contentDescription) {
        setCharSequence(viewId, "setContentDescription", contentDescription);
    }

    /**
     * Equivalent to calling {@link android.view.View#setAccessibilityTraversalBefore(int)}.
     *
     * @param viewId The id of the view whose before view in accessibility traversal to set.
     * @param nextId The id of the next in the accessibility traversal.
     **/
    public void setAccessibilityTraversalBefore(@IdRes int viewId, @IdRes int nextId) {
        setInt(viewId, "setAccessibilityTraversalBefore", nextId);
    }

    /**
     * Equivalent to calling {@link android.view.View#setAccessibilityTraversalAfter(int)}.
     *
     * @param viewId The id of the view whose after view in accessibility traversal to set.
     * @param nextId The id of the next in the accessibility traversal.
     **/
    public void setAccessibilityTraversalAfter(@IdRes int viewId, @IdRes int nextId) {
        setInt(viewId, "setAccessibilityTraversalAfter", nextId);
    }

    /**
     * Equivalent to calling {@link View#setLabelFor(int)}.
     *
     * @param viewId The id of the view whose property to set.
     * @param labeledId The id of a view for which this view serves as a label.
     */
    public void setLabelFor(@IdRes int viewId, @IdRes int labeledId) {
        setInt(viewId, "setLabelFor", labeledId);
    }

    /**
     * Equivalent to calling {@link android.widget.CompoundButton#setChecked(boolean)}.
     *
     * @param viewId The id of the view whose property to set.
     * @param checked true to check the button, false to uncheck it.
     */
    public void setCompoundButtonChecked(@IdRes int viewId, boolean checked) {
        addAction(new SetCompoundButtonCheckedAction(viewId, checked));
    }

    /**
     * Equivalent to calling {@link android.widget.RadioGroup#check(int)}.
     *
     * @param viewId The id of the view whose property to set.
     * @param checkedId The unique id of the radio button to select in the group.
     */
    public void setRadioGroupChecked(@IdRes int viewId, @IdRes int checkedId) {
        addAction(new SetRadioGroupCheckedAction(viewId, checkedId));
    }

    /**
     * Provides an alternate layout ID, which can be used to inflate this view. This layout will be
     * used by the host when the widgets displayed on a light-background where foreground elements
     * and text can safely draw using a dark color without any additional background protection.
     */
    public void setLightBackgroundLayoutId(@LayoutRes int layoutId) {
        mLightBackgroundLayoutId = layoutId;
    }

    /**
     * If this view supports dark text versions, creates a copy representing that version,
     * otherwise returns itself.
     * @hide
     */
    public RemoteViews getDarkTextViews() {
        if (hasFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT)) {
            return this;
        }

        try {
            addFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT);
            return new RemoteViews(this);
        } finally {
            mApplyFlags &= ~FLAG_USE_LIGHT_BACKGROUND_LAYOUT;
        }
    }

    private RemoteViews getRemoteViewsToApply(Context context) {
        if (hasLandscapeAndPortraitLayouts()) {
            int orientation = context.getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return mLandscape;
            }
            return mPortrait;
        }
        if (hasSizedRemoteViews()) {
            return findSmallestRemoteView();
        }
        return this;
    }

    /**
     * Returns the square distance between two points.
     *
     * This is particularly useful when we only care about the ordering of the distances.
     */
    private static float squareDistance(PointF p1, PointF p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        return dx * dx + dy * dy;
    }

    /**
     * Returns whether the layout fits in the space available to the widget.
     *
     * A layout fits on a widget if the widget size is known (i.e. not null) and both dimensions
     * are smaller than the ones of the widget, adding some padding to account for rounding errors.
     */
    private static boolean fitsIn(PointF sizeLayout, @Nullable PointF sizeWidget) {
        return sizeWidget != null && (Math.ceil(sizeWidget.x) + 1 > sizeLayout.x)
                && (Math.ceil(sizeWidget.y) + 1 > sizeLayout.y);
    }

    /**
     * Returns the most appropriate {@link RemoteViews} given the context and, if not null, the
     * size of the widget.
     *
     * If {@link RemoteViews#hasSizedRemoteViews()} returns true, the most appropriate view is
     * the one that fits in the widget (according to {@link RemoteViews#fitsIn}) and has the
     * diagonal the most similar to the widget. If no layout fits or the size of the widget is
     * not specified, the one with the smallest area will be chosen.
     */
    private RemoteViews getRemoteViewsToApply(@NonNull Context context,
            @Nullable PointF widgetSize) {
        if (!hasSizedRemoteViews()) {
            // If there isn't multiple remote views, fall back on the previous methods.
            return getRemoteViewsToApply(context);
        }
        // Find the better remote view
        RemoteViews bestFit = null;
        float bestSqDist = Float.MAX_VALUE;
        for (RemoteViews layout : mSizedRemoteViews) {
            PointF layoutSize = layout.getIdealSize();
            if (fitsIn(layoutSize, widgetSize)) {
                if (bestFit == null) {
                    bestFit = layout;
                    bestSqDist = squareDistance(layoutSize, widgetSize);
                } else {
                    float newSqDist = squareDistance(layoutSize, widgetSize);
                    if (newSqDist < bestSqDist) {
                        bestFit = layout;
                        bestSqDist = newSqDist;
                    }
                }
            }
        }
        if (bestFit == null) {
            Log.w(LOG_TAG, "Could not find a RemoteViews fitting the current size: " + widgetSize);
            return findSmallestRemoteView();
        }
        return bestFit;
    }


    /**
     * Inflates the view hierarchy represented by this object and applies
     * all of the actions.
     *
     * <p><strong>Caller beware: this may throw</strong>
     *
     * @param context Default context to use
     * @param parent Parent that the resulting view hierarchy will be attached to. This method
     * does <strong>not</strong> attach the hierarchy. The caller should do so when appropriate.
     * @return The inflated view hierarchy
     */
    public View apply(Context context, ViewGroup parent) {
        return apply(context, parent, null);
    }

    /** @hide */
    public View apply(Context context, ViewGroup parent, InteractionHandler handler) {
        return apply(context, parent, handler, null);
    }

    /** @hide */
    public View apply(@NonNull Context context, @NonNull ViewGroup parent,
            @Nullable InteractionHandler handler, @Nullable PointF size) {
        RemoteViews rvToApply = getRemoteViewsToApply(context, size);

        View result = inflateView(context, rvToApply, parent);
        rvToApply.performApply(result, parent, handler, null);
        return result;
    }

    /** @hide */
    public View applyWithTheme(@NonNull Context context, @NonNull ViewGroup parent,
            @Nullable InteractionHandler handler, @StyleRes int applyThemeResId) {
        return applyWithTheme(context, parent, handler, applyThemeResId, null);
    }

    /** @hide */
    public View applyWithTheme(@NonNull Context context, @NonNull ViewGroup parent,
            @Nullable InteractionHandler handler, @StyleRes int applyThemeResId,
            @Nullable PointF size) {
        RemoteViews rvToApply = getRemoteViewsToApply(context, size);

        View result = inflateView(context, rvToApply, parent, applyThemeResId, null);
        rvToApply.performApply(result, parent, handler, null);
        return result;
    }

    /** @hide */
    public View apply(Context context, ViewGroup parent, InteractionHandler handler,
            @NonNull PointF size, @Nullable ColorResources colorResources) {
        RemoteViews rvToApply = getRemoteViewsToApply(context, size);

        View result = inflateView(context, rvToApply, parent, 0, colorResources);
        rvToApply.performApply(result, parent, handler, colorResources);
        return result;
    }

    private View inflateView(Context context, RemoteViews rv, ViewGroup parent) {
        return inflateView(context, rv, parent, 0, null);
    }

    private View inflateView(Context context, RemoteViews rv, ViewGroup parent,
            @StyleRes int applyThemeResId, @Nullable ColorResources colorResources) {
        // RemoteViews may be built by an application installed in another
        // user. So build a context that loads resources from that user but
        // still returns the current users userId so settings like data / time formats
        // are loaded without requiring cross user persmissions.
        final Context contextForResources = getContextForResources(context);
        if (colorResources != null) {
            colorResources.apply(contextForResources);
        }
        Context inflationContext = new RemoteViewsContextWrapper(context, contextForResources);

        // If mApplyThemeResId is not given, Theme.DeviceDefault will be used.
        if (applyThemeResId != 0) {
            inflationContext = new ContextThemeWrapper(inflationContext, applyThemeResId);
        }
        LayoutInflater inflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Clone inflater so we load resources from correct context and
        // we don't add a filter to the static version returned by getSystemService.
        inflater = inflater.cloneInContext(inflationContext);
        inflater.setFilter(shouldUseStaticFilter() ? INFLATER_FILTER : this);
        View v = inflater.inflate(rv.getLayoutId(), parent, false);
        if (mViewId != View.NO_ID) {
            v.setId(mViewId);
        }
        v.setTagInternal(R.id.widget_frame, rv.getLayoutId());
        return v;
    }

    /**
     * A static filter is much lighter than RemoteViews itself. It's optimized here only for
     * RemoteVies class. Subclasses should always override this and return true if not overriding
     * {@link this#onLoadClass(Class)}.
     *
     * @hide
     */
    protected boolean shouldUseStaticFilter() {
        return this.getClass().equals(RemoteViews.class);
    }

    /**
     * Implement this interface to receive a callback when
     * {@link #applyAsync} or {@link #reapplyAsync} is finished.
     * @hide
     */
    public interface OnViewAppliedListener {
        /**
         * Callback when the RemoteView has finished inflating,
         * but no actions have been applied yet.
         */
        default void onViewInflated(View v) {};

        void onViewApplied(View v);

        void onError(Exception e);
    }

    /**
     * Applies the views asynchronously, moving as much of the task on the background
     * thread as possible.
     *
     * @see #apply(Context, ViewGroup)
     * @param context Default context to use
     * @param parent Parent that the resulting view hierarchy will be attached to. This method
     * does <strong>not</strong> attach the hierarchy. The caller should do so when appropriate.
     * @param listener the callback to run when all actions have been applied. May be null.
     * @param executor The executor to use. If null {@link AsyncTask#THREAD_POOL_EXECUTOR} is used.
     * @return CancellationSignal
     * @hide
     */
    public CancellationSignal applyAsync(
            Context context, ViewGroup parent, Executor executor, OnViewAppliedListener listener) {
        return applyAsync(context, parent, executor, listener, null /* handler */);
    }


    /** @hide */
    public CancellationSignal applyAsync(Context context, ViewGroup parent,
            Executor executor, OnViewAppliedListener listener, InteractionHandler handler) {
        return applyAsync(context, parent, executor, listener, handler, null /* size */);
    }

    /** @hide */
    public CancellationSignal applyAsync(Context context, ViewGroup parent,
            Executor executor, OnViewAppliedListener listener, InteractionHandler handler,
            PointF size) {
        return getAsyncApplyTask(context, parent, listener, handler, size, null /* themeColors */)
                .startTaskOnExecutor(executor);
    }

    /** @hide */
    public CancellationSignal applyAsync(Context context, ViewGroup parent, Executor executor,
            OnViewAppliedListener listener, InteractionHandler handler, PointF size,
            ColorResources colorResources) {
        return getAsyncApplyTask(context, parent, listener, handler, size, colorResources)
                .startTaskOnExecutor(executor);
    }

    private AsyncApplyTask getAsyncApplyTask(Context context, ViewGroup parent,
            OnViewAppliedListener listener, InteractionHandler handler, PointF size,
            ColorResources colorResources) {
        return new AsyncApplyTask(getRemoteViewsToApply(context, size), parent, context, listener,
                handler, colorResources, null /* result */);
    }

    private class AsyncApplyTask extends AsyncTask<Void, Void, ViewTree>
            implements CancellationSignal.OnCancelListener {
        final CancellationSignal mCancelSignal = new CancellationSignal();
        final RemoteViews mRV;
        final ViewGroup mParent;
        final Context mContext;
        final OnViewAppliedListener mListener;
        final InteractionHandler mHandler;
        final ColorResources mColorResources;

        private View mResult;
        private ViewTree mTree;
        private Action[] mActions;
        private Exception mError;

        private AsyncApplyTask(
                RemoteViews rv, ViewGroup parent, Context context, OnViewAppliedListener listener,
                InteractionHandler handler, ColorResources colorResources, View result) {
            mRV = rv;
            mParent = parent;
            mContext = context;
            mListener = listener;
            mColorResources = colorResources;
            mHandler = handler;

            mResult = result;
        }

        @Override
        protected ViewTree doInBackground(Void... params) {
            try {
                if (mResult == null) {
                    mResult = inflateView(mContext, mRV, mParent, 0, mColorResources);
                }

                mTree = new ViewTree(mResult);
                if (mRV.mActions != null) {
                    int count = mRV.mActions.size();
                    mActions = new Action[count];
                    for (int i = 0; i < count && !isCancelled(); i++) {
                        // TODO: check if isCancelled in nested views.
                        mActions[i] = mRV.mActions.get(i).initActionAsync(mTree, mParent, mHandler,
                                mColorResources);
                    }
                } else {
                    mActions = null;
                }
                return mTree;
            } catch (Exception e) {
                mError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(ViewTree viewTree) {
            mCancelSignal.setOnCancelListener(null);
            if (mError == null) {
                if (mListener != null) {
                    mListener.onViewInflated(viewTree.mRoot);
                }

                try {
                    if (mActions != null) {
                        InteractionHandler handler = mHandler == null
                                ? DEFAULT_INTERACTION_HANDLER : mHandler;
                        for (Action a : mActions) {
                            a.apply(viewTree.mRoot, mParent, handler, mColorResources);
                        }
                    }
                } catch (Exception e) {
                    mError = e;
                }
            }

            if (mListener != null) {
                if (mError != null) {
                    mListener.onError(mError);
                } else {
                    mListener.onViewApplied(viewTree.mRoot);
                }
            } else if (mError != null) {
                if (mError instanceof ActionException) {
                    throw (ActionException) mError;
                } else {
                    throw new ActionException(mError);
                }
            }
        }

        @Override
        public void onCancel() {
            cancel(true);
        }

        private CancellationSignal startTaskOnExecutor(Executor executor) {
            mCancelSignal.setOnCancelListener(this);
            executeOnExecutor(executor == null ? AsyncTask.THREAD_POOL_EXECUTOR : executor);
            return mCancelSignal;
        }
    }

    /**
     * Applies all of the actions to the provided view.
     *
     * <p><strong>Caller beware: this may throw</strong>
     *
     * @param v The view to apply the actions to.  This should be the result of
     * the {@link #apply(Context,ViewGroup)} call.
     */
    public void reapply(Context context, View v) {
        reapply(context, v, null /* handler */);
    }

    /** @hide */
    public void reapply(Context context, View v, InteractionHandler handler) {
        reapply(context, v, handler, null /* size */, null /* colorResources */);
    }

    /** @hide */
    public void reapply(Context context, View v, InteractionHandler handler, PointF size,
            ColorResources colorResources) {
        RemoteViews rvToApply = getRemoteViewsToApply(context, size);

        // In the case that a view has this RemoteViews applied in one orientation or size, is
        // persisted across change, and has the RemoteViews re-applied in a different situation
        // (orientation or size), we throw an exception, since the layouts may be completely
        // unrelated.
        if (hasMultipleLayouts()) {
            if ((Integer) v.getTag(R.id.widget_frame) != rvToApply.getLayoutId()) {
                throw new RuntimeException("Attempting to re-apply RemoteViews to a view that" +
                        " that does not share the same root layout id.");
            }
        }

        rvToApply.performApply(v, (ViewGroup) v.getParent(), handler, colorResources);
    }

    /**
     * Applies all the actions to the provided view, moving as much of the task on the background
     * thread as possible.
     *
     * @see #reapply(Context, View)
     * @param context Default context to use
     * @param v The view to apply the actions to.  This should be the result of
     * the {@link #apply(Context,ViewGroup)} call.
     * @param listener the callback to run when all actions have been applied. May be null.
     * @param executor The executor to use. If null {@link AsyncTask#THREAD_POOL_EXECUTOR} is used
     * @return CancellationSignal
     * @hide
     */
    public CancellationSignal reapplyAsync(Context context, View v, Executor executor,
            OnViewAppliedListener listener) {
        return reapplyAsync(context, v, executor, listener, null);
    }

    /** @hide */
    public CancellationSignal reapplyAsync(Context context, View v, Executor executor,
            OnViewAppliedListener listener, InteractionHandler handler) {
        return reapplyAsync(context, v, executor, listener, handler, null, null);
    }

    /** @hide */
    public CancellationSignal reapplyAsync(Context context, View v, Executor executor,
            OnViewAppliedListener listener, InteractionHandler handler, PointF size,
            ColorResources colorResources) {
        RemoteViews rvToApply = getRemoteViewsToApply(context, size);

        // In the case that a view has this RemoteViews applied in one orientation, is persisted
        // across orientation change, and has the RemoteViews re-applied in the new orientation,
        // we throw an exception, since the layouts may be completely unrelated.
        if (hasMultipleLayouts()) {
            if ((Integer) v.getTag(R.id.widget_frame) != rvToApply.getLayoutId()) {
                throw new RuntimeException("Attempting to re-apply RemoteViews to a view that" +
                        " that does not share the same root layout id.");
            }
        }

        return new AsyncApplyTask(rvToApply, (ViewGroup) v.getParent(),
                context, listener, handler, colorResources, v).startTaskOnExecutor(
                executor);
    }

    private void performApply(View v, ViewGroup parent, InteractionHandler handler,
            ColorResources colorResources) {
        if (mActions != null) {
            handler = handler == null ? DEFAULT_INTERACTION_HANDLER : handler;
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                Action a = mActions.get(i);
                a.apply(v, parent, handler, colorResources);
            }
        }
    }

    /**
     * Returns true if the RemoteViews contains potentially costly operations and should be
     * applied asynchronously.
     *
     * @hide
     */
    public boolean prefersAsyncApply() {
        if (mActions != null) {
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                if (mActions.get(i).prefersAsyncApply()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Context getContextForResources(Context context) {
        if (mApplication != null) {
            if (context.getUserId() == UserHandle.getUserId(mApplication.uid)
                    && context.getPackageName().equals(mApplication.packageName)) {
                return context;
            }
            try {
                return context.createApplicationContext(mApplication,
                        Context.CONTEXT_RESTRICTED);
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Package name " + mApplication.packageName + " not found");
            }
        }

        return context;
    }

    /**
     * Object allowing the modification of a context to overload the system's dynamic colors.
     *
     * Only colors from {@link android.R.color#system_primary_0} to
     * {@link android.R.color#system_neutral_1000} can be overloaded.
     * @hide
     */
    public static final class ColorResources {
        // Set of valid colors resources.
        private static final int FIRST_RESOURCE_COLOR_ID = android.R.color.system_primary_0;
        private static final int LAST_RESOURCE_COLOR_ID = android.R.color.system_neutral_1000;
        // Size, in bytes, of an entry in the array of colors in an ARSC file.
        private static final int ARSC_ENTRY_SIZE = 16;

        private ResourcesLoader mLoader;

        private ColorResources(ResourcesLoader loader) {
            mLoader = loader;
        }

        /**
         * Apply the color resources to the given context.
         *
         * No resource resolution must have be done on the context given to that method.
         */
        public void apply(Context context) {
            context.getResources().addLoaders(mLoader);
        }

        private static ByteArrayOutputStream readFileContent(InputStream input) throws IOException {
            ByteArrayOutputStream content = new ByteArrayOutputStream(2048);
            byte[] buffer = new byte[4096];
            while (input.available() > 0) {
                int read = input.read(buffer);
                content.write(buffer, 0, read);
            }
            return content;
        }

        /**
         * Creates the compiled resources content from the asset stored in the APK.
         *
         * The asset is a compiled resource with the correct resources name and correct ids, only
         * the values are incorrect. The last value is at the very end of the file. The resources
         * are in an array, the array's entries are 16 bytes each. We use this to work out the
         * location of all the positions of the various resources.
         */
        private static byte[] createCompiledResourcesContent(Context context,
                SparseIntArray colorResources) throws IOException {
            byte[] content;
            try (InputStream input = context.getResources().openRawResource(
                    com.android.internal.R.raw.remote_views_color_resources)) {
                ByteArrayOutputStream rawContent = readFileContent(input);
                content = rawContent.toByteArray();
            }
            int valuesOffset =
                    content.length - (LAST_RESOURCE_COLOR_ID & 0xffff) * ARSC_ENTRY_SIZE - 4;
            if (valuesOffset < 0) {
                Log.e(LOG_TAG, "ARSC file for theme colors is invalid.");
                return null;
            }
            for (int colorRes = FIRST_RESOURCE_COLOR_ID; colorRes <= LAST_RESOURCE_COLOR_ID;
                    colorRes++) {
                // The last 2 bytes are the index in the color array.
                int index = colorRes & 0xffff;
                int offset = valuesOffset + index * ARSC_ENTRY_SIZE;
                int value = colorResources.get(colorRes, context.getColor(colorRes));
                // Write the 32 bit integer in little endian
                for (int b = 0; b < 4; b++) {
                    content[offset + b] = (byte) (value & 0xff);
                    value >>= 8;
                }
            }
            return content;
        }

        /**
         *  Adds a resource loader for theme colors to the given context.
         *
         * @param context Context of the view hosting the widget.
         * @param colorMapping Mapping of resources to color values.
         *
         * @hide
         */
        public static ColorResources create(Context context, SparseIntArray colorMapping) {
            try {
                byte[] contentBytes = createCompiledResourcesContent(context, colorMapping);
                if (contentBytes == null) {
                    return null;
                }
                FileDescriptor arscFile = null;
                try {
                    arscFile = Os.memfd_create("remote_views_theme_colors.arsc", 0 /* flags */);
                    // Note: This must not be closed through the OutputStream.
                    try (OutputStream pipeWriter = new FileOutputStream(arscFile)) {
                        pipeWriter.write(contentBytes);

                        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(arscFile)) {
                            ResourcesLoader colorsLoader = new ResourcesLoader();
                            colorsLoader.addProvider(ResourcesProvider
                                    .loadFromTable(pfd, null /* assetsProvider */));
                            return new ColorResources(colorsLoader);
                        }
                    }
                } finally {
                    if (arscFile != null) {
                        Os.close(arscFile);
                    }
                }
            } catch (Exception ex) {
                Log.e(LOG_TAG, "Failed to setup the context for theme colors", ex);
            }
            return null;
        }
    }

    /**
     * Returns the number of actions in this RemoteViews. Can be used as a sequence number.
     *
     * @hide
     */
    public int getSequenceNumber() {
        return (mActions == null) ? 0 : mActions.size();
    }

    /**
     * Used to restrict the views which can be inflated
     *
     * @see android.view.LayoutInflater.Filter#onLoadClass(java.lang.Class)
     * @deprecated Used by system to enforce safe inflation of {@link RemoteViews}. Apps should not
     * override this method. Changing of this method will NOT affect the process where RemoteViews
     * is rendered.
     */
    @Deprecated
    public boolean onLoadClass(Class clazz) {
        return clazz.isAnnotationPresent(RemoteView.class);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        if (!hasMultipleLayouts()) {
            dest.writeInt(MODE_NORMAL);
            // We only write the bitmap cache if we are the root RemoteViews, as this cache
            // is shared by all children.
            if (mIsRoot) {
                mBitmapCache.writeBitmapsToParcel(dest, flags);
            }
            if (!mIsRoot && (flags & PARCELABLE_ELIDE_DUPLICATES) != 0) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                mApplication.writeToParcel(dest, flags);
            }
            if (mIsRoot || mIdealSize == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                mIdealSize.writeToParcel(dest, flags);
            }
            dest.writeInt(mLayoutId);
            dest.writeInt(mLightBackgroundLayoutId);
            writeActionsToParcel(dest);
        } else if (hasSizedRemoteViews()) {
            dest.writeInt(MODE_HAS_SIZED_REMOTEVIEWS);
            if (mIsRoot) {
                mBitmapCache.writeBitmapsToParcel(dest, flags);
            }
            int childFlags = flags;
            dest.writeInt(mSizedRemoteViews.size());
            for (RemoteViews view : mSizedRemoteViews) {
                view.writeToParcel(dest, childFlags);
                childFlags |= PARCELABLE_ELIDE_DUPLICATES;
            }
        } else {
            dest.writeInt(MODE_HAS_LANDSCAPE_AND_PORTRAIT);
            // We only write the bitmap cache if we are the root RemoteViews, as this cache
            // is shared by all children.
            if (mIsRoot) {
                mBitmapCache.writeBitmapsToParcel(dest, flags);
            }
            mLandscape.writeToParcel(dest, flags);
            // Both RemoteViews already share the same package and user
            mPortrait.writeToParcel(dest, flags | PARCELABLE_ELIDE_DUPLICATES);
        }
        dest.writeInt(mApplyFlags);
    }

    private void writeActionsToParcel(Parcel parcel) {
        int count;
        if (mActions != null) {
            count = mActions.size();
        } else {
            count = 0;
        }
        parcel.writeInt(count);
        for (int i = 0; i < count; i++) {
            Action a = mActions.get(i);
            parcel.writeInt(a.getActionTag());
            a.writeToParcel(parcel, a.hasSameAppInfo(mApplication)
                    ? PARCELABLE_ELIDE_DUPLICATES : 0);
        }
    }

    private static ApplicationInfo getApplicationInfo(String packageName, int userId) {
        if (packageName == null) {
            return null;
        }

        // Get the application for the passed in package and user.
        Application application = ActivityThread.currentApplication();
        if (application == null) {
            throw new IllegalStateException("Cannot create remote views out of an aplication.");
        }

        ApplicationInfo applicationInfo = application.getApplicationInfo();
        if (UserHandle.getUserId(applicationInfo.uid) != userId
                || !applicationInfo.packageName.equals(packageName)) {
            try {
                Context context = application.getBaseContext().createPackageContextAsUser(
                        packageName, 0, new UserHandle(userId));
                applicationInfo = context.getApplicationInfo();
            } catch (NameNotFoundException nnfe) {
                throw new IllegalArgumentException("No such package " + packageName);
            }
        }

        return applicationInfo;
    }

    /**
     * Returns true if the {@link #mApplication} is same as the provided info.
     *
     * @hide
     */
    public boolean hasSameAppInfo(ApplicationInfo info) {
        return mApplication.packageName.equals(info.packageName) && mApplication.uid == info.uid;
    }

    /**
     * Parcelable.Creator that instantiates RemoteViews objects
     */
    public static final @android.annotation.NonNull Parcelable.Creator<RemoteViews> CREATOR = new Parcelable.Creator<RemoteViews>() {
        public RemoteViews createFromParcel(Parcel parcel) {
            return new RemoteViews(parcel);
        }

        public RemoteViews[] newArray(int size) {
            return new RemoteViews[size];
        }
    };

    /**
     * A representation of the view hierarchy. Only views which have a valid ID are added
     * and can be searched.
     */
    private static class ViewTree {
        private static final int INSERT_AT_END_INDEX = -1;
        private View mRoot;
        private ArrayList<ViewTree> mChildren;

        private ViewTree(View root) {
            mRoot = root;
        }

        public void createTree() {
            if (mChildren != null) {
                return;
            }

            mChildren = new ArrayList<>();
            if (mRoot instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) mRoot;
                int count = vg.getChildCount();
                for (int i = 0; i < count; i++) {
                    addViewChild(vg.getChildAt(i));
                }
            }
        }

        public ViewTree findViewTreeById(@IdRes int id) {
            if (mRoot.getId() == id) {
                return this;
            }
            if (mChildren == null) {
                return null;
            }
            for (ViewTree tree : mChildren) {
                ViewTree result = tree.findViewTreeById(id);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        public ViewTree findViewTreeParentOf(ViewTree child) {
            if (mChildren == null) {
                return null;
            }
            for (ViewTree tree : mChildren) {
                if (tree == child) {
                    return this;
                }
                ViewTree result = tree.findViewTreeParentOf(child);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        public void replaceView(View v) {
            mRoot = v;
            mChildren = null;
            createTree();
        }

        public <T extends View> T findViewById(@IdRes int id) {
            if (mChildren == null) {
                return mRoot.findViewById(id);
            }
            ViewTree tree = findViewTreeById(id);
            return tree == null ? null : (T) tree.mRoot;
        }

        public void addChild(ViewTree child) {
            addChild(child, INSERT_AT_END_INDEX);
        }

        /**
         * Adds the given {@link ViewTree} as a child at the given index.
         *
         * @param index The position at which to add the child or -1 to add last.
         */
        public void addChild(ViewTree child, int index) {
            if (mChildren == null) {
                mChildren = new ArrayList<>();
            }
            child.createTree();

            if (index == INSERT_AT_END_INDEX) {
                mChildren.add(child);
                return;
            }

            mChildren.add(index, child);
        }

        private void addViewChild(View v) {
            // ViewTree only contains Views which can be found using findViewById.
            // If isRootNamespace is true, this view is skipped.
            // @see ViewGroup#findViewTraversal(int)
            if (v.isRootNamespace()) {
                return;
            }
            final ViewTree target;

            // If the view has a valid id, i.e., if can be found using findViewById, add it to the
            // tree, otherwise skip this view and add its children instead.
            if (v.getId() != 0) {
                ViewTree tree = new ViewTree(v);
                mChildren.add(tree);
                target = tree;
            } else {
                target = this;
            }

            if (v instanceof ViewGroup) {
                if (target.mChildren == null) {
                    target.mChildren = new ArrayList<>();
                    ViewGroup vg = (ViewGroup) v;
                    int count = vg.getChildCount();
                    for (int i = 0; i < count; i++) {
                        target.addViewChild(vg.getChildAt(i));
                    }
                }
            }
        }
    }

    /**
     * Class representing a response to an action performed on any element of a RemoteViews.
     */
    public static class RemoteResponse {

        /** @hide **/
        @IntDef(prefix = "INTERACTION_TYPE_", value = {
                INTERACTION_TYPE_CLICK,
                INTERACTION_TYPE_CHECKED_CHANGE,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface InteractionType {}
        /** @hide */
        public static final int INTERACTION_TYPE_CLICK = 0;
        /** @hide */
        public static final int INTERACTION_TYPE_CHECKED_CHANGE = 1;

        private PendingIntent mPendingIntent;
        private Intent mFillIntent;

        private int mInteractionType = INTERACTION_TYPE_CLICK;
        private IntArray mViewIds;
        private ArrayList<String> mElementNames;

        /**
         * Creates a response which sends a pending intent as part of the response. The source
         * bounds ({@link Intent#getSourceBounds()}) of the intent will be set to the bounds of the
         * target view in screen space.
         * Note that any activity options associated with the mPendingIntent may get overridden
         * before starting the intent.
         *
         * @param pendingIntent The {@link PendingIntent} to send as part of the response
         */
        @NonNull
        public static RemoteResponse fromPendingIntent(@NonNull PendingIntent pendingIntent) {
            RemoteResponse response = new RemoteResponse();
            response.mPendingIntent = pendingIntent;
            return response;
        }

        /**
         * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is
         * very costly to set PendingIntents on the individual items, and is hence not recommended.
         * Instead a single PendingIntent template can be set on the collection, see {@link
         * RemoteViews#setPendingIntentTemplate(int, PendingIntent)}, and the individual on-click
         * action of a given item can be distinguished by setting a fillInIntent on that item. The
         * fillInIntent is then combined with the PendingIntent template in order to determine the
         * final intent which will be executed when the item is clicked. This works as follows: any
         * fields which are left blank in the PendingIntent template, but are provided by the
         * fillInIntent will be overwritten, and the resulting PendingIntent will be used. The rest
         * of the PendingIntent template will then be filled in with the associated fields that are
         * set in fillInIntent. See {@link Intent#fillIn(Intent, int)} for more details.
         * Creates a response which sends a pending intent as part of the response. The source
         * bounds ({@link Intent#getSourceBounds()}) of the intent will be set to the bounds of the
         * target view in screen space.
         * Note that any activity options associated with the mPendingIntent may get overridden
         * before starting the intent.
         *
         * @param fillIntent The intent which will be combined with the parent's PendingIntent in
         *                   order to determine the behavior of the response
         * @see RemoteViews#setPendingIntentTemplate(int, PendingIntent)
         * @see RemoteViews#setOnClickFillInIntent(int, Intent)
         */
        @NonNull
        public static RemoteResponse fromFillInIntent(@NonNull Intent fillIntent) {
            RemoteResponse response = new RemoteResponse();
            response.mFillIntent = fillIntent;
            return response;
        }

        /**
         * Adds a shared element to be transferred as part of the transition between Activities
         * using cross-Activity scene animations. The position of the first element will be used as
         * the epicenter for the exit Transition. The position of the associated shared element in
         * the launched Activity will be the epicenter of its entering Transition.
         *
         * @param viewId            The id of the view to be shared as part of the transition
         * @param sharedElementName The shared element name for this view
         * @see ActivityOptions#makeSceneTransitionAnimation(Activity, Pair[])
         */
        @NonNull
        public RemoteResponse addSharedElement(@IdRes int viewId,
                @NonNull String sharedElementName) {
            if (mViewIds == null) {
                mViewIds = new IntArray();
                mElementNames = new ArrayList<>();
            }
            mViewIds.add(viewId);
            mElementNames.add(sharedElementName);
            return this;
        }

        /**
         * Sets the interaction type for which this RemoteResponse responds.
         *
         * @param type the type of interaction for which this is a response, such as clicking or
         *             checked state changing
         *
         * @hide
         */
        @NonNull
        public RemoteResponse setInteractionType(@InteractionType int type) {
            mInteractionType = type;
            return this;
        }

        private void writeToParcel(Parcel dest, int flags) {
            PendingIntent.writePendingIntentOrNullToParcel(mPendingIntent, dest);
            if (mPendingIntent == null) {
                // Only write the intent if pending intent is null
                dest.writeTypedObject(mFillIntent, flags);
            }
            dest.writeInt(mInteractionType);
            dest.writeIntArray(mViewIds == null ? null : mViewIds.toArray());
            dest.writeStringList(mElementNames);
        }

        private void readFromParcel(Parcel parcel) {
            mPendingIntent = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
            if (mPendingIntent == null) {
                mFillIntent = parcel.readTypedObject(Intent.CREATOR);
            }
            mInteractionType = parcel.readInt();
            int[] viewIds = parcel.createIntArray();
            mViewIds = viewIds == null ? null : IntArray.wrap(viewIds);
            mElementNames = parcel.createStringArrayList();
        }

        private void handleViewInteraction(
                View v,
                InteractionHandler handler) {
            final PendingIntent pi;
            if (mPendingIntent != null) {
                pi = mPendingIntent;
            } else if (mFillIntent != null) {
                AdapterView<?> ancestor = getAdapterViewAncestor(v);
                if (ancestor == null) {
                    Log.e(LOG_TAG, "Collection item doesn't have AdapterView parent");
                    return;
                }

                // Ensure that a template pending intent has been set on the ancestor
                if (!(ancestor.getTag() instanceof PendingIntent)) {
                    Log.e(LOG_TAG, "Attempting setOnClickFillInIntent or "
                            + "setOnCheckedChangeFillInIntent without calling "
                            + "setPendingIntentTemplate on parent.");
                    return;
                }

                pi = (PendingIntent) ancestor.getTag();
            } else {
                Log.e(LOG_TAG, "Response has neither pendingIntent nor fillInIntent");
                return;
            }

            handler.onInteraction(v, pi, this);
        }

        /**
         * Returns the closest ancestor of the view that is an AdapterView or null if none could be
         * found.
         */
        @Nullable
        private static AdapterView<?> getAdapterViewAncestor(@Nullable View view) {
            View parent = (View) view.getParent();
            // Break the for loop on the first encounter of:
            //    1) an AdapterView,
            //    2) an AppWidgetHostView that is not a RemoteViewsFrameLayout, or
            //    3) a null parent.
            // 2) and 3) are unexpected and catch the case where a child is not
            // correctly parented in an AdapterView.
            while (parent != null && !(parent instanceof AdapterView<?>)
                    && !((parent instanceof AppWidgetHostView)
                    && !(parent instanceof RemoteViewsAdapter.RemoteViewsFrameLayout))) {
                parent = (View) parent.getParent();
            }

            return parent instanceof AdapterView<?> ? (AdapterView<?>) parent : null;
        }

        /** @hide */
        public Pair<Intent, ActivityOptions> getLaunchOptions(View view) {
            Intent intent = mPendingIntent != null ? new Intent() : new Intent(mFillIntent);
            intent.setSourceBounds(getSourceBounds(view));

            if (view instanceof CompoundButton
                    && mInteractionType == INTERACTION_TYPE_CHECKED_CHANGE) {
                intent.putExtra(EXTRA_CHECKED, ((CompoundButton) view).isChecked());
            }

            ActivityOptions opts = null;

            Context context = view.getContext();
            if (context.getResources().getBoolean(
                    com.android.internal.R.bool.config_overrideRemoteViewsActivityTransition)) {
                TypedArray windowStyle = context.getTheme().obtainStyledAttributes(
                        com.android.internal.R.styleable.Window);
                int windowAnimations = windowStyle.getResourceId(
                        com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
                TypedArray windowAnimationStyle = context.obtainStyledAttributes(
                        windowAnimations, com.android.internal.R.styleable.WindowAnimation);
                int enterAnimationId = windowAnimationStyle.getResourceId(com.android.internal.R
                        .styleable.WindowAnimation_activityOpenRemoteViewsEnterAnimation, 0);
                windowStyle.recycle();
                windowAnimationStyle.recycle();

                if (enterAnimationId != 0) {
                    opts = ActivityOptions.makeCustomAnimation(context,
                            enterAnimationId, 0);
                    opts.setPendingIntentLaunchFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            }

            if (opts == null && mViewIds != null && mElementNames != null) {
                View parent = (View) view.getParent();
                while (parent != null && !(parent instanceof AppWidgetHostView)) {
                    parent = (View) parent.getParent();
                }
                if (parent instanceof AppWidgetHostView) {
                    opts = ((AppWidgetHostView) parent).createSharedElementActivityOptions(
                            mViewIds.toArray(),
                            mElementNames.toArray(new String[mElementNames.size()]), intent);
                }
            }

            if (opts == null) {
                opts = ActivityOptions.makeBasic();
                opts.setPendingIntentLaunchFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return Pair.create(intent, opts);
        }
    }

    /** @hide */
    public static boolean startPendingIntent(View view, PendingIntent pendingIntent,
            Pair<Intent, ActivityOptions> options) {
        try {
            // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
            Context context = view.getContext();
            // The NEW_TASK flags are applied through the activity options and not as a part of
            // the call to startIntentSender() to ensure that they are consistently applied to
            // both mutable and immutable PendingIntents.
            context.startIntentSender(
                    pendingIntent.getIntentSender(), options.first,
                    0, 0, 0, options.second.toBundle());
        } catch (IntentSender.SendIntentException e) {
            Log.e(LOG_TAG, "Cannot send pending intent: ", e);
            return false;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Cannot send pending intent due to unknown exception: ", e);
            return false;
        }
        return true;
    }

    /**
     *  Set the ID of the top-level view of the XML layout.
     *
     *  Set to {@link View#NO_ID} to reset and simply keep the id defined in the XML layout.
     *
     * @throws UnsupportedOperationException if the method is called on a RemoteViews defined in
     * term of other RemoteViews (e.g. {@link #RemoteViews(RemoteViews, RemoteViews)}).
     */
    public void setViewId(@IdRes int viewId) {
        if (hasMultipleLayouts()) {
            throw new UnsupportedOperationException(
                    "The viewId can only be set on RemoteViews defined from a XML layout.");
        }
        mViewId = viewId;
    }

    /** Get the ID of the top-level view of the XML layout, as set by {@link #setViewId}. */
    public @IdRes int getViewId() {
        return mViewId;
    }
}
