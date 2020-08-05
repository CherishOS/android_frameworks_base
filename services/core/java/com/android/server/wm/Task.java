/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityTaskManager.RESIZE_MODE_FORCED;
import static android.app.ActivityTaskManager.RESIZE_MODE_SYSTEM_SCREEN_ROTATION;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.PINNED_WINDOWING_MODE_ELEVATION_IN_DIP;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.app.WindowConfiguration.windowingModeToString;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_AND_PIPABLE_DEPRECATED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.provider.Settings.Secure.USER_SETUP_COMPLETE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.SurfaceControl.METADATA_TASK_ID;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
import static android.view.WindowManager.TRANSIT_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;

import static com.android.internal.policy.DecorView.DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP;
import static com.android.internal.policy.DecorView.DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP;
import static com.android.server.wm.ActivityRecord.STARTING_WINDOW_SHOWN;
import static com.android.server.wm.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.ActivityStackSupervisor.dumpHistoryList;
import static com.android.server.wm.ActivityStackSupervisor.printThisActivity;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.H.FIRST_ACTIVITY_STACK_MSG;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_RECENTS_ANIMATIONS;
import static com.android.server.wm.Task.ActivityState.PAUSED;
import static com.android.server.wm.Task.ActivityState.PAUSING;
import static com.android.server.wm.Task.ActivityState.RESUMED;
import static com.android.server.wm.Task.ActivityState.STARTED;
import static com.android.server.wm.Task.ActivityState.STOPPED;
import static com.android.server.wm.Task.ActivityState.STOPPING;
import static com.android.server.wm.TaskProto.ACTIVITY_TYPE;
import static com.android.server.wm.TaskProto.BOUNDS;
import static com.android.server.wm.TaskProto.CREATED_BY_ORGANIZER;
import static com.android.server.wm.TaskProto.DISPLAY_ID;
import static com.android.server.wm.TaskProto.FILLS_PARENT;
import static com.android.server.wm.TaskProto.LAST_NON_FULLSCREEN_BOUNDS;
import static com.android.server.wm.TaskProto.MIN_HEIGHT;
import static com.android.server.wm.TaskProto.MIN_WIDTH;
import static com.android.server.wm.TaskProto.ORIG_ACTIVITY;
import static com.android.server.wm.TaskProto.REAL_ACTIVITY;
import static com.android.server.wm.TaskProto.RESIZE_MODE;
import static com.android.server.wm.TaskProto.RESUMED_ACTIVITY;
import static com.android.server.wm.TaskProto.ROOT_TASK_ID;
import static com.android.server.wm.TaskProto.SURFACE_HEIGHT;
import static com.android.server.wm.TaskProto.SURFACE_WIDTH;
import static com.android.server.wm.TaskProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.TASK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_BEFORE_ANIM;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.RemoteAction;
import android.app.ResultInfo;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITaskOrganizer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.AppTimeTracker;
import com.android.server.protolog.common.ProtoLog;
import com.android.server.uri.NeededUriGrants;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

class Task extends WindowContainer<WindowContainer> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "Task" : TAG_ATM;
    static final String TAG_ADD_REMOVE = TAG + POSTFIX_ADD_REMOVE;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    private static final String TAG_APP = TAG + POSTFIX_APP;
    static final String TAG_CLEANUP = TAG + POSTFIX_CLEANUP;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;

    private static final String ATTR_TASKID = "task_id";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_AFFINITYINTENT = "affinity_intent";
    private static final String ATTR_REALACTIVITY = "real_activity";
    private static final String ATTR_REALACTIVITY_SUSPENDED = "real_activity_suspended";
    private static final String ATTR_ORIGACTIVITY = "orig_activity";
    private static final String TAG_ACTIVITY = "activity";
    private static final String ATTR_AFFINITY = "affinity";
    private static final String ATTR_ROOT_AFFINITY = "root_affinity";
    private static final String ATTR_ROOTHASRESET = "root_has_reset";
    private static final String ATTR_AUTOREMOVERECENTS = "auto_remove_recents";
    private static final String ATTR_ASKEDCOMPATMODE = "asked_compat_mode";
    private static final String ATTR_USERID = "user_id";
    private static final String ATTR_USER_SETUP_COMPLETE = "user_setup_complete";
    private static final String ATTR_EFFECTIVE_UID = "effective_uid";
    @Deprecated
    private static final String ATTR_TASKTYPE = "task_type";
    private static final String ATTR_LASTDESCRIPTION = "last_description";
    private static final String ATTR_LASTTIMEMOVED = "last_time_moved";
    private static final String ATTR_NEVERRELINQUISH = "never_relinquish_identity";
    private static final String ATTR_TASK_AFFILIATION = "task_affiliation";
    private static final String ATTR_PREV_AFFILIATION = "prev_affiliation";
    private static final String ATTR_NEXT_AFFILIATION = "next_affiliation";
    private static final String ATTR_CALLING_UID = "calling_uid";
    private static final String ATTR_CALLING_PACKAGE = "calling_package";
    private static final String ATTR_CALLING_FEATURE_ID = "calling_feature_id";
    private static final String ATTR_SUPPORTS_PICTURE_IN_PICTURE = "supports_picture_in_picture";
    private static final String ATTR_RESIZE_MODE = "resize_mode";
    private static final String ATTR_NON_FULLSCREEN_BOUNDS = "non_fullscreen_bounds";
    private static final String ATTR_MIN_WIDTH = "min_width";
    private static final String ATTR_MIN_HEIGHT = "min_height";
    private static final String ATTR_PERSIST_TASK_VERSION = "persist_task_version";
    private static final String ATTR_WINDOW_LAYOUT_AFFINITY = "window_layout_affinity";

    // Set to false to disable the preview that is shown while a new activity
    // is being started.
    private static final boolean SHOW_APP_STARTING_PREVIEW = true;

    // How long to wait for all background Activities to redraw following a call to
    // convertToTranslucent().
    private static final long TRANSLUCENT_CONVERSION_TIMEOUT = 2000;

    // Current version of the task record we persist. Used to check if we need to run any upgrade
    // code.
    static final int PERSIST_TASK_VERSION = 1;

    static final int INVALID_MIN_SIZE = -1;
    private float mShadowRadius = 0;

    /**
     * The modes to control how the stack is moved to the front when calling {@link Task#reparent}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            REPARENT_MOVE_STACK_TO_FRONT,
            REPARENT_KEEP_STACK_AT_FRONT,
            REPARENT_LEAVE_STACK_IN_PLACE
    })
    @interface ReparentMoveStackMode {}
    // Moves the stack to the front if it was not at the front
    static final int REPARENT_MOVE_STACK_TO_FRONT = 0;
    // Only moves the stack to the front if it was focused or front most already
    static final int REPARENT_KEEP_STACK_AT_FRONT = 1;
    // Do not move the stack as a part of reparenting
    static final int REPARENT_LEAVE_STACK_IN_PLACE = 2;

    @IntDef(prefix = {"STACK_VISIBILITY"}, value = {
            STACK_VISIBILITY_VISIBLE,
            STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
            STACK_VISIBILITY_INVISIBLE,
    })
    @interface StackVisibility {}

    /** Stack is visible. No other stacks on top that fully or partially occlude it. */
    static final int STACK_VISIBILITY_VISIBLE = 0;

    /** Stack is partially occluded by other translucent stack(s) on top of it. */
    static final int STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT = 1;

    /** Stack is completely invisible. */
    static final int STACK_VISIBILITY_INVISIBLE = 2;

    enum ActivityState {
        INITIALIZING,
        STARTED,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED,
        RESTARTING_PROCESS
    }

    // The topmost Activity passed to convertToTranslucent(). When non-null it means we are
    // waiting for all Activities in mUndrawnActivitiesBelowTopTranslucent to be removed as they
    // are drawn. When the last member of mUndrawnActivitiesBelowTopTranslucent is removed the
    // Activity in mTranslucentActivityWaiting is notified via
    // Activity.onTranslucentConversionComplete(false). If a timeout occurs prior to the last
    // background activity being drawn then the same call will be made with a true value.
    ActivityRecord mTranslucentActivityWaiting = null;
    ArrayList<ActivityRecord> mUndrawnActivitiesBelowTopTranslucent = new ArrayList<>();

    /**
     * Set when we know we are going to be calling updateConfiguration()
     * soon, so want to skip intermediate config checks.
     */
    boolean mConfigWillChange;

    /**
     * Used to keep resumeTopActivityUncheckedLocked() from being entered recursively
     */
    boolean mInResumeTopActivity = false;

    int mCurrentUser;

    String affinity;        // The affinity name for this task, or null; may change identity.
    String rootAffinity;    // Initial base affinity, or null; does not change from initial root.
    String mWindowLayoutAffinity; // Launch param affinity of this task or null. Used when saving
                                // launch params of this task.
    IVoiceInteractionSession voiceSession;    // Voice interaction session driving task
    IVoiceInteractor voiceInteractor;         // Associated interactor to provide to app
    Intent intent;          // The original intent that started the task. Note that this value can
                            // be null.
    Intent affinityIntent;  // Intent of affinity-moved activity that started this task.
    int effectiveUid;       // The current effective uid of the identity of this task.
    ComponentName origActivity; // The non-alias activity component of the intent.
    ComponentName realActivity; // The actual activity component that started the task.
    boolean realActivitySuspended; // True if the actual activity component that started the
                                   // task is suspended.
    boolean inRecents;      // Actually in the recents list?
    long lastActiveTime;    // Last time this task was active in the current device session,
                            // including sleep. This time is initialized to the elapsed time when
                            // restored from disk.
    boolean isAvailable;    // Is the activity available to be launched?
    boolean rootWasReset;   // True if the intent at the root of the task had
                            // the FLAG_ACTIVITY_RESET_TASK_IF_NEEDED flag.
    boolean autoRemoveRecents;  // If true, we should automatically remove the task from
                                // recents when activity finishes
    boolean askedCompatMode;// Have asked the user about compat mode for this task.
    private boolean mHasBeenVisible; // Set if any activities in the task have been visible

    String stringName;      // caching of toString() result.
    boolean mUserSetupComplete; // The user set-up is complete as of the last time the task activity
                                // was changed.

    /** Can't be put in lockTask mode. */
    final static int LOCK_TASK_AUTH_DONT_LOCK = 0;
    /** Can enter app pinning with user approval. Can never start over existing lockTask task. */
    final static int LOCK_TASK_AUTH_PINNABLE = 1;
    /** Starts in LOCK_TASK_MODE_LOCKED automatically. Can start over existing lockTask task. */
    final static int LOCK_TASK_AUTH_LAUNCHABLE = 2;
    /** Can enter lockTask without user approval. Can start over existing lockTask task. */
    final static int LOCK_TASK_AUTH_ALLOWLISTED = 3;
    /** Priv-app that starts in LOCK_TASK_MODE_LOCKED automatically. Can start over existing
     * lockTask task. */
    final static int LOCK_TASK_AUTH_LAUNCHABLE_PRIV = 4;
    int mLockTaskAuth = LOCK_TASK_AUTH_PINNABLE;

    int mLockTaskUid = -1;  // The uid of the application that called startLockTask().

    /** The process that had previously hosted the root activity of this task.
     * Used to know that we should try harder to keep this process around, in case the
     * user wants to return to it. */
    private WindowProcessController mRootProcess;

    /** Takes on same value as first root activity */
    boolean isPersistable = false;
    int maxRecents;

    /** Only used for persistable tasks, otherwise 0. The last time this task was moved. Used for
     * determining the order when restoring. Sign indicates whether last task movement was to front
     * (positive) or back (negative). Absolute value indicates time. */
    long mLastTimeMoved;

    /** If original intent did not allow relinquishing task identity, save that information */
    private boolean mNeverRelinquishIdentity = true;

    // Used in the unique case where we are clearing the task in order to reuse it. In that case we
    // do not want to delete the stack when the task goes empty.
    private boolean mReuseTask = false;

    CharSequence lastDescription; // Last description captured for this item.

    int mAffiliatedTaskId; // taskId of parent affiliation or self if no parent.
    Task mPrevAffiliate; // previous task in affiliated chain.
    int mPrevAffiliateTaskId = INVALID_TASK_ID; // previous id for persistence.
    Task mNextAffiliate; // next task in affiliated chain.
    int mNextAffiliateTaskId = INVALID_TASK_ID; // next id for persistence.

    // For relaunching the task from recents as though it was launched by the original launcher.
    int mCallingUid;
    String mCallingPackage;
    String mCallingFeatureId;

    private final Rect mTmpStableBounds = new Rect();
    private final Rect mTmpNonDecorBounds = new Rect();
    private final Rect mTmpBounds = new Rect();
    private final Rect mTmpInsets = new Rect();
    private final Rect mTmpFullBounds = new Rect();

    // Last non-fullscreen bounds the task was launched in or resized to.
    // The information is persisted and used to determine the appropriate stack to launch the
    // task into on restore.
    Rect mLastNonFullscreenBounds = null;
    // Minimal width and height of this task when it's resizeable. -1 means it should use the
    // default minimal width/height.
    int mMinWidth;
    int mMinHeight;

    // Ranking (from top) of this task among all visible tasks. (-1 means it's not visible)
    // This number will be assigned when we evaluate OOM scores for all visible tasks.
    int mLayerRank = -1;

    /** Helper object used for updating override configuration. */
    private Configuration mTmpConfig = new Configuration();

    /** Used by fillTaskInfo */
    final TaskActivitiesReport mReuseActivitiesReport = new TaskActivitiesReport();

    final ActivityTaskManagerService mAtmService;
    final ActivityStackSupervisor mStackSupervisor;
    final RootWindowContainer mRootWindowContainer;

    /* Unique identifier for this task. */
    final int mTaskId;
    /* User for which this task was created. */
    // TODO: Make final
    int mUserId;

    final Rect mPreparedFrozenBounds = new Rect();
    final Configuration mPreparedFrozenMergedConfig = new Configuration();

    // Id of the previous display the stack was on.
    int mPrevDisplayId = INVALID_DISPLAY;

    /** ID of the display which rotation {@link #mRotation} has. */
    private int mLastRotationDisplayId = INVALID_DISPLAY;

    /**
     * Display rotation as of the last time {@link #setBounds(Rect)} was called or this task was
     * moved to a new display.
     */
    @Surface.Rotation
    private int mRotation;

    /**
     * Last requested orientation reported to DisplayContent. This is different from {@link
     * #mOrientation} in the sense that this takes activities' requested orientation into
     * account. Start with {@link ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} so that we don't need
     * to notify for activities that don't specify any orientation.
     */
    int mLastReportedRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    // For comparison with DisplayContent bounds.
    private Rect mTmpRect = new Rect();
    // For handling display rotations.
    private Rect mTmpRect2 = new Rect();

    // Resize mode of the task. See {@link ActivityInfo#resizeMode}
    // Based on the {@link ActivityInfo#resizeMode} of the root activity.
    int mResizeMode;

    // Whether or not this task and its activities support PiP. Based on the
    // {@link ActivityInfo#FLAG_SUPPORTS_PICTURE_IN_PICTURE} flag of the root activity.
    boolean mSupportsPictureInPicture;

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;
    private int mDragResizeMode;

    // This represents the last resolved activity values for this task
    // NOTE: This value needs to be persisted with each task
    private TaskDescription mTaskDescription;

    // If set to true, the task will report that it is not in the floating
    // state regardless of it's stack affiliation. As the floating state drives
    // production of content insets this can be used to preserve them across
    // stack moves and we in fact do so when moving from full screen to pinned.
    private boolean mPreserveNonFloatingState = false;

    private Dimmer mDimmer = new Dimmer(this);
    private final Rect mTmpDimBoundsRect = new Rect();
    private final Point mLastSurfaceSize = new Point();

    /** @see #setCanAffectSystemUiFlags */
    private boolean mCanAffectSystemUiFlags = true;

    private static Exception sTmpException;

    /** ActivityRecords that are exiting, but still on screen for animations. */
    final ArrayList<ActivityRecord> mExitingActivities = new ArrayList<>();

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     */
    ActivityRecord mPausingActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    ActivityRecord mLastPausedActivity = null;

    /**
     * Activities that specify No History must be removed once the user navigates away from them.
     * If the device goes to sleep with such an activity in the paused state then we save it here
     * and finish it later if another activity replaces it on wakeup.
     */
    ActivityRecord mLastNoHistoryActivity = null;

    /** Current activity that is resumed, or null if there is none. */
    ActivityRecord mResumedActivity = null;

    private boolean mForceShowForAllUsers;

    /** When set, will force the task to report as invisible. */
    static final int FLAG_FORCE_HIDDEN_FOR_PINNED_TASK = 1;
    static final int FLAG_FORCE_HIDDEN_FOR_TASK_ORG = 1 << 1;
    private int mForceHiddenFlags = 0;

    // TODO(b/160201781): Revisit double invocation issue in Task#removeChild.
    /**
     * Skip {@link ActivityStackSupervisor#removeTask(Task, boolean, boolean, String)} execution if
     * {@code true} to prevent double traversal of {@link #mChildren} in a loop.
     */
    boolean mInRemoveTask;

    // When non-null, this is a transaction that will get applied on the next frame returned after
    // a relayout is requested from the client. While this is only valid on a leaf task; since the
    // transaction can effect an ancestor task, this also needs to keep track of the ancestor task
    // that this transaction manipulates because deferUntilFrame acts on individual surfaces.
    SurfaceControl.Transaction mMainWindowSizeChangeTransaction;
    Task mMainWindowSizeChangeTask;

    Rect mPreAnimationBounds = new Rect();

    private final AnimatingActivityRegistry mAnimatingActivityRegistry =
            new AnimatingActivityRegistry();

    private boolean mTopActivityOccludesKeyguard;
    private ActivityRecord mTopDismissingKeyguardActivity;

    private static final int TRANSLUCENT_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 1;

    private final Handler mHandler;

    private class ActivityStackHandler extends Handler {

        ActivityStackHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case TRANSLUCENT_TIMEOUT_MSG: {
                    synchronized (mAtmService.mGlobalLock) {
                        notifyActivityDrawnLocked(null);
                    }
                } break;
            }
        }
    }

    private static final ResetTargetTaskHelper sResetTargetTaskHelper = new ResetTargetTaskHelper();
    private final EnsureActivitiesVisibleHelper mEnsureActivitiesVisibleHelper =
            new EnsureActivitiesVisibleHelper(this);
    private final EnsureVisibleActivitiesConfigHelper mEnsureVisibleActivitiesConfigHelper =
            new EnsureVisibleActivitiesConfigHelper();
    private class EnsureVisibleActivitiesConfigHelper {
        private boolean mUpdateConfig;
        private boolean mPreserveWindow;
        private boolean mBehindFullscreen;

        void reset(boolean preserveWindow) {
            mPreserveWindow = preserveWindow;
            mUpdateConfig = false;
            mBehindFullscreen = false;
        }

        void process(ActivityRecord start, boolean preserveWindow) {
            if (start == null || !start.mVisibleRequested) {
                return;
            }
            reset(preserveWindow);

            final PooledFunction f = PooledLambda.obtainFunction(
                    EnsureVisibleActivitiesConfigHelper::processActivity, this,
                    PooledLambda.__(ActivityRecord.class));
            forAllActivities(f, start, true /*includeBoundary*/, true /*traverseTopToBottom*/);
            f.recycle();

            if (mUpdateConfig) {
                // Ensure the resumed state of the focus activity if we updated the configuration of
                // any activity.
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
        }

        boolean processActivity(ActivityRecord r) {
            mUpdateConfig |= r.ensureActivityConfiguration(0 /*globalChanges*/, mPreserveWindow);
            mBehindFullscreen |= r.occludesParent();
            return mBehindFullscreen;
        }
    }

    private final CheckBehindFullscreenActivityHelper mCheckBehindFullscreenActivityHelper =
            new CheckBehindFullscreenActivityHelper();
    private class CheckBehindFullscreenActivityHelper {
        private boolean mAboveTop;
        private boolean mBehindFullscreenActivity;
        private ActivityRecord mToCheck;
        private Consumer<ActivityRecord> mHandleBehindFullscreenActivity;
        private boolean mHandlingOccluded;

        private void reset(ActivityRecord toCheck,
                Consumer<ActivityRecord> handleBehindFullscreenActivity) {
            mToCheck = toCheck;
            mHandleBehindFullscreenActivity = handleBehindFullscreenActivity;
            mAboveTop = true;
            mBehindFullscreenActivity = false;

            if (!shouldBeVisible(null)) {
                // The stack is not visible, so no activity in it should be displaying a starting
                // window. Mark all activities below top and behind fullscreen.
                mAboveTop = false;
                mBehindFullscreenActivity = true;
            }

            mHandlingOccluded = mToCheck == null && mHandleBehindFullscreenActivity != null;
        }

        boolean process(ActivityRecord toCheck,
                Consumer<ActivityRecord> handleBehindFullscreenActivity) {
            reset(toCheck, handleBehindFullscreenActivity);

            if (!mHandlingOccluded && mBehindFullscreenActivity) {
                return true;
            }

            final ActivityRecord topActivity = topRunningActivity();
            final PooledFunction f = PooledLambda.obtainFunction(
                    CheckBehindFullscreenActivityHelper::processActivity, this,
                    PooledLambda.__(ActivityRecord.class), topActivity);
            forAllActivities(f);
            f.recycle();

            return mBehindFullscreenActivity;
        }

        /** Returns {@code true} to stop the outer loop and indicate the result is computed. */
        private boolean processActivity(ActivityRecord r, ActivityRecord topActivity) {
            if (mAboveTop) {
                if (r == topActivity) {
                    if (r == mToCheck) {
                        // It is the top activity in a visible stack.
                        mBehindFullscreenActivity = false;
                        return true;
                    }
                    mAboveTop = false;
                }
                mBehindFullscreenActivity |= r.occludesParent();
                return false;
            }

            if (mHandlingOccluded) {
                // Iterating through all occluded activities.
                if (mBehindFullscreenActivity) {
                    mHandleBehindFullscreenActivity.accept(r);
                }
            } else if (r == mToCheck) {
                return true;
            } else if (mBehindFullscreenActivity) {
                // It is occluded before {@param toCheck} is found.
                return true;
            }
            mBehindFullscreenActivity |= r.occludesParent();
            return false;
        }
    }

    private final FindRootHelper mFindRootHelper = new FindRootHelper();
    private class FindRootHelper {
        private ActivityRecord mRoot;

        private void clear() {
            mRoot = null;
        }

        ActivityRecord findRoot(boolean ignoreRelinquishIdentity, boolean setToBottomIfNone) {
            final PooledFunction f = PooledLambda.obtainFunction(FindRootHelper::processActivity,
                    this, PooledLambda.__(ActivityRecord.class), ignoreRelinquishIdentity,
                    setToBottomIfNone);
            clear();
            forAllActivities(f, false /*traverseTopToBottom*/);
            f.recycle();
            return mRoot;
        }

        private boolean processActivity(ActivityRecord r,
                boolean ignoreRelinquishIdentity, boolean setToBottomIfNone) {
            if (mRoot == null && setToBottomIfNone) {
                // This is the first activity we are process. Set it as the candidate root in case
                // we don't find a better one.
                mRoot = r;
            }

            if (r.finishing) return false;

            // Set this as the candidate root since it isn't finishing.
            mRoot = r;

            // Only end search if we are ignore relinquishing identity or we are not relinquishing.
            return ignoreRelinquishIdentity || (r.info.flags & FLAG_RELINQUISH_TASK_IDENTITY) == 0;
        }
    }

    /**
     * The TaskOrganizer which is delegated presentation of this task. If set the Task will
     * emit an WindowContainerToken (allowing access to it's SurfaceControl leash) to the organizers
     * taskAppeared callback, and emit a taskRemoved callback when the Task is vanished.
     */
    ITaskOrganizer mTaskOrganizer;
    private int mLastTaskOrganizerWindowingMode = -1;
    /**
     * Prevent duplicate calls to onTaskAppeared.
     */
    boolean mTaskAppearedSent;

    /**
     * This task was created by the task organizer which has the following implementations.
     * <ul>
     *     <lis>The task won't be removed when it is empty. Removal has to be an explicit request
     *     from the task organizer.</li>
     *     <li>Unlike other non-root tasks, it's direct children are visible to the task
     *     organizer for ordering purposes.</li>
     * </ul>
     */
    boolean mCreatedByOrganizer;

    /**
     * Don't use constructor directly. Use {@link TaskDisplayArea#createStackUnchecked()} instead.
     */
    Task(ActivityTaskManagerService atmService, int id, int activityType,
            ActivityInfo info, Intent intent, boolean createdByOrganizer) {
        this(atmService, id, info, intent, null /*voiceSession*/, null /*voiceInteractor*/,
                null /*taskDescription*/, null /*stack*/);
        mCreatedByOrganizer = createdByOrganizer;
        setActivityType(activityType);
    }

    /**
     * Don't use constructor directly. Use {@link Task#reuseOrCreateTask()} instead.
     */
    Task(ActivityTaskManagerService atmService, int _taskId, ActivityInfo info, Intent _intent,
            IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
            TaskDescription _taskDescription, Task stack) {
        this(atmService, _taskId, _intent,  null /*_affinityIntent*/, null /*_affinity*/,
                null /*_rootAffinity*/, null /*_realActivity*/, null /*_origActivity*/,
                false /*_rootWasReset*/, false /*_autoRemoveRecents*/, false /*_askedCompatMode*/,
                UserHandle.getUserId(info.applicationInfo.uid), 0 /*_effectiveUid*/,
                null /*_lastDescription*/, System.currentTimeMillis(),
                true /*neverRelinquishIdentity*/,
                _taskDescription != null ? _taskDescription : new TaskDescription(),
                _taskId, INVALID_TASK_ID, INVALID_TASK_ID,
                info.applicationInfo.uid, info.packageName, null /* default featureId */,
                info.resizeMode, info.supportsPictureInPicture(), false /*_realActivitySuspended*/,
                false /*userSetupComplete*/, INVALID_MIN_SIZE, INVALID_MIN_SIZE, info,
                _voiceSession, _voiceInteractor, stack);
    }

    /** Don't use constructor directly. This is only used by XML parser. */
    Task(ActivityTaskManagerService atmService, int _taskId, Intent _intent, Intent _affinityIntent,
            String _affinity, String _rootAffinity, ComponentName _realActivity,
            ComponentName _origActivity, boolean _rootWasReset, boolean _autoRemoveRecents,
            boolean _askedCompatMode, int _userId, int _effectiveUid, String _lastDescription,
            long lastTimeMoved, boolean neverRelinquishIdentity,
            TaskDescription _lastTaskDescription, int taskAffiliation, int prevTaskId,
            int nextTaskId, int callingUid, String callingPackage,
            @Nullable String callingFeatureId, int resizeMode, boolean supportsPictureInPicture,
            boolean _realActivitySuspended, boolean userSetupComplete, int minWidth, int minHeight,
            ActivityInfo info, IVoiceInteractionSession _voiceSession,
            IVoiceInteractor _voiceInteractor, Task stack) {
        super(atmService.mWindowManager);

        EventLogTags.writeWmTaskCreated(_taskId, stack != null ? getRootTaskId() : INVALID_TASK_ID);
        mAtmService = atmService;
        mStackSupervisor = atmService.mStackSupervisor;
        mRootWindowContainer = mAtmService.mRootWindowContainer;
        mTaskId = _taskId;
        mUserId = _userId;
        mResizeMode = resizeMode;
        mSupportsPictureInPicture = supportsPictureInPicture;
        mTaskDescription = _lastTaskDescription;
        // Tasks have no set orientation value (including SCREEN_ORIENTATION_UNSPECIFIED).
        setOrientation(SCREEN_ORIENTATION_UNSET);
        mRemoteToken = new RemoteToken(this);
        affinityIntent = _affinityIntent;
        affinity = _affinity;
        rootAffinity = _rootAffinity;
        voiceSession = _voiceSession;
        voiceInteractor = _voiceInteractor;
        realActivity = _realActivity;
        realActivitySuspended = _realActivitySuspended;
        origActivity = _origActivity;
        rootWasReset = _rootWasReset;
        isAvailable = true;
        autoRemoveRecents = _autoRemoveRecents;
        askedCompatMode = _askedCompatMode;
        mUserSetupComplete = userSetupComplete;
        effectiveUid = _effectiveUid;
        touchActiveTime();
        lastDescription = _lastDescription;
        mLastTimeMoved = lastTimeMoved;
        mNeverRelinquishIdentity = neverRelinquishIdentity;
        mAffiliatedTaskId = taskAffiliation;
        mPrevAffiliateTaskId = prevTaskId;
        mNextAffiliateTaskId = nextTaskId;
        mCallingUid = callingUid;
        mCallingPackage = callingPackage;
        mCallingFeatureId = callingFeatureId;
        mResizeMode = resizeMode;
        if (info != null) {
            setIntent(_intent, info);
            setMinDimensions(info);
        } else {
            intent = _intent;
            mMinWidth = minWidth;
            mMinHeight = minHeight;
        }
        mAtmService.getTaskChangeNotificationController().notifyTaskCreated(_taskId, realActivity);
        mHandler = new ActivityStackHandler(mStackSupervisor.mLooper);
        mCurrentUser = mAtmService.mAmInternal.getCurrentUserId();
    }

    Task reuseAsLeafTask(IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
            Intent intent, ActivityInfo info, ActivityRecord activity) {
        voiceSession = _voiceSession;
        voiceInteractor = _voiceInteractor;
        setIntent(activity, intent, info);
        setMinDimensions(info);
        // Before we began to reuse a root task (old ActivityStack) as the leaf task, we used to
        // create a leaf task in this case. Therefore now we won't send out the task created
        // notification when we decide to reuse it here, so we send out the notification below.
        // The reason why the created notification sent out when root task is created doesn't work
        // is that realActivity isn't set until setIntent() method above is called for the first
        // time. Eventually this notification will be removed when we can populate those information
        // when root task is created.
        mAtmService.getTaskChangeNotificationController().notifyTaskCreated(mTaskId, realActivity);
        return this;
    }

    private void cleanUpResourcesForDestroy(ConfigurationContainer oldParent) {
        if (hasChild()) {
            return;
        }

        if (isLeafTask()) {
            // This task is going away, so save the last state if necessary.
            saveLaunchingStateIfNeeded(((WindowContainer) oldParent).getDisplayContent());
        }

        // TODO: VI what about activity?
        final boolean isVoiceSession = voiceSession != null;
        if (isVoiceSession) {
            try {
                voiceSession.taskFinished(intent, mTaskId);
            } catch (RemoteException e) {
            }
        }
        if (autoRemoveFromRecents() || isVoiceSession) {
            // Task creator asked to remove this when done, or this task was a voice
            // interaction, so it should not remain on the recent tasks list.
            mStackSupervisor.mRecentTasks.remove(this);
        }

        removeIfPossible();
    }

    @VisibleForTesting
    @Override
    void removeIfPossible() {
        final boolean isRootTask = isRootTask();
        if (!isRootTask) {
            mAtmService.getLockTaskController().clearLockedTask(this);
        }
        if (shouldDeferRemoval()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            return;
        }
        removeImmediately();
        if (isLeafTask()) {
            mAtmService.getTaskChangeNotificationController().notifyTaskRemoved(mTaskId);
        }
    }

    void setResizeMode(int resizeMode) {
        if (mResizeMode == resizeMode) {
            return;
        }
        mResizeMode = resizeMode;
        mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        mRootWindowContainer.resumeFocusedStacksTopActivities();
        updateTaskDescription();
    }

    boolean resize(Rect bounds, int resizeMode, boolean preserveWindow) {
        mAtmService.deferWindowLayout();

        try {
            final boolean forced = (resizeMode & RESIZE_MODE_FORCED) != 0;

            if (getParent() == null) {
                // Task doesn't exist in window manager yet (e.g. was restored from recents).
                // All we can do for now is update the bounds so it can be used when the task is
                // added to window manager.
                setBounds(bounds);
                if (!inFreeformWindowingMode()) {
                    // re-restore the task so it can have the proper stack association.
                    mStackSupervisor.restoreRecentTaskLocked(this, null, !ON_TOP);
                }
                return true;
            }

            if (!canResizeToBounds(bounds)) {
                throw new IllegalArgumentException("resizeTask: Can not resize task=" + this
                        + " to bounds=" + bounds + " resizeMode=" + mResizeMode);
            }

            // Do not move the task to another stack here.
            // This method assumes that the task is already placed in the right stack.
            // we do not mess with that decision and we only do the resize!

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "resizeTask_" + mTaskId);

            boolean updatedConfig = false;
            mTmpConfig.setTo(getResolvedOverrideConfiguration());
            if (setBounds(bounds) != BOUNDS_CHANGE_NONE) {
                updatedConfig = !mTmpConfig.equals(getResolvedOverrideConfiguration());
            }
            // This variable holds information whether the configuration didn't change in a
            // significant way and the activity was kept the way it was. If it's false, it means
            // the activity had to be relaunched due to configuration change.
            boolean kept = true;
            if (updatedConfig) {
                final ActivityRecord r = topRunningActivityLocked();
                if (r != null) {
                    kept = r.ensureActivityConfiguration(0 /* globalChanges */,
                            preserveWindow);
                    // Preserve other windows for resizing because if resizing happens when there
                    // is a dialog activity in the front, the activity that still shows some
                    // content to the user will become black and cause flickers. Note in most cases
                    // this won't cause tons of irrelevant windows being preserved because only
                    // activities in this task may experience a bounds change. Configs for other
                    // activities stay the same.
                    mRootWindowContainer.ensureActivitiesVisible(r, 0, preserveWindow);
                    if (!kept) {
                        mRootWindowContainer.resumeFocusedStacksTopActivities();
                    }
                }
            }
            resize(kept, forced);

            saveLaunchingStateIfNeeded();

            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return kept;
        } finally {
            mAtmService.continueWindowLayout();
        }
    }

    /** Convenience method to reparent a task to the top or bottom position of the stack. */
    boolean reparent(Task preferredStack, boolean toTop,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            String reason) {
        return reparent(preferredStack, toTop ? MAX_VALUE : 0, moveStackMode, animate, deferResume,
                true /* schedulePictureInPictureModeChange */, reason);
    }

    /**
     * Convenience method to reparent a task to the top or bottom position of the stack, with
     * an option to skip scheduling the picture-in-picture mode change.
     */
    boolean reparent(Task preferredStack, boolean toTop,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            boolean schedulePictureInPictureModeChange, String reason) {
        return reparent(preferredStack, toTop ? MAX_VALUE : 0, moveStackMode, animate,
                deferResume, schedulePictureInPictureModeChange, reason);
    }

    /** Convenience method to reparent a task to a specific position of the stack. */
    boolean reparent(Task preferredStack, int position,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            String reason) {
        return reparent(preferredStack, position, moveStackMode, animate, deferResume,
                true /* schedulePictureInPictureModeChange */, reason);
    }

    /**
     * Reparents the task into a preferred stack, creating it if necessary.
     *
     * @param preferredStack the target stack to move this task
     * @param position the position to place this task in the new stack
     * @param animate whether or not we should wait for the new window created as a part of the
     *            reparenting to be drawn and animated in
     * @param moveStackMode whether or not to move the stack to the front always, only if it was
     *            previously focused & in front, or never
     * @param deferResume whether or not to update the visibility of other tasks and stacks that may
     *            have changed as a result of this reparenting
     * @param schedulePictureInPictureModeChange specifies whether or not to schedule the PiP mode
     *            change. Callers may set this to false if they are explicitly scheduling PiP mode
     *            changes themselves, like during the PiP animation
     * @param reason the caller of this reparenting
     * @return whether the task was reparented
     */
    // TODO: Inspect all call sites and change to just changing windowing mode of the stack vs.
    // re-parenting the task. Can only be done when we are no longer using static stack Ids.
    boolean reparent(Task preferredStack, int position,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            boolean schedulePictureInPictureModeChange, String reason) {
        final ActivityStackSupervisor supervisor = mStackSupervisor;
        final RootWindowContainer root = mRootWindowContainer;
        final WindowManagerService windowManager = mAtmService.mWindowManager;
        final Task sourceStack = getRootTask();
        final Task toStack = supervisor.getReparentTargetStack(this, preferredStack,
                position == MAX_VALUE);
        if (toStack == sourceStack) {
            return false;
        }
        if (!canBeLaunchedOnDisplay(toStack.getDisplayId())) {
            return false;
        }

        final boolean toTopOfStack = position == MAX_VALUE;
        if (toTopOfStack && toStack.getResumedActivity() != null
                && toStack.topRunningActivity() != null) {
            // Pause the resumed activity on the target stack while re-parenting task on top of it.
            toStack.startPausingLocked(false /* userLeaving */, false /* uiSleeping */,
                    null /* resuming */);
        }

        final int toStackWindowingMode = toStack.getWindowingMode();
        final ActivityRecord topActivity = getTopNonFinishingActivity();

        final boolean mightReplaceWindow = topActivity != null
                && replaceWindowsOnTaskMove(getWindowingMode(), toStackWindowingMode);
        if (mightReplaceWindow) {
            // We are about to relaunch the activity because its configuration changed due to
            // being maximized, i.e. size change. The activity will first remove the old window
            // and then add a new one. This call will tell window manager about this, so it can
            // preserve the old window until the new one is drawn. This prevents having a gap
            // between the removal and addition, in which no window is visible. We also want the
            // entrance of the new window to be properly animated.
            // Note here we always set the replacing window first, as the flags might be needed
            // during the relaunch. If we end up not doing any relaunch, we clear the flags later.
            windowManager.setWillReplaceWindow(topActivity.appToken, animate);
        }

        mAtmService.deferWindowLayout();
        boolean kept = true;
        try {
            final ActivityRecord r = topRunningActivityLocked();
            final boolean wasFocused = r != null && root.isTopDisplayFocusedStack(sourceStack)
                    && (topRunningActivityLocked() == r);
            final boolean wasResumed = r != null && sourceStack.getResumedActivity() == r;
            final boolean wasPaused = r != null && sourceStack.mPausingActivity == r;

            // In some cases the focused stack isn't the front stack. E.g. pinned stack.
            // Whenever we are moving the top activity from the front stack we want to make sure to
            // move the stack to the front.
            final boolean wasFront = r != null && sourceStack.isTopStackInDisplayArea()
                    && (sourceStack.topRunningActivity() == r);

            final boolean moveStackToFront = moveStackMode == REPARENT_MOVE_STACK_TO_FRONT
                    || (moveStackMode == REPARENT_KEEP_STACK_AT_FRONT && (wasFocused || wasFront));

            reparent(toStack, position, moveStackToFront, reason);

            if (schedulePictureInPictureModeChange) {
                // Notify of picture-in-picture mode changes
                supervisor.scheduleUpdatePictureInPictureModeIfNeeded(this, sourceStack);
            }

            // If the task had focus before (or we're requested to move focus), move focus to the
            // new stack by moving the stack to the front.
            if (r != null) {
                toStack.moveToFrontAndResumeStateIfNeeded(r, moveStackToFront, wasResumed,
                        wasPaused, reason);
            }
            if (!animate) {
                mStackSupervisor.mNoAnimActivities.add(topActivity);
            }

            // We might trigger a configuration change. Save the current task bounds for freezing.
            // TODO: Should this call be moved inside the resize method in WM?
            toStack.prepareFreezingTaskBounds();

            if (toStackWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    && moveStackMode == REPARENT_KEEP_STACK_AT_FRONT) {
                // Move recents to front so it is not behind home stack when going into docked
                // mode
                mStackSupervisor.moveRecentsStackToFront(reason);
            }
        } finally {
            mAtmService.continueWindowLayout();
        }

        if (mightReplaceWindow) {
            // If we didn't actual do a relaunch (indicated by kept==true meaning we kept the old
            // window), we need to clear the replace window settings. Otherwise, we schedule a
            // timeout to remove the old window if the replacing window is not coming in time.
            windowManager.scheduleClearWillReplaceWindows(topActivity.appToken, !kept);
        }

        if (!deferResume) {
            // The task might have already been running and its visibility needs to be synchronized
            // with the visibility of the stack / windows.
            root.ensureActivitiesVisible(null, 0, !mightReplaceWindow);
            root.resumeFocusedStacksTopActivities();
        }

        // TODO: Handle incorrect request to move before the actual move, not after.
        supervisor.handleNonResizableTaskIfNeeded(this, preferredStack.getWindowingMode(),
                mRootWindowContainer.getDefaultTaskDisplayArea(), toStack);

        return (preferredStack == toStack);
    }

    /**
     * @return {@code true} if the windows of tasks being moved to the target stack from the
     * source stack should be replaced, meaning that window manager will keep the old window
     * around until the new is ready.
     */
    private static boolean replaceWindowsOnTaskMove(
            int sourceWindowingMode, int targetWindowingMode) {
        return sourceWindowingMode == WINDOWING_MODE_FREEFORM
                || targetWindowingMode == WINDOWING_MODE_FREEFORM;
    }

    /**
     * DO NOT HOLD THE ACTIVITY MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    TaskSnapshot getSnapshot(boolean isLowResolution, boolean restoreFromDisk) {

        // TODO: Move this to {@link TaskWindowContainerController} once recent tasks are more
        // synchronized between AM and WM.
        return mAtmService.mWindowManager.getTaskSnapshot(mTaskId, mUserId, isLowResolution,
                restoreFromDisk);
    }

    void touchActiveTime() {
        lastActiveTime = SystemClock.elapsedRealtime();
    }

    long getInactiveDuration() {
        return SystemClock.elapsedRealtime() - lastActiveTime;
    }

    /** @see #setIntent(ActivityRecord, Intent, ActivityInfo) */
    void setIntent(ActivityRecord r) {
        setIntent(r, null /* intent */, null /* info */);
    }

    /**
     * Sets the original intent, and the calling uid and package.
     *
     * @param r The activity that started the task
     * @param intent The task info which could be different from {@code r.intent} if set.
     * @param info The activity info which could be different from {@code r.info} if set.
     */
    void setIntent(ActivityRecord r, @Nullable Intent intent, @Nullable ActivityInfo info) {
        mCallingUid = r.launchedFromUid;
        mCallingPackage = r.launchedFromPackage;
        mCallingFeatureId = r.launchedFromFeatureId;
        setIntent(intent != null ? intent : r.intent, info != null ? info : r.info);
        setLockTaskAuth(r);

        final WindowContainer parent = getParent();
        if (parent != null) {
            final Task t = parent.asTask();
            if (t != null) {
                t.setIntent(r);
            }
        }
    }

    /** Sets the original intent, _without_ updating the calling uid or package. */
    private void setIntent(Intent _intent, ActivityInfo info) {
        final boolean isLeaf = isLeafTask();
        if (intent == null) {
            mNeverRelinquishIdentity =
                    (info.flags & FLAG_RELINQUISH_TASK_IDENTITY) == 0;
        } else if (mNeverRelinquishIdentity && isLeaf) {
            return;
        }

        affinity = isLeaf ? info.taskAffinity : null;
        if (intent == null) {
            // If this task already has an intent associated with it, don't set the root
            // affinity -- we don't want it changing after initially set, but the initially
            // set value may be null.
            rootAffinity = affinity;
        }
        effectiveUid = info.applicationInfo.uid;
        stringName = null;

        if (info.targetActivity == null) {
            if (_intent != null) {
                // If this Intent has a selector, we want to clear it for the
                // recent task since it is not relevant if the user later wants
                // to re-launch the app.
                if (_intent.getSelector() != null || _intent.getSourceBounds() != null) {
                    _intent = new Intent(_intent);
                    _intent.setSelector(null);
                    _intent.setSourceBounds(null);
                }
            }
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Setting Intent of " + this + " to " + _intent);
            intent = _intent;
            realActivity = _intent != null ? _intent.getComponent() : null;
            origActivity = null;
        } else {
            ComponentName targetComponent = new ComponentName(
                    info.packageName, info.targetActivity);
            if (_intent != null) {
                Intent targetIntent = new Intent(_intent);
                targetIntent.setSelector(null);
                targetIntent.setSourceBounds(null);
                if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                        "Setting Intent of " + this + " to target " + targetIntent);
                intent = targetIntent;
                realActivity = targetComponent;
                origActivity = _intent.getComponent();
            } else {
                intent = null;
                realActivity = targetComponent;
                origActivity = new ComponentName(info.packageName, info.name);
            }
        }
        mWindowLayoutAffinity =
                info.windowLayout == null ? null : info.windowLayout.windowLayoutAffinity;

        final int intentFlags = intent == null ? 0 : intent.getFlags();
        if ((intentFlags & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            // Once we are set to an Intent with this flag, we count this
            // task as having a true root activity.
            rootWasReset = true;
        }
        mUserId = UserHandle.getUserId(info.applicationInfo.uid);
        mUserSetupComplete = Settings.Secure.getIntForUser(
                mAtmService.mContext.getContentResolver(), USER_SETUP_COMPLETE, 0, mUserId) != 0;
        if ((info.flags & ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS) != 0) {
            // If the activity itself has requested auto-remove, then just always do it.
            autoRemoveRecents = true;
        } else if ((intentFlags & (FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_RETAIN_IN_RECENTS))
                == FLAG_ACTIVITY_NEW_DOCUMENT) {
            // If the caller has not asked for the document to be retained, then we may
            // want to turn on auto-remove, depending on whether the target has set its
            // own document launch mode.
            if (info.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NONE) {
                autoRemoveRecents = false;
            } else {
                autoRemoveRecents = true;
            }
        } else {
            autoRemoveRecents = false;
        }
        if (mResizeMode != info.resizeMode) {
            mResizeMode = info.resizeMode;
            updateTaskDescription();
        }
        mSupportsPictureInPicture = info.supportsPictureInPicture();
    }

    /** Sets the original minimal width and height. */
    void setMinDimensions(ActivityInfo info) {
        if (info != null && info.windowLayout != null) {
            mMinWidth = info.windowLayout.minWidth;
            mMinHeight = info.windowLayout.minHeight;
        } else {
            mMinWidth = INVALID_MIN_SIZE;
            mMinHeight = INVALID_MIN_SIZE;
        }
    }

    /**
     * Return true if the input activity has the same intent filter as the intent this task
     * record is based on (normally the root activity intent).
     */
    boolean isSameIntentFilter(ActivityRecord r) {
        final Intent intent = new Intent(r.intent);
        // Make sure the component are the same if the input activity has the same real activity
        // as the one in the task because either one of them could be the alias activity.
        if (Objects.equals(realActivity, r.mActivityComponent) && this.intent != null) {
            intent.setComponent(this.intent.getComponent());
        }
        return intent.filterEquals(this.intent);
    }

    boolean returnsToHomeStack() {
        if (inMultiWindowMode() || !hasChild()) return false;
        if (intent != null) {
            final int returnHomeFlags = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME;
            return intent != null && (intent.getFlags() & returnHomeFlags) == returnHomeFlags;
        }
        final Task bottomTask = getBottomMostTask();
        return bottomTask != this && bottomTask.returnsToHomeStack();
    }

    void setPrevAffiliate(Task prevAffiliate) {
        mPrevAffiliate = prevAffiliate;
        mPrevAffiliateTaskId = prevAffiliate == null ? INVALID_TASK_ID : prevAffiliate.mTaskId;
    }

    void setNextAffiliate(Task nextAffiliate) {
        mNextAffiliate = nextAffiliate;
        mNextAffiliateTaskId = nextAffiliate == null ? INVALID_TASK_ID : nextAffiliate.mTaskId;
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        final DisplayContent display = newParent != null
                ? ((WindowContainer) newParent).getDisplayContent() : null;
        final DisplayContent oldDisplay = oldParent != null
                ? ((WindowContainer) oldParent).getDisplayContent() : null;

        mPrevDisplayId = (oldDisplay != null) ? oldDisplay.mDisplayId : INVALID_DISPLAY;

        if (oldParent != null && newParent == null) {
            cleanUpResourcesForDestroy(oldParent);
        }

        if (display != null) {
            // TODO(NOW!): Chat with the erosky@ of this code to see if this really makes sense here...
            // Rotations are relative to the display. This means if there are 2 displays rotated
            // differently (eg. 2 monitors with one landscape and one portrait), moving a stack
            // from one to the other could look like a rotation change. To prevent this
            // apparent rotation change (and corresponding bounds rotation), pretend like our
            // current rotation is already the same as the new display.
            // Note, if ActivityStack or related logic ever gets nested, this logic will need
            // to move to onConfigurationChanged.
            getConfiguration().windowConfiguration.setRotation(
                    display.getWindowConfiguration().getRotation());
        }

        super.onParentChanged(newParent, oldParent);

        // TODO(NOW): The check for null display content and setting it to null doesn't really
        //  make sense here...

        // TODO(stack-merge): This is mostly taking care of the case where the stask is removing from
        // the display, so we should probably consolidate it there instead.

        if (getParent() == null && mDisplayContent != null) {
            EventLogTags.writeWmStackRemoved(getRootTaskId());
            mDisplayContent = null;
            mWmService.mWindowPlacerLocked.requestTraversal();
        }

        if (oldParent != null) {
            final Task oldParentTask = ((WindowContainer) oldParent).asTask();
            if (oldParentTask != null) {
                final PooledConsumer c = PooledLambda.obtainConsumer(
                        Task::cleanUpActivityReferences, oldParentTask,
                        PooledLambda.__(ActivityRecord.class));
                forAllActivities(c);
                c.recycle();
            }

            if (oldParent.inPinnedWindowingMode()
                    && (newParent == null || !newParent.inPinnedWindowingMode())) {
                // Notify if a task from the pinned stack is being removed
                // (or moved depending on the mode).
                mAtmService.getTaskChangeNotificationController().notifyActivityUnpinned();
            }
        }

        if (newParent != null) {
            final Task newParentTask = ((WindowContainer) newParent).asTask();
            if (newParentTask != null) {
                final ActivityRecord top = newParentTask.getTopNonFinishingActivity(
                        false /* includeOverlays */);
                if (top != null && top.isState(RESUMED)) {
                    newParentTask.setResumedActivity(top, "addedToTask");
                }
            }

            // TODO: Ensure that this is actually necessary here
            // Notify the voice session if required
            if (voiceSession != null) {
                try {
                    voiceSession.taskStarted(intent, mTaskId);
                } catch (RemoteException e) {
                }
            }
        }

        // First time we are adding the task to the system.
        if (oldParent == null && newParent != null) {

            // TODO: Super random place to be doing this, but aligns with what used to be done
            // before we unified Task level. Look into if this can be done in a better place.
            updateOverrideConfigurationFromLaunchBounds();
        }

        // Update task bounds if needed.
        adjustBoundsForDisplayChangeIfNeeded(getDisplayContent());

        if (getWindowConfiguration().windowsAreScaleable()) {
            // We force windows out of SCALING_MODE_FREEZE so that we can continue to animate them
            // while a resize is pending.
            forceWindowsScaleable(true /* force */);
        } else {
            forceWindowsScaleable(false /* force */);
        }

        mRootWindowContainer.updateUIDsPresentOnDisplay();

        // Resume next focusable stack after reparenting to another display if we aren't removing
        // the prevous display.
        if (oldDisplay != null && oldDisplay.isRemoving()) {
            postReparent();
        }
    }

    void cleanUpActivityReferences(ActivityRecord r) {
        final WindowContainer parent = getParent();
        if (parent != null && parent.asTask() != null) {
            parent.asTask().cleanUpActivityReferences(r);
            return;
        }
        r.removeTimeouts();
        mExitingActivities.remove(r);

        if (mResumedActivity != null && mResumedActivity == r) {
            setResumedActivity(null, "cleanUpActivityReferences");
        }
        if (mPausingActivity != null && mPausingActivity == r) {
            mPausingActivity = null;
        }
    }

    /** @return the currently resumed activity. */
    ActivityRecord getResumedActivity() {
        return mResumedActivity;
    }

    void setResumedActivity(ActivityRecord r, String reason) {
        if (mResumedActivity == r) {
            return;
        }

        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) Slog.d(TAG_STACK,
                "setResumedActivity stack:" + this + " + from: "
                + mResumedActivity + " to:" + r + " reason:" + reason);
        mResumedActivity = r;
        mStackSupervisor.updateTopResumedActivityIfNeeded();
    }

    void updateTaskMovement(boolean toFront) {
        if (isPersistable) {
            mLastTimeMoved = System.currentTimeMillis();
            // Sign is used to keep tasks sorted when persisted. Tasks sent to the bottom most
            // recently will be most negative, tasks sent to the bottom before that will be less
            // negative. Similarly for recent tasks moved to the top which will be most positive.
            if (!toFront) {
                mLastTimeMoved *= -1;
            }
        }
        mRootWindowContainer.invalidateTaskLayers();
    }

    // Close up recents linked list.
    private void closeRecentsChain() {
        if (mPrevAffiliate != null) {
            mPrevAffiliate.setNextAffiliate(mNextAffiliate);
        }
        if (mNextAffiliate != null) {
            mNextAffiliate.setPrevAffiliate(mPrevAffiliate);
        }
        setPrevAffiliate(null);
        setNextAffiliate(null);
    }

    void removedFromRecents() {
        closeRecentsChain();
        if (inRecents) {
            inRecents = false;
            mAtmService.notifyTaskPersisterLocked(this, false);
        }

        clearRootProcess();

        mAtmService.mWindowManager.mTaskSnapshotController.notifyTaskRemovedFromRecents(
                mTaskId, mUserId);
    }

    void setTaskToAffiliateWith(Task taskToAffiliateWith) {
        closeRecentsChain();
        mAffiliatedTaskId = taskToAffiliateWith.mAffiliatedTaskId;
        // Find the end
        while (taskToAffiliateWith.mNextAffiliate != null) {
            final Task nextRecents = taskToAffiliateWith.mNextAffiliate;
            if (nextRecents.mAffiliatedTaskId != mAffiliatedTaskId) {
                Slog.e(TAG, "setTaskToAffiliateWith: nextRecents=" + nextRecents + " affilTaskId="
                        + nextRecents.mAffiliatedTaskId + " should be " + mAffiliatedTaskId);
                if (nextRecents.mPrevAffiliate == taskToAffiliateWith) {
                    nextRecents.setPrevAffiliate(null);
                }
                taskToAffiliateWith.setNextAffiliate(null);
                break;
            }
            taskToAffiliateWith = nextRecents;
        }
        taskToAffiliateWith.setNextAffiliate(this);
        setPrevAffiliate(taskToAffiliateWith);
        setNextAffiliate(null);
    }

    /** Returns the intent for the root activity for this task */
    Intent getBaseIntent() {
        if (intent != null) return intent;
        if (affinityIntent != null) return affinityIntent;
        // Probably a task that contains other tasks, so return the intent for the top task?
        final Task topTask = getTopMostTask();
        return (topTask != this && topTask != null) ? topTask.getBaseIntent() : null;
    }

    /** Returns the first non-finishing activity from the bottom. */
    ActivityRecord getRootActivity() {
        // TODO: Figure out why we historical ignore relinquish identity for this case...
        return getRootActivity(true /*ignoreRelinquishIdentity*/, false /*setToBottomIfNone*/);
    }

    ActivityRecord getRootActivity(boolean setToBottomIfNone) {
        return getRootActivity(false /*ignoreRelinquishIdentity*/, setToBottomIfNone);
    }

    ActivityRecord getRootActivity(boolean ignoreRelinquishIdentity, boolean setToBottomIfNone) {
        return mFindRootHelper.findRoot(ignoreRelinquishIdentity, setToBottomIfNone);
    }

    ActivityRecord getTopNonFinishingActivity() {
        return getTopNonFinishingActivity(true /* includeOverlays */);
    }

    ActivityRecord getTopNonFinishingActivity(boolean includeOverlays) {
        return getTopActivity(false /*includeFinishing*/, includeOverlays);
    }

    ActivityRecord topRunningActivityLocked() {
        if (getParent() == null) {
            return null;
        }
        return getActivity(ActivityRecord::canBeTopRunning);
    }

    /**
     * Return true if any activities in this task belongs to input uid.
     */
    boolean isUidPresent(int uid) {
        final PooledPredicate p = PooledLambda.obtainPredicate(
                ActivityRecord::isUid, PooledLambda.__(ActivityRecord.class), uid);
        final boolean isUidPresent = getActivity(p) != null;
        p.recycle();
        return isUidPresent;
    }

    ActivityRecord topActivityWithStartingWindow() {
        if (getParent() == null) {
            return null;
        }
        return getActivity((r) -> r.mStartingWindowState == STARTING_WINDOW_SHOWN
                && r.okToShowLocked());
    }

    /**
     * Return the number of running activities, and the number of non-finishing/initializing
     * activities in the provided {@param reportOut} respectively.
     */
    private void getNumRunningActivities(TaskActivitiesReport reportOut) {
        reportOut.reset();
        forAllActivities(reportOut);
    }

    /**
     * Reorder the history stack so that the passed activity is brought to the front.
     */
    final void moveActivityToFrontLocked(ActivityRecord newTop) {
        if (DEBUG_ADD_REMOVE) Slog.i(TAG_ADD_REMOVE, "Removing and adding activity "
                + newTop + " to stack at top callers=" + Debug.getCallers(4));

        positionChildAtTop(newTop);
        updateEffectiveIntent();
    }

    @Override
    public int getActivityType() {
        final int applicationType = super.getActivityType();
        if (applicationType != ACTIVITY_TYPE_UNDEFINED || !hasChild()) {
            return applicationType;
        }
        return getTopChild().getActivityType();
    }

    @Override
    void addChild(WindowContainer child, int index) {
        // If this task had any child before we added this one.
        boolean hadChild = hasChild();
        // getActivityType() looks at the top child, so we need to read the type before adding
        // a new child in case the new child is on top and UNDEFINED.
        final int activityType = getActivityType();

        index = getAdjustedChildPosition(child, index);
        super.addChild(child, index);

        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "addChild: %s at top.", this);

        // A rootable task that is now being added to be the child of an organized task. Making
        // sure the stack references is keep updated.
        if (mTaskOrganizer != null && mCreatedByOrganizer && child.asTask() != null) {
            getDisplayArea().addStackReferenceIfNeeded((Task) child);
        }

        // Make sure the list of display UID allowlists is updated
        // now that this record is in a new task.
        mRootWindowContainer.updateUIDsPresentOnDisplay();

        final ActivityRecord r = child.asActivityRecord();
        if (r == null) return;

        r.inHistory = true;

        // Only set this based on the first activity
        if (!hadChild) {
            if (r.getActivityType() == ACTIVITY_TYPE_UNDEFINED) {
                // Normally non-standard activity type for the activity record will be set when the
                // object is created, however we delay setting the standard application type until
                // this point so that the task can set the type for additional activities added in
                // the else condition below.
                r.setActivityType(ACTIVITY_TYPE_STANDARD);
            }
            setActivityType(r.getActivityType());
            isPersistable = r.isPersistable();
            mCallingUid = r.launchedFromUid;
            mCallingPackage = r.launchedFromPackage;
            mCallingFeatureId = r.launchedFromFeatureId;
            // Clamp to [1, max].
            maxRecents = Math.min(Math.max(r.info.maxRecents, 1),
                    ActivityTaskManager.getMaxAppRecentsLimitStatic());
        } else {
            // Otherwise make all added activities match this one.
            r.setActivityType(activityType);
        }

        updateEffectiveIntent();
    }

    void addChild(ActivityRecord r) {
        addChild(r, Integer.MAX_VALUE /* add on top */);
    }

    @Override
    void removeChild(WindowContainer child) {
        removeChild(child, "removeChild");
    }

    void removeChild(WindowContainer r, String reason) {
        // A rootable child task that is now being removed from an organized task. Making sure
        // the stack references is keep updated.
        if (mCreatedByOrganizer && r.asTask() != null) {
            getDisplayArea().removeStackReferenceIfNeeded((Task) r);
        }
        if (!mChildren.contains(r)) {
            Slog.e(TAG, "removeChild: r=" + r + " not found in t=" + this);
            return;
        }

        if (DEBUG_TASK_MOVEMENT) {
            Slog.d(TAG_WM, "removeChild: child=" + r + " reason=" + reason);
        }
        super.removeChild(r);

        if (inPinnedWindowingMode()) {
            // We normally notify listeners of task stack changes on pause, however pinned stack
            // activities are normally in the paused state so no notification will be sent there
            // before the activity is removed. We send it here so instead.
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
        }

        if (hasChild()) {
            updateEffectiveIntent();

            // The following block can be executed multiple times if there is more than one overlay.
            // {@link ActivityStackSupervisor#removeTaskByIdLocked} handles this by reverse lookup
            // of the task by id and exiting early if not found.
            if (onlyHasTaskOverlayActivities(true /*includeFinishing*/)) {
                // When destroying a task, tell the supervisor to remove it so that any activity it
                // has can be cleaned up correctly. This is currently the only place where we remove
                // a task with the DESTROYING mode, so instead of passing the onlyHasTaskOverlays
                // state into removeChild(), we just clear the task here before the other residual
                // work.
                // TODO: If the callers to removeChild() changes such that we have multiple places
                //       where we are destroying the task, move this back into removeChild()
                mStackSupervisor.removeTask(this, false /* killProcess */,
                        !REMOVE_FROM_RECENTS, reason);
            }
        } else if (!mReuseTask && !mCreatedByOrganizer) {
            // Remove entire task if it doesn't have any activity left and it isn't marked for reuse
            // or created by task organizer.
            if (!isRootTask()) {
                getRootTask().removeChild(this, reason);
            }
            EventLogTags.writeWmTaskRemoved(mTaskId,
                    "removeChild: last r=" + r + " in t=" + this);
            removeIfPossible();
        }
    }

    /**
     * @return whether or not there are ONLY task overlay activities in the task.
     *         If {@param includeFinishing} is set, then don't ignore finishing activities in the
     *         check. If there are no task overlay activities, this call returns false.
     */
    boolean onlyHasTaskOverlayActivities(boolean includeFinishing) {
        int count = 0;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final ActivityRecord r = getChildAt(i).asActivityRecord();
            if (r == null) {
                // Has a child that is other than Activity.
                return false;
            }
            if (!includeFinishing && r.finishing) {
                continue;
            }
            if (!r.isTaskOverlay()) {
                return false;
            }
            count++;
        }
        return count > 0;
    }

    private boolean autoRemoveFromRecents() {
        // We will automatically remove the task either if it has explicitly asked for
        // this, or it is empty and has never contained an activity that got shown to
        // the user.
        return autoRemoveRecents || (!hasChild() && !getHasBeenVisible());
    }

    /** Completely remove all activities associated with an existing task. */
    void performClearTask(String reason) {
        // Broken down into to cases to avoid object create due to capturing mStack.
        if (getRootTask() == null) {
            forAllActivities((r) -> {
                if (r.finishing) return;
                // Task was restored from persistent storage.
                r.takeFromHistory();
                removeChild(r);
            });
        } else {
            forAllActivities((r) -> {
                if (r.finishing) return;
                // TODO: figure-out how to avoid object creation due to capture of reason variable.
                r.finishIfPossible(Activity.RESULT_CANCELED,
                        null /* resultData */, null /* resultGrants */, reason, false /* oomAdj */);
            });
        }
    }

    /**
     * Completely remove all activities associated with an existing task.
     */
    void performClearTaskLocked() {
        mReuseTask = true;
        performClearTask("clear-task-all");
        mReuseTask = false;
    }

    ActivityRecord performClearTaskForReuseLocked(ActivityRecord newR, int launchFlags) {
        mReuseTask = true;
        final ActivityRecord result = performClearTaskLocked(newR, launchFlags);
        mReuseTask = false;
        return result;
    }

    /**
     * Perform clear operation as requested by
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TOP}: search from the top of the
     * stack to the given task, then look for
     * an instance of that activity in the stack and, if found, finish all
     * activities on top of it and return the instance.
     *
     * @param newR Description of the new activity being started.
     * @return Returns the old activity that should be continued to be used,
     * or {@code null} if none was found.
     */
    private ActivityRecord performClearTaskLocked(ActivityRecord newR, int launchFlags) {
        final ActivityRecord r = findActivityInHistory(newR.mActivityComponent);
        if (r == null) return null;

        final PooledFunction f = PooledLambda.obtainFunction(Task::finishActivityAbove,
                PooledLambda.__(ActivityRecord.class), r);
        forAllActivities(f);
        f.recycle();

        // Finally, if this is a normal launch mode (that is, not expecting onNewIntent()), then we
        // will finish the current instance of the activity so a new fresh one can be started.
        if (r.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                && (launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) == 0
                && !ActivityStarter.isDocumentLaunchesIntoExisting(launchFlags)) {
            if (!r.finishing) {
                r.finishIfPossible("clear-task-top", false /* oomAdj */);
                return null;
            }
        }

        return r;
    }

    private static boolean finishActivityAbove(ActivityRecord r, ActivityRecord boundaryActivity) {
        // Stop operation once we reach the boundary activity.
        if (r == boundaryActivity) return true;

        if (!r.finishing) {
            final ActivityOptions opts = r.takeOptionsLocked(false /* fromClient */);
            if (opts != null) {
                // TODO: Why is this updating the boundary activity vs. the current activity???
                boundaryActivity.updateOptionsLocked(opts);
            }
            r.finishIfPossible("clear-task-stack", false /* oomAdj */);
        }

        return false;
    }

    String lockTaskAuthToString() {
        switch (mLockTaskAuth) {
            case LOCK_TASK_AUTH_DONT_LOCK: return "LOCK_TASK_AUTH_DONT_LOCK";
            case LOCK_TASK_AUTH_PINNABLE: return "LOCK_TASK_AUTH_PINNABLE";
            case LOCK_TASK_AUTH_LAUNCHABLE: return "LOCK_TASK_AUTH_LAUNCHABLE";
            case LOCK_TASK_AUTH_ALLOWLISTED: return "LOCK_TASK_AUTH_ALLOWLISTED";
            case LOCK_TASK_AUTH_LAUNCHABLE_PRIV: return "LOCK_TASK_AUTH_LAUNCHABLE_PRIV";
            default: return "unknown=" + mLockTaskAuth;
        }
    }

    void setLockTaskAuth() {
        setLockTaskAuth(getRootActivity());
    }

    private void setLockTaskAuth(@Nullable ActivityRecord r) {
        if (r == null) {
            mLockTaskAuth = LOCK_TASK_AUTH_PINNABLE;
            return;
        }

        final String pkg = (realActivity != null) ? realActivity.getPackageName() : null;
        final LockTaskController lockTaskController = mAtmService.getLockTaskController();
        switch (r.lockTaskLaunchMode) {
            case LOCK_TASK_LAUNCH_MODE_DEFAULT:
                mLockTaskAuth = lockTaskController.isPackageAllowlisted(mUserId, pkg)
                        ? LOCK_TASK_AUTH_ALLOWLISTED : LOCK_TASK_AUTH_PINNABLE;
                break;

            case LOCK_TASK_LAUNCH_MODE_NEVER:
                mLockTaskAuth = LOCK_TASK_AUTH_DONT_LOCK;
                break;

            case LOCK_TASK_LAUNCH_MODE_ALWAYS:
                mLockTaskAuth = LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
                break;

            case LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED:
                mLockTaskAuth = lockTaskController.isPackageAllowlisted(mUserId, pkg)
                        ? LOCK_TASK_AUTH_LAUNCHABLE : LOCK_TASK_AUTH_PINNABLE;
                break;
        }
        if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "setLockTaskAuth: task=" + this
                + " mLockTaskAuth=" + lockTaskAuthToString());
    }

    @Override
    public boolean supportsSplitScreenWindowingMode() {
        final Task topTask = getTopMostTask();
        return super.supportsSplitScreenWindowingMode()
                && (topTask == null || topTask.supportsSplitScreenWindowingModeInner());
    }

    private boolean supportsSplitScreenWindowingModeInner() {
        // A task can not be docked even if it is considered resizeable because it only supports
        // picture-in-picture mode but has a non-resizeable resizeMode
        return super.supportsSplitScreenWindowingMode()
                && mAtmService.mSupportsSplitScreenMultiWindow
                && (mAtmService.mForceResizableActivities
                        || (isResizeable(false /* checkSupportsPip */)
                                && !ActivityInfo.isPreserveOrientationMode(mResizeMode)));
    }

    /**
     * Check whether this task can be launched on the specified display.
     *
     * @param displayId Target display id.
     * @return {@code true} if either it is the default display or this activity can be put on a
     *         secondary display.
     */
    boolean canBeLaunchedOnDisplay(int displayId) {
        return mStackSupervisor.canPlaceEntityOnDisplay(displayId,
                -1 /* don't check PID */, -1 /* don't check UID */, null /* activityInfo */);
    }

    /**
     * Check that a given bounds matches the application requested orientation.
     *
     * @param bounds The bounds to be tested.
     * @return True if the requested bounds are okay for a resizing request.
     */
    private boolean canResizeToBounds(Rect bounds) {
        if (bounds == null || !inFreeformWindowingMode()) {
            // Note: If not on the freeform workspace, we ignore the bounds.
            return true;
        }
        final boolean landscape = bounds.width() > bounds.height();
        final Rect configBounds = getRequestedOverrideBounds();
        if (mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION) {
            return configBounds.isEmpty()
                    || landscape == (configBounds.width() > configBounds.height());
        }
        return (mResizeMode != RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY || !landscape)
                && (mResizeMode != RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY || landscape);
    }

    /**
     * @return {@code true} if the task is being cleared for the purposes of being reused.
     */
    boolean isClearingToReuseTask() {
        return mReuseTask;
    }

    /**
     * Find the activity in the history stack within the given task.  Returns
     * the index within the history at which it's found, or < 0 if not found.
     */
    ActivityRecord findActivityInHistory(ComponentName component) {
        final PooledPredicate p = PooledLambda.obtainPredicate(Task::matchesActivityInHistory,
                PooledLambda.__(ActivityRecord.class), component);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean matchesActivityInHistory(
            ActivityRecord r, ComponentName activityComponent) {
        return !r.finishing && r.mActivityComponent.equals(activityComponent);
    }

    /** Updates the last task description values. */
    void updateTaskDescription() {
        final ActivityRecord root = getRootActivity(true);
        if (root == null) return;

        final TaskDescription taskDescription = new TaskDescription();
        final PooledFunction f = PooledLambda.obtainFunction(
                Task::setTaskDescriptionFromActivityAboveRoot,
                PooledLambda.__(ActivityRecord.class), root, taskDescription);
        forAllActivities(f);
        f.recycle();
        taskDescription.setResizeMode(mResizeMode);
        taskDescription.setMinWidth(mMinWidth);
        taskDescription.setMinHeight(mMinHeight);
        setTaskDescription(taskDescription);
        mAtmService.getTaskChangeNotificationController().notifyTaskDescriptionChanged(
                getTaskInfo());

        final WindowContainer parent = getParent();
        if (parent != null) {
            final Task t = parent.asTask();
            if (t != null) {
                t.updateTaskDescription();
            }
        }

        if (isOrganized()) {
            mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(this, false /* force */);
        }
    }

    private static boolean setTaskDescriptionFromActivityAboveRoot(
            ActivityRecord r, ActivityRecord root, TaskDescription td) {
        if (!r.isTaskOverlay() && r.taskDescription != null) {
            final TaskDescription atd = r.taskDescription;
            if (td.getLabel() == null) {
                td.setLabel(atd.getLabel());
            }
            if (td.getRawIcon() == null) {
                td.setIcon(atd.getRawIcon());
            }
            if (td.getIconFilename() == null) {
                td.setIconFilename(atd.getIconFilename());
            }
            if (td.getPrimaryColor() == 0) {
                td.setPrimaryColor(atd.getPrimaryColor());
            }
            if (td.getBackgroundColor() == 0) {
                td.setBackgroundColor(atd.getBackgroundColor());
            }
            if (td.getStatusBarColor() == 0) {
                td.setStatusBarColor(atd.getStatusBarColor());
                td.setEnsureStatusBarContrastWhenTransparent(
                        atd.getEnsureStatusBarContrastWhenTransparent());
            }
            if (td.getNavigationBarColor() == 0) {
                td.setNavigationBarColor(atd.getNavigationBarColor());
                td.setEnsureNavigationBarContrastWhenTransparent(
                        atd.getEnsureNavigationBarContrastWhenTransparent());
            }

        }

        // End search once we get to root.
        return r == root;
    }

    // TODO (AM refactor): Invoke automatically when there is a change in children
    @VisibleForTesting
    void updateEffectiveIntent() {
        final ActivityRecord root = getRootActivity(true /*setToBottomIfNone*/);
        if (root != null) {
            setIntent(root);
            // Update the task description when the activities change
            updateTaskDescription();
        }
    }

    void adjustForMinimalTaskDimensions(@NonNull Rect bounds, @NonNull Rect previousBounds,
            @NonNull Configuration parentConfig) {
        int minWidth = mMinWidth;
        int minHeight = mMinHeight;
        // If the task has no requested minimal size, we'd like to enforce a minimal size
        // so that the user can not render the task too small to manipulate. We don't need
        // to do this for the pinned stack as the bounds are controlled by the system.
        if (!inPinnedWindowingMode()) {
            final int defaultMinSizeDp = mRootWindowContainer.mDefaultMinSizeOfResizeableTaskDp;
            final float density = (float) parentConfig.densityDpi / DisplayMetrics.DENSITY_DEFAULT;
            final int defaultMinSize = (int) (defaultMinSizeDp * density);

            if (minWidth == INVALID_MIN_SIZE) {
                minWidth = defaultMinSize;
            }
            if (minHeight == INVALID_MIN_SIZE) {
                minHeight = defaultMinSize;
            }
        }
        if (bounds.isEmpty()) {
            // If inheriting parent bounds, check if parent bounds adhere to minimum size. If they
            // do, we can just skip.
            final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
            if (parentBounds.width() >= minWidth && parentBounds.height() >= minHeight) {
                return;
            }
            bounds.set(parentBounds);
        }
        final boolean adjustWidth = minWidth > bounds.width();
        final boolean adjustHeight = minHeight > bounds.height();
        if (!(adjustWidth || adjustHeight)) {
            return;
        }

        if (adjustWidth) {
            if (!previousBounds.isEmpty() && bounds.right == previousBounds.right) {
                bounds.left = bounds.right - minWidth;
            } else {
                // Either left bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping left.
                bounds.right = bounds.left + minWidth;
            }
        }
        if (adjustHeight) {
            if (!previousBounds.isEmpty() && bounds.bottom == previousBounds.bottom) {
                bounds.top = bounds.bottom - minHeight;
            } else {
                // Either top bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping top.
                bounds.bottom = bounds.top + minHeight;
            }
        }
    }

    void setLastNonFullscreenBounds(Rect bounds) {
        if (mLastNonFullscreenBounds == null) {
            mLastNonFullscreenBounds = new Rect(bounds);
        } else {
            mLastNonFullscreenBounds.set(bounds);
        }
    }

    /**
     * This should be called when an child activity changes state. This should only
     * be called from
     * {@link ActivityRecord#setState(ActivityState, String)} .
     * @param record The {@link ActivityRecord} whose state has changed.
     * @param state The new state.
     * @param reason The reason for the change.
     */
    void onActivityStateChanged(ActivityRecord record, ActivityState state, String reason) {
        final Task parentTask = getParent().asTask();
        if (parentTask != null) {
            parentTask.onActivityStateChanged(record, state, reason);
            // We still want to update the resumed activity if the parent task is created by
            // organizer in order to keep the information synced once got reparented out from the
            // organized task.
            if (!parentTask.mCreatedByOrganizer) {
                return;
            }
        }

        if (record == mResumedActivity && state != RESUMED) {
            setResumedActivity(null, reason + " - onActivityStateChanged");
        }

        if (state == RESUMED) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.v(TAG_STACK, "set resumed activity to:" + record + " reason:" + reason);
            }
            setResumedActivity(record, reason + " - onActivityStateChanged");
            if (record == mRootWindowContainer.getTopResumedActivity()) {
                mAtmService.setResumedActivityUncheckLocked(record, reason);
            }
            mStackSupervisor.mRecentTasks.add(record.getTask());
        }
    }

    private void onConfigurationChangedInner(Configuration newParentConfig) {
        // Check if the new configuration supports persistent bounds (eg. is Freeform) and if so
        // restore the last recorded non-fullscreen bounds.
        final boolean prevPersistTaskBounds = getWindowConfiguration().persistTaskBounds();
        boolean nextPersistTaskBounds =
                getRequestedOverrideConfiguration().windowConfiguration.persistTaskBounds();
        if (getRequestedOverrideWindowingMode() == WINDOWING_MODE_UNDEFINED) {
            nextPersistTaskBounds = newParentConfig.windowConfiguration.persistTaskBounds();
        }
        if (!prevPersistTaskBounds && nextPersistTaskBounds
                && mLastNonFullscreenBounds != null && !mLastNonFullscreenBounds.isEmpty()) {
            // Bypass onRequestedOverrideConfigurationChanged here to avoid infinite loop.
            getRequestedOverrideConfiguration().windowConfiguration
                    .setBounds(mLastNonFullscreenBounds);
        }

        final int prevWinMode = getWindowingMode();
        mTmpPrevBounds.set(getBounds());
        final boolean wasInMultiWindowMode = inMultiWindowMode();
        final boolean wasInPictureInPicture = inPinnedWindowingMode();
        final int oldOrientation = getOrientation();
        super.onConfigurationChanged(newParentConfig);
        // Only need to update surface size here since the super method will handle updating
        // surface position.
        updateSurfaceSize(getSyncTransaction());

        final boolean pipChanging = wasInPictureInPicture != inPinnedWindowingMode();
        if (pipChanging) {
            mStackSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(this, getRootTask());
        } else if (wasInMultiWindowMode != inMultiWindowMode()) {
            mStackSupervisor.scheduleUpdateMultiWindowMode(this);
        }

        final int newWinMode = getWindowingMode();
        if ((prevWinMode != newWinMode) && (mDisplayContent != null)
                && shouldStartChangeTransition(prevWinMode, newWinMode)) {
            initializeChangeTransition(mTmpPrevBounds);
        }

        // If the configuration supports persistent bounds (eg. Freeform), keep track of the
        // current (non-fullscreen) bounds for persistence.
        if (getWindowConfiguration().persistTaskBounds()) {
            final Rect currentBounds = getRequestedOverrideBounds();
            if (!currentBounds.isEmpty()) {
                setLastNonFullscreenBounds(currentBounds);
            }
        }

        if (pipChanging) {
            mDisplayContent.getPinnedStackController().setPipWindowingModeChanging(true);
        }
        try {
            // We have 2 reasons why we need to report orientation change here.
            // 1. In some cases (e.g. freeform -> fullscreen) we don't have other ways of reporting.
            // 2. Report orientation as soon as possible so that the display can freeze earlier if
            // the display orientation will be changed. Because the surface bounds of activity
            // may have been set to fullscreen but the activity hasn't redrawn its content yet,
            // the rotation animation needs to capture snapshot earlier to avoid animating from
            // an intermediate state.
            if (oldOrientation != getOrientation()) {
                onDescendantOrientationChanged(null, this);
            }
        } finally {
            if (pipChanging) {
                mDisplayContent.getPinnedStackController().setPipWindowingModeChanging(false);
            }
        }

        saveLaunchingStateIfNeeded();
        final boolean taskOrgChanged = updateTaskOrganizerState(false /* forceUpdate */);
        if (taskOrgChanged) {
            updateSurfacePosition(getSyncTransaction());
            if (!isOrganized()) {
                // Surface-size update was skipped before (since internally it no-ops if
                // isOrganized() is true); however, now that this is not organized, the surface
                // size needs to be updated by WM.
                updateSurfaceSize(getSyncTransaction());
            }
        }
        // If the task organizer has changed, then it will already be receiving taskAppeared with
        // the latest task-info thus the task-info won't have changed.
        if (!taskOrgChanged && isOrganized()) {
            mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(this, false /* force */);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        // Calling Task#onConfigurationChanged() for leaf task since the ops in this method are
        // particularly for ActivityStack, like preventing bounds changes when inheriting certain
        // windowing mode.
        if (!isRootTask()) {
            onConfigurationChangedInner(newParentConfig);
            return;
        }

        final int prevWindowingMode = getWindowingMode();
        final boolean prevIsAlwaysOnTop = isAlwaysOnTop();
        final int prevRotation = getWindowConfiguration().getRotation();
        final Rect newBounds = mTmpRect;
        // Initialize the new bounds by previous bounds as the input and output for calculating
        // override bounds in pinned (pip) or split-screen mode.
        getBounds(newBounds);

        onConfigurationChangedInner(newParentConfig);

        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            return;
        }

        if (prevWindowingMode != getWindowingMode()) {
            taskDisplayArea.onStackWindowingModeChanged(this);
        }

        final DisplayContent display = getDisplay();
        if (display == null ) {
            return;
        }

        final boolean windowingModeChanged = prevWindowingMode != getWindowingMode();
        final int overrideWindowingMode = getRequestedOverrideWindowingMode();
        // Update bounds if applicable
        boolean hasNewOverrideBounds = false;
        // Use override windowing mode to prevent extra bounds changes if inheriting the mode.
        if ((overrideWindowingMode != WINDOWING_MODE_PINNED)
                && !getRequestedOverrideBounds().isEmpty()) {
            // If the parent (display) has rotated, rotate our bounds to best-fit where their
            // bounds were on the pre-rotated display.
            final int newRotation = getWindowConfiguration().getRotation();
            final boolean rotationChanged = prevRotation != newRotation;
            if (rotationChanged) {
                display.mDisplayContent.rotateBounds(
                        newParentConfig.windowConfiguration.getBounds(), prevRotation, newRotation,
                        newBounds);
                hasNewOverrideBounds = true;
            }
        }

        if (windowingModeChanged) {
            taskDisplayArea.onStackWindowingModeChanged(this);
        }
        if (hasNewOverrideBounds) {
            if (inSplitScreenWindowingMode()) {
                setBounds(newBounds);
            } else if (overrideWindowingMode != WINDOWING_MODE_PINNED) {
                // For pinned stack, resize is now part of the {@link WindowContainerTransaction}
                resize(new Rect(newBounds), PRESERVE_WINDOWS, true /* deferResume */);
            }
        }
        if (prevIsAlwaysOnTop != isAlwaysOnTop()) {
            // Since always on top is only on when the stack is freeform or pinned, the state
            // can be toggled when the windowing mode changes. We must make sure the stack is
            // placed properly when always on top state changes.
            taskDisplayArea.positionChildAt(POSITION_TOP, this, false /* includingParents */);
        }
    }

    /**
     * Initializes a change transition. See {@link SurfaceFreezer} for more information.
     */
    private void initializeChangeTransition(Rect startBounds) {
        mDisplayContent.prepareAppTransition(TRANSIT_TASK_CHANGE_WINDOWING_MODE,
                false /* alwaysKeepCurrent */, 0, false /* forceOverride */);
        mDisplayContent.mChangingContainers.add(this);

        mSurfaceFreezer.freeze(getPendingTransaction(), startBounds);
    }

    private boolean shouldStartChangeTransition(int prevWinMode, int newWinMode) {
        if (mWmService.mDisableTransitionAnimation
                || !isVisible()
                || getDisplayContent().mAppTransition.isTransitionSet()
                || getSurfaceControl() == null
                || !isLeafTask()) {
            return false;
        }
        // Only do an animation into and out-of freeform mode for now. Other mode
        // transition animations are currently handled by system-ui.
        return (prevWinMode == WINDOWING_MODE_FREEFORM) != (newWinMode == WINDOWING_MODE_FREEFORM);
    }

    @Override
    void migrateToNewSurfaceControl() {
        super.migrateToNewSurfaceControl();
        mLastSurfaceSize.x = 0;
        mLastSurfaceSize.y = 0;
        updateSurfaceSize(getPendingTransaction());
    }

    void updateSurfaceSize(SurfaceControl.Transaction transaction) {
        if (mSurfaceControl == null || isOrganized()) {
            return;
        }

        // Apply crop to root tasks only and clear the crops of the descendant tasks.
        int width = 0;
        int height = 0;
        if (isRootTask()) {
            final Rect taskBounds = getBounds();
            width = taskBounds.width();
            height = taskBounds.height();

            final int outset = getTaskOutset();
            width += 2 * outset;
            height += 2 * outset;
        }
        if (width == mLastSurfaceSize.x && height == mLastSurfaceSize.y) {
            return;
        }
        transaction.setWindowCrop(mSurfaceControl, width, height);
        mLastSurfaceSize.set(width, height);
    }

    /**
     * Calculate an amount by which to expand the task bounds in each direction.
     * Used to make room for shadows in the pinned windowing mode.
     */
    int getTaskOutset() {
        // If we are drawing shadows on the task then don't outset the stack.
        if (mWmService.mRenderShadowsInCompositor) {
            return 0;
        }
        DisplayContent displayContent = getDisplayContent();
        if (inPinnedWindowingMode() && displayContent != null) {
            final DisplayMetrics displayMetrics = displayContent.getDisplayMetrics();

            // We multiply by two to match the client logic for converting view elevation
            // to insets, as in {@link WindowManager.LayoutParams#setSurfaceInsets}
            return (int) Math.ceil(
                    mWmService.dipToPixel(PINNED_WINDOWING_MODE_ELEVATION_IN_DIP, displayMetrics)
                            * 2);
        }
        return 0;
    }

    @VisibleForTesting
    Point getLastSurfaceSize() {
        return mLastSurfaceSize;
    }

    @VisibleForTesting
    boolean isInChangeTransition() {
        return mSurfaceFreezer.hasLeash() || AppTransition.isChangeTransit(mTransit);
    }

    @Override
    public SurfaceControl getFreezeSnapshotTarget() {
        final int transit = mDisplayContent.mAppTransition.getAppTransition();
        if (!AppTransition.isChangeTransit(transit)) {
            return null;
        }
        // Skip creating snapshot if this transition is controlled by a remote animator which
        // doesn't need it.
        final ArraySet<Integer> activityTypes = new ArraySet<>();
        activityTypes.add(getActivityType());
        final RemoteAnimationAdapter adapter =
                mDisplayContent.mAppTransitionController.getRemoteAnimationOverride(
                        this, transit, activityTypes);
        if (adapter != null && !adapter.getChangeNeedsSnapshot()) {
            return null;
        }
        return getSurfaceControl();
    }

    @Override
    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, mUserId);
        proto.write(TITLE, intent != null && intent.getComponent() != null
                ? intent.getComponent().flattenToShortString() : "Task");
        proto.end(token);
    }

    /**
     * Saves launching state if necessary so that we can launch the activity to its latest state.
     * It only saves state if this task has been shown to user and it's in fullscreen or freeform
     * mode on freeform displays.
     */
    private void saveLaunchingStateIfNeeded() {
        saveLaunchingStateIfNeeded(getDisplayContent());
    }

    private void saveLaunchingStateIfNeeded(DisplayContent display) {
        if (!getHasBeenVisible()) {
            // Not ever visible to user.
            return;
        }

        final int windowingMode = getWindowingMode();
        if (windowingMode != WINDOWING_MODE_FULLSCREEN
                && windowingMode != WINDOWING_MODE_FREEFORM) {
            return;
        }

        // Don't persist state if display isn't in freeform mode. Then the task will be launched
        // back to its last state in a freeform display when it's launched in a freeform display
        // next time.
        if (getWindowConfiguration().getDisplayWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return;
        }

        // Saves the new state so that we can launch the activity at the same location.
        mStackSupervisor.mLaunchParamsPersister.saveTask(this, display);
    }

    /**
     * Adjust bounds to stay within stack bounds.
     *
     * Since bounds might be outside of stack bounds, this method tries to move the bounds in a way
     * that keep them unchanged, but be contained within the stack bounds.
     *
     * @param bounds Bounds to be adjusted.
     * @param stackBounds Bounds within which the other bounds should remain.
     * @param overlapPxX The amount of px required to be visible in the X dimension.
     * @param overlapPxY The amount of px required to be visible in the Y dimension.
     */
    private static void fitWithinBounds(Rect bounds, Rect stackBounds, int overlapPxX,
            int overlapPxY) {
        if (stackBounds == null || stackBounds.isEmpty() || stackBounds.contains(bounds)) {
            return;
        }

        // For each side of the parent (eg. left), check if the opposing side of the window (eg.
        // right) is at least overlap pixels away. If less, offset the window by that difference.
        int horizontalDiff = 0;
        // If window is smaller than overlap, use it's smallest dimension instead
        int overlapLR = Math.min(overlapPxX, bounds.width());
        if (bounds.right < (stackBounds.left + overlapLR)) {
            horizontalDiff = overlapLR - (bounds.right - stackBounds.left);
        } else if (bounds.left > (stackBounds.right - overlapLR)) {
            horizontalDiff = -(overlapLR - (stackBounds.right - bounds.left));
        }
        int verticalDiff = 0;
        int overlapTB = Math.min(overlapPxY, bounds.width());
        if (bounds.bottom < (stackBounds.top + overlapTB)) {
            verticalDiff = overlapTB - (bounds.bottom - stackBounds.top);
        } else if (bounds.top > (stackBounds.bottom - overlapTB)) {
            verticalDiff = -(overlapTB - (stackBounds.bottom - bounds.top));
        }
        bounds.offset(horizontalDiff, verticalDiff);
    }

    /**
     * Intersects inOutBounds with intersectBounds-intersectInsets. If inOutBounds is larger than
     * intersectBounds on a side, then the respective side will not be intersected.
     *
     * The assumption is that if inOutBounds is initially larger than intersectBounds, then the
     * inset on that side is no-longer applicable. This scenario happens when a task's minimal
     * bounds are larger than the provided parent/display bounds.
     *
     * @param inOutBounds the bounds to intersect.
     * @param intersectBounds the bounds to intersect with.
     * @param intersectInsets insets to apply to intersectBounds before intersecting.
     */
    static void intersectWithInsetsIfFits(
            Rect inOutBounds, Rect intersectBounds, Rect intersectInsets) {
        if (inOutBounds.right <= intersectBounds.right) {
            inOutBounds.right =
                    Math.min(intersectBounds.right - intersectInsets.right, inOutBounds.right);
        }
        if (inOutBounds.bottom <= intersectBounds.bottom) {
            inOutBounds.bottom =
                    Math.min(intersectBounds.bottom - intersectInsets.bottom, inOutBounds.bottom);
        }
        if (inOutBounds.left >= intersectBounds.left) {
            inOutBounds.left =
                    Math.max(intersectBounds.left + intersectInsets.left, inOutBounds.left);
        }
        if (inOutBounds.top >= intersectBounds.top) {
            inOutBounds.top =
                    Math.max(intersectBounds.top + intersectInsets.top, inOutBounds.top);
        }
    }

    /**
     * Gets bounds with non-decor and stable insets applied respectively.
     *
     * If bounds overhangs the display, those edges will not get insets. See
     * {@link #intersectWithInsetsIfFits}
     *
     * @param outNonDecorBounds where to place bounds with non-decor insets applied.
     * @param outStableBounds where to place bounds with stable insets applied.
     * @param bounds the bounds to inset.
     */
    private void calculateInsetFrames(Rect outNonDecorBounds, Rect outStableBounds, Rect bounds,
            DisplayInfo displayInfo) {
        outNonDecorBounds.set(bounds);
        outStableBounds.set(bounds);
        final Task rootTask = getRootTask();
        if (rootTask == null || rootTask.getDisplay() == null) {
            return;
        }
        DisplayPolicy policy = rootTask.getDisplay().mDisplayContent.getDisplayPolicy();
        if (policy == null) {
            return;
        }
        mTmpBounds.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);

        policy.getNonDecorInsetsLw(displayInfo.rotation, displayInfo.logicalWidth,
                displayInfo.logicalHeight, displayInfo.displayCutout, mTmpInsets);
        intersectWithInsetsIfFits(outNonDecorBounds, mTmpBounds, mTmpInsets);

        policy.convertNonDecorInsetsToStableInsets(mTmpInsets, displayInfo.rotation);
        intersectWithInsetsIfFits(outStableBounds, mTmpBounds, mTmpInsets);
    }

    /**
     * Forces the app bounds related configuration can be computed by
     * {@link #computeConfigResourceOverrides(Configuration, Configuration, DisplayInfo,
     * ActivityRecord.CompatDisplayInsets)}.
     */
    private static void invalidateAppBoundsConfig(@NonNull Configuration inOutConfig) {
        final Rect appBounds = inOutConfig.windowConfiguration.getAppBounds();
        if (appBounds != null) {
            appBounds.setEmpty();
        }
        inOutConfig.screenWidthDp = Configuration.SCREEN_WIDTH_DP_UNDEFINED;
        inOutConfig.screenHeightDp = Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig, @Nullable DisplayInfo overrideDisplayInfo) {
        if (overrideDisplayInfo != null) {
            // Make sure the screen related configs can be computed by the provided display info.
            inOutConfig.screenLayout = Configuration.SCREENLAYOUT_UNDEFINED;
            invalidateAppBoundsConfig(inOutConfig);
        }
        computeConfigResourceOverrides(inOutConfig, parentConfig, overrideDisplayInfo,
                null /* compatInsets */);
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig) {
        computeConfigResourceOverrides(inOutConfig, parentConfig, null /* overrideDisplayInfo */,
                null /* compatInsets */);
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig,
            @Nullable ActivityRecord.CompatDisplayInsets compatInsets) {
        if (compatInsets != null) {
            // Make sure the app bounds can be computed by the compat insets.
            invalidateAppBoundsConfig(inOutConfig);
        }
        computeConfigResourceOverrides(inOutConfig, parentConfig, null /* overrideDisplayInfo */,
                compatInsets);
    }

    /**
     * Calculates configuration values used by the client to get resources. This should be run
     * using app-facing bounds (bounds unmodified by animations or transient interactions).
     *
     * This assumes bounds are non-empty/null. For the null-bounds case, the caller is likely
     * configuring an "inherit-bounds" window which means that all configuration settings would
     * just be inherited from the parent configuration.
     **/
    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig, @Nullable DisplayInfo overrideDisplayInfo,
            @Nullable ActivityRecord.CompatDisplayInsets compatInsets) {
        int windowingMode = inOutConfig.windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = parentConfig.windowConfiguration.getWindowingMode();
        }

        float density = inOutConfig.densityDpi;
        if (density == Configuration.DENSITY_DPI_UNDEFINED) {
            density = parentConfig.densityDpi;
        }
        density *= DisplayMetrics.DENSITY_DEFAULT_SCALE;

        // The bounds may have been overridden at this level. If the parent cannot cover these
        // bounds, the configuration is still computed according to the override bounds.
        final boolean insideParentBounds;

        final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
        final Rect resolvedBounds = inOutConfig.windowConfiguration.getBounds();
        if (resolvedBounds == null || resolvedBounds.isEmpty()) {
            mTmpFullBounds.set(parentBounds);
            insideParentBounds = true;
        } else {
            mTmpFullBounds.set(resolvedBounds);
            insideParentBounds = parentBounds.contains(resolvedBounds);
        }

        // Non-null compatibility insets means the activity prefers to keep its original size, so
        // out bounds doesn't need to be restricted by the parent or current display
        final boolean customContainerPolicy = compatInsets != null;

        Rect outAppBounds = inOutConfig.windowConfiguration.getAppBounds();
        if (outAppBounds == null || outAppBounds.isEmpty()) {
            // App-bounds hasn't been overridden, so calculate a value for it.
            inOutConfig.windowConfiguration.setAppBounds(mTmpFullBounds);
            outAppBounds = inOutConfig.windowConfiguration.getAppBounds();

            if (!customContainerPolicy && windowingMode != WINDOWING_MODE_FREEFORM) {
                final Rect containingAppBounds;
                if (insideParentBounds) {
                    containingAppBounds = parentConfig.windowConfiguration.getAppBounds();
                } else {
                    // Restrict appBounds to display non-decor rather than parent because the
                    // override bounds are beyond the parent. Otherwise, it won't match the
                    // overridden bounds.
                    final TaskDisplayArea displayArea = getDisplayArea();
                    containingAppBounds = displayArea != null
                            ? displayArea.getWindowConfiguration().getAppBounds() : null;
                }
                if (containingAppBounds != null && !containingAppBounds.isEmpty()) {
                    outAppBounds.intersect(containingAppBounds);
                }
            }
        }

        if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED
                || inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            if (!customContainerPolicy && WindowConfiguration.isFloating(windowingMode)) {
                mTmpNonDecorBounds.set(mTmpFullBounds);
                mTmpStableBounds.set(mTmpFullBounds);
            } else if (!customContainerPolicy
                    && (overrideDisplayInfo != null || getDisplayContent() != null)) {
                final DisplayInfo di = overrideDisplayInfo != null
                        ? overrideDisplayInfo
                        : getDisplayContent().getDisplayInfo();

                // For calculating screenWidthDp, screenWidthDp, we use the stable inset screen
                // area, i.e. the screen area without the system bars.
                // The non decor inset are areas that could never be removed in Honeycomb. See
                // {@link WindowManagerPolicy#getNonDecorInsetsLw}.
                calculateInsetFrames(mTmpNonDecorBounds, mTmpStableBounds, mTmpFullBounds, di);
            } else {
                // Apply the given non-decor and stable insets to calculate the corresponding bounds
                // for screen size of configuration.
                int rotation = inOutConfig.windowConfiguration.getRotation();
                if (rotation == ROTATION_UNDEFINED) {
                    rotation = parentConfig.windowConfiguration.getRotation();
                }
                if (rotation != ROTATION_UNDEFINED && customContainerPolicy) {
                    mTmpNonDecorBounds.set(mTmpFullBounds);
                    mTmpStableBounds.set(mTmpFullBounds);
                    compatInsets.getBoundsByRotation(mTmpBounds, rotation);
                    intersectWithInsetsIfFits(mTmpNonDecorBounds, mTmpBounds,
                            compatInsets.mNonDecorInsets[rotation]);
                    intersectWithInsetsIfFits(mTmpStableBounds, mTmpBounds,
                            compatInsets.mStableInsets[rotation]);
                    outAppBounds.set(mTmpNonDecorBounds);
                } else {
                    // Set to app bounds because it excludes decor insets.
                    mTmpNonDecorBounds.set(outAppBounds);
                    mTmpStableBounds.set(outAppBounds);
                }
            }

            if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                final int overrideScreenWidthDp = (int) (mTmpStableBounds.width() / density);
                inOutConfig.screenWidthDp = (insideParentBounds && !customContainerPolicy)
                        ? Math.min(overrideScreenWidthDp, parentConfig.screenWidthDp)
                        : overrideScreenWidthDp;
            }
            if (inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                final int overrideScreenHeightDp = (int) (mTmpStableBounds.height() / density);
                inOutConfig.screenHeightDp = (insideParentBounds && !customContainerPolicy)
                        ? Math.min(overrideScreenHeightDp, parentConfig.screenHeightDp)
                        : overrideScreenHeightDp;
            }

            if (inOutConfig.smallestScreenWidthDp
                    == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
                if (WindowConfiguration.isFloating(windowingMode)) {
                    // For floating tasks, calculate the smallest width from the bounds of the task
                    inOutConfig.smallestScreenWidthDp = (int) (
                            Math.min(mTmpFullBounds.width(), mTmpFullBounds.height()) / density);
                }
                // otherwise, it will just inherit
            }
        }

        if (inOutConfig.orientation == ORIENTATION_UNDEFINED) {
            inOutConfig.orientation = (inOutConfig.screenWidthDp <= inOutConfig.screenHeightDp)
                    ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        }
        if (inOutConfig.screenLayout == Configuration.SCREENLAYOUT_UNDEFINED) {
            // For calculating screen layout, we need to use the non-decor inset screen area for the
            // calculation for compatibility reasons, i.e. screen area without system bars that
            // could never go away in Honeycomb.
            final int compatScreenWidthDp = (int) (mTmpNonDecorBounds.width() / density);
            final int compatScreenHeightDp = (int) (mTmpNonDecorBounds.height() / density);
            // Reducing the screen layout starting from its parent config.
            inOutConfig.screenLayout = computeScreenLayoutOverride(parentConfig.screenLayout,
                    compatScreenWidthDp, compatScreenHeightDp);
        }
    }

    /** Computes LONG, SIZE and COMPAT parts of {@link Configuration#screenLayout}. */
    static int computeScreenLayoutOverride(int sourceScreenLayout, int screenWidthDp,
            int screenHeightDp) {
        sourceScreenLayout = sourceScreenLayout
                & (Configuration.SCREENLAYOUT_LONG_MASK | Configuration.SCREENLAYOUT_SIZE_MASK);
        final int longSize = Math.max(screenWidthDp, screenHeightDp);
        final int shortSize = Math.min(screenWidthDp, screenHeightDp);
        return Configuration.reduceScreenLayout(sourceScreenLayout, longSize, shortSize);
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        mTmpBounds.set(getResolvedOverrideConfiguration().windowConfiguration.getBounds());
        super.resolveOverrideConfiguration(newParentConfig);

        int windowingMode =
                getResolvedOverrideConfiguration().windowConfiguration.getWindowingMode();

        // Resolve override windowing mode to fullscreen for home task (even on freeform
        // display), or split-screen if in split-screen mode.
        if (getActivityType() == ACTIVITY_TYPE_HOME && windowingMode == WINDOWING_MODE_UNDEFINED) {
            final int parentWindowingMode = newParentConfig.windowConfiguration.getWindowingMode();
            windowingMode = WindowConfiguration.isSplitScreenWindowingMode(parentWindowingMode)
                    ? parentWindowingMode : WINDOWING_MODE_FULLSCREEN;
            getResolvedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode);
        }

        if (isLeafTask()) {
            resolveLeafOnlyOverrideConfigs(newParentConfig, mTmpBounds /* previousBounds */);
        }
        computeConfigResourceOverrides(getResolvedOverrideConfiguration(), newParentConfig);
    }

    private void resolveLeafOnlyOverrideConfigs(Configuration newParentConfig,
            Rect previousBounds) {
        int windowingMode =
                getResolvedOverrideConfiguration().windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = newParentConfig.windowConfiguration.getWindowingMode();
        }
        Rect outOverrideBounds =
                getResolvedOverrideConfiguration().windowConfiguration.getBounds();

        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            computeFullscreenBounds(outOverrideBounds, null /* refActivity */,
                    newParentConfig.windowConfiguration.getBounds(),
                    newParentConfig.orientation);
            // The bounds for fullscreen mode shouldn't be adjusted by minimal size. Otherwise if
            // the parent or display is smaller than the size, the content may be cropped.
            return;
        }

        adjustForMinimalTaskDimensions(outOverrideBounds, previousBounds, newParentConfig);
        if (windowingMode == WINDOWING_MODE_FREEFORM) {
            // by policy, make sure the window remains within parent somewhere
            final float density =
                    ((float) newParentConfig.densityDpi) / DisplayMetrics.DENSITY_DEFAULT;
            final Rect parentBounds =
                    new Rect(newParentConfig.windowConfiguration.getBounds());
            final DisplayContent display = getDisplayContent();
            if (display != null) {
                // If a freeform window moves below system bar, there is no way to move it again
                // by touch. Because its caption is covered by system bar. So we exclude them
                // from stack bounds. and then caption will be shown inside stable area.
                final Rect stableBounds = new Rect();
                display.getStableRect(stableBounds);
                parentBounds.intersect(stableBounds);
            }

            fitWithinBounds(outOverrideBounds, parentBounds,
                    (int) (density * WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP),
                    (int) (density * WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP));

            // Prevent to overlap caption with stable insets.
            final int offsetTop = parentBounds.top - outOverrideBounds.top;
            if (offsetTop > 0) {
                outOverrideBounds.offset(0, offsetTop);
            }
        }
    }

    /**
     * Compute bounds (letterbox or pillarbox) for
     * {@link WindowConfiguration#WINDOWING_MODE_FULLSCREEN} when the parent doesn't handle the
     * orientation change and the requested orientation is different from the parent.
     */
    void computeFullscreenBounds(@NonNull Rect outBounds, @Nullable ActivityRecord refActivity,
            @NonNull Rect parentBounds, int parentOrientation) {
        // In FULLSCREEN mode, always start with empty bounds to indicate "fill parent".
        outBounds.setEmpty();
        if (handlesOrientationChangeFromDescendant()) {
            return;
        }
        if (refActivity == null) {
            // Use the top activity as the reference of orientation. Don't include overlays because
            // it is usually not the actual content or just temporarily shown.
            // E.g. ForcedResizableInfoActivity.
            refActivity = getTopNonFinishingActivity(false /* includeOverlays */);
        }

        // If the task or the reference activity requires a different orientation (either by
        // override or activityInfo), make it fit the available bounds by scaling down its bounds.
        final int overrideOrientation = getRequestedOverrideConfiguration().orientation;
        final int forcedOrientation =
                (overrideOrientation != ORIENTATION_UNDEFINED || refActivity == null)
                        ? overrideOrientation : refActivity.getRequestedConfigurationOrientation();
        if (forcedOrientation == ORIENTATION_UNDEFINED || forcedOrientation == parentOrientation) {
            return;
        }

        final int parentWidth = parentBounds.width();
        final int parentHeight = parentBounds.height();
        final float aspect = ((float) parentHeight) / parentWidth;
        if (forcedOrientation == ORIENTATION_LANDSCAPE) {
            final int height = (int) (parentWidth / aspect);
            final int top = parentBounds.centerY() - height / 2;
            outBounds.set(parentBounds.left, top, parentBounds.right, top + height);
        } else {
            final int width = (int) (parentHeight * aspect);
            final int left = parentBounds.centerX() - width / 2;
            outBounds.set(left, parentBounds.top, left + width, parentBounds.bottom);
        }
    }

    Rect updateOverrideConfigurationFromLaunchBounds() {
        // If the task is controlled by another organized task, do not set override
        // configurations and let its parent (organized task) to control it;
        final Task rootTask = getRootTask();
        final Rect bounds = rootTask != this && rootTask.isOrganized() ? null : getLaunchBounds();
        setBounds(bounds);
        if (bounds != null && !bounds.isEmpty()) {
            // TODO: Review if we actually want to do this - we are setting the launch bounds
            // directly here.
            bounds.set(getRequestedOverrideBounds());
        }
        return bounds;
    }

    /** Updates the task's bounds and override configuration to match what is expected for the
     * input stack. */
    void updateOverrideConfigurationForStack(Task inStack) {
        final Task stack = getRootTask();

        if (stack != null && stack == inStack) {
            return;
        }

        if (!inStack.inFreeformWindowingMode()) {
            setBounds(inStack.getRequestedOverrideBounds());
        }
    }

    /** Returns the bounds that should be used to launch this task. */
    Rect getLaunchBounds() {
        final Task stack = getRootTask();
        if (stack == null) {
            return null;
        }

        final int windowingMode = getWindowingMode();
        if (!isActivityTypeStandardOrUndefined()
                || windowingMode == WINDOWING_MODE_FULLSCREEN
                || (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY && !isResizeable())) {
            return isResizeable() ? stack.getRequestedOverrideBounds() : null;
        } else if (!getWindowConfiguration().persistTaskBounds()) {
            return stack.getRequestedOverrideBounds();
        }
        return mLastNonFullscreenBounds;
    }

    void setRootProcess(WindowProcessController proc) {
        clearRootProcess();
        if (intent != null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0) {
            mRootProcess = proc;
            mRootProcess.addRecentTask(this);
        }
    }

    void clearRootProcess() {
        if (mRootProcess != null) {
            mRootProcess.removeRecentTask(this);
            mRootProcess = null;
        }
    }

    @Override
    DisplayContent getDisplayContent() {
        // TODO: Why aren't we just using our own display content vs. parent's???
        final Task stack = getRootTask();
        return stack != null && stack != this
                ? stack.getDisplayContent() : super.getDisplayContent();
    }

    int getDisplayId() {
        final DisplayContent dc = getDisplayContent();
        return dc != null ? dc.mDisplayId : INVALID_DISPLAY;
    }

    /** @return Id of root task. */
    int getRootTaskId() {
        return getRootTask().mTaskId;
    }

    Task getRootTask() {
        final WindowContainer parent = getParent();
        if (parent == null) return this;

        final Task parentTask = parent.asTask();
        return parentTask == null ? this : parentTask.getRootTask();
    }

    // TODO(task-merge): Figure out what's the right thing to do for places that used it.
    boolean isRootTask() {
        return getRootTask() == this;
    }

    boolean isLeafTask() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).asTask() != null) {
                return false;
            }
        }
        return true;
    }

    int getDescendantTaskCount() {
        final int[] currentCount = {0};
        final PooledConsumer c = PooledLambda.obtainConsumer((t, count) -> { count[0]++; },
                PooledLambda.__(Task.class), currentCount);
        forAllLeafTasks(c, false /* traverseTopToBottom */);
        c.recycle();
        return currentCount[0];
    }

    /**
     * Find next proper focusable stack and make it focused.
     * @return The stack that now got the focus, {@code null} if none found.
     */
    Task adjustFocusToNextFocusableTask(String reason) {
        return adjustFocusToNextFocusableTask(reason, false /* allowFocusSelf */,
                true /* moveDisplayToTop */);
    }

    /** Return the next focusable task by looking from the siblings and parent tasks */
    private Task getNextFocusableTask(boolean allowFocusSelf) {
        final WindowContainer parent = getParent();
        if (parent == null) {
            return null;
        }

        final Task focusableTask = parent.getTask((task) -> (allowFocusSelf || task != this)
                && ((Task) task).isFocusableAndVisible());
        if (focusableTask == null && parent.asTask() != null) {
            return parent.asTask().getNextFocusableTask(allowFocusSelf);
        } else {
            return focusableTask;
        }
    }

    /**
     * Find next proper focusable task and make it focused.
     * @param reason The reason of making the adjustment.
     * @param allowFocusSelf Is the focus allowed to remain on the same task.
     * @param moveDisplayToTop Whether to move display to top while making the task focused.
     * @return The root task that now got the focus, {@code null} if none found.
     */
    Task adjustFocusToNextFocusableTask(String reason, boolean allowFocusSelf,
            boolean moveDisplayToTop) {
        Task focusableTask = getNextFocusableTask(allowFocusSelf);
        if (focusableTask == null) {
            focusableTask = mRootWindowContainer.getNextFocusableStack(this, !allowFocusSelf);
        }
        if (focusableTask == null) {
            return null;
        }

        final Task rootTask = focusableTask.getRootTask();
        if (!moveDisplayToTop) {
            // There may be multiple task layers above this task, so when relocating the task to the
            // top, we should move this task and each of its parent task that below display area to
            // the top of each layer.
            WindowContainer parent = focusableTask.getParent();
            WindowContainer next = focusableTask;
            do {
                parent.positionChildAt(POSITION_TOP, next, false /* includingParents */);
                next = parent;
                parent = next.getParent();
            } while (next.asTask() != null && parent != null);
            return rootTask;
        }

        final String myReason = reason + " adjustFocusToNextFocusableStack";
        final ActivityRecord top = focusableTask.topRunningActivity();
        if (focusableTask.isActivityTypeHome() && (top == null || !top.mVisibleRequested)) {
            // If we will be focusing on the home stack next and its current top activity isn't
            // visible, then use the move the home stack task to top to make the activity visible.
            focusableTask.getDisplayArea().moveHomeActivityToTop(myReason);
            return rootTask;
        }

        // Move the entire hierarchy to top with updating global top resumed activity
        // and focused application if needed.
        focusableTask.moveToFront(myReason);
        // Top display focused stack is changed, update top resumed activity if needed.
        if (rootTask.mResumedActivity != null) {
            mStackSupervisor.updateTopResumedActivityIfNeeded();
            // Set focused app directly because if the next focused activity is already resumed
            // (e.g. the next top activity is on a different display), there won't have activity
            // state change to update it.
            mAtmService.setResumedActivityUncheckLocked(rootTask.mResumedActivity, reason);
        }
        return rootTask;
    }

    /** Calculate the minimum possible position for a task that can be shown to the user.
     *  The minimum position will be above all other tasks that can't be shown.
     *  @param minPosition The minimum position the caller is suggesting.
     *                  We will start adjusting up from here.
     *  @param size The size of the current task list.
     */
    // TODO: Move user to their own window container.
    private int computeMinUserPosition(int minPosition, int size) {
        while (minPosition < size) {
            final WindowContainer child = mChildren.get(minPosition);
            final boolean canShow = child.showToCurrentUser();
            if (canShow) {
                break;
            }
            minPosition++;
        }
        return minPosition;
    }

    /** Calculate the maximum possible position for a task that can't be shown to the user.
     *  The maximum position will be below all other tasks that can be shown.
     *  @param maxPosition The maximum position the caller is suggesting.
     *                  We will start adjusting down from here.
     */
    // TODO: Move user to their own window container.
    private int computeMaxUserPosition(int maxPosition) {
        while (maxPosition > 0) {
            final WindowContainer child = mChildren.get(maxPosition);
            final boolean canShow = child.showToCurrentUser();
            if (!canShow) {
                break;
            }
            maxPosition--;
        }
        return maxPosition;
    }

    private int getAdjustedChildPosition(WindowContainer wc, int suggestedPosition) {
        final boolean canShowChild = wc.showToCurrentUser();

        final int size = mChildren.size();

        // Figure-out min/max possible position depending on if child can show for current user.
        int minPosition = (canShowChild) ? computeMinUserPosition(0, size) : 0;
        int maxPosition = (canShowChild) ? size : computeMaxUserPosition(size - 1);

        // Factor in always-on-top children in max possible position.
        if (!wc.isAlwaysOnTop()) {

            // We want to place all non-always-on-top containers below always-on-top ones.
            while (maxPosition > minPosition) {
                if (!mChildren.get(maxPosition - 1).isAlwaysOnTop()) break;
                --maxPosition;
            }
        }

        // preserve POSITION_BOTTOM/POSITION_TOP positions if they are still valid.
        if (suggestedPosition == POSITION_BOTTOM && minPosition == 0) {
            return POSITION_BOTTOM;
        } else if (suggestedPosition == POSITION_TOP && maxPosition >= (size - 1)) {
            return POSITION_TOP;
        }
        // Reset position based on minimum/maximum possible positions.
        return Math.min(Math.max(suggestedPosition, minPosition), maxPosition);
    }

    @Override
    void positionChildAt(int position, WindowContainer child, boolean includingParents) {
        position = getAdjustedChildPosition(child, position);
        super.positionChildAt(position, child, includingParents);

        // Log positioning.
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG_WM, "positionChildAt: child=" + child
                + " position=" + position + " parent=" + this);

        final int toTop = position >= (mChildren.size() - 1) ? 1 : 0;
        final Task task = child.asTask();
        if (task != null) {
            EventLogTags.writeWmTaskMoved(task.mTaskId, toTop, position);
        }
    }

    @VisibleForTesting
    boolean hasWindowsAlive() {
        return getActivity(ActivityRecord::hasWindowsAlive) != null;
    }

    @VisibleForTesting
    boolean shouldDeferRemoval() {
        if (mChildren.isEmpty()) {
            // No reason to defer removal of a Task that doesn't have any child.
            return false;
        }
        return hasWindowsAlive() && getRootTask().isAnimating(TRANSITION | CHILDREN);
    }

    @Override
    void removeImmediately() {
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLogTags.writeWmTaskRemoved(mTaskId, "removeTask");

        if (mDisplayContent != null && mDisplayContent.isSingleTaskInstance()) {
            mAtmService.notifySingleTaskDisplayEmpty(mDisplayContent.mDisplayId);
        }

        // If applicable let the TaskOrganizer know the Task is vanishing.
        setTaskOrganizer(null);

        super.removeImmediately();
    }

    // TODO: Consolidate this with Task.reparent()
    void reparent(Task stack, int position, boolean moveParents, String reason) {
        if (DEBUG_STACK) Slog.i(TAG, "reParentTask: removing taskId=" + mTaskId
                + " from stack=" + getRootTask());
        EventLogTags.writeWmTaskRemoved(mTaskId, "reParentTask:" + reason);

        reparent(stack, position);

        stack.positionChildAt(position, this, moveParents);

        // If we are moving from the fullscreen stack to the pinned stack then we want to preserve
        // our insets so that there will not be a jump in the area covered by system decorations.
        // We rely on the pinned animation to later unset this value.
        mPreserveNonFloatingState = stack.inPinnedWindowingMode();
    }

    public int setBounds(Rect bounds, boolean forceResize) {
        final int boundsChanged = setBounds(bounds);

        if (forceResize && (boundsChanged & BOUNDS_CHANGE_SIZE) != BOUNDS_CHANGE_SIZE) {
            onResize();
            return BOUNDS_CHANGE_SIZE | boundsChanged;
        }

        return boundsChanged;
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    @Override
    public int setBounds(Rect bounds) {
        if (isRootTask()) {
            return setBounds(getRequestedOverrideBounds(), bounds);
        }

        int rotation = Surface.ROTATION_0;
        final DisplayContent displayContent = getRootTask() != null
                ? getRootTask().getDisplayContent() : null;
        if (displayContent != null) {
            rotation = displayContent.getDisplayInfo().rotation;
        }

        final int boundsChange = super.setBounds(bounds);
        mRotation = rotation;
        updateSurfacePositionNonOrganized();
        return boundsChange;
    }

    @Override
    public boolean isCompatible(int windowingMode, int activityType) {
        // TODO: Should we just move this to ConfigurationContainer?
        if (activityType == ACTIVITY_TYPE_UNDEFINED) {
            // Undefined activity types end up in a standard stack once the stack is created on a
            // display, so they should be considered compatible.
            activityType = ACTIVITY_TYPE_STANDARD;
        }
        return super.isCompatible(windowingMode, activityType);
    }

    @Override
    public boolean onDescendantOrientationChanged(IBinder freezeDisplayToken,
            ConfigurationContainer requestingContainer) {
        if (super.onDescendantOrientationChanged(freezeDisplayToken, requestingContainer)) {
            return true;
        }

        // No one in higher hierarchy handles this request, let's adjust our bounds to fulfill
        // it if possible.
        if (getParent() != null) {
            onConfigurationChanged(getParent().getConfiguration());
            return true;
        }
        return false;
    }

    void resize(boolean relayout, boolean forced) {
        if (setBounds(getRequestedOverrideBounds(), forced) != BOUNDS_CHANGE_NONE && relayout) {
            getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        final boolean isRootTask = isRootTask();
        if (!isRootTask) {
            adjustBoundsForDisplayChangeIfNeeded(dc);
        }
        super.onDisplayChanged(dc);
        if (isLeafTask()) {
            final int displayId = (dc != null) ? dc.getDisplayId() : INVALID_DISPLAY;
            mWmService.mAtmService.getTaskChangeNotificationController().notifyTaskDisplayChanged(
                    mTaskId, displayId);
        }
        if (isRootTask()) {
            updateSurfaceBounds();
        }
    }

    boolean isResizeable(boolean checkSupportsPip) {
        return (mAtmService.mForceResizableActivities || ActivityInfo.isResizeableMode(mResizeMode)
                || (checkSupportsPip && mSupportsPictureInPicture));
    }

    boolean isResizeable() {
        return isResizeable(true /* checkSupportsPip */);
    }

    /**
     * Tests if the orientation should be preserved upon user interactive resizig operations.

     * @return true if orientation should not get changed upon resizing operation.
     */
    boolean preserveOrientationOnResize() {
        return mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
    }

    boolean cropWindowsToStackBounds() {
        // Don't crop HOME/RECENTS windows to stack bounds. This is because in split-screen
        // they extend past their stack and sysui uses the stack surface to control cropping.
        // TODO(b/158242495): get rid of this when drag/drop can use surface bounds.
        if (isActivityTypeHome() || isActivityTypeRecents()) {
            // Make sure this is the top-most non-organizer root task (if not top-most, it means
            // another translucent task could be above this, so this needs to stay cropped.
            final Task rootTask = getRootTask();
            final Task topNonOrgTask =
                    rootTask.mCreatedByOrganizer ? rootTask.getTopMostTask() : rootTask;
            if (this == topNonOrgTask || isDescendantOf(topNonOrgTask)) {
                return false;
            }
        }
        return isResizeable();
    }

    /**
     * Prepares the task bounds to be frozen with the current size. See
     * {@link ActivityRecord#freezeBounds}.
     */
    void prepareFreezingBounds() {
        mPreparedFrozenBounds.set(getBounds());
        mPreparedFrozenMergedConfig.setTo(getConfiguration());
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final WindowState windowState = getTopVisibleAppMainWindow();
        if (windowState != null) {
            windowState.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        } else {
            super.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        }
    }

    /**
     * Calculate the maximum visible area of this task. If the task has only one app,
     * the result will be visible frame of that app. If the task has more than one apps,
     * we search from top down if the next app got different visible area.
     *
     * This effort is to handle the case where some task (eg. GMail composer) might pop up
     * a dialog that's different in size from the activity below, in which case we should
     * be dimming the entire task area behind the dialog.
     *
     * @param out Rect containing the max visible bounds.
     * @return true if the task has some visible app windows; false otherwise.
     */
    private static void getMaxVisibleBounds(ActivityRecord token, Rect out, boolean[] foundTop) {
        // skip hidden (or about to hide) apps
        if (token.mIsExiting || !token.isClientVisible() || !token.mVisibleRequested) {
            return;
        }
        final WindowState win = token.findMainWindow();
        if (win == null) {
            return;
        }
        if (!foundTop[0]) {
            foundTop[0] = true;
            out.setEmpty();
        }

        win.getMaxVisibleBounds(out);
    }

    /** Bounds of the task to be used for dimming, as well as touch related tests. */
    void getDimBounds(Rect out) {
        if (isRootTask()) {
            getBounds(out);
            return;
        }

        final Task rootTask = getRootTask();
        final DisplayContent displayContent = rootTask.getDisplayContent();
        // It doesn't matter if we in particular are part of the resize, since we couldn't have
        // a DimLayer anyway if we weren't visible.
        final boolean dockedResizing = displayContent != null
                && displayContent.mDividerControllerLocked.isResizing();
        if (inFreeformWindowingMode()) {
            boolean[] foundTop = { false };
            final PooledConsumer c = PooledLambda.obtainConsumer(Task::getMaxVisibleBounds,
                    PooledLambda.__(ActivityRecord.class), out, foundTop);
            forAllActivities(c);
            c.recycle();
            if (foundTop[0]) {
                return;
            }
        }

        if (!matchParentBounds()) {
            // When minimizing the docked stack when going home, we don't adjust the task bounds
            // so we need to intersect the task bounds with the stack bounds here.
            //
            // If we are Docked Resizing with snap points, the task bounds could be smaller than the
            // stack bounds and so we don't even want to use them. Even if the app should not be
            // resized the Dim should keep up with the divider.
            if (dockedResizing) {
                rootTask.getBounds(out);
            } else {
                rootTask.getBounds(mTmpRect);
                mTmpRect.intersect(getBounds());
                out.set(mTmpRect);
            }
        } else {
            out.set(getBounds());
        }
        return;
    }

    void setDragResizing(boolean dragResizing, int dragResizeMode) {
        if (mDragResizing != dragResizing) {
            // No need to check if the mode is allowed if it's leaving dragResize
            if (dragResizing
                    && !DragResizeMode.isModeAllowedForStack(getRootTask(), dragResizeMode)) {
                throw new IllegalArgumentException("Drag resize mode not allow for stack stackId="
                        + getRootTaskId() + " dragResizeMode=" + dragResizeMode);
            }
            mDragResizing = dragResizing;
            mDragResizeMode = dragResizeMode;
            resetDragResizingChangeReported();
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    int getDragResizeMode() {
        return mDragResizeMode;
    }

    void adjustBoundsForDisplayChangeIfNeeded(final DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        if (getRequestedOverrideBounds().isEmpty()) {
            return;
        }
        final int displayId = displayContent.getDisplayId();
        final int newRotation = displayContent.getDisplayInfo().rotation;
        if (displayId != mLastRotationDisplayId) {
            // This task is on a display that it wasn't on. There is no point to keep the relative
            // position if display rotations for old and new displays are different. Just keep these
            // values.
            mLastRotationDisplayId = displayId;
            mRotation = newRotation;
            return;
        }

        if (mRotation == newRotation) {
            // Rotation didn't change. We don't need to adjust the bounds to keep the relative
            // position.
            return;
        }

        // Device rotation changed.
        // - We don't want the task to move around on the screen when this happens, so update the
        //   task bounds so it stays in the same place.
        // - Rotate the bounds and notify activity manager if the task can be resized independently
        //   from its stack. The stack will take care of task rotation for the other case.
        mTmpRect2.set(getBounds());

        if (!getWindowConfiguration().canResizeTask()) {
            setBounds(mTmpRect2);
            return;
        }

        displayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (setBounds(mTmpRect2) != BOUNDS_CHANGE_NONE) {
            mAtmService.resizeTask(mTaskId, getBounds(), RESIZE_MODE_SYSTEM_SCREEN_ROTATION);
        }
    }

    /** Cancels any running app transitions associated with the task. */
    void cancelTaskWindowTransition() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).cancelAnimation();
        }
    }

    boolean showForAllUsers() {
        if (mChildren.isEmpty()) return false;
        final ActivityRecord r = getTopNonFinishingActivity();
        return r != null && r.mShowForAllUsers;
    }

    @Override
    boolean showToCurrentUser() {
        return mForceShowForAllUsers || showForAllUsers()
                || mWmService.isCurrentProfile(getTopMostTask().mUserId);
    }

    void setForceShowForAllUsers(boolean forceShowForAllUsers) {
        mForceShowForAllUsers = forceShowForAllUsers;
    }

    @Override
    public boolean isAttached() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        return taskDisplayArea != null && !taskDisplayArea.isRemoved();
    }

    @Override
    @Nullable
    TaskDisplayArea getDisplayArea() {
        return (TaskDisplayArea) super.getDisplayArea();
    }

    /**
     * When we are in a floating stack (Freeform, Pinned, ...) we calculate
     * insets differently. However if we are animating to the fullscreen stack
     * we need to begin calculating insets as if we were fullscreen, otherwise
     * we will have a jump at the end.
     */
    boolean isFloating() {
        return getWindowConfiguration().tasksAreFloating() && !mPreserveNonFloatingState;
    }

    /**
     * Returns true if the stack is translucent and can have other contents visible behind it if
     * needed. A stack is considered translucent if it don't contain a visible or
     * starting (about to be visible) activity that is fullscreen (opaque).
     * @param starting The currently starting activity or null if there is none.
     */
    @VisibleForTesting
    boolean isTranslucent(ActivityRecord starting) {
        if (!isAttached() || isForceHidden()) {
            return true;
        }
        final PooledPredicate p = PooledLambda.obtainPredicate(Task::isOpaqueActivity,
                PooledLambda.__(ActivityRecord.class), starting);
        final ActivityRecord opaque = getActivity(p);
        p.recycle();
        return opaque == null;
    }

    private static boolean isOpaqueActivity(ActivityRecord r, ActivityRecord starting) {
        if (r.finishing) {
            // We don't factor in finishing activities when determining translucency since
            // they will be gone soon.
            return false;
        }

        if (!r.visibleIgnoringKeyguard && r != starting) {
            // Also ignore invisible activities that are not the currently starting
            // activity (about to be visible).
            return false;
        }

        if (r.occludesParent() || r.hasWallpaper) {
            // Stack isn't translucent if it has at least one fullscreen activity
            // that is visible.
            return true;
        }
        return false;
    }

    /** Returns the top-most activity that occludes the given one, or {@code null} if none. */
    @Nullable
    ActivityRecord getOccludingActivityAbove(ActivityRecord activity) {
        final ActivityRecord top = getActivity(ActivityRecord::occludesParent,
                true /* traverseTopToBottom */, activity);
        return top != activity ? top : null;
    }

    /** Iterates through all occluded activities. */
    void forAllOccludedActivities(Consumer<ActivityRecord> handleOccludedActivity) {
        if (!shouldBeVisible(null /* starting */)) {
            // The stack is invisible so all activities are occluded.
            forAllActivities(handleOccludedActivity);
            return;
        }
        final ActivityRecord topOccluding = getOccludingActivityAbove(null);
        if (topOccluding == null) {
            // No activities are occluded.
            return;
        }
        // Invoke the callback on the activities behind the top occluding activity.
        forAllActivities(r -> {
            handleOccludedActivity.accept(r);
            return false;
        }, topOccluding, false /* includeBoundary */, true /* traverseTopToBottom */);
    }

    @Override
    public SurfaceControl.Builder makeAnimationLeash() {
        return super.makeAnimationLeash().setMetadata(METADATA_TASK_ID, mTaskId);
    }

    @Override
    void resetSurfacePositionForAnimationLeash(SurfaceControl.Transaction t) {
        if (isOrganized()) return;
        super.resetSurfacePositionForAnimationLeash(t);
    }

    @Override
    Rect getAnimationBounds(int appStackClipMode) {
        // TODO(b/131661052): we should remove appStackClipMode with hierarchical animations.
        if (appStackClipMode == STACK_CLIP_BEFORE_ANIM && getRootTask() != null) {
            // Using the stack bounds here effectively applies the clipping before animation.
            return getRootTask().getBounds();
        }
        return super.getAnimationBounds(appStackClipMode);
    }

    boolean shouldAnimate() {
        /**
         * Animations are handled by the TaskOrganizer implementation.
         */
        if (isOrganized()) {
            return false;
        }
        // Don't animate while the task runs recents animation but only if we are in the mode
        // where we cancel with deferred screenshot, which means that the controller has
        // transformed the task.
        final RecentsAnimationController controller = mWmService.getRecentsAnimationController();
        if (controller != null && controller.isAnimatingTask(this)
                && controller.shouldDeferCancelUntilNextTransition()) {
            return false;
        }
        return true;
    }

    @Override
    void setInitialSurfaceControlProperties(SurfaceControl.Builder b) {
        b.setEffectLayer().setMetadata(METADATA_TASK_ID, mTaskId);
        super.setInitialSurfaceControlProperties(b);
    }

    boolean isTaskAnimating() {
        final RecentsAnimationController recentsAnim = mWmService.getRecentsAnimationController();
        if (recentsAnim != null) {
            if (recentsAnim.isAnimatingTask(this)) {
                return true;
            }
        }
        return forAllTasks((t) -> { return t != this && t.isTaskAnimating(); });
    }

    @Override
    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        final ActivityRecord activity = getTopMostActivity();
        return activity != null ? activity.createRemoteAnimationTarget(record) : null;
    }

    @Override
    boolean canCreateRemoteAnimationTarget() {
        return true;
    }

    WindowState getTopVisibleAppMainWindow() {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null ? activity.findMainWindow() : null;
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* focusableOnly */);
    }

    ActivityRecord topRunningActivity(boolean focusableOnly) {
        // Split into 2 to avoid object creation due to variable capture.
        if (focusableOnly) {
            return getActivity((r) -> r.canBeTopRunning() && r.isFocusable());
        } else {
            return getActivity(ActivityRecord::canBeTopRunning);
        }
    }

    ActivityRecord topRunningNonDelayedActivityLocked(ActivityRecord notTop) {
        final PooledPredicate p = PooledLambda.obtainPredicate(Task::isTopRunningNonDelayed
                , PooledLambda.__(ActivityRecord.class), notTop);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isTopRunningNonDelayed(ActivityRecord r, ActivityRecord notTop) {
        return !r.delayedResume && r != notTop && r.canBeTopRunning();
    }

    /**
     * This is a simplified version of topRunningActivity that provides a number of
     * optional skip-over modes.  It is intended for use with the ActivityController hook only.
     *
     * @param token If non-null, any history records matching this token will be skipped.
     * @param taskId If non-zero, we'll attempt to skip over records with the same task ID.
     *
     * @return Returns the HistoryRecord of the next activity on the stack.
     */
    ActivityRecord topRunningActivity(IBinder token, int taskId) {
        final PooledPredicate p = PooledLambda.obtainPredicate(Task::isTopRunning,
                PooledLambda.__(ActivityRecord.class), taskId, token);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isTopRunning(ActivityRecord r, int taskId, IBinder notTop) {
        return r.getTask().mTaskId != taskId && r.appToken != notTop && r.canBeTopRunning();
    }

    ActivityRecord getTopFullscreenActivity() {
        return getActivity((r) -> {
            final WindowState win = r.findMainWindow();
            return (win != null && win.mAttrs.isFullscreen());
        });
    }

    ActivityRecord getTopVisibleActivity() {
        return getActivity((r) -> {
            // skip hidden (or about to hide) apps
            return !r.mIsExiting && r.isClientVisible() && r.mVisibleRequested;
        });
    }

    boolean isTopActivityFocusable() {
        final ActivityRecord r = topRunningActivity();
        return r != null ? r.isFocusable()
                : (isFocusable() && getWindowConfiguration().canReceiveKeys());
    }

    boolean isFocusableAndVisible() {
        return isTopActivityFocusable() && shouldBeVisible(null /* starting */);
    }

    void positionChildAtTop(ActivityRecord child) {
        positionChildAt(child, POSITION_TOP);
    }

    void positionChildAt(ActivityRecord child, int position) {
        if (child == null) {
            Slog.w(TAG_WM,
                    "Attempted to position of non-existing app");
            return;
        }

        positionChildAt(position, child, false /* includeParents */);
    }

    void forceWindowsScaleable(boolean force) {
        mWmService.openSurfaceTransaction();
        try {
            for (int i = mChildren.size() - 1; i >= 0; i--) {
                mChildren.get(i).forceWindowsScaleableInTransaction(force);
            }
        } finally {
            mWmService.closeSurfaceTransaction("forceWindowsScaleable");
        }
    }

    void setTaskDescription(TaskDescription taskDescription) {
        mTaskDescription = taskDescription;
    }

    void onSnapshotChanged(ActivityManager.TaskSnapshot snapshot) {
        mAtmService.getTaskChangeNotificationController().notifyTaskSnapshotChanged(
                mTaskId, snapshot);
    }

    TaskDescription getTaskDescription() {
        return mTaskDescription;
    }

    @Override
    int getOrientation(int candidate) {
        return canSpecifyOrientation() ? super.getOrientation(candidate) : SCREEN_ORIENTATION_UNSET;
    }

    private boolean canSpecifyOrientation() {
        final int windowingMode = getWindowingMode();
        final int activityType = getActivityType();
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                || activityType == ACTIVITY_TYPE_HOME
                || activityType == ACTIVITY_TYPE_RECENTS
                || activityType == ACTIVITY_TYPE_ASSISTANT;
    }

    @Override
    boolean fillsParent() {
        return matchParentBounds();
    }

    @Override
    void forAllLeafTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        boolean isLeafTask = true;
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                final Task child = mChildren.get(i).asTask();
                if (child != null) {
                    isLeafTask = false;
                    child.forAllLeafTasks(callback, traverseTopToBottom);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                final Task child = mChildren.get(i).asTask();
                if (child != null) {
                    isLeafTask = false;
                    child.forAllLeafTasks(callback, traverseTopToBottom);
                }
            }
        }
        if (isLeafTask) callback.accept(this);
    }

    @Override
    void forAllTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        super.forAllTasks(callback, traverseTopToBottom);
        callback.accept(this);
    }

    @Override
    boolean forAllTasks(Function<Task, Boolean> callback) {
        if (super.forAllTasks(callback)) return true;
        return callback.apply(this);
    }

    @Override
    boolean forAllLeafTasks(Function<Task, Boolean> callback) {
        boolean isLeafTask = true;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task child = mChildren.get(i).asTask();
            if (child != null) {
                isLeafTask = false;
                if (child.forAllLeafTasks(callback)) {
                    return true;
                }
            }
        }
        if (isLeafTask) {
            return callback.apply(this);
        }
        return false;
    }

    @Override
    Task getTask(Predicate<Task> callback, boolean traverseTopToBottom) {
        final Task t = super.getTask(callback, traverseTopToBottom);
        if (t != null) return t;
        return callback.test(this) ? this : null;
    }

    /**
     * @param canAffectSystemUiFlags If false, all windows in this task can not affect SystemUI
     *                               flags. See {@link WindowState#canAffectSystemUiFlags()}.
     */
    void setCanAffectSystemUiFlags(boolean canAffectSystemUiFlags) {
        mCanAffectSystemUiFlags = canAffectSystemUiFlags;
    }

    /**
     * @see #setCanAffectSystemUiFlags
     */
    boolean canAffectSystemUiFlags() {
        return mCanAffectSystemUiFlags;
    }

    void dontAnimateDimExit() {
        mDimmer.dontAnimateExit();
    }

    String getName() {
        return "Task=" + mTaskId;
    }

    void clearPreserveNonFloatingState() {
        mPreserveNonFloatingState = false;
    }

    @Override
    Dimmer getDimmer() {
        // If the window is in multi-window mode, we want to dim at the Task level to ensure the dim
        // bounds match the area the app lives in
        if (inMultiWindowMode()) {
            return mDimmer;
        }

        // If we're not at the root task level, we want to keep traversing through the parents to
        // find the root.
        // Once at the root task level, we want to check {@link #isTranslucent(ActivityRecord)}.
        // If true, we want to get the Dimmer from the level above since we don't want to animate
        // the dim with the Task.
        if (!isRootTask() || isTranslucent(null)) {
            return super.getDimmer();
        }

        return mDimmer;
    }

    @Override
    void prepareSurfaces() {
        mDimmer.resetDimStates();
        super.prepareSurfaces();
        getDimBounds(mTmpDimBoundsRect);

        // Bounds need to be relative, as the dim layer is a child.
        if (inFreeformWindowingMode()) {
            getBounds(mTmpRect);
            mTmpDimBoundsRect.offsetTo(mTmpDimBoundsRect.left - mTmpRect.left,
                    mTmpDimBoundsRect.top - mTmpRect.top);
        } else {
            mTmpDimBoundsRect.offsetTo(0, 0);
        }

        updateShadowsRadius(isFocused(), getSyncTransaction());

        if (mDimmer.updateDims(getPendingTransaction(), mTmpDimBoundsRect)) {
            scheduleAnimation();
        }
    }

    @Override
    protected void applyAnimationUnchecked(WindowManager.LayoutParams lp, boolean enter,
            int transit, boolean isVoiceInteraction,
            @Nullable ArrayList<WindowContainer> sources) {
        final RecentsAnimationController control = mWmService.getRecentsAnimationController();
        if (control != null) {
            // We let the transition to be controlled by RecentsAnimation, and callback task's
            // RemoteAnimationTarget for remote runner to animate.
            if (enter) {
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                        "applyAnimationUnchecked, control: %s, task: %s, transit: %s",
                        control, asTask(), AppTransition.appTransitionToString(transit));
                control.addTaskToTargets(this, (type, anim) -> {
                    for (int i = 0; i < sources.size(); ++i) {
                        sources.get(i).onAnimationFinished(type, anim);
                    }
                });
            }
        } else {
            super.applyAnimationUnchecked(lp, enter, transit, isVoiceInteraction, sources);
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.println(prefix + "bounds=" + getBounds().toShortString());
        final String doublePrefix = prefix + "  ";
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowContainer<?> child = mChildren.get(i);
            pw.println(prefix + "* " + child);
            // Only dump non-activity because full activity info is already printed by
            // RootWindowContainer#dumpActivities.
            if (child.asActivityRecord() == null) {
                child.dump(pw, doublePrefix, dumpAll);
            }
        }

        if (!mExitingActivities.isEmpty()) {
            pw.println();
            pw.println(prefix + "Exiting application tokens:");
            for (int i = mExitingActivities.size() - 1; i >= 0; i--) {
                WindowToken token = mExitingActivities.get(i);
                pw.print(doublePrefix + "Exiting App #" + i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, doublePrefix, dumpAll);
            }
            pw.println();
        }
        mAnimatingActivityRegistry.dump(pw, "AnimatingApps:", prefix);
    }


    /**
     * Fills in a {@link TaskInfo} with information from this task. Note that the base intent in the
     * task info will not include any extras or clip data.
     */
    void fillTaskInfo(TaskInfo info) {
        fillTaskInfo(info, true /* stripExtras */);
    }

    /**
     * Fills in a {@link TaskInfo} with information from this task.
     */
    void fillTaskInfo(TaskInfo info, boolean stripExtras) {
        getNumRunningActivities(mReuseActivitiesReport);
        info.userId = mUserId;
        info.stackId = getRootTaskId();
        info.taskId = mTaskId;
        info.displayId = getDisplayId();
        info.isRunning = getTopNonFinishingActivity() != null;
        final Intent baseIntent = getBaseIntent();
        // Make a copy of base intent because this is like a snapshot info.
        // Besides, {@link RecentTasks#getRecentTasksImpl} may modify it.
        final int baseIntentFlags = baseIntent == null ? 0 : baseIntent.getFlags();
        info.baseIntent = baseIntent == null
                ? new Intent()
                : stripExtras ? baseIntent.cloneFilter() : new Intent(baseIntent);
        info.baseIntent.setFlags(baseIntentFlags);
        info.baseActivity = mReuseActivitiesReport.base != null
                ? mReuseActivitiesReport.base.intent.getComponent()
                : null;
        info.topActivity = mReuseActivitiesReport.top != null
                ? mReuseActivitiesReport.top.mActivityComponent
                : null;
        info.origActivity = origActivity;
        info.realActivity = realActivity;
        info.numActivities = mReuseActivitiesReport.numActivities;
        info.lastActiveTime = lastActiveTime;
        info.taskDescription = new ActivityManager.TaskDescription(getTaskDescription());
        info.supportsSplitScreenMultiWindow = supportsSplitScreenWindowingMode();
        info.configuration.setTo(getConfiguration());
        info.token = mRemoteToken.toWindowContainerToken();

        //TODO (AM refactor): Just use local once updateEffectiveIntent is run during all child
        //                    order changes.
        final Task top = getTopMostTask();
        info.resizeMode = top != null ? top.mResizeMode : mResizeMode;
        info.topActivityType = top.getActivityType();
        info.isResizeable = isResizeable();

        ActivityRecord rootActivity = top.getRootActivity();
        if (rootActivity == null || rootActivity.pictureInPictureArgs.empty()) {
            info.pictureInPictureParams = null;
        } else {
            info.pictureInPictureParams = rootActivity.pictureInPictureArgs;
        }
        info.topActivityInfo = mReuseActivitiesReport.top != null
                ? mReuseActivitiesReport.top.info
                : null;
    }

    /**
     * Returns a {@link TaskInfo} with information from this task.
     */
    ActivityManager.RunningTaskInfo getTaskInfo() {
        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        fillTaskInfo(info);
        return info;
    }

    boolean isTaskId(int taskId) {
        return mTaskId == taskId;
    }

    @Override
    Task asTask() {
        // I'm a task!
        return this;
    }

    /**
     * Returns true if the task should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    boolean shouldBeVisible(ActivityRecord starting) {
        return getVisibility(starting) != STACK_VISIBILITY_INVISIBLE;
    }

    /**
     * Returns true if the task should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    @Task.StackVisibility
    int getVisibility(ActivityRecord starting) {
        if (!isAttached() || isForceHidden()) {
            return STACK_VISIBILITY_INVISIBLE;
        }

        boolean gotSplitScreenStack = false;
        boolean gotOpaqueSplitScreenPrimary = false;
        boolean gotOpaqueSplitScreenSecondary = false;
        boolean gotTranslucentFullscreen = false;
        boolean gotTranslucentSplitScreenPrimary = false;
        boolean gotTranslucentSplitScreenSecondary = false;
        boolean shouldBeVisible = true;

        // This stack is only considered visible if all its parent stacks are considered visible,
        // so check the visibility of all ancestor stacks first.
        final WindowContainer parent = getParent();
        if (parent.asTask() != null) {
            final int parentVisibility = parent.asTask().getVisibility(starting);
            if (parentVisibility == STACK_VISIBILITY_INVISIBLE) {
                // Can't be visible if parent isn't visible
                return STACK_VISIBILITY_INVISIBLE;
            } else if (parentVisibility == STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT) {
                // Parent is behind a translucent container so the highest visibility this container
                // can get is that.
                gotTranslucentFullscreen = true;
            }
        }

        final int windowingMode = getWindowingMode();
        final boolean isAssistantType = isActivityTypeAssistant();
        for (int i = parent.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer wc = parent.getChildAt(i);
            final Task other = wc.asTask();
            if (other == null) continue;

            final boolean hasRunningActivities = other.topRunningActivity() != null;
            if (other == this) {
                // Should be visible if there is no other stack occluding it, unless it doesn't
                // have any running activities, not starting one and not home stack.
                shouldBeVisible = hasRunningActivities || isInTask(starting) != null
                        || isActivityTypeHome();
                break;
            }

            if (!hasRunningActivities) {
                continue;
            }

            final int otherWindowingMode = other.getWindowingMode();

            if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                if (other.isTranslucent(starting)) {
                    // Can be visible behind a translucent fullscreen stack.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                return STACK_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    && !gotOpaqueSplitScreenPrimary) {
                gotSplitScreenStack = true;
                gotTranslucentSplitScreenPrimary = other.isTranslucent(starting);
                gotOpaqueSplitScreenPrimary = !gotTranslucentSplitScreenPrimary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        && gotOpaqueSplitScreenPrimary) {
                    // Can not be visible behind another opaque stack in split-screen-primary mode.
                    return STACK_VISIBILITY_INVISIBLE;
                }
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && !gotOpaqueSplitScreenSecondary) {
                gotSplitScreenStack = true;
                gotTranslucentSplitScreenSecondary = other.isTranslucent(starting);
                gotOpaqueSplitScreenSecondary = !gotTranslucentSplitScreenSecondary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        && gotOpaqueSplitScreenSecondary) {
                    // Can not be visible behind another opaque stack in split-screen-secondary mode.
                    return STACK_VISIBILITY_INVISIBLE;
                }
            }
            if (gotOpaqueSplitScreenPrimary && gotOpaqueSplitScreenSecondary) {
                // Can not be visible if we are in split-screen windowing mode and both halves of
                // the screen are opaque.
                return STACK_VISIBILITY_INVISIBLE;
            }
            if (isAssistantType && gotSplitScreenStack) {
                // Assistant stack can't be visible behind split-screen. In addition to this not
                // making sense, it also works around an issue here we boost the z-order of the
                // assistant window surfaces in window manager whenever it is visible.
                return STACK_VISIBILITY_INVISIBLE;
            }
        }

        if (!shouldBeVisible) {
            return STACK_VISIBILITY_INVISIBLE;
        }

        // Handle cases when there can be a translucent split-screen stack on top.
        switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                if (gotTranslucentSplitScreenPrimary || gotTranslucentSplitScreenSecondary) {
                    // At least one of the split-screen stacks that covers this one is translucent.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                if (gotTranslucentSplitScreenPrimary) {
                    // Covered by translucent primary split-screen on top.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                if (gotTranslucentSplitScreenSecondary) {
                    // Covered by translucent secondary split-screen on top.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
        }

        // Lastly - check if there is a translucent fullscreen stack on top.
        return gotTranslucentFullscreen ? STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT
                : STACK_VISIBILITY_VISIBLE;
    }

    ActivityRecord isInTask(ActivityRecord r) {
        if (r == null) {
            return null;
        }
        if (r.isDescendantOf(this)) {
            return r;
        }
        return null;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("userId="); pw.print(mUserId);
        pw.print(" effectiveUid="); UserHandle.formatUid(pw, effectiveUid);
        pw.print(" mCallingUid="); UserHandle.formatUid(pw, mCallingUid);
        pw.print(" mUserSetupComplete="); pw.print(mUserSetupComplete);
        pw.print(" mCallingPackage="); pw.print(mCallingPackage);
        pw.print(" mCallingFeatureId="); pw.println(mCallingFeatureId);
        if (affinity != null || rootAffinity != null) {
            pw.print(prefix); pw.print("affinity="); pw.print(affinity);
            if (affinity == null || !affinity.equals(rootAffinity)) {
                pw.print(" root="); pw.println(rootAffinity);
            } else {
                pw.println();
            }
        }
        if (mWindowLayoutAffinity != null) {
            pw.print(prefix); pw.print("windowLayoutAffinity="); pw.println(mWindowLayoutAffinity);
        }
        if (voiceSession != null || voiceInteractor != null) {
            pw.print(prefix); pw.print("VOICE: session=0x");
            pw.print(Integer.toHexString(System.identityHashCode(voiceSession)));
            pw.print(" interactor=0x");
            pw.println(Integer.toHexString(System.identityHashCode(voiceInteractor)));
        }
        if (intent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("intent={");
            intent.toShortString(sb, false, true, false, false);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (affinityIntent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("affinityIntent={");
            affinityIntent.toShortString(sb, false, true, false, false);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (origActivity != null) {
            pw.print(prefix); pw.print("origActivity=");
            pw.println(origActivity.flattenToShortString());
        }
        if (realActivity != null) {
            pw.print(prefix); pw.print("mActivityComponent=");
            pw.println(realActivity.flattenToShortString());
        }
        if (autoRemoveRecents || isPersistable || !isActivityTypeStandard()) {
            pw.print(prefix); pw.print("autoRemoveRecents="); pw.print(autoRemoveRecents);
            pw.print(" isPersistable="); pw.print(isPersistable);
            pw.print(" activityType="); pw.println(getActivityType());
        }
        if (rootWasReset || mNeverRelinquishIdentity || mReuseTask
                || mLockTaskAuth != LOCK_TASK_AUTH_PINNABLE) {
            pw.print(prefix); pw.print("rootWasReset="); pw.print(rootWasReset);
            pw.print(" mNeverRelinquishIdentity="); pw.print(mNeverRelinquishIdentity);
            pw.print(" mReuseTask="); pw.print(mReuseTask);
            pw.print(" mLockTaskAuth="); pw.println(lockTaskAuthToString());
        }
        if (mAffiliatedTaskId != mTaskId || mPrevAffiliateTaskId != INVALID_TASK_ID
                || mPrevAffiliate != null || mNextAffiliateTaskId != INVALID_TASK_ID
                || mNextAffiliate != null) {
            pw.print(prefix); pw.print("affiliation="); pw.print(mAffiliatedTaskId);
            pw.print(" prevAffiliation="); pw.print(mPrevAffiliateTaskId);
            pw.print(" (");
            if (mPrevAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(mPrevAffiliate)));
            }
            pw.print(") nextAffiliation="); pw.print(mNextAffiliateTaskId);
            pw.print(" (");
            if (mNextAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(mNextAffiliate)));
            }
            pw.println(")");
        }
        pw.print(prefix); pw.print("Activities="); pw.println(mChildren);
        if (!askedCompatMode || !inRecents || !isAvailable) {
            pw.print(prefix); pw.print("askedCompatMode="); pw.print(askedCompatMode);
            pw.print(" inRecents="); pw.print(inRecents);
            pw.print(" isAvailable="); pw.println(isAvailable);
        }
        if (lastDescription != null) {
            pw.print(prefix); pw.print("lastDescription="); pw.println(lastDescription);
        }
        if (mRootProcess != null) {
            pw.print(prefix); pw.print("mRootProcess="); pw.println(mRootProcess);
        }
        pw.print(prefix); pw.print("taskId=" + mTaskId); pw.println(" stackId=" + getRootTaskId());
        pw.print(prefix); pw.print("mHasBeenVisible="); pw.println(getHasBeenVisible());
        pw.print(prefix); pw.print("mResizeMode=");
        pw.print(ActivityInfo.resizeModeToString(mResizeMode));
        pw.print(" mSupportsPictureInPicture="); pw.print(mSupportsPictureInPicture);
        pw.print(" isResizeable="); pw.println(isResizeable());
        pw.print(prefix); pw.print("lastActiveTime="); pw.print(lastActiveTime);
        pw.println(" (inactive for " + (getInactiveDuration() / 1000) + "s)");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        if (stringName != null) {
            sb.append(stringName);
            sb.append(" U=");
            sb.append(mUserId);
            sb.append(" StackId=");
            sb.append(getRootTaskId());
            sb.append(" sz=");
            sb.append(getChildCount());
            sb.append('}');
            return sb.toString();
        }
        sb.append("Task{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        sb.append(mTaskId);
        sb.append(" visible=" + shouldBeVisible(null /* starting */));
        sb.append(" type=" + activityTypeToString(getActivityType()));
        sb.append(" mode=" + windowingModeToString(getWindowingMode()));
        sb.append(" translucent=" + isTranslucent(null /* starting */));
        if (affinity != null) {
            sb.append(" A=");
            sb.append(affinity);
        } else if (intent != null && intent.getComponent() != null) {
            sb.append(" I=");
            sb.append(intent.getComponent().flattenToShortString());
        } else if (affinityIntent != null && affinityIntent.getComponent() != null) {
            sb.append(" aI=");
            sb.append(affinityIntent.getComponent().flattenToShortString());
        } else {
            sb.append(" ??");
        }
        stringName = sb.toString();
        return toString();
    }

    /** @see #getNumRunningActivities(TaskActivitiesReport) */
    static class TaskActivitiesReport implements Consumer<ActivityRecord> {
        int numRunning;
        int numActivities;
        ActivityRecord top;
        ActivityRecord base;

        void reset() {
            numRunning = numActivities = 0;
            top = base = null;
        }

        @Override
        public void accept(ActivityRecord r) {
            if (r.finishing) {
                return;
            }

            base = r;

            // Increment the total number of non-finishing activities
            numActivities++;

            if (top == null || (top.isState(ActivityState.INITIALIZING))) {
                top = r;
                // Reset the number of running activities until we hit the first non-initializing
                // activity
                numRunning = 0;
            }
            if (r.attachedToProcess()) {
                // Increment the number of actually running activities
                numRunning++;
            }
        }
    }

    /**
     * Saves this {@link Task} to XML using given serializer.
     */
    void saveToXml(XmlSerializer out) throws Exception {
        if (DEBUG_RECENTS) Slog.i(TAG_RECENTS, "Saving task=" + this);

        out.attribute(null, ATTR_TASKID, String.valueOf(mTaskId));
        if (realActivity != null) {
            out.attribute(null, ATTR_REALACTIVITY, realActivity.flattenToShortString());
        }
        out.attribute(null, ATTR_REALACTIVITY_SUSPENDED, String.valueOf(realActivitySuspended));
        if (origActivity != null) {
            out.attribute(null, ATTR_ORIGACTIVITY, origActivity.flattenToShortString());
        }
        // Write affinity, and root affinity if it is different from affinity.
        // We use the special string "@" for a null root affinity, so we can identify
        // later whether we were given a root affinity or should just make it the
        // same as the affinity.
        if (affinity != null) {
            out.attribute(null, ATTR_AFFINITY, affinity);
            if (!affinity.equals(rootAffinity)) {
                out.attribute(null, ATTR_ROOT_AFFINITY, rootAffinity != null ? rootAffinity : "@");
            }
        } else if (rootAffinity != null) {
            out.attribute(null, ATTR_ROOT_AFFINITY, rootAffinity != null ? rootAffinity : "@");
        }
        if (mWindowLayoutAffinity != null) {
            out.attribute(null, ATTR_WINDOW_LAYOUT_AFFINITY, mWindowLayoutAffinity);
        }
        out.attribute(null, ATTR_ROOTHASRESET, String.valueOf(rootWasReset));
        out.attribute(null, ATTR_AUTOREMOVERECENTS, String.valueOf(autoRemoveRecents));
        out.attribute(null, ATTR_ASKEDCOMPATMODE, String.valueOf(askedCompatMode));
        out.attribute(null, ATTR_USERID, String.valueOf(mUserId));
        out.attribute(null, ATTR_USER_SETUP_COMPLETE, String.valueOf(mUserSetupComplete));
        out.attribute(null, ATTR_EFFECTIVE_UID, String.valueOf(effectiveUid));
        out.attribute(null, ATTR_LASTTIMEMOVED, String.valueOf(mLastTimeMoved));
        out.attribute(null, ATTR_NEVERRELINQUISH, String.valueOf(mNeverRelinquishIdentity));
        if (lastDescription != null) {
            out.attribute(null, ATTR_LASTDESCRIPTION, lastDescription.toString());
        }
        if (getTaskDescription() != null) {
            getTaskDescription().saveToXml(out);
        }
        out.attribute(null, ATTR_TASK_AFFILIATION, String.valueOf(mAffiliatedTaskId));
        out.attribute(null, ATTR_PREV_AFFILIATION, String.valueOf(mPrevAffiliateTaskId));
        out.attribute(null, ATTR_NEXT_AFFILIATION, String.valueOf(mNextAffiliateTaskId));
        out.attribute(null, ATTR_CALLING_UID, String.valueOf(mCallingUid));
        out.attribute(null, ATTR_CALLING_PACKAGE, mCallingPackage == null ? "" : mCallingPackage);
        out.attribute(null, ATTR_CALLING_FEATURE_ID,
                mCallingFeatureId == null ? "" : mCallingFeatureId);
        out.attribute(null, ATTR_RESIZE_MODE, String.valueOf(mResizeMode));
        out.attribute(null, ATTR_SUPPORTS_PICTURE_IN_PICTURE,
                String.valueOf(mSupportsPictureInPicture));
        if (mLastNonFullscreenBounds != null) {
            out.attribute(
                    null, ATTR_NON_FULLSCREEN_BOUNDS, mLastNonFullscreenBounds.flattenToString());
        }
        out.attribute(null, ATTR_MIN_WIDTH, String.valueOf(mMinWidth));
        out.attribute(null, ATTR_MIN_HEIGHT, String.valueOf(mMinHeight));
        out.attribute(null, ATTR_PERSIST_TASK_VERSION, String.valueOf(PERSIST_TASK_VERSION));

        if (affinityIntent != null) {
            out.startTag(null, TAG_AFFINITYINTENT);
            affinityIntent.saveToXml(out);
            out.endTag(null, TAG_AFFINITYINTENT);
        }

        if (intent != null) {
            out.startTag(null, TAG_INTENT);
            intent.saveToXml(out);
            out.endTag(null, TAG_INTENT);
        }

        sTmpException = null;
        final PooledFunction f = PooledLambda.obtainFunction(Task::saveActivityToXml,
                PooledLambda.__(ActivityRecord.class), getBottomMostActivity(), out);
        forAllActivities(f);
        f.recycle();
        if (sTmpException != null) {
            throw sTmpException;
        }
    }

    private static boolean saveActivityToXml(
            ActivityRecord r, ActivityRecord first, XmlSerializer out) {
        if (r.info.persistableMode == ActivityInfo.PERSIST_ROOT_ONLY || !r.isPersistable()
                || ((r.intent.getFlags() & FLAG_ACTIVITY_NEW_DOCUMENT
                | FLAG_ACTIVITY_RETAIN_IN_RECENTS) == FLAG_ACTIVITY_NEW_DOCUMENT)
                && r != first) {
            // Stop at first non-persistable or first break in task (CLEAR_WHEN_TASK_RESET).
            return true;
        }
        try {
            out.startTag(null, TAG_ACTIVITY);
            r.saveToXml(out);
            out.endTag(null, TAG_ACTIVITY);
            return false;
        } catch (Exception e) {
            sTmpException = e;
            return true;
        }
    }

    static Task restoreFromXml(XmlPullParser in, ActivityStackSupervisor stackSupervisor)
            throws IOException, XmlPullParserException {
        Intent intent = null;
        Intent affinityIntent = null;
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        ComponentName realActivity = null;
        boolean realActivitySuspended = false;
        ComponentName origActivity = null;
        String affinity = null;
        String rootAffinity = null;
        boolean hasRootAffinity = false;
        String windowLayoutAffinity = null;
        boolean rootHasReset = false;
        boolean autoRemoveRecents = false;
        boolean askedCompatMode = false;
        int taskType = 0;
        int userId = 0;
        boolean userSetupComplete = true;
        int effectiveUid = -1;
        String lastDescription = null;
        long lastTimeOnTop = 0;
        boolean neverRelinquishIdentity = true;
        int taskId = INVALID_TASK_ID;
        final int outerDepth = in.getDepth();
        TaskDescription taskDescription = new TaskDescription();
        int taskAffiliation = INVALID_TASK_ID;
        int prevTaskId = INVALID_TASK_ID;
        int nextTaskId = INVALID_TASK_ID;
        int callingUid = -1;
        String callingPackage = "";
        String callingFeatureId = null;
        int resizeMode = RESIZE_MODE_FORCE_RESIZEABLE;
        boolean supportsPictureInPicture = false;
        Rect lastNonFullscreenBounds = null;
        int minWidth = INVALID_MIN_SIZE;
        int minHeight = INVALID_MIN_SIZE;
        int persistTaskVersion = 0;

        for (int attrNdx = in.getAttributeCount() - 1; attrNdx >= 0; --attrNdx) {
            final String attrName = in.getAttributeName(attrNdx);
            final String attrValue = in.getAttributeValue(attrNdx);
            if (TaskPersister.DEBUG) {
                Slog.d(TaskPersister.TAG, "Task: attribute name=" + attrName + " value="
                        + attrValue);
            }
            switch (attrName) {
                case ATTR_TASKID:
                    if (taskId == INVALID_TASK_ID) taskId = Integer.parseInt(attrValue);
                    break;
                case ATTR_REALACTIVITY:
                    realActivity = ComponentName.unflattenFromString(attrValue);
                    break;
                case ATTR_REALACTIVITY_SUSPENDED:
                    realActivitySuspended = Boolean.valueOf(attrValue);
                    break;
                case ATTR_ORIGACTIVITY:
                    origActivity = ComponentName.unflattenFromString(attrValue);
                    break;
                case ATTR_AFFINITY:
                    affinity = attrValue;
                    break;
                case ATTR_ROOT_AFFINITY:
                    rootAffinity = attrValue;
                    hasRootAffinity = true;
                    break;
                case ATTR_WINDOW_LAYOUT_AFFINITY:
                    windowLayoutAffinity = attrValue;
                    break;
                case ATTR_ROOTHASRESET:
                    rootHasReset = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_AUTOREMOVERECENTS:
                    autoRemoveRecents = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_ASKEDCOMPATMODE:
                    askedCompatMode = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_USERID:
                    userId = Integer.parseInt(attrValue);
                    break;
                case ATTR_USER_SETUP_COMPLETE:
                    userSetupComplete = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_EFFECTIVE_UID:
                    effectiveUid = Integer.parseInt(attrValue);
                    break;
                case ATTR_TASKTYPE:
                    taskType = Integer.parseInt(attrValue);
                    break;
                case ATTR_LASTDESCRIPTION:
                    lastDescription = attrValue;
                    break;
                case ATTR_LASTTIMEMOVED:
                    lastTimeOnTop = Long.parseLong(attrValue);
                    break;
                case ATTR_NEVERRELINQUISH:
                    neverRelinquishIdentity = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_TASK_AFFILIATION:
                    taskAffiliation = Integer.parseInt(attrValue);
                    break;
                case ATTR_PREV_AFFILIATION:
                    prevTaskId = Integer.parseInt(attrValue);
                    break;
                case ATTR_NEXT_AFFILIATION:
                    nextTaskId = Integer.parseInt(attrValue);
                    break;
                case ATTR_CALLING_UID:
                    callingUid = Integer.parseInt(attrValue);
                    break;
                case ATTR_CALLING_PACKAGE:
                    callingPackage = attrValue;
                    break;
                case ATTR_CALLING_FEATURE_ID:
                    callingFeatureId = attrValue;
                    break;
                case ATTR_RESIZE_MODE:
                    resizeMode = Integer.parseInt(attrValue);
                    break;
                case ATTR_SUPPORTS_PICTURE_IN_PICTURE:
                    supportsPictureInPicture = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_NON_FULLSCREEN_BOUNDS:
                    lastNonFullscreenBounds = Rect.unflattenFromString(attrValue);
                    break;
                case ATTR_MIN_WIDTH:
                    minWidth = Integer.parseInt(attrValue);
                    break;
                case ATTR_MIN_HEIGHT:
                    minHeight = Integer.parseInt(attrValue);
                    break;
                case ATTR_PERSIST_TASK_VERSION:
                    persistTaskVersion = Integer.parseInt(attrValue);
                    break;
                default:
                    if (!attrName.startsWith(TaskDescription.ATTR_TASKDESCRIPTION_PREFIX)) {
                        Slog.w(TAG, "Task: Unknown attribute=" + attrName);
                    }
            }
        }
        taskDescription.restoreFromXml(in);

        int event;
        while (((event = in.next()) != XmlPullParser.END_DOCUMENT)
                && (event != XmlPullParser.END_TAG || in.getDepth() >= outerDepth)) {
            if (event == XmlPullParser.START_TAG) {
                final String name = in.getName();
                if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG, "Task: START_TAG name=" + name);
                if (TAG_AFFINITYINTENT.equals(name)) {
                    affinityIntent = Intent.restoreFromXml(in);
                } else if (TAG_INTENT.equals(name)) {
                    intent = Intent.restoreFromXml(in);
                } else if (TAG_ACTIVITY.equals(name)) {
                    ActivityRecord activity =
                            ActivityRecord.restoreFromXml(in, stackSupervisor);
                    if (TaskPersister.DEBUG) {
                        Slog.d(TaskPersister.TAG, "Task: activity=" + activity);
                    }
                    if (activity != null) {
                        activities.add(activity);
                    }
                } else {
                    Slog.e(TAG, "restoreTask: Unexpected name=" + name);
                    XmlUtils.skipCurrentTag(in);
                }
            }
        }
        if (!hasRootAffinity) {
            rootAffinity = affinity;
        } else if ("@".equals(rootAffinity)) {
            rootAffinity = null;
        }
        if (effectiveUid <= 0) {
            Intent checkIntent = intent != null ? intent : affinityIntent;
            effectiveUid = 0;
            if (checkIntent != null) {
                IPackageManager pm = AppGlobals.getPackageManager();
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(
                            checkIntent.getComponent().getPackageName(),
                            PackageManager.MATCH_UNINSTALLED_PACKAGES
                                    | PackageManager.MATCH_DISABLED_COMPONENTS, userId);
                    if (ai != null) {
                        effectiveUid = ai.uid;
                    }
                } catch (RemoteException e) {
                }
            }
            Slog.w(TAG, "Updating task #" + taskId + " for " + checkIntent
                    + ": effectiveUid=" + effectiveUid);
        }

        if (persistTaskVersion < 1) {
            // We need to convert the resize mode of home activities saved before version one if
            // they are marked as RESIZE_MODE_RESIZEABLE to
            // RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION since we didn't have that differentiation
            // before version 1 and the system didn't resize home activities before then.
            if (taskType == 1 /* old home type */ && resizeMode == RESIZE_MODE_RESIZEABLE) {
                resizeMode = RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
            }
        } else {
            // This activity has previously marked itself explicitly as both resizeable and
            // supporting picture-in-picture.  Since there is no longer a requirement for
            // picture-in-picture activities to be resizeable, we can mark this simply as
            // resizeable and supporting picture-in-picture separately.
            if (resizeMode == RESIZE_MODE_RESIZEABLE_AND_PIPABLE_DEPRECATED) {
                resizeMode = RESIZE_MODE_RESIZEABLE;
                supportsPictureInPicture = true;
            }
        }

        final Task task = new Task(stackSupervisor.mService, taskId, intent,
                affinityIntent, affinity, rootAffinity, realActivity, origActivity, rootHasReset,
                autoRemoveRecents, askedCompatMode, userId, effectiveUid, lastDescription,
                lastTimeOnTop, neverRelinquishIdentity, taskDescription, taskAffiliation,
                prevTaskId, nextTaskId, callingUid, callingPackage,
                callingFeatureId, resizeMode, supportsPictureInPicture, realActivitySuspended,
                userSetupComplete, minWidth, minHeight, null /*ActivityInfo*/,
                null /*_voiceSession*/, null /*_voiceInteractor*/, null /* stack */);
        task.mLastNonFullscreenBounds = lastNonFullscreenBounds;
        task.setBounds(lastNonFullscreenBounds);
        task.mWindowLayoutAffinity = windowLayoutAffinity;

        for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
            task.addChild(activities.get(activityNdx));
        }

        if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "Restored task=" + task);
        return task;
    }

    @Override
    boolean isOrganized() {
        return mTaskOrganizer != null;
    }

    @Override
    protected void reparentSurfaceControl(SurfaceControl.Transaction t, SurfaceControl newParent) {
        /**
         * Avoid reparenting SurfaceControl of the organized tasks that are always on top, since
         * the surfaces should be controlled by the organizer itself, like bubbles.
         */
        if (isOrganized() && isAlwaysOnTop()) {
            return;
        }
        super.reparentSurfaceControl(t, newParent);
    }

    void setHasBeenVisible(boolean hasBeenVisible) {
        final boolean prevHasBeenVisible = mHasBeenVisible;
        mHasBeenVisible = hasBeenVisible;
        if (hasBeenVisible) {
            // If the task is not yet visible when it is added to the task organizer, then we should
            // hide it to allow the task organizer to show it when it is properly reparented. We
            // skip this for tasks created by the organizer because they can synchronously update
            // the leash before new children are added to the task.
            if (!mCreatedByOrganizer && mTaskOrganizer != null && !prevHasBeenVisible) {
                getSyncTransaction().hide(getSurfaceControl());
                commitPendingTransaction();
            }

            sendTaskAppeared();
            if (!isRootTask()) {
                getRootTask().setHasBeenVisible(true);
            }
        }
    }

    boolean getHasBeenVisible() {
        return mHasBeenVisible;
    }

    /** In the case that these conditions are true, we want to send the Task to the organizer:
     *     1. An organizer has been set
     *     2. The Task was created by the organizer
     *     or
     *     2a. We have a SurfaceControl
     *     2b. We have finished drawing
     * Any time any of these conditions are updated, the updating code should call
     * sendTaskAppeared.
     */
    boolean taskAppearedReady() {
        if (mTaskOrganizer == null) {
            return false;
        }

        if (mCreatedByOrganizer) {
            return true;
        }

        return mSurfaceControl != null && getHasBeenVisible();
    }

    private void sendTaskAppeared() {
        if (mTaskOrganizer != null) {
            mAtmService.mTaskOrganizerController.onTaskAppeared(mTaskOrganizer, this);
        }
    }

    private void sendTaskVanished(ITaskOrganizer organizer) {
        if (organizer != null) {
            mAtmService.mTaskOrganizerController.onTaskVanished(organizer, this);
        }
   }

    @VisibleForTesting
    boolean setTaskOrganizer(ITaskOrganizer organizer) {
        if (mTaskOrganizer == organizer) {
            return false;
        }

        ITaskOrganizer previousOrganizer = mTaskOrganizer;
        // Update the new task organizer before calling sendTaskVanished since it could result in
        // a new SurfaceControl getting created that would notify the old organizer about it.
        mTaskOrganizer = organizer;
        // Let the old organizer know it has lost control.
        sendTaskVanished(previousOrganizer);

        if (mTaskOrganizer != null) {
            sendTaskAppeared();
        } else {
            // No longer managed by any organizer.
            mTaskAppearedSent = false;
            mLastTaskOrganizerWindowingMode = -1;
            setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, false /* set */);
            if (mCreatedByOrganizer) {
                removeImmediately();
            }
        }

        return true;
    }

    /**
     * Called when the task state changes (ie. from windowing mode change) an the task organizer
     * state should also be updated.
     *
     * @param forceUpdate Updates the task organizer to the one currently specified in the task
     *                    org controller for the task's windowing mode, ignoring the cached
     *                    windowing mode checks.
     * @return {@code true} if task organizer changed.
     */
    boolean updateTaskOrganizerState(boolean forceUpdate) {
        if (!isRootTask()) {
            final boolean result = setTaskOrganizer(null);
            mLastTaskOrganizerWindowingMode = -1;
            return result;
        }

        final int windowingMode = getWindowingMode();
        if (!forceUpdate && windowingMode == mLastTaskOrganizerWindowingMode) {
            // If our windowing mode hasn't actually changed, then just stick
            // with our old organizer. This lets us implement the semantic
            // where SysUI can continue to manage it's old tasks
            // while CTS temporarily takes over the registration.
            return false;
        }
        /*
         * Different windowing modes may be managed by different task organizers. If
         * getTaskOrganizer returns null, we still call setTaskOrganizer to
         * make sure we clear it.
         */
        final ITaskOrganizer org =
                mWmService.mAtmService.mTaskOrganizerController.getTaskOrganizer(windowingMode);
        final boolean result = setTaskOrganizer(org);
        mLastTaskOrganizerWindowingMode = org != null ? windowingMode : -1;
        return result;
    }

    @Override
    void setSurfaceControl(SurfaceControl sc) {
        super.setSurfaceControl(sc);
        // If the TaskOrganizer was set before we created the SurfaceControl, we need to
        // emit the callbacks now.
        sendTaskAppeared();
    }

    /**
     * @return true if the task is currently focused.
     */
    private boolean isFocused() {
        if (mDisplayContent == null || mDisplayContent.mCurrentFocus == null) {
            return false;
        }
        return mDisplayContent.mCurrentFocus.getTask() == this;
    }

    /**
     * @return true if the task is visible and has at least one visible child.
     */
    private boolean hasVisibleChildren() {
        if (!isAttached() || isForceHidden()) {
            return false;
        }

        return getActivity(ActivityRecord::isVisible) != null;
    }

    /**
     * @return the desired shadow radius in pixels for the current task.
     */
    private float getShadowRadius(boolean taskIsFocused) {
        int elevation = 0;

        // Get elevation for a specific windowing mode.
        if (inPinnedWindowingMode()) {
            elevation = PINNED_WINDOWING_MODE_ELEVATION_IN_DIP;
        } else if (inFreeformWindowingMode()) {
            elevation = taskIsFocused
                    ? DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP : DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP;
        } else {
            // For all other windowing modes, do not draw a shadow.
            return 0;
        }

        // If the task has no visible children, do not draw a shadow.
        if (!hasVisibleChildren()) {
            return 0;
        }

        return dipToPixel(elevation, getDisplayContent().getDisplayMetrics());
    }

    /**
     * Update the length of the shadow if needed based on windowing mode and task focus state.
     */
    private void updateShadowsRadius(boolean taskIsFocused,
            SurfaceControl.Transaction pendingTransaction) {
        if (!mWmService.mRenderShadowsInCompositor || !isRootTask()) return;

        final float newShadowRadius = getShadowRadius(taskIsFocused);
        if (mShadowRadius != newShadowRadius) {
            mShadowRadius = newShadowRadius;
            pendingTransaction.setShadowRadius(getSurfaceControl(), mShadowRadius);
        }
    }

    /**
     * Called on the task of a window which gained or lost focus.
     * @param hasFocus
     */
    void onWindowFocusChanged(boolean hasFocus) {
        updateShadowsRadius(hasFocus, getSyncTransaction());
    }

    void onPictureInPictureParamsChanged() {
        if (isOrganized()) {
            mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(this, true /* force */);
        }
    }

    /**
     * See {@link WindowContainerTransaction#setBoundsChangeTransaction}. In short this
     * transaction will be consumed by the next BASE_APPLICATION window within our hierarchy
     * to resize, and it will defer the transaction until that resize frame completes.
     */
    void setMainWindowSizeChangeTransaction(SurfaceControl.Transaction t) {
        setMainWindowSizeChangeTransaction(t, this);
        forAllWindows(WindowState::requestRedrawForSync, true);
    }

    private void setMainWindowSizeChangeTransaction(SurfaceControl.Transaction t, Task origin) {
        // This is only meaningful on an activity's task, so put it on the top one.
        ActivityRecord topActivity = getTopNonFinishingActivity();
        Task leaf = topActivity != null ? topActivity.getTask() : null;
        if (leaf == null) {
            return;
        }
        if (leaf != this) {
            leaf.setMainWindowSizeChangeTransaction(t, origin);
            return;
        }
        mMainWindowSizeChangeTransaction = t;
        mMainWindowSizeChangeTask = t == null ? null : origin;
    }

    SurfaceControl.Transaction getMainWindowSizeChangeTransaction() {
        return mMainWindowSizeChangeTransaction;
    }

    Task getMainWindowSizeChangeTask() {
        return mMainWindowSizeChangeTask;
    }

    void setActivityWindowingMode(int windowingMode) {
        PooledConsumer c = PooledLambda.obtainConsumer(ActivityRecord::setWindowingMode,
                PooledLambda.__(ActivityRecord.class), windowingMode);
        forAllActivities(c);
        c.recycle();
    }

    /**
     * Sets/unsets the forced-hidden state flag for this task depending on {@param set}.
     * @return Whether the force hidden state changed
     */
    boolean setForceHidden(int flags, boolean set) {
        int newFlags = mForceHiddenFlags;
        if (set) {
            newFlags |= flags;
        } else {
            newFlags &= ~flags;
        }
        if (mForceHiddenFlags == newFlags) {
            return false;
        }
        final boolean wasHidden = isForceHidden();
        mForceHiddenFlags = newFlags;
        if (wasHidden && isFocusableAndVisible()) {
            // The change in force-hidden state will change visibility without triggering a stack
            // order change, so we should reset the preferred top focusable stack to ensure it's not
            // used if a new activity is started from this task.
            getDisplayArea().resetPreferredTopFocusableStackIfBelow(this);
        }
        return true;
    }

    /**
     * Returns whether this task is currently forced to be hidden for any reason.
     */
    protected boolean isForceHidden() {
        return mForceHiddenFlags != 0;
    }

    @Override
    long getProtoFieldId() {
        return TASK;
    }

    @Override
    public void setWindowingMode(int windowingMode) {
        // Reset the cached result of toString()
        stringName = null;

        // Calling Task#setWindowingMode() for leaf task since this is the a specialization of
        // {@link #setWindowingMode(int)} for ActivityStack.
        if (!isRootTask()) {
            super.setWindowingMode(windowingMode);
            return;
        }

        setWindowingMode(windowingMode, false /* creating */);
    }

    /**
     * Specialization of {@link #setWindowingMode(int)} for this subclass.
     *
     * @param preferredWindowingMode the preferred windowing mode. This may not be honored depending
     *         on the state of things. For example, WINDOWING_MODE_UNDEFINED will resolve to the
     *         previous non-transient mode if this stack is currently in a transient mode.
     * @param creating {@code true} if this is being run during ActivityStack construction.
     */
    void setWindowingMode(int preferredWindowingMode, boolean creating) {
        mWmService.inSurfaceTransaction(() -> setWindowingModeInSurfaceTransaction(
                preferredWindowingMode, creating));
    }

    private void setWindowingModeInSurfaceTransaction(int preferredWindowingMode,
            boolean creating) {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            Slog.d(TAG, "taskDisplayArea is null, bail early");
            return;
        }
        final int currentMode = getWindowingMode();
        final int currentOverrideMode = getRequestedOverrideWindowingMode();
        final Task topTask = getTopMostTask();
        int windowingMode = preferredWindowingMode;

        // Need to make sure windowing mode is supported. If we in the process of creating the stack
        // no need to resolve the windowing mode again as it is already resolved to the right mode.
        if (!creating) {
            if (!taskDisplayArea.isValidWindowingMode(windowingMode, null /* ActivityRecord */,
                    topTask, getActivityType())) {
                windowingMode = WINDOWING_MODE_UNDEFINED;
            }
        }

        final boolean alreadyInSplitScreenMode = taskDisplayArea.isSplitScreenModeActivated();

        if (creating && alreadyInSplitScreenMode && windowingMode == WINDOWING_MODE_FULLSCREEN
                && isActivityTypeStandardOrUndefined()) {
            // If the stack is being created explicitly in fullscreen mode, dismiss split-screen
            // and display a warning toast about it.
            mAtmService.getTaskChangeNotificationController()
                    .notifyActivityDismissingDockedStack();
            taskDisplayArea.onSplitScreenModeDismissed(this);
        }

        if (currentMode == windowingMode) {
            // You are already in the window mode, so we can skip most of the work below. However,
            // it's possible that we have inherited the current windowing mode from a parent. So,
            // fulfill this method's contract by setting the override mode directly.
            getRequestedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode);
            return;
        }

        final ActivityRecord topActivity = getTopNonFinishingActivity();

        // For now, assume that the Stack's windowing mode is what will actually be used
        // by it's activities. In the future, there may be situations where this doesn't
        // happen; so at that point, this message will need to handle that.
        int likelyResolvedMode = windowingMode;
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            final ConfigurationContainer parent = getParent();
            likelyResolvedMode = parent != null ? parent.getWindowingMode()
                    : WINDOWING_MODE_FULLSCREEN;
        }
        if (currentMode == WINDOWING_MODE_PINNED) {
            mAtmService.getTaskChangeNotificationController().notifyActivityUnpinned();
        }
        if (likelyResolvedMode == WINDOWING_MODE_PINNED
                && taskDisplayArea.getRootPinnedTask() != null) {
            // Can only have 1 pip at a time, so replace an existing pip
            taskDisplayArea.getRootPinnedTask().dismissPip();
        }
        if (likelyResolvedMode != WINDOWING_MODE_FULLSCREEN
                && topActivity != null && !topActivity.noDisplay
                && topActivity.isNonResizableOrForcedResizable(likelyResolvedMode)) {
            // Inform the user that they are starting an app that may not work correctly in
            // multi-window mode.
            final String packageName = topActivity.info.applicationInfo.packageName;
            mAtmService.getTaskChangeNotificationController().notifyActivityForcedResizable(
                    topTask.mTaskId, FORCED_RESIZEABLE_REASON_SPLIT_SCREEN, packageName);
        }

        mAtmService.deferWindowLayout();
        try {
            if (topActivity != null) {
                mStackSupervisor.mNoAnimActivities.add(topActivity);
            }
            super.setWindowingMode(windowingMode);
            // setWindowingMode triggers an onConfigurationChanged cascade which can result in a
            // different resolved windowing mode (usually when preferredWindowingMode is UNDEFINED).
            windowingMode = getWindowingMode();

            if (creating) {
                // Nothing else to do if we don't have a window container yet. E.g. call from ctor.
                return;
            }

            if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY && alreadyInSplitScreenMode) {
                // We already have a split-screen stack in this display, so just move the tasks over.
                // TODO: Figure-out how to do all the stuff in
                // AMS.setTaskWindowingModeSplitScreenPrimary
                throw new IllegalArgumentException("Setting primary split-screen windowing mode"
                        + " while there is already one isn't currently supported");
                //return;
            }
        } finally {
            mAtmService.continueWindowLayout();
        }

        mRootWindowContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);
        mRootWindowContainer.resumeFocusedStacksTopActivities();
    }

    /** Resume next focusable stack after reparenting to another display. */
    void postReparent() {
        adjustFocusToNextFocusableTask("reparent", true /* allowFocusSelf */,
                true /* moveDisplayToTop */);
        mRootWindowContainer.resumeFocusedStacksTopActivities();
        // Update visibility of activities before notifying WM. This way it won't try to resize
        // windows that are no longer visible.
        mRootWindowContainer.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                !PRESERVE_WINDOWS);
    }

    DisplayContent getDisplay() {
        return getDisplayContent();
    }

    /** @return true if the stack can only contain one task */
    boolean isSingleTaskInstance() {
        final DisplayContent display = getDisplay();
        return display != null && display.isSingleTaskInstance();
    }

    final boolean isHomeOrRecentsStack() {
        return isActivityTypeHome() || isActivityTypeRecents();
    }

    final boolean isOnHomeDisplay() {
        return getDisplayId() == DEFAULT_DISPLAY;
    }

    void moveToFront(String reason) {
        moveToFront(reason, null);
    }

    /**
     * @param reason The reason for moving the stack to the front.
     * @param task If non-null, the task will be moved to the top of the stack.
     */
    void moveToFront(String reason, Task task) {
        if (!isAttached()) {
            return;
        }

        final TaskDisplayArea taskDisplayArea = getDisplayArea();

        if (inSplitScreenSecondaryWindowingMode()) {
            // If the stack is in split-screen secondary mode, we need to make sure we move the
            // primary split-screen stack forward in the case it is currently behind a fullscreen
            // stack so both halves of the split-screen appear on-top and the fullscreen stack isn't
            // cutting between them.
            // TODO(b/70677280): This is a workaround until we can fix as part of b/70677280.
            final Task topFullScreenStack =
                    taskDisplayArea.getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (topFullScreenStack != null) {
                final Task primarySplitScreenStack =
                        taskDisplayArea.getRootSplitScreenPrimaryTask();
                if (primarySplitScreenStack != null
                        && taskDisplayArea.getIndexOf(topFullScreenStack)
                        > taskDisplayArea.getIndexOf(primarySplitScreenStack)) {
                    primarySplitScreenStack.moveToFront(reason + " splitScreenToTop");
                }
            }
        }

        if (!isActivityTypeHome() && returnsToHomeStack()) {
            // Make sure the home stack is behind this stack since that is where we should return to
            // when this stack is no longer visible.
            taskDisplayArea.moveHomeStackToFront(reason + " returnToHome");
        }

        if (isRootTask()) {
            taskDisplayArea.positionChildAt(POSITION_TOP, this, false /* includingParents */,
                    reason);
        }
        if (task == null) {
            task = this;
        }
        task.getParent().positionChildAt(POSITION_TOP, task, true /* includingParents */);
    }

    /**
     * This moves 'task' to the back of this task and also recursively moves this task to the back
     * of its parents (if applicable).
     *
     * @param reason The reason for moving the stack to the back.
     * @param task If non-null, the task will be moved to the bottom of the stack.
     **/
    void moveToBack(String reason, Task task) {
        if (!isAttached()) {
            return;
        }
        final TaskDisplayArea displayArea = getDisplayArea();
        if (!mCreatedByOrganizer) {
            // If this is just a normal task, so move to back of parent and then move 'task' to
            // back of this.
            final WindowContainer parent = getParent();
            final Task parentTask = parent != null ? parent.asTask() : null;
            if (parentTask != null) {
                parentTask.moveToBack(reason, this);
            } else {
                displayArea.positionChildAt(POSITION_BOTTOM, this, false /*includingParents*/,
                        reason);
            }
            if (task != null && task != this) {
                positionChildAtBottom(task);
            }
            return;
        }
        if (task == null || task == this) {
            return;
        }
        // This is a created-by-organizer task. In this case, let the organizer deal with this
        // task's ordering. However, we still need to move 'task' to back. The intention is that
        // this ends up behind the home-task so that it is made invisible; so, if the home task
        // is not a child of this, reparent 'task' to the back of the home task's actual parent.
        displayArea.positionTaskBehindHome(task);
    }

    // TODO: Should each user have there own stacks?
    @Override
    void switchUser(int userId) {
        if (mCurrentUser == userId) {
            return;
        }
        mCurrentUser = userId;

        super.switchUser(userId);
        if (isLeafTask() && showToCurrentUser()) {
            getParent().positionChildAt(POSITION_TOP, this, false /*includeParents*/);
        }
    }

    void minimalResumeActivityLocked(ActivityRecord r) {
        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + r + " (starting new instance)"
                + " callers=" + Debug.getCallers(5));
        r.setState(RESUMED, "minimalResumeActivityLocked");
        r.completeResumeLocked();
    }

    private void clearLaunchTime(ActivityRecord r) {
        // Make sure that there is no activity waiting for this to launch.
        if (!mStackSupervisor.mWaitingActivityLaunched.isEmpty()) {
            mStackSupervisor.removeIdleTimeoutForActivity(r);
            mStackSupervisor.scheduleIdleTimeout(r);
        }
    }

    void awakeFromSleepingLocked() {
        // Ensure activities are no longer sleeping.
        forAllActivities((Consumer<ActivityRecord>) (r) -> r.setSleeping(false));
        if (mPausingActivity != null) {
            Slog.d(TAG, "awakeFromSleepingLocked: previously pausing activity didn't pause");
            mPausingActivity.activityPaused(true);
        }
    }

    void checkReadyForSleep() {
        if (shouldSleepActivities() && goToSleepIfPossible(false /* shuttingDown */)) {
            mStackSupervisor.checkReadyForSleepLocked(true /* allowDelay */);
        }
    }

    /**
     * Tries to put the activities in the stack to sleep.
     *
     * If the stack is not in a state where its activities can be put to sleep, this function will
     * start any necessary actions to move the stack into such a state. It is expected that this
     * function get called again when those actions complete.
     *
     * @param shuttingDown true when the called because the device is shutting down.
     * @return true if the stack finished going to sleep, false if the stack only started the
     * process of going to sleep (checkReadyForSleep will be called when that process finishes).
     */
    boolean goToSleepIfPossible(boolean shuttingDown) {
        boolean shouldSleep = true;

        if (mResumedActivity != null) {
            // Still have something resumed; can't sleep until it is paused.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep needs to pause " + mResumedActivity);
            if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                    "Sleep => pause with userLeaving=false");

            startPausingLocked(false /* userLeaving */, true /* uiSleeping */, null /* resuming */);
            shouldSleep = false ;
        } else if (mPausingActivity != null) {
            // Still waiting for something to pause; can't sleep yet.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still waiting to pause " + mPausingActivity);
            shouldSleep = false;
        }

        if (!shuttingDown) {
            if (containsActivityFromStack(mStackSupervisor.mStoppingActivities)) {
                // Still need to tell some activities to stop; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still need to stop "
                        + mStackSupervisor.mStoppingActivities.size() + " activities");

                mStackSupervisor.scheduleIdle();
                shouldSleep = false;
            }
        }

        if (shouldSleep) {
            goToSleep();
        }

        return shouldSleep;
    }

    void goToSleep() {
        // Make sure all visible activities are now sleeping. This will update the activity's
        // visibility and onStop() will be called.
        forAllActivities((r) -> {
            if (r.isState(STARTED, RESUMED, PAUSING, PAUSED, STOPPING, STOPPED)) {
                r.setSleeping(true);
            }
        });

        // Ensure visibility after updating sleep states without updating configuration,
        // as activities are about to be sent to sleep.
        ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                !PRESERVE_WINDOWS);
    }

    private boolean containsActivityFromStack(List<ActivityRecord> rs) {
        for (ActivityRecord r : rs) {
            if (r.getRootTask() == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Start pausing the currently resumed activity.  It is an error to call this if there
     * is already an activity being paused or there is no resumed activity.
     *
     * @param userLeaving True if this should result in an onUserLeaving to the current activity.
     * @param uiSleeping True if this is happening with the user interface going to sleep (the
     * screen turning off).
     * @param resuming The activity we are currently trying to resume or null if this is not being
     *                 called as part of resuming the top activity, so we shouldn't try to instigate
     *                 a resume here if not null.
     * @return Returns true if an activity now is in the PAUSING state, and we are waiting for
     * it to tell us when it is done.
     */
    final boolean startPausingLocked(boolean userLeaving, boolean uiSleeping,
            ActivityRecord resuming) {
        if (mPausingActivity != null) {
            Slog.wtf(TAG, "Going to pause when pause is already pending for " + mPausingActivity
                    + " state=" + mPausingActivity.getState());
            if (!shouldSleepActivities()) {
                // Avoid recursion among check for sleep and complete pause during sleeping.
                // Because activity will be paused immediately after resume, just let pause
                // be completed by the order of activity paused from clients.
                completePauseLocked(false, resuming);
            }
        }
        ActivityRecord prev = mResumedActivity;

        if (prev == null) {
            if (resuming == null) {
                Slog.wtf(TAG, "Trying to pause when nothing is resumed");
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
            return false;
        }

        if (prev == resuming) {
            Slog.wtf(TAG, "Trying to pause activity that is in process of being resumed");
            return false;
        }

        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSING: " + prev);
        else if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Start pausing: " + prev);
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        mLastNoHistoryActivity = prev.isNoHistory() ? prev : null;
        prev.setState(PAUSING, "startPausingLocked");
        prev.getTask().touchActiveTime();
        clearLaunchTime(prev);

        mAtmService.updateCpuStats();

        boolean pauseImmediately = false;
        if (resuming != null && (resuming.info.flags & FLAG_RESUME_WHILE_PAUSING) != 0) {
            // If the flag RESUME_WHILE_PAUSING is set, then continue to schedule the previous
            // activity to be paused, while at the same time resuming the new resume activity
            // only if the previous activity can't go into Pip since we want to give Pip
            // activities a chance to enter Pip before resuming the next activity.
            final boolean lastResumedCanPip = prev != null && prev.checkEnterPictureInPictureState(
                    "shouldResumeWhilePausing", userLeaving);
            if (!lastResumedCanPip) {
                pauseImmediately = true;
            }
        }

        if (prev.attachedToProcess()) {
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueueing pending pause: " + prev);
            try {
                EventLogTags.writeWmPauseActivity(prev.mUserId, System.identityHashCode(prev),
                        prev.shortComponentName, "userLeaving=" + userLeaving);

                mAtmService.getLifecycleManager().scheduleTransaction(prev.app.getThread(),
                        prev.appToken, PauseActivityItem.obtain(prev.finishing, userLeaving,
                                prev.configChangeFlags, pauseImmediately));
            } catch (Exception e) {
                // Ignore exception, if process died other code will cleanup.
                Slog.w(TAG, "Exception thrown during pause", e);
                mPausingActivity = null;
                mLastPausedActivity = null;
                mLastNoHistoryActivity = null;
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!uiSleeping && !mAtmService.isSleepingOrShuttingDownLocked()) {
            mStackSupervisor.acquireLaunchWakelock();
        }

        if (mPausingActivity != null) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else if (DEBUG_PAUSE) {
                Slog.v(TAG_PAUSE, "Key dispatch not paused for screen off");
            }

            if (pauseImmediately) {
                // If the caller said they don't want to wait for the pause, then complete
                // the pause now.
                completePauseLocked(false, resuming);
                return false;

            } else {
                prev.schedulePauseTimeout();
                return true;
            }

        } else {
            // This activity failed to schedule the
            // pause, so just treat it as being paused now.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Activity not running, resuming next.");
            if (resuming == null) {
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
            return false;
        }
    }

    @VisibleForTesting
    void completePauseLocked(boolean resumeNext, ActivityRecord resuming) {
        ActivityRecord prev = mPausingActivity;
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Complete pause: " + prev);

        if (prev != null) {
            prev.setWillCloseOrEnterPip(false);
            final boolean wasStopping = prev.isState(STOPPING);
            prev.setState(PAUSED, "completePausedLocked");
            if (prev.finishing) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Executing finish of activity: " + prev);
                prev = prev.completeFinishing("completePausedLocked");
            } else if (prev.hasProcess()) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueue pending stop if needed: " + prev
                        + " wasStopping=" + wasStopping
                        + " visibleRequested=" + prev.mVisibleRequested);
                if (prev.deferRelaunchUntilPaused) {
                    // Complete the deferred relaunch that was waiting for pause to complete.
                    if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Re-launching after pause: " + prev);
                    prev.relaunchActivityLocked(prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    // We are also stopping, the stop request must have gone soon after the pause.
                    // We can't clobber it, because the stop confirmation will not be handled.
                    // We don't need to schedule another stop, we only need to let it happen.
                    prev.setState(STOPPING, "completePausedLocked");
                } else if (!prev.mVisibleRequested || shouldSleepOrShutDownActivities()) {
                    // Clear out any deferred client hide we might currently have.
                    prev.setDeferHidingClient(false);
                    // If we were visible then resumeTopActivities will release resources before
                    // stopping.
                    prev.addToStopping(true /* scheduleIdle */, false /* idleDelayed */,
                            "completePauseLocked");
                }
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "App died during pause, not stopping: " + prev);
                prev = null;
            }
            // It is possible the activity was freezing the screen before it was paused.
            // In that case go ahead and remove the freeze this activity has on the screen
            // since it is no longer visible.
            if (prev != null) {
                prev.stopFreezingScreenLocked(true /*force*/);
            }
            mPausingActivity = null;
        }

        if (resumeNext) {
            final Task topStack = mRootWindowContainer.getTopDisplayFocusedStack();
            if (topStack != null && !topStack.shouldSleepOrShutDownActivities()) {
                mRootWindowContainer.resumeFocusedStacksTopActivities(topStack, prev, null);
            } else {
                checkReadyForSleep();
                final ActivityRecord top = topStack != null ? topStack.topRunningActivity() : null;
                if (top == null || (prev != null && top != prev)) {
                    // If there are no more activities available to run, do resume anyway to start
                    // something. Also if the top activity on the stack is not the just paused
                    // activity, we need to go ahead and resume it to ensure we complete an
                    // in-flight app switch.
                    mRootWindowContainer.resumeFocusedStacksTopActivities();
                }
            }
        }

        if (prev != null) {
            prev.resumeKeyDispatchingLocked();

            if (prev.hasProcess() && prev.cpuTimeAtResume > 0) {
                final long diff = prev.app.getCpuTime() - prev.cpuTimeAtResume;
                if (diff > 0) {
                    final Runnable r = PooledLambda.obtainRunnable(
                            ActivityManagerInternal::updateForegroundTimeIfOnBattery,
                            mAtmService.mAmInternal, prev.info.packageName,
                            prev.info.applicationInfo.uid,
                            diff);
                    mAtmService.mH.post(r);
                }
            }
            prev.cpuTimeAtResume = 0; // reset it
        }

        mRootWindowContainer.ensureActivitiesVisible(resuming, 0, !PRESERVE_WINDOWS);

        // Notify when the task stack has changed, but only if visibilities changed (not just
        // focus). Also if there is an active pinned stack - we always want to notify it about
        // task stack changes, because its positioning may depend on it.
        if (mStackSupervisor.mAppVisibilitiesChangedSinceLastPause
                || (getDisplayArea() != null && getDisplayArea().hasPinnedTask())) {
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
            mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }
    }

    boolean isTopStackInDisplayArea() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        return taskDisplayArea != null && taskDisplayArea.isTopStack(this);
    }

    /**
     * @return {@code true} if this is the focused stack on its current display, {@code false}
     * otherwise.
     */
    boolean isFocusedStackOnDisplay() {
        final DisplayContent display = getDisplay();
        return display != null && this == display.getFocusedStack();
    }

    /**
     * Make sure that all activities that need to be visible in the stack (that is, they
     * currently can be seen by the user) actually are and update their configuration.
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     * @param preserveWindows Flag indicating whether windows should be preserved when updating
     *                        configuration in {@link mEnsureActivitiesVisibleHelper}.
     * @param configChanges Parts of the configuration that changed for this activity for evaluating
     *                      if the screen should be frozen as part of
     *                      {@link mEnsureActivitiesVisibleHelper}.
     *
     */
    void ensureActivitiesVisible(@Nullable ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        ensureActivitiesVisible(starting, configChanges, preserveWindows, true /* notifyClients */);
    }

    /**
     * Ensure visibility with an option to also update the configuration of visible activities.
     * @see #ensureActivitiesVisible(ActivityRecord, int, boolean)
     * @see RootWindowContainer#ensureActivitiesVisible(ActivityRecord, int, boolean)
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     * @param notifyClients Flag indicating whether the visibility updates should be sent to the
     *                      clients in {@link mEnsureActivitiesVisibleHelper}.
     * @param preserveWindows Flag indicating whether windows should be preserved when updating
     *                        configuration in {@link mEnsureActivitiesVisibleHelper}.
     * @param configChanges Parts of the configuration that changed for this activity for evaluating
     *                      if the screen should be frozen as part of
     *                      {@link mEnsureActivitiesVisibleHelper}.
     */
    // TODO: Should be re-worked based on the fact that each task as a stack in most cases.
    void ensureActivitiesVisible(@Nullable ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        mTopActivityOccludesKeyguard = false;
        mTopDismissingKeyguardActivity = null;
        mStackSupervisor.beginActivityVisibilityUpdate();
        try {
            mEnsureActivitiesVisibleHelper.process(
                    starting, configChanges, preserveWindows, notifyClients);

            if (mTranslucentActivityWaiting != null &&
                    mUndrawnActivitiesBelowTopTranslucent.isEmpty()) {
                // Nothing is getting drawn or everything was already visible, don't wait for timeout.
                notifyActivityDrawnLocked(null);
            }
        } finally {
            mStackSupervisor.endActivityVisibilityUpdate();
        }
    }

    /**
     * @return true if the top visible activity wants to occlude the Keyguard, false otherwise
     */
    boolean topActivityOccludesKeyguard() {
        return mTopActivityOccludesKeyguard;
    }

    /**
     * Returns true if this stack should be resized to match the bounds specified by
     * {@link ActivityOptions#setLaunchBounds} when launching an activity into the stack.
     */
    boolean shouldResizeStackWithLaunchBounds() {
        return inPinnedWindowingMode();
    }

    // TODO(NOW!)
    /**
     * Returns {@code true} if this is the top-most split-screen-primary or
     * split-screen-secondary stack, {@code false} otherwise.
     */
    boolean isTopSplitScreenStack() {
        return inSplitScreenWindowingMode()
                && this == getDisplayArea().getTopStackInWindowingMode(getWindowingMode());
    }

    /**
     * @return the top most visible activity that wants to dismiss Keyguard
     */
    ActivityRecord getTopDismissingKeyguardActivity() {
        return mTopDismissingKeyguardActivity;
    }

    /**
     * Checks whether {@param r} should be visible depending on Keyguard state and updates
     * {@link #mTopActivityOccludesKeyguard} and {@link #mTopDismissingKeyguardActivity} if
     * necessary.
     *
     * @return true if {@param r} is visible taken Keyguard state into account, false otherwise
     */
    boolean checkKeyguardVisibility(ActivityRecord r, boolean shouldBeVisible, boolean isTop) {
        int displayId = getDisplayId();
        if (displayId == INVALID_DISPLAY) displayId = DEFAULT_DISPLAY;

        final boolean keyguardOrAodShowing = mStackSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        final boolean keyguardLocked = mStackSupervisor.getKeyguardController().isKeyguardLocked();
        final boolean showWhenLocked = r.canShowWhenLocked();
        final boolean dismissKeyguard = r.containsDismissKeyguardWindow();
        if (shouldBeVisible) {
            if (dismissKeyguard && mTopDismissingKeyguardActivity == null) {
                mTopDismissingKeyguardActivity = r;
            }

            // Only the top activity may control occluded, as we can't occlude the Keyguard if the
            // top app doesn't want to occlude it.
            if (isTop) {
                mTopActivityOccludesKeyguard |= showWhenLocked;
            }

            final boolean canShowWithKeyguard = canShowWithInsecureKeyguard()
                    && mStackSupervisor.getKeyguardController().canDismissKeyguard();
            if (canShowWithKeyguard) {
                return true;
            }
        }
        if (keyguardOrAodShowing) {
            // If keyguard is showing, nothing is visible, except if we are able to dismiss Keyguard
            // right away and AOD isn't visible.
            return shouldBeVisible && mStackSupervisor.getKeyguardController()
                    .canShowActivityWhileKeyguardShowing(r, dismissKeyguard);
        } else if (keyguardLocked) {
            return shouldBeVisible && mStackSupervisor.getKeyguardController().canShowWhileOccluded(
                    dismissKeyguard, showWhenLocked);
        } else {
            return shouldBeVisible;
        }
    }

    /**
     * Check if the display to which this stack is attached has
     * {@link Display#FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD} applied.
     */
    boolean canShowWithInsecureKeyguard() {
        final DisplayContent displayContent = getDisplay();
        if (displayContent == null) {
            throw new IllegalStateException("Stack is not attached to any display, stackId="
                    + getRootTaskId());
        }

        final int flags = displayContent.mDisplay.getFlags();
        return (flags & FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0;
    }

    void checkTranslucentActivityWaiting(ActivityRecord top) {
        if (mTranslucentActivityWaiting != top) {
            mUndrawnActivitiesBelowTopTranslucent.clear();
            if (mTranslucentActivityWaiting != null) {
                // Call the callback with a timeout indication.
                notifyActivityDrawnLocked(null);
                mTranslucentActivityWaiting = null;
            }
            mHandler.removeMessages(TRANSLUCENT_TIMEOUT_MSG);
        }
    }

    void convertActivityToTranslucent(ActivityRecord r) {
        mTranslucentActivityWaiting = r;
        mUndrawnActivitiesBelowTopTranslucent.clear();
        mHandler.sendEmptyMessageDelayed(TRANSLUCENT_TIMEOUT_MSG, TRANSLUCENT_CONVERSION_TIMEOUT);
    }

    /**
     * Called as activities below the top translucent activity are redrawn. When the last one is
     * redrawn notify the top activity by calling
     * {@link Activity#onTranslucentConversionComplete}.
     *
     * @param r The most recent background activity to be drawn. Or, if r is null then a timeout
     * occurred and the activity will be notified immediately.
     */
    void notifyActivityDrawnLocked(ActivityRecord r) {
        if ((r == null)
                || (mUndrawnActivitiesBelowTopTranslucent.remove(r) &&
                mUndrawnActivitiesBelowTopTranslucent.isEmpty())) {
            // The last undrawn activity below the top has just been drawn. If there is an
            // opaque activity at the top, notify it that it can become translucent safely now.
            final ActivityRecord waitingActivity = mTranslucentActivityWaiting;
            mTranslucentActivityWaiting = null;
            mUndrawnActivitiesBelowTopTranslucent.clear();
            mHandler.removeMessages(TRANSLUCENT_TIMEOUT_MSG);

            if (waitingActivity != null) {
                mWmService.setWindowOpaqueLocked(waitingActivity.appToken, false);
                if (waitingActivity.attachedToProcess()) {
                    try {
                        waitingActivity.app.getThread().scheduleTranslucentConversionComplete(
                                waitingActivity.appToken, r != null);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    /** @see ActivityRecord#cancelInitializing() */
    void cancelInitializingActivities() {
        // We don't want to clear starting window for activities that aren't behind fullscreen
        // activities as we need to display their starting window until they are done initializing.
        checkBehindFullscreenActivity(null /* toCheck */, ActivityRecord::cancelInitializing);
    }

    /**
     * If an activity {@param toCheck} is given, this method returns {@code true} if the activity
     * is occluded by any fullscreen activity. If there is no {@param toCheck} and the handling
     * function {@param handleBehindFullscreenActivity} is given, this method will pass all occluded
     * activities to the function.
     */
    boolean checkBehindFullscreenActivity(ActivityRecord toCheck,
            Consumer<ActivityRecord> handleBehindFullscreenActivity) {
        return mCheckBehindFullscreenActivityHelper.process(
                toCheck, handleBehindFullscreenActivity);
    }

    /**
     * Ensure that the top activity in the stack is resumed.
     *
     * @param prev The previously resumed activity, for when in the process
     * of pausing; can be null to call from elsewhere.
     * @param options Activity options.
     *
     * @return Returns true if something is being resumed, or false if
     * nothing happened.
     *
     * NOTE: It is not safe to call this method directly as it can cause an activity in a
     *       non-focused stack to be resumed.
     *       Use {@link RootWindowContainer#resumeFocusedStacksTopActivities} to resume the
     *       right activity for the current system state.
     */
    @GuardedBy("mService")
    boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        if (mInResumeTopActivity) {
            // Don't even start recursing.
            return false;
        }

        boolean result = false;
        try {
            // Protect against recursion.
            mInResumeTopActivity = true;
            result = resumeTopActivityInnerLocked(prev, options);

            // When resuming the top activity, it may be necessary to pause the top activity (for
            // example, returning to the lock screen. We suppress the normal pause logic in
            // {@link #resumeTopActivityUncheckedLocked}, since the top activity is resumed at the
            // end. We call the {@link ActivityStackSupervisor#checkReadyForSleepLocked} again here
            // to ensure any necessary pause logic occurs. In the case where the Activity will be
            // shown regardless of the lock screen, the call to
            // {@link ActivityStackSupervisor#checkReadyForSleepLocked} is skipped.
            final ActivityRecord next = topRunningActivity(true /* focusableOnly */);
            if (next == null || !next.canTurnScreenOn()) {
                checkReadyForSleep();
            }
        } finally {
            mInResumeTopActivity = false;
        }

        return result;
    }

    @GuardedBy("mService")
    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options) {
        if (!mAtmService.isBooting() && !mAtmService.isBooted()) {
            // Not ready yet!
            return false;
        }

        // Find the next top-most activity to resume in this stack that is not finishing and is
        // focusable. If it is not focusable, we will fall into the case below to resume the
        // top activity in the next focusable task.
        ActivityRecord next = topRunningActivity(true /* focusableOnly */);

        final boolean hasRunningActivity = next != null;

        // TODO: Maybe this entire condition can get removed?
        if (hasRunningActivity && !isAttached()) {
            return false;
        }

        mRootWindowContainer.cancelInitializingActivities();

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        boolean userLeaving = mStackSupervisor.mUserLeaving;
        mStackSupervisor.mUserLeaving = false;

        if (!hasRunningActivity) {
            // There are no activities left in the stack, let's look somewhere else.
            return resumeNextFocusableActivityWhenStackIsEmpty(prev, options);
        }

        next.delayedResume = false;
        final TaskDisplayArea taskDisplayArea = getDisplayArea();

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed " + next);
            return false;
        }

        if (!next.canResumeByCompat()) {
            return false;
        }

        // If we are currently pausing an activity, then don't do anything until that is done.
        final boolean allPausedComplete = mRootWindowContainer.allPausedActivitiesComplete();
        if (!allPausedComplete) {
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) {
                Slog.v(TAG_PAUSE, "resumeTopActivityLocked: Skip resume: some activity pausing.");
            }
            return false;
        }

        // If we are sleeping, and there is no resumed activity, and the top activity is paused,
        // well that is the state we want.
        if (shouldSleepOrShutDownActivities()
                && mLastPausedActivity == next
                && mRootWindowContainer.allPausedActivitiesComplete()) {
            // If the current top activity may be able to occlude keyguard but the occluded state
            // has not been set, update visibility and check again if we should continue to resume.
            boolean nothingToResume = true;
            if (!mAtmService.mShuttingDown) {
                final boolean canShowWhenLocked = !mTopActivityOccludesKeyguard
                        && next.canShowWhenLocked();
                final boolean mayDismissKeyguard = mTopDismissingKeyguardActivity != next
                        && next.containsDismissKeyguardWindow();

                if (canShowWhenLocked || mayDismissKeyguard) {
                    ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                            !PRESERVE_WINDOWS);
                    nothingToResume = shouldSleepActivities();
                } else if (next.currentLaunchCanTurnScreenOn() && next.canTurnScreenOn()) {
                    nothingToResume = false;
                }
            }
            if (nothingToResume) {
                // Make sure we have executed any pending transitions, since there
                // should be nothing left to do at this point.
                executeAppTransition(options);
                if (DEBUG_STATES) Slog.d(TAG_STATES,
                        "resumeTopActivityLocked: Going to sleep and all paused");
                return false;
            }
        }

        // Make sure that the user who owns this activity is started.  If not,
        // we will just leave it as is because someone should be bringing
        // another user's activities to the top of the stack.
        if (!mAtmService.mAmInternal.hasStartedUserState(next.mUserId)) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.mUserId + " is stopped");
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStackSupervisor.mStoppingActivities.remove(next);
        next.setSleeping(false);

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything until that is done.
        if (!mRootWindowContainer.allPausedActivitiesComplete()) {
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                    "resumeTopActivityLocked: Skip resume: some activity pausing.");

            return false;
        }

        mStackSupervisor.setLaunchSource(next.info.applicationInfo.uid);

        ActivityRecord lastResumed = null;
        final Task lastFocusedStack = taskDisplayArea.getLastFocusedStack();
        if (lastFocusedStack != null && lastFocusedStack != this) {
            // So, why aren't we using prev here??? See the param comment on the method. prev
            // doesn't represent the last resumed activity. However, the last focus stack does if
            // it isn't null.
            lastResumed = lastFocusedStack.mResumedActivity;
            if (userLeaving && inMultiWindowMode() && lastFocusedStack.shouldBeVisible(next)) {
                // The user isn't leaving if this stack is the multi-window mode and the last
                // focused stack should still be visible.
                if(DEBUG_USER_LEAVING) Slog.i(TAG_USER_LEAVING, "Overriding userLeaving to false"
                        + " next=" + next + " lastResumed=" + lastResumed);
                userLeaving = false;
            }
        }

        boolean pausing = taskDisplayArea.pauseBackStacks(userLeaving, next);
        if (mResumedActivity != null) {
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Pausing " + mResumedActivity);
            pausing |= startPausingLocked(userLeaving, false /* uiSleeping */, next);
        }
        if (pausing) {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.v(TAG_STATES,
                    "resumeTopActivityLocked: Skip resume: need to start pausing");
            // At this point we want to put the upcoming activity's process
            // at the top of the LRU list, since we know we will be needing it
            // very soon and it would be a waste to let it get killed if it
            // happens to be sitting towards the end.
            if (next.attachedToProcess()) {
                next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                        true /* activityChange */, false /* updateOomAdj */,
                        false /* addPendingTopUid */);
            } else if (!next.isProcessRunning()) {
                // Since the start-process is asynchronous, if we already know the process of next
                // activity isn't running, we can start the process earlier to save the time to wait
                // for the current activity to be paused.
                final boolean isTop = this == taskDisplayArea.getFocusedStack();
                mAtmService.startProcessAsync(next, false /* knownToBeDead */, isTop,
                        isTop ? "pre-top-activity" : "pre-activity");
            }
            if (lastResumed != null) {
                lastResumed.setWillCloseOrEnterPip(true);
            }
            return true;
        } else if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // It is possible for the activity to be resumed when we paused back stacks above if the
            // next activity doesn't have to wait for pause to complete.
            // So, nothing else to-do except:
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed (dontWaitForPause) " + next);
            return true;
        }

        // If the most recent activity was noHistory but was only stopped rather
        // than stopped+finished because the device went to sleep, we need to make
        // sure to finish it as we're making a new activity topmost.
        if (shouldSleepActivities() && mLastNoHistoryActivity != null &&
                !mLastNoHistoryActivity.finishing) {
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "no-history finish of " + mLastNoHistoryActivity + " on new resume");
            mLastNoHistoryActivity.finishIfPossible("resume-no-history", false /* oomAdj */);
            mLastNoHistoryActivity = null;
        }

        if (prev != null && prev != next && next.nowVisible) {

            // The next activity is already visible, so hide the previous
            // activity's windows right now so we can show the new one ASAP.
            // We only do this if the previous is finishing, which should mean
            // it is on top of the one being resumed so hiding it quickly
            // is good.  Otherwise, we want to do the normal route of allowing
            // the resumed activity to be shown so we can decide if the
            // previous should actually be hidden depending on whether the
            // new one is found to be full-screen or not.
            if (prev.finishing) {
                prev.setVisibility(false);
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Not waiting for visible to hide: " + prev
                                + ", nowVisible=" + next.nowVisible);
            } else {
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Previous already visible but still waiting to hide: " + prev
                                + ", nowVisible=" + next.nowVisible);
            }

        }

        // Launching this app's activity, make sure the app is no longer
        // considered stopped.
        try {
            mAtmService.getPackageManager().setPackageStoppedState(
                    next.packageName, false, next.mUserId); /* TODO: Verify if correct userid */
        } catch (RemoteException e1) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + next.packageName + ": " + e);
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        boolean anim = true;
        final DisplayContent dc = taskDisplayArea.mDisplayContent;
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare close transition: prev=" + prev);
                if (mStackSupervisor.mNoAnimActivities.contains(prev)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    dc.prepareAppTransition(
                            prev.getTask() == next.getTask() ? TRANSIT_ACTIVITY_CLOSE
                                    : TRANSIT_TASK_CLOSE, false);
                }
                prev.setVisibility(false);
            } else {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare open transition: prev=" + prev);
                if (mStackSupervisor.mNoAnimActivities.contains(next)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    dc.prepareAppTransition(
                            prev.getTask() == next.getTask() ? TRANSIT_ACTIVITY_OPEN
                                    : next.mLaunchTaskBehind ? TRANSIT_TASK_OPEN_BEHIND
                                            : TRANSIT_TASK_OPEN, false);
                }
            }
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare open transition: no previous");
            if (mStackSupervisor.mNoAnimActivities.contains(next)) {
                anim = false;
                dc.prepareAppTransition(TRANSIT_NONE, false);
            } else {
                dc.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false);
            }
        }

        if (anim) {
            next.applyOptionsLocked();
        } else {
            next.clearOptionsLocked();
        }

        mStackSupervisor.mNoAnimActivities.clear();

        if (next.attachedToProcess()) {
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resume running: " + next
                    + " stopped=" + next.stopped
                    + " visibleRequested=" + next.mVisibleRequested);

            // If the previous activity is translucent, force a visibility update of
            // the next activity, so that it's added to WM's opening app list, and
            // transition animation can be set up properly.
            // For example, pressing Home button with a translucent activity in focus.
            // Launcher is already visible in this case. If we don't add it to opening
            // apps, maybeUpdateTransitToWallpaper() will fail to identify this as a
            // TRANSIT_WALLPAPER_OPEN animation, and run some funny animation.
            final boolean lastActivityTranslucent = lastFocusedStack != null
                    && (lastFocusedStack.inMultiWindowMode()
                    || (lastFocusedStack.mLastPausedActivity != null
                    && !lastFocusedStack.mLastPausedActivity.occludesParent()));

            // This activity is now becoming visible.
            if (!next.mVisibleRequested || next.stopped || lastActivityTranslucent) {
                next.setVisibility(true);
            }

            // schedule launch ticks to collect information about slow apps.
            next.startLaunchTickingLocked();

            ActivityRecord lastResumedActivity =
                    lastFocusedStack == null ? null : lastFocusedStack.mResumedActivity;
            final ActivityState lastState = next.getState();

            mAtmService.updateCpuStats();

            if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + next
                    + " (in existing)");

            next.setState(RESUMED, "resumeTopActivityInnerLocked");

            next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                    true /* activityChange */, true /* updateOomAdj */,
                    true /* addPendingTopUid */);

            // Have the window manager re-evaluate the orientation of
            // the screen based on the new activity order.
            boolean notUpdated = true;

            // Activity should also be visible if set mLaunchTaskBehind to true (see
            // ActivityRecord#shouldBeVisibleIgnoringKeyguard()).
            if (shouldBeVisible(next)) {
                // We have special rotation behavior when here is some active activity that
                // requests specific orientation or Keyguard is locked. Make sure all activity
                // visibilities are set correctly as well as the transition is updated if needed
                // to get the correct rotation behavior. Otherwise the following call to update
                // the orientation may cause incorrect configurations delivered to client as a
                // result of invisible window resize.
                // TODO: Remove this once visibilities are set correctly immediately when
                // starting an activity.
                notUpdated = !mRootWindowContainer.ensureVisibilityAndConfig(next, getDisplayId(),
                        true /* markFrozenIfConfigChanged */, false /* deferResume */);
            }

            if (notUpdated) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                ActivityRecord nextNext = topRunningActivity();
                if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_STATES,
                        "Activity config changed during resume: " + next
                                + ", new next: " + nextNext);
                if (nextNext != next) {
                    // Do over!
                    mStackSupervisor.scheduleResumeTopActivities();
                }
                if (!next.mVisibleRequested || next.stopped) {
                    next.setVisibility(true);
                }
                next.completeResumeLocked();
                return true;
            }

            try {
                final ClientTransaction transaction =
                        ClientTransaction.obtain(next.app.getThread(), next.appToken);
                // Deliver all pending results.
                ArrayList<ResultInfo> a = next.results;
                if (a != null) {
                    final int N = a.size();
                    if (!next.finishing && N > 0) {
                        if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                                "Delivering results to " + next + ": " + a);
                        transaction.addCallback(ActivityResultItem.obtain(a));
                    }
                }

                if (next.newIntents != null) {
                    transaction.addCallback(
                            NewIntentItem.obtain(next.newIntents, true /* resume */));
                }

                // Well the app will no longer be stopped.
                // Clear app token stopped state in window manager if needed.
                next.notifyAppResumed(next.stopped);

                EventLogTags.writeWmResumeActivity(next.mUserId, System.identityHashCode(next),
                        next.getTask().mTaskId, next.shortComponentName);

                next.setSleeping(false);
                mAtmService.getAppWarningsLocked().onResumeActivity(next);
                next.app.setPendingUiCleanAndForceProcessStateUpTo(mAtmService.mTopProcessState);
                next.clearOptionsLocked();
                transaction.setLifecycleStateRequest(
                        ResumeActivityItem.obtain(next.app.getReportedProcState(),
                                dc.isNextTransitionForward()));
                mAtmService.getLifecycleManager().scheduleTransaction(transaction);

                if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Resumed "
                        + next);
            } catch (Exception e) {
                // Whoops, need to restart this activity!
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Resume failed; resetting state to "
                        + lastState + ": " + next);
                next.setState(lastState, "resumeTopActivityInnerLocked");

                // lastResumedActivity being non-null implies there is a lastStack present.
                if (lastResumedActivity != null) {
                    lastResumedActivity.setState(RESUMED, "resumeTopActivityInnerLocked");
                }

                Slog.i(TAG, "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else  if (SHOW_APP_STARTING_PREVIEW && lastFocusedStack != null
                        && lastFocusedStack.isTopStackInDisplayArea()) {
                    next.showStartingWindow(null /* prev */, false /* newTask */,
                            false /* taskSwitch */);
                }
                mStackSupervisor.startSpecificActivity(next, true, false);
                return true;
            }

            // From this point on, if something goes wrong there is no way
            // to recover the activity.
            try {
                next.completeResumeLocked();
            } catch (Exception e) {
                // If any exception gets thrown, toss away this
                // activity and try the next one.
                Slog.w(TAG, "Exception thrown during resume of " + next, e);
                next.finishIfPossible("resume-exception", true /* oomAdj */);
                return true;
            }
        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_PREVIEW) {
                    next.showStartingWindow(null /* prev */, false /* newTask */,
                            false /* taskSwich */);
                }
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Restarting: " + next);
            }
            if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Restarting " + next);
            mStackSupervisor.startSpecificActivity(next, true, true);
        }

        return true;
    }

    /**
     * Resume the next eligible activity in a focusable stack when this one does not have any
     * running activities left. The focus will be adjusted to the next focusable stack and
     * top running activities will be resumed in all focusable stacks. However, if the current stack
     * is a home stack - we have to keep it focused, start and resume a home activity on the current
     * display instead to make sure that the display is not empty.
     */
    private boolean resumeNextFocusableActivityWhenStackIsEmpty(ActivityRecord prev,
            ActivityOptions options) {
        final String reason = "noMoreActivities";

        if (!isActivityTypeHome()) {
            final Task nextFocusedStack = adjustFocusToNextFocusableTask(reason);
            if (nextFocusedStack != null) {
                // Try to move focus to the next visible stack with a running activity if this
                // stack is not covering the entire screen or is on a secondary display with no home
                // stack.
                return mRootWindowContainer.resumeFocusedStacksTopActivities(nextFocusedStack,
                        prev, null /* targetOptions */);
            }
        }

        // If the current stack is a home stack, or if focus didn't switch to a different stack -
        // just start up the Launcher...
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeNextFocusableActivityWhenStackIsEmpty: " + reason + ", go home");
        return mRootWindowContainer.resumeHomeActivity(prev, reason, getDisplayArea());
    }

    void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity,
            boolean newTask, boolean keepCurTransition, ActivityOptions options) {
        Task rTask = r.getTask();
        final boolean allowMoveToFront = options == null || !options.getAvoidMoveToFront();
        final boolean isOrhasTask = rTask == this || hasChild(rTask);
        // mLaunchTaskBehind tasks get placed at the back of the task stack.
        if (!r.mLaunchTaskBehind && allowMoveToFront && (!isOrhasTask || newTask)) {
            // Last activity in task had been removed or ActivityManagerService is reusing task.
            // Insert or replace.
            // Might not even be in.
            positionChildAtTop(rTask);
        }
        Task task = null;
        if (!newTask && isOrhasTask) {
            // Starting activity cannot be occluding activity, otherwise starting window could be
            // remove immediately without transferring to starting activity.
            final ActivityRecord occludingActivity = getOccludingActivityAbove(r);
            if (occludingActivity != null) {
                // Here it is!  Now, if this is not yet visible (occluded by another task) to the
                // user, then just add it without starting; it will get started when the user
                // navigates back to it.
                if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to task " + task,
                        new RuntimeException("here").fillInStackTrace());
                rTask.positionChildAtTop(r);
                ActivityOptions.abort(options);
                return;
            }
        }

        // Place a new activity at top of stack, so it is next to interact with the user.

        // If we are not placing the new activity frontmost, we do not want to deliver the
        // onUserLeaving callback to the actual frontmost activity
        final Task activityTask = r.getTask();
        if (task == activityTask && mChildren.indexOf(task) != (getChildCount() - 1)) {
            mStackSupervisor.mUserLeaving = false;
            if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                    "startActivity() behind front, mUserLeaving=false");
        }

        task = activityTask;

        // Slot the activity into the history stack and proceed
        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to stack to task " + task,
                new RuntimeException("here").fillInStackTrace());
        task.positionChildAtTop(r);

        // The transition animation and starting window are not needed if {@code allowMoveToFront}
        // is false, because the activity won't be visible.
        if ((!isHomeOrRecentsStack() || hasActivity()) && allowMoveToFront) {
            final DisplayContent dc = getDisplay().mDisplayContent;
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                    "Prepare open transition: starting " + r);
            if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                dc.prepareAppTransition(TRANSIT_NONE, keepCurTransition);
                mStackSupervisor.mNoAnimActivities.add(r);
            } else {
                int transit = TRANSIT_ACTIVITY_OPEN;
                if (newTask) {
                    if (r.mLaunchTaskBehind) {
                        transit = TRANSIT_TASK_OPEN_BEHIND;
                    } else if (getDisplay().isSingleTaskInstance()) {
                        // If a new task is being launched in a single task display, we don't need
                        // to play normal animation, but need to trigger a callback when an app
                        // transition is actually handled. So ignore already prepared activity, and
                        // override it.
                        transit = TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
                        keepCurTransition = false;
                    } else {
                        // If a new task is being launched, then mark the existing top activity as
                        // supporting picture-in-picture while pausing only if the starting activity
                        // would not be considered an overlay on top of the current activity
                        // (eg. not fullscreen, or the assistant)
                        if (canEnterPipOnTaskSwitch(focusedTopActivity,
                                null /* toFrontTask */, r, options)) {
                            focusedTopActivity.supportsEnterPipOnTaskSwitch = true;
                        }
                        transit = TRANSIT_TASK_OPEN;
                    }
                }
                dc.prepareAppTransition(transit, keepCurTransition);
                mStackSupervisor.mNoAnimActivities.remove(r);
            }
            boolean doShow = true;
            if (newTask) {
                // Even though this activity is starting fresh, we still need
                // to reset it to make sure we apply affinities to move any
                // existing activities from other tasks in to it.
                // If the caller has requested that the target task be
                // reset, then do so.
                if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                    resetTaskIfNeeded(r, r);
                    doShow = topRunningNonDelayedActivityLocked(null) == r;
                }
            } else if (options != null && options.getAnimationType()
                    == ActivityOptions.ANIM_SCENE_TRANSITION) {
                doShow = false;
            }
            if (r.mLaunchTaskBehind) {
                // Don't do a starting window for mLaunchTaskBehind. More importantly make sure we
                // tell WindowManager that r is visible even though it is at the back of the stack.
                r.setVisibility(true);
                ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
                // Go ahead to execute app transition for this activity since the app transition
                // will not be triggered through the resume channel.
                getDisplay().mDisplayContent.executeAppTransition();
            } else if (SHOW_APP_STARTING_PREVIEW && doShow) {
                // Figure out if we are transitioning from another activity that is
                // "has the same starting icon" as the next one.  This allows the
                // window manager to keep the previous window it had previously
                // created, if it still had one.
                Task prevTask = r.getTask();
                ActivityRecord prev = prevTask.topActivityWithStartingWindow();
                if (prev != null) {
                    // We don't want to reuse the previous starting preview if:
                    // (1) The current activity is in a different task.
                    if (prev.getTask() != prevTask) {
                        prev = null;
                    }
                    // (2) The current activity is already displayed.
                    else if (prev.nowVisible) {
                        prev = null;
                    }
                }
                r.showStartingWindow(prev, newTask, isTaskSwitch(r, focusedTopActivity));
            }
        } else {
            // If this is the first activity, don't do any fancy animations,
            // because there is nothing for it to animate on top of.
            ActivityOptions.abort(options);
        }
    }

    /**
     * @return Whether the switch to another task can trigger the currently running activity to
     * enter PiP while it is pausing (if supported). Only one of {@param toFrontTask} or
     * {@param toFrontActivity} should be set.
     */
    private boolean canEnterPipOnTaskSwitch(ActivityRecord pipCandidate,
            Task toFrontTask, ActivityRecord toFrontActivity, ActivityOptions opts) {
        if (opts != null && opts.disallowEnterPictureInPictureWhileLaunching()) {
            // Ensure the caller has requested not to trigger auto-enter PiP
            return false;
        }
        if (pipCandidate == null || pipCandidate.inPinnedWindowingMode()) {
            // Ensure that we do not trigger entering PiP an activity on the pinned stack
            return false;
        }
        final Task targetStack = toFrontTask != null
                ? toFrontTask.getRootTask() : toFrontActivity.getRootTask();
        if (targetStack != null && targetStack.isActivityTypeAssistant()) {
            // Ensure the task/activity being brought forward is not the assistant
            return false;
        }
        return true;
    }

    private boolean isTaskSwitch(ActivityRecord r, ActivityRecord topFocusedActivity) {
        return topFocusedActivity != null && r.getTask() != topFocusedActivity.getTask();
    }

    /**
     * Reset the task by reparenting the activities that have same affinity to the task or
     * reparenting the activities that have different affinityies out of the task, while these
     * activities allow task reparenting.
     *
     * @param taskTop     Top activity of the task might be reset.
     * @param newActivity The activity that going to be started.
     * @return The non-finishing top activity of the task after reset or the original task top
     *         activity if all activities within the task are finishing.
     */
    ActivityRecord resetTaskIfNeeded(ActivityRecord taskTop, ActivityRecord newActivity) {
        final boolean forceReset =
                (newActivity.info.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0;
        final Task task = taskTop.getTask();

        // If ActivityOptions are moved out and need to be aborted or moved to taskTop.
        final ActivityOptions topOptions = sResetTargetTaskHelper.process(task, forceReset);

        if (mChildren.contains(task)) {
            final ActivityRecord newTop = task.getTopNonFinishingActivity();
            if (newTop != null) {
                taskTop = newTop;
            }
        }

        if (topOptions != null) {
            // If we got some ActivityOptions from an activity on top that
            // was removed from the task, propagate them to the new real top.
            taskTop.updateOptionsLocked(topOptions);
        }

        return taskTop;
    }

    /**
     * Finish the topmost activity that belongs to the crashed app. We may also finish the activity
     * that requested launch of the crashed one to prevent launch-crash loop.
     * @param app The app that crashed.
     * @param reason Reason to perform this action.
     * @return The task that was finished in this stack, {@code null} if top running activity does
     *         not belong to the crashed app.
     */
    final Task finishTopCrashedActivityLocked(WindowProcessController app, String reason) {
        final ActivityRecord r = topRunningActivity();
        if (r == null || r.app != app) {
            return null;
        }
        if (r.isActivityTypeHome() && mAtmService.mHomeProcess == app) {
            // Home activities should not be force-finished as we have nothing else to go
            // back to. AppErrors will get to it after two crashes in MIN_CRASH_INTERVAL.
            Slog.w(TAG, "  Not force finishing home activity "
                    + r.intent.getComponent().flattenToShortString());
            return null;
        }
        Slog.w(TAG, "  Force finishing activity "
                + r.intent.getComponent().flattenToShortString());
        Task finishedTask = r.getTask();
        getDisplay().mDisplayContent.prepareAppTransition(
                TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
        r.finishIfPossible(reason, false /* oomAdj */);

        // Also terminate any activities below it that aren't yet stopped, to avoid a situation
        // where one will get re-start our crashing activity once it gets resumed again.
        final ActivityRecord activityBelow = getActivityBelow(r);
        if (activityBelow != null) {
            if (activityBelow.isState(STARTED, RESUMED, PAUSING, PAUSED)) {
                if (!activityBelow.isActivityTypeHome()
                        || mAtmService.mHomeProcess != activityBelow.app) {
                    Slog.w(TAG, "  Force finishing activity "
                            + activityBelow.intent.getComponent().flattenToShortString());
                    activityBelow.finishIfPossible(reason, false /* oomAdj */);
                }
            }
        }

        return finishedTask;
    }

    void finishVoiceTask(IVoiceInteractionSession session) {
        final PooledConsumer c = PooledLambda.obtainConsumer(Task::finishIfVoiceTask,
                PooledLambda.__(Task.class), session.asBinder());
        forAllLeafTasks(c, true /* traverseTopToBottom */);
        c.recycle();
    }

    private static void finishIfVoiceTask(Task tr, IBinder binder) {
        if (tr.voiceSession != null && tr.voiceSession.asBinder() == binder) {
            tr.forAllActivities((r) -> {
                if (r.finishing) return;
                r.finishIfPossible("finish-voice", false /* oomAdj */);
                tr.mAtmService.updateOomAdj();
            });
        } else {
            // Check if any of the activities are using voice
            final PooledFunction f = PooledLambda.obtainFunction(
                    Task::finishIfVoiceActivity, PooledLambda.__(ActivityRecord.class),
                    binder);
            tr.forAllActivities(f);
            f.recycle();
        }
    }

    private static boolean finishIfVoiceActivity(ActivityRecord r, IBinder binder) {
        if (r.voiceSession == null || r.voiceSession.asBinder() != binder) return false;
        // Inform of cancellation
        r.clearVoiceSessionLocked();
        try {
            r.app.getThread().scheduleLocalVoiceInteractionStarted(r.appToken, null);
        } catch (RemoteException re) {
            // Ok Boomer...
        }
        r.mAtmService.finishRunningVoiceLocked();
        return true;
    }

    /** Finish all activities in the stack without waiting. */
    void finishAllActivitiesImmediately() {
        if (!hasChild()) {
            removeIfPossible();
            return;
        }
        forAllActivities((r) -> {
            Slog.d(TAG, "finishAllActivitiesImmediatelyLocked: finishing " + r);
            r.destroyIfPossible("finishAllActivitiesImmediately");
        });
    }

    /** @return true if the stack behind this one is a standard activity type. */
    private boolean inFrontOfStandardStack() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            return false;
        }
        final int index = taskDisplayArea.getIndexOf(this);
        if (index == 0) {
            return false;
        }
        final Task stackBehind = taskDisplayArea.getChildAt(index - 1);
        return stackBehind.isActivityTypeStandard();
    }

    boolean shouldUpRecreateTaskLocked(ActivityRecord srec, String destAffinity) {
        // Basic case: for simple app-centric recents, we need to recreate
        // the task if the affinity has changed.

        final String affinity = ActivityRecord.getTaskAffinityWithUid(destAffinity, srec.getUid());
        if (srec == null || srec.getTask().affinity == null
                || !srec.getTask().affinity.equals(affinity)) {
            return true;
        }
        // Document-centric case: an app may be split in to multiple documents;
        // they need to re-create their task if this current activity is the root
        // of a document, unless simply finishing it will return them to the
        // correct app behind.
        final Task task = srec.getTask();
        if (srec.isRootOfTask() && task.getBaseIntent() != null
                && task.getBaseIntent().isDocument()) {
            // Okay, this activity is at the root of its task.  What to do, what to do...
            if (!inFrontOfStandardStack()) {
                // Finishing won't return to an application, so we need to recreate.
                return true;
            }
            // We now need to get the task below it to determine what to do.
            final Task prevTask = getTaskBelow(task);
            if (prevTask == null) {
                Slog.w(TAG, "shouldUpRecreateTask: task not in history for " + srec);
                return false;
            }
            if (!task.affinity.equals(prevTask.affinity)) {
                // These are different apps, so need to recreate.
                return true;
            }
        }
        return false;
    }

    boolean navigateUpTo(ActivityRecord srec, Intent destIntent, NeededUriGrants destGrants,
            int resultCode, Intent resultData, NeededUriGrants resultGrants) {
        if (!srec.attachedToProcess()) {
            // Nothing to do if the caller is not attached, because this method should be called
            // from an alive activity.
            return false;
        }
        final Task task = srec.getTask();
        if (!srec.isDescendantOf(this)) {
            return false;
        }

        ActivityRecord parent = task.getActivityBelow(srec);
        boolean foundParentInTask = false;
        final ComponentName dest = destIntent.getComponent();
        if (task.getBottomMostActivity() != srec && dest != null) {
            final ActivityRecord candidate = task.getActivity(
                    (ar) -> ar.info.packageName.equals(dest.getPackageName())
                            && ar.info.name.equals(dest.getClassName()), srec,
                    false /*includeBoundary*/, true /*traverseTopToBottom*/);
            if (candidate != null) {
                parent = candidate;
                foundParentInTask = true;
            }
        }

        // TODO: There is a dup. of this block of code in ActivityTaskManagerService.finishActivity
        // We should consolidate.
        IActivityController controller = mAtmService.mController;
        if (controller != null) {
            ActivityRecord next = topRunningActivity(srec.appToken, INVALID_TASK_ID);
            if (next != null) {
                // ask watcher if this is allowed
                boolean resumeOK = true;
                try {
                    resumeOK = controller.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mAtmService.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }

                if (!resumeOK) {
                    return false;
                }
            }
        }
        final long origId = Binder.clearCallingIdentity();

        final int[] resultCodeHolder = new int[1];
        resultCodeHolder[0] = resultCode;
        final Intent[] resultDataHolder = new Intent[1];
        resultDataHolder[0] = resultData;
        final NeededUriGrants[] resultGrantsHolder = new NeededUriGrants[1];
        resultGrantsHolder[0] = resultGrants;
        final ActivityRecord finalParent = parent;
        task.forAllActivities((ar) -> {
            if (ar == finalParent) return true;

            ar.finishIfPossible(resultCodeHolder[0], resultDataHolder[0], resultGrantsHolder[0],
                    "navigate-up", true /* oomAdj */);
            // Only return the supplied result for the first activity finished
            resultCodeHolder[0] = Activity.RESULT_CANCELED;
            resultDataHolder[0] = null;
            return false;
        }, srec, true, true);
        resultCode = resultCodeHolder[0];
        resultData = resultDataHolder[0];

        if (parent != null && foundParentInTask) {
            final int callingUid = srec.info.applicationInfo.uid;
            final int parentLaunchMode = parent.info.launchMode;
            final int destIntentFlags = destIntent.getFlags();
            if (parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TOP ||
                    (destIntentFlags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                parent.deliverNewIntentLocked(callingUid, destIntent, destGrants, srec.packageName);
            } else {
                try {
                    ActivityInfo aInfo = AppGlobals.getPackageManager().getActivityInfo(
                            destIntent.getComponent(), ActivityManagerService.STOCK_PM_FLAGS,
                            srec.mUserId);
                    // TODO(b/64750076): Check if calling pid should really be -1.
                    final int res = mAtmService.getActivityStartController()
                            .obtainStarter(destIntent, "navigateUpTo")
                            .setCaller(srec.app.getThread())
                            .setActivityInfo(aInfo)
                            .setResultTo(parent.appToken)
                            .setCallingPid(-1)
                            .setCallingUid(callingUid)
                            .setCallingPackage(srec.packageName)
                            .setCallingFeatureId(parent.launchedFromFeatureId)
                            .setRealCallingPid(-1)
                            .setRealCallingUid(callingUid)
                            .setComponentSpecified(true)
                            .execute();
                    foundParentInTask = res == ActivityManager.START_SUCCESS;
                } catch (RemoteException e) {
                    foundParentInTask = false;
                }
                parent.finishIfPossible(resultCode, resultData, resultGrants,
                        "navigate-top", true /* oomAdj */);
            }
        }
        Binder.restoreCallingIdentity(origId);
        return foundParentInTask;
    }

    void removeLaunchTickMessages() {
        forAllActivities(ActivityRecord::removeLaunchTickRunnable);
    }

    private void updateTransitLocked(int transit, ActivityOptions options, boolean forceOverride) {
        if (options != null) {
            ActivityRecord r = topRunningActivity();
            if (r != null && !r.isState(RESUMED)) {
                r.updateOptionsLocked(options);
            } else {
                ActivityOptions.abort(options);
            }
        }
        getDisplay().mDisplayContent.prepareAppTransition(transit, false,
                0 /* flags */, forceOverride);
    }

    final void moveTaskToFront(Task tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, String reason) {
        moveTaskToFront(tr, noAnimation, options, timeTracker, !DEFER_RESUME, reason);
    }

    final void moveTaskToFront(Task tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, boolean deferResume, String reason) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "moveTaskToFront: " + tr);

        final Task topStack = getDisplayArea().getTopStack();
        final ActivityRecord topActivity = topStack != null
                ? topStack.getTopNonFinishingActivity() : null;

        if (tr != this && !tr.isDescendantOf(this)) {
            // nothing to do!
            if (noAnimation) {
                ActivityOptions.abort(options);
            } else if (isSingleTaskInstance()) {
                // When a task is moved front on the display which can only contain one task, start
                // a special transition.
                // {@link AppTransitionController#handleAppTransitionReady} later picks up the
                // transition, and schedules
                // {@link ITaskStackListener#onSingleTaskDisplayDrawn} callback which is triggered
                // after contents are drawn on the display.
                updateTransitLocked(TRANSIT_SHOW_SINGLE_TASK_DISPLAY, options,
                        true /* forceOverride */);
            } else {
                updateTransitLocked(TRANSIT_TASK_TO_FRONT, options, false /* forceOverride */);
            }
            return;
        }

        if (timeTracker != null) {
            // The caller wants a time tracker associated with this task.
            final PooledConsumer c = PooledLambda.obtainConsumer(ActivityRecord::setAppTimeTracker,
                    PooledLambda.__(ActivityRecord.class), timeTracker);
            tr.forAllActivities(c);
            c.recycle();
        }

        try {
            // Defer updating the IME target since the new IME target will try to get computed
            // before updating all closing and opening apps, which can cause the ime target to
            // get calculated incorrectly.
            getDisplay().deferUpdateImeTarget();

            // Shift all activities with this task up to the top
            // of the stack, keeping them in the same internal order.
            positionChildAtTop(tr);

            // Don't refocus if invisible to current user
            final ActivityRecord top = tr.getTopNonFinishingActivity();
            if (top == null || !top.okToShowLocked()) {
                if (top != null) {
                    mStackSupervisor.mRecentTasks.add(top.getTask());
                }
                ActivityOptions.abort(options);
                return;
            }

            // Set focus to the top running activity of this stack.
            final ActivityRecord r = topRunningActivity();
            if (r != null) {
                r.moveFocusableActivityToTop(reason);
            }

            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to front transition: task=" + tr);
            if (noAnimation) {
                getDisplay().mDisplayContent.prepareAppTransition(TRANSIT_NONE, false);
                if (r != null) {
                    mStackSupervisor.mNoAnimActivities.add(r);
                }
                ActivityOptions.abort(options);
            } else if (isSingleTaskInstance()) {
                updateTransitLocked(TRANSIT_SHOW_SINGLE_TASK_DISPLAY, options,
                        true /* forceOverride */);
            } else {
                updateTransitLocked(TRANSIT_TASK_TO_FRONT, options, false /* forceOverride */);
            }

            // If a new task is moved to the front, then mark the existing top activity as
            // supporting

            // picture-in-picture while paused only if the task would not be considered an oerlay
            // on top
            // of the current activity (eg. not fullscreen, or the assistant)
            if (canEnterPipOnTaskSwitch(topActivity, tr, null /* toFrontActivity */,
                    options)) {
                topActivity.supportsEnterPipOnTaskSwitch = true;
            }

            if (!deferResume) {
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
            EventLogTags.writeWmTaskToFront(tr.mUserId, tr.mTaskId);
            mAtmService.getTaskChangeNotificationController()
                    .notifyTaskMovedToFront(tr.getTaskInfo());
        } finally {
            getDisplay().continueUpdateImeTarget();
        }
    }

    /**
     * Worker method for rearranging history stack. Implements the function of moving all
     * activities for a specific task (gathering them if disjoint) into a single group at the
     * bottom of the stack.
     *
     * If a watcher is installed, the action is preflighted and the watcher has an opportunity
     * to premeptively cancel the move.
     *
     * @param tr The task to collect and move to the bottom.
     * @return Returns true if the move completed, false if not.
     */
    boolean moveTaskToBack(Task tr) {
        Slog.i(TAG, "moveTaskToBack: " + tr);

        // In LockTask mode, moving a locked task to the back of the stack may expose unlocked
        // ones. Therefore we need to check if this operation is allowed.
        if (!mAtmService.getLockTaskController().canMoveTaskToBack(tr)) {
            return false;
        }

        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (isTopStackInDisplayArea() && mAtmService.mController != null) {
            ActivityRecord next = topRunningActivity(null, tr.mTaskId);
            if (next == null) {
                next = topRunningActivity(null, INVALID_TASK_ID);
            }
            if (next != null) {
                // ask watcher if this is allowed
                boolean moveOK = true;
                try {
                    moveOK = mAtmService.mController.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mAtmService.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }
                if (!moveOK) {
                    return false;
                }
            }
        }

        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to back transition: task="
                + tr.mTaskId);

        getDisplay().mDisplayContent.prepareAppTransition(TRANSIT_TASK_TO_BACK, false);
        moveToBack("moveTaskToBackLocked", tr);

        if (inPinnedWindowingMode()) {
            mStackSupervisor.removeStack(this);
            return true;
        }

        mRootWindowContainer.ensureVisibilityAndConfig(null /* starting */,
                getDisplay().mDisplayId, false /* markFrozenIfConfigChanged */,
                false /* deferResume */);

        ActivityRecord topActivity = getDisplayArea().topRunningActivity();
        Task topStack = topActivity.getRootTask();
        if (topStack != null && topStack != this && topActivity.isState(RESUMED)) {
            // Usually resuming a top activity triggers the next app transition, but nothing's got
            // resumed in this case, so we need to execute it explicitly.
            getDisplay().mDisplayContent.executeAppTransition();
        } else {
            mRootWindowContainer.resumeFocusedStacksTopActivities();
        }
        return true;
    }

    /**
     * Ensures all visible activities at or below the input activity have the right configuration.
     */
    void ensureVisibleActivitiesConfiguration(ActivityRecord start, boolean preserveWindow) {
        mEnsureVisibleActivitiesConfigHelper.process(start, preserveWindow);
    }

    // TODO: Can only be called from special methods in ActivityStackSupervisor.
    // Need to consolidate those calls points into this resize method so anyone can call directly.
    void resize(Rect displayedBounds, boolean preserveWindows, boolean deferResume) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "stack.resize_" + getRootTaskId());
        mAtmService.deferWindowLayout();
        try {
            // TODO: Why not just set this on the stack directly vs. on each tasks?
            // Update override configurations of all tasks in the stack.
            final PooledConsumer c = PooledLambda.obtainConsumer(
                    Task::processTaskResizeBounds, PooledLambda.__(Task.class),
                    displayedBounds);
            forAllTasks(c, true /* traverseTopToBottom */);
            c.recycle();

            if (!deferResume) {
                ensureVisibleActivitiesConfiguration(topRunningActivity(), preserveWindows);
            }
        } finally {
            mAtmService.continueWindowLayout();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private static void processTaskResizeBounds(Task task, Rect displayedBounds) {
        if (!task.isResizeable()) return;

        task.setBounds(displayedBounds);
    }

    /**
     * Until we can break this "set task bounds to same as stack bounds" behavior, this
     * basically resizes both stack and task bounds to the same bounds.
     */
    private void setTaskBounds(Rect bounds) {
        final PooledConsumer c = PooledLambda.obtainConsumer(Task::setTaskBoundsInner,
                PooledLambda.__(Task.class), bounds);
        forAllLeafTasks(c, true /* traverseTopToBottom */);
        c.recycle();
    }

    private static void setTaskBoundsInner(Task task, Rect bounds) {
        task.setBounds(task.isResizeable() ? bounds : null);
    }

    boolean willActivityBeVisible(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            return false;
        }

        // See if there is an occluding activity on-top of this one.
        final ActivityRecord occludingActivity = getOccludingActivityAbove(r);
        if (occludingActivity != null) return false;

        if (r.finishing) Slog.e(TAG, "willActivityBeVisible: Returning false,"
                + " would have returned true for r=" + r);
        return !r.finishing;
    }

    void unhandledBackLocked() {
        final ActivityRecord topActivity = getTopMostActivity();
        if (DEBUG_SWITCH) Slog.d(TAG_SWITCH,
                "Performing unhandledBack(): top activity: " + topActivity);
        if (topActivity != null) {
            topActivity.finishIfPossible("unhandled-back", true /* oomAdj */);
        }
    }

    /**
     * Reset local parameters because an app's activity died.
     * @param app The app of the activity that died.
     */
    void handleAppDied(WindowProcessController app) {
        if (mPausingActivity != null && mPausingActivity.app == app) {
            if (DEBUG_PAUSE || DEBUG_CLEANUP) Slog.v(TAG_PAUSE,
                    "App died while pausing: " + mPausingActivity);
            mPausingActivity = null;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }
    }

    boolean dump(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage, final boolean needSep) {
        Runnable headerPrinter = () -> {
            if (needSep) {
                pw.println();
            }
            pw.println("  Stack #" + getRootTaskId()
                    + ": type=" + activityTypeToString(getActivityType())
                    + " mode=" + windowingModeToString(getWindowingMode()));
            pw.println("  isSleeping=" + shouldSleepActivities());
            pw.println("  mBounds=" + getRequestedOverrideBounds());
        };

        boolean printed = false;

        if (dumpPackage == null) {
            // If we are not filtering by package, we want to print absolutely everything,
            // so always print the header even if there are no tasks/activities inside.
            headerPrinter.run();
            headerPrinter = null;
            printed = true;
        }

        printed |= printThisActivity(pw, mPausingActivity, dumpPackage, false,
                "    mPausingActivity: ", null);
        printed |= printThisActivity(pw, getResumedActivity(), dumpPackage, false,
                "    mResumedActivity: ", null);
        if (dumpAll) {
            printed |= printThisActivity(pw, mLastPausedActivity, dumpPackage, false,
                    "    mLastPausedActivity: ", null);
            printed |= printThisActivity(pw, mLastNoHistoryActivity, dumpPackage,
                    false, "    mLastNoHistoryActivity: ", null);
        }

        printed |= dumpActivities(fd, pw, dumpAll, dumpClient, dumpPackage, false, headerPrinter);

        return printed;
    }

    private boolean dumpActivities(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage, boolean needSep, Runnable header) {
        if (!hasChild()) {
            return false;
        }
        final AtomicBoolean printedHeader = new AtomicBoolean(false);
        final AtomicBoolean printed = new AtomicBoolean(false);
        forAllLeafTasks((task) -> {
            final String prefix = "    ";
            Runnable headerPrinter = () -> {
                printed.set(true);
                if (!printedHeader.get()) {
                    if (needSep) {
                        pw.println("");
                    }
                    if (header != null) {
                        header.run();
                    }
                    printedHeader.set(true);
                }
                pw.print(prefix); pw.print("* "); pw.println(task);
                pw.print(prefix); pw.print("  mBounds=");
                pw.println(task.getRequestedOverrideBounds());
                pw.print(prefix); pw.print("  mMinWidth="); pw.print(task.mMinWidth);
                pw.print(" mMinHeight="); pw.println(task.mMinHeight);
                if (mLastNonFullscreenBounds != null) {
                    pw.print(prefix);
                    pw.print("  mLastNonFullscreenBounds=");
                    pw.println(task.mLastNonFullscreenBounds);
                }
                task.dump(pw, prefix + "  ");
            };
            if (dumpPackage == null) {
                // If we are not filtering by package, we want to print absolutely everything,
                // so always print the header even if there are no activities inside.
                headerPrinter.run();
                headerPrinter = null;
            }
            final ArrayList<ActivityRecord> activities = new ArrayList<>();
            // Add activities by traversing the hierarchy from bottom to top, since activities
            // are dumped in reverse order in {@link ActivityStackSupervisor#dumpHistoryList()}.
            task.forAllActivities((Consumer<ActivityRecord>) activities::add,
                    false /* traverseTopToBottom */);
            dumpHistoryList(fd, pw, activities, prefix, "Hist", true, !dumpAll, dumpClient,
                    dumpPackage, false, headerPrinter, task);
        }, true /* traverseTopToBottom */);
        return printed.get();
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        ArrayList<ActivityRecord> activities = new ArrayList<>();

        if ("all".equals(name)) {
            forAllActivities((Consumer<ActivityRecord>) activities::add);
        } else if ("top".equals(name)) {
            final ActivityRecord topActivity = getTopMostActivity();
            if (topActivity != null) {
                activities.add(topActivity);
            }
        } else {
            ActivityManagerService.ItemMatcher matcher = new ActivityManagerService.ItemMatcher();
            matcher.build(name);

            forAllActivities((r) -> {
                if (matcher.match(r, r.intent.getComponent())) {
                    activities.add(r);
                }
            });
        }

        return activities;
    }

    ActivityRecord restartPackage(String packageName) {
        ActivityRecord starting = topRunningActivity();

        // All activities that came from the package must be
        // restarted as if there was a config change.
        PooledConsumer c = PooledLambda.obtainConsumer(Task::restartPackage,
                PooledLambda.__(ActivityRecord.class), starting, packageName);
        forAllActivities(c);
        c.recycle();

        return starting;
    }

    private static void restartPackage(
            ActivityRecord r, ActivityRecord starting, String packageName) {
        if (r.info.packageName.equals(packageName)) {
            r.forceNewConfig = true;
            if (starting != null && r == starting && r.mVisibleRequested) {
                r.startFreezingScreenLocked(CONFIG_SCREEN_LAYOUT);
            }
        }
    }

    Task reuseOrCreateTask(ActivityInfo info, Intent intent, boolean toTop) {
        return reuseOrCreateTask(info, intent, null /*voiceSession*/, null /*voiceInteractor*/,
                toTop, null /*activity*/, null /*source*/, null /*options*/);
    }
    // TODO: Can be removed once we change callpoints creating stacks to be creating tasks.
    /** Either returns this current task to be re-used or creates a new child task. */
    Task reuseOrCreateTask(ActivityInfo info, Intent intent, IVoiceInteractionSession voiceSession,
            IVoiceInteractor voiceInteractor, boolean toTop, ActivityRecord activity,
            ActivityRecord source, ActivityOptions options) {

        Task task;
        if (DisplayContent.alwaysCreateStack(getWindowingMode(), getActivityType())) {
            // This stack will only contain one task, so just return itself since all stacks ara now
            // tasks and all tasks are now stacks.
            task = reuseAsLeafTask(voiceSession, voiceInteractor, intent, info, activity);
        } else {
            // Create child task since this stack can contain multiple tasks.
            final int taskId = activity != null
                    ? mStackSupervisor.getNextTaskIdForUser(activity.mUserId)
                    : mStackSupervisor.getNextTaskIdForUser();
            task = new Task(mAtmService, taskId, info, intent, voiceSession,
                    voiceInteractor, null /* taskDescription */, this);

            // add the task to stack first, mTaskPositioner might need the stack association
            addChild(task, toTop, (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0);
        }

        int displayId = getDisplayId();
        if (displayId == INVALID_DISPLAY) displayId = DEFAULT_DISPLAY;
        final boolean isLockscreenShown = mAtmService.mStackSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        if (!mStackSupervisor.getLaunchParamsController()
                .layoutTask(task, info.windowLayout, activity, source, options)
                && !getRequestedOverrideBounds().isEmpty()
                && task.isResizeable() && !isLockscreenShown) {
            task.setBounds(getRequestedOverrideBounds());
        }

        return task;
    }

    void addChild(WindowContainer child, final boolean toTop, boolean showForAllUsers) {
        if (isSingleTaskInstance() && hasChild()) {
            throw new IllegalStateException("Can only have one child on stack=" + this);
        }

        Task task = child.asTask();
        try {

            if (task != null) {
                task.setForceShowForAllUsers(showForAllUsers);
            }
            // We only want to move the parents to the parents if we are creating this task at the
            // top of its stack.
            addChild(child, toTop ? MAX_VALUE : 0, toTop /*moveParents*/);
        } finally {
            if (task != null) {
                task.setForceShowForAllUsers(false);
            }
        }
    }

    void positionChildAt(Task task, int position) {
        if (task.getRootTask() != this) {
            throw new IllegalArgumentException("AS.positionChildAt: task=" + task
                    + " is not a child of stack=" + this + " current parent=" + task.getRootTask());
        }

        task.updateOverrideConfigurationForStack(this);

        final ActivityRecord topRunningActivity = task.topRunningActivityLocked();
        final boolean wasResumed = topRunningActivity == task.getRootTask().mResumedActivity;

        boolean toTop = position >= getChildCount();
        boolean includingParents = toTop || getDisplayArea().getNextFocusableStack(this,
                true /* ignoreCurrent */) == null;
        if (WindowManagerDebugConfig.DEBUG_STACK) {
            Slog.i(TAG_WM, "positionChildAt: positioning task=" + task + " at " + position);
        }
        positionChildAt(position, task, includingParents);
        task.updateTaskMovement(toTop);
        getDisplayContent().layoutAndAssignWindowLayersIfNeeded();


        // TODO: Investigate if this random code is really needed.
        if (task.voiceSession != null) {
            try {
                task.voiceSession.taskStarted(task.intent, task.mTaskId);
            } catch (RemoteException e) {
            }
        }

        if (wasResumed) {
            if (mResumedActivity != null) {
                Log.wtf(TAG, "mResumedActivity was already set when moving mResumedActivity from"
                        + " other stack to this stack mResumedActivity=" + mResumedActivity
                        + " other mResumedActivity=" + topRunningActivity);
            }
            topRunningActivity.setState(RESUMED, "positionChildAt");
        }

        // The task might have already been running and its visibility needs to be synchronized with
        // the visibility of the stack / windows.
        ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        mRootWindowContainer.resumeFocusedStacksTopActivities();
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (isAlwaysOnTop() == alwaysOnTop) {
            return;
        }
        super.setAlwaysOnTop(alwaysOnTop);
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        // positionChildAtTop() must be called even when always on top gets turned off because we
        // need to make sure that the stack is moved from among always on top windows to below other
        // always on top windows. Since the position the stack should be inserted into is calculated
        // properly in {@link DisplayContent#getTopInsertPosition()} in both cases, we can just
        // request that the stack is put at top here.
        taskDisplayArea.positionChildAt(POSITION_TOP, this, false /* includingParents */);
    }

    /** NOTE: Should only be called from {@link Task#reparent}. */
    void moveToFrontAndResumeStateIfNeeded(ActivityRecord r, boolean moveToFront, boolean setResume,
            boolean setPause, String reason) {
        if (!moveToFront) {
            return;
        }

        final ActivityState origState = r.getState();
        // If the activity owns the last resumed activity, transfer that together,
        // so that we don't resume the same activity again in the new stack.
        // Apps may depend on onResume()/onPause() being called in pairs.
        if (setResume) {
            r.setState(RESUMED, "moveToFrontAndResumeStateIfNeeded");
        }
        // If the activity was previously pausing, then ensure we transfer that as well
        if (setPause) {
            mPausingActivity = r;
            r.schedulePauseTimeout();
        }
        // Move the stack in which we are placing the activity to the front.
        moveToFront(reason);
        // If the original state is resumed, there is no state change to update focused app.
        // So here makes sure the activity focus is set if it is the top.
        if (origState == RESUMED && r == mRootWindowContainer.getTopResumedActivity()) {
            mAtmService.setResumedActivityUncheckLocked(r, reason);
        }
    }

    void dismissPip() {
        if (!isActivityTypeStandardOrUndefined()) {
            throw new IllegalArgumentException(
                    "You can't move tasks from non-standard stacks.");
        }
        if (getWindowingMode() != WINDOWING_MODE_PINNED) {
            throw new IllegalArgumentException(
                    "Can't exit pinned mode if it's not pinned already.");
        }

        mWmService.inSurfaceTransaction(() -> {
            final Task task = getBottomMostTask();
            setWindowingMode(WINDOWING_MODE_UNDEFINED);

            getDisplayArea().positionChildAt(POSITION_TOP, this, false /* includingParents */);

            mStackSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(task, this);
            MetricsLoggerWrapper.logPictureInPictureFullScreen(mAtmService.mContext,
                    task.effectiveUid, task.realActivity.flattenToString());
        });
    }

    void prepareFreezingTaskBounds() {
        forAllLeafTasks(Task::prepareFreezingBounds, true /* traverseTopToBottom */);
    }

    private int setBounds(Rect existing, Rect bounds) {
        if (equivalentBounds(existing, bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        final int result = super.setBounds(!inMultiWindowMode() ? null : bounds);

        updateSurfaceBounds();
        return result;
    }

    @Override
    public void getBounds(Rect bounds) {
        bounds.set(getBounds());
    }

    /**
     * Put a Task in this stack. Used for adding only.
     * When task is added to top of the stack, the entire branch of the hierarchy (including stack
     * and display) will be brought to top.
     * @param child The child to add.
     * @param position Target position to add the task to.
     */
    private void addChild(WindowContainer child, int position, boolean moveParents) {
        // Add child task.
        addChild(child, null);

        // Move child to a proper position, as some restriction for position might apply.
        positionChildAt(position, child, moveParents /* includingParents */);
    }

    void positionChildAtTop(Task child) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        if (child == this) {
            // TODO: Fix call-points
            moveToFront("positionChildAtTop");
            return;
        }

        positionChildAt(POSITION_TOP, child, true /* includingParents */);
        child.updateTaskMovement(true);

        final DisplayContent displayContent = getDisplayContent();
        displayContent.layoutAndAssignWindowLayersIfNeeded();
    }

    void positionChildAtBottom(Task child) {
        // If there are other focusable stacks on the display, the z-order of the display should not
        // be changed just because a task was placed at the bottom. E.g. if it is moving the topmost
        // task to bottom, the next focusable stack on the same display should be focused.
        final Task nextFocusableStack = getDisplayArea().getNextFocusableStack(
                child.getRootTask(), true /* ignoreCurrent */);
        positionChildAtBottom(child, nextFocusableStack == null /* includingParents */);
        child.updateTaskMovement(true);
    }

    @VisibleForTesting
    void positionChildAtBottom(Task child, boolean includingParents) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        positionChildAt(POSITION_BOTTOM, child, includingParents);
        getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        if (isOrganized()) {
            mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(this, false /* force */);
        }

        if (!mChildren.contains(child)) {
            return;
        }

        final boolean isTop = getTopChild() == child;

        final Task task = child.asTask();
        if (task != null) {
            task.updateTaskMovement(isTop);
        }

        if (isTop) {
            final DisplayContent displayContent = getDisplayContent();
            displayContent.layoutAndAssignWindowLayersIfNeeded();
        }
    }

    void reparent(TaskDisplayArea newParent, boolean onTop) {
        reparent(newParent, onTop ? POSITION_TOP : POSITION_BOTTOM);
    }

    private void updateSurfaceBounds() {
        updateSurfaceSize(getSyncTransaction());
        updateSurfacePositionNonOrganized();
        scheduleAnimation();
    }

    @Override
    void getRelativePosition(Point outPos) {
        super.getRelativePosition(outPos);
        final int outset = getTaskOutset();
        outPos.x -= outset;
        outPos.y -= outset;
    }

    boolean shouldIgnoreInput() {
        if (inSplitScreenPrimaryWindowingMode() && !isFocusable()) {
            return true;
        }
        if (mAtmService.mHasLeanbackFeature && inPinnedWindowingMode()
                && !isFocusedStackOnDisplay()) {
            // Preventing Picture-in-Picture stack from receiving input on TVs.
            return true;
        }
        return false;
    }

    /**
     * Sets the current picture-in-picture aspect ratio.
     */
    void setPictureInPictureAspectRatio(float aspectRatio) {
        if (!mWmService.mAtmService.mSupportsPictureInPicture) {
            return;
        }

        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return;
        }

        if (!inPinnedWindowingMode()) {
            return;
        }

        final PinnedStackController pinnedStackController =
                getDisplayContent().getPinnedStackController();

        if (Float.compare(aspectRatio, pinnedStackController.getAspectRatio()) == 0) {
            return;
        }

        // Notify the pinned stack controller about aspect ratio change.
        // This would result a callback delivered from SystemUI to WM to start animation,
        // if the bounds are ought to be altered due to aspect ratio change.
        pinnedStackController.setAspectRatio(
                pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)
                        ? aspectRatio : -1f);
    }

    /**
     * Sets the current picture-in-picture actions.
     */
    void setPictureInPictureActions(List<RemoteAction> actions) {
        if (!mWmService.mAtmService.mSupportsPictureInPicture) {
            return;
        }

        if (!inPinnedWindowingMode()) {
            return;
        }

        getDisplayContent().getPinnedStackController().setActions(actions);
    }

    /** Returns true if a removal action is still being deferred. */
    boolean handleCompleteDeferredRemoval() {
        if (isAnimating(TRANSITION | CHILDREN)) {
            return true;
        }

        return super.handleCompleteDeferredRemoval();
    }

    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    AnimatingActivityRegistry getAnimatingActivityRegistry() {
        return mAnimatingActivityRegistry;
    }

    void executeAppTransition(ActivityOptions options) {
        getDisplay().mDisplayContent.executeAppTransition();
        ActivityOptions.abort(options);
    }

    boolean shouldSleepActivities() {
        final DisplayContent display = getDisplay();

        // Do not sleep activities in this stack if we're marked as focused and the keyguard
        // is in the process of going away.
        if (isFocusedStackOnDisplay()
                && mStackSupervisor.getKeyguardController().isKeyguardGoingAway()) {
            return false;
        }

        return display != null ? display.isSleeping() : mAtmService.isSleepingLocked();
    }

    boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || mAtmService.mShuttingDown;
    }

    /** Bounds of the stack without adjusting for other factors in the system like visibility
     * of docked stack.
     * Most callers should be using {@link ConfigurationContainer#getRequestedOverrideBounds} a
     * it takes into consideration other system factors. */
    void getRawBounds(Rect out) {
        out.set(getRawBounds());
    }

    private Rect getRawBounds() {
        return super.getBounds();
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);

        proto.write(TaskProto.ID, mTaskId);
        proto.write(DISPLAY_ID, getDisplayId());
        proto.write(ROOT_TASK_ID, getRootTaskId());

        if (mResumedActivity != null) {
            mResumedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
        }
        if (realActivity != null) {
            proto.write(REAL_ACTIVITY, realActivity.flattenToShortString());
        }
        if (origActivity != null) {
            proto.write(ORIG_ACTIVITY, origActivity.flattenToShortString());
        }
        proto.write(ACTIVITY_TYPE, getActivityType());
        proto.write(RESIZE_MODE, mResizeMode);
        proto.write(MIN_WIDTH, mMinWidth);
        proto.write(MIN_HEIGHT, mMinHeight);

        proto.write(FILLS_PARENT, matchParentBounds());
        getRawBounds().dumpDebug(proto, BOUNDS);

        if (mLastNonFullscreenBounds != null) {
            mLastNonFullscreenBounds.dumpDebug(proto, LAST_NON_FULLSCREEN_BOUNDS);
        }

        if (mSurfaceControl != null) {
            proto.write(SURFACE_WIDTH, mSurfaceControl.getWidth());
            proto.write(SURFACE_HEIGHT, mSurfaceControl.getHeight());
        }

        proto.write(CREATED_BY_ORGANIZER, mCreatedByOrganizer);

        proto.end(token);
    }
}
