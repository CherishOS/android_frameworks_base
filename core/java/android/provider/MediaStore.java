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

package android.provider;

import android.annotation.BytesLong;
import android.annotation.DurationMillisLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.app.Activity;
import android.app.AppGlobals;
import android.content.ClipData;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.FileUtils;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.service.media.CameraPrewarmService;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Media provider contains meta data for all available media on both internal
 * and external storage devices.
 */
public final class MediaStore {
    private final static String TAG = "MediaStore";

    /** The authority for the media provider */
    public static final String AUTHORITY = "media";
    /** A content:// style uri to the authority for the media provider */
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /** {@hide} */
    public static final String VOLUME_INTERNAL = "internal";
    /** {@hide} */
    public static final String VOLUME_EXTERNAL = "external";

    /**
     * The method name used by the media scanner and mtp to tell the media provider to
     * rescan and reclassify that have become unhidden because of renaming folders or
     * removing nomedia files
     * @hide
     */
    public static final String UNHIDE_CALL = "unhide";

    /**
     * The method name used by the media scanner service to reload all localized ringtone titles due
     * to a locale change.
     * @hide
     */
    public static final String RETRANSLATE_CALL = "update_titles";

    /** {@hide} */
    public static final String GET_DOCUMENT_URI_CALL = "get_document_uri";
    /** {@hide} */
    public static final String GET_MEDIA_URI_CALL = "get_media_uri";

    /** {@hide} */
    public static final String GET_CONTRIBUTED_MEDIA_CALL = "get_contributed_media";
    /** {@hide} */
    public static final String DELETE_CONTRIBUTED_MEDIA_CALL = "delete_contributed_media";

    /**
     * This is for internal use by the media scanner only.
     * Name of the (optional) Uri parameter that determines whether to skip deleting
     * the file pointed to by the _data column, when deleting the database entry.
     * The only appropriate value for this parameter is "false", in which case the
     * delete will be skipped. Note especially that setting this to true, or omitting
     * the parameter altogether, will perform the default action, which is different
     * for different types of media.
     * @hide
     */
    public static final String PARAM_DELETE_DATA = "deletedata";

    /** {@hide} */
    public static final String PARAM_PRIMARY = "primary";
    /** {@hide} */
    public static final String PARAM_SECONDARY = "secondary";
    /** {@hide} */
    public static final String PARAM_INCLUDE_PENDING = "includePending";
    /** {@hide} */
    public static final String PARAM_INCLUDE_TRASHED = "includeTrashed";
    /** {@hide} */
    public static final String PARAM_PROGRESS = "progress";
    /** {@hide} */
    public static final String PARAM_REQUIRE_ORIGINAL = "requireOriginal";

    /**
     * Activity Action: Launch a music player.
     * The activity should be able to play, browse, or manipulate music files stored on the device.
     *
     * @deprecated Use {@link android.content.Intent#CATEGORY_APP_MUSIC} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_MUSIC_PLAYER = "android.intent.action.MUSIC_PLAYER";

    /**
     * Activity Action: Perform a search for media.
     * Contains at least the {@link android.app.SearchManager#QUERY} extra.
     * May also contain any combination of the following extras:
     * EXTRA_MEDIA_ARTIST, EXTRA_MEDIA_ALBUM, EXTRA_MEDIA_TITLE, EXTRA_MEDIA_FOCUS
     *
     * @see android.provider.MediaStore#EXTRA_MEDIA_ARTIST
     * @see android.provider.MediaStore#EXTRA_MEDIA_ALBUM
     * @see android.provider.MediaStore#EXTRA_MEDIA_TITLE
     * @see android.provider.MediaStore#EXTRA_MEDIA_FOCUS
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_MEDIA_SEARCH = "android.intent.action.MEDIA_SEARCH";

    /**
     * An intent to perform a search for music media and automatically play content from the
     * result when possible. This can be fired, for example, by the result of a voice recognition
     * command to listen to music.
     * <p>This intent always includes the {@link android.provider.MediaStore#EXTRA_MEDIA_FOCUS}
     * and {@link android.app.SearchManager#QUERY} extras. The
     * {@link android.provider.MediaStore#EXTRA_MEDIA_FOCUS} extra determines the search mode, and
     * the value of the {@link android.app.SearchManager#QUERY} extra depends on the search mode.
     * For more information about the search modes for this intent, see
     * <a href="{@docRoot}guide/components/intents-common.html#PlaySearch">Play music based
     * on a search query</a> in <a href="{@docRoot}guide/components/intents-common.html">Common
     * Intents</a>.</p>
     *
     * <p>This intent makes the most sense for apps that can support large-scale search of music,
     * such as services connected to an online database of music which can be streamed and played
     * on the device.</p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH =
            "android.media.action.MEDIA_PLAY_FROM_SEARCH";

    /**
     * An intent to perform a search for readable media and automatically play content from the
     * result when possible. This can be fired, for example, by the result of a voice recognition
     * command to read a book or magazine.
     * <p>
     * Contains the {@link android.app.SearchManager#QUERY} extra, which is a string that can
     * contain any type of unstructured text search, like the name of a book or magazine, an author
     * a genre, a publisher, or any combination of these.
     * <p>
     * Because this intent includes an open-ended unstructured search string, it makes the most
     * sense for apps that can support large-scale search of text media, such as services connected
     * to an online database of books and/or magazines which can be read on the device.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_TEXT_OPEN_FROM_SEARCH =
            "android.media.action.TEXT_OPEN_FROM_SEARCH";

    /**
     * An intent to perform a search for video media and automatically play content from the
     * result when possible. This can be fired, for example, by the result of a voice recognition
     * command to play movies.
     * <p>
     * Contains the {@link android.app.SearchManager#QUERY} extra, which is a string that can
     * contain any type of unstructured video search, like the name of a movie, one or more actors,
     * a genre, or any combination of these.
     * <p>
     * Because this intent includes an open-ended unstructured search string, it makes the most
     * sense for apps that can support large-scale search of video, such as services connected to an
     * online database of videos which can be streamed and played on the device.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_VIDEO_PLAY_FROM_SEARCH =
            "android.media.action.VIDEO_PLAY_FROM_SEARCH";

    /**
     * The name of the Intent-extra used to define the artist
     */
    public static final String EXTRA_MEDIA_ARTIST = "android.intent.extra.artist";
    /**
     * The name of the Intent-extra used to define the album
     */
    public static final String EXTRA_MEDIA_ALBUM = "android.intent.extra.album";
    /**
     * The name of the Intent-extra used to define the song title
     */
    public static final String EXTRA_MEDIA_TITLE = "android.intent.extra.title";
    /**
     * The name of the Intent-extra used to define the genre.
     */
    public static final String EXTRA_MEDIA_GENRE = "android.intent.extra.genre";
    /**
     * The name of the Intent-extra used to define the playlist.
     */
    public static final String EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist";
    /**
     * The name of the Intent-extra used to define the radio channel.
     */
    public static final String EXTRA_MEDIA_RADIO_CHANNEL = "android.intent.extra.radio_channel";
    /**
     * The name of the Intent-extra used to define the search focus. The search focus
     * indicates whether the search should be for things related to the artist, album
     * or song that is identified by the other extras.
     */
    public static final String EXTRA_MEDIA_FOCUS = "android.intent.extra.focus";

    /**
     * The name of the Intent-extra used to control the orientation of a ViewImage or a MovieView.
     * This is an int property that overrides the activity's requestedOrientation.
     * @see android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED
     */
    public static final String EXTRA_SCREEN_ORIENTATION = "android.intent.extra.screenOrientation";

    /**
     * The name of an Intent-extra used to control the UI of a ViewImage.
     * This is a boolean property that overrides the activity's default fullscreen state.
     */
    public static final String EXTRA_FULL_SCREEN = "android.intent.extra.fullScreen";

    /**
     * The name of an Intent-extra used to control the UI of a ViewImage.
     * This is a boolean property that specifies whether or not to show action icons.
     */
    public static final String EXTRA_SHOW_ACTION_ICONS = "android.intent.extra.showActionIcons";

    /**
     * The name of the Intent-extra used to control the onCompletion behavior of a MovieView.
     * This is a boolean property that specifies whether or not to finish the MovieView activity
     * when the movie completes playing. The default value is true, which means to automatically
     * exit the movie player activity when the movie completes playing.
     */
    public static final String EXTRA_FINISH_ON_COMPLETION = "android.intent.extra.finishOnCompletion";

    /**
     * The name of the Intent action used to launch a camera in still image mode.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_STILL_IMAGE_CAMERA = "android.media.action.STILL_IMAGE_CAMERA";

    /**
     * Name under which an activity handling {@link #INTENT_ACTION_STILL_IMAGE_CAMERA} or
     * {@link #INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE} publishes the service name for its prewarm
     * service.
     * <p>
     * This meta-data should reference the fully qualified class name of the prewarm service
     * extending {@link CameraPrewarmService}.
     * <p>
     * The prewarm service will get bound and receive a prewarm signal
     * {@link CameraPrewarmService#onPrewarm()} when a camera launch intent fire might be imminent.
     * An application implementing a prewarm service should do the absolute minimum amount of work
     * to initialize the camera in order to reduce startup time in likely case that shortly after a
     * camera launch intent would be sent.
     */
    public static final String META_DATA_STILL_IMAGE_CAMERA_PREWARM_SERVICE =
            "android.media.still_image_camera_preview_service";

    /**
     * The name of the Intent action used to launch a camera in still image mode
     * for use when the device is secured (e.g. with a pin, password, pattern,
     * or face unlock). Applications responding to this intent must not expose
     * any personal content like existing photos or videos on the device. The
     * applications should be careful not to share any photo or video with other
     * applications or internet. The activity should use {@link
     * Activity#setShowWhenLocked} to display
     * on top of the lock screen while secured. There is no activity stack when
     * this flag is used, so launching more than one activity is strongly
     * discouraged.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE =
            "android.media.action.STILL_IMAGE_CAMERA_SECURE";

    /**
     * The name of the Intent action used to launch a camera in video mode.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_VIDEO_CAMERA = "android.media.action.VIDEO_CAMERA";

    /**
     * Standard Intent action that can be sent to have the camera application
     * capture an image and return it.
     * <p>
     * The caller may pass an extra EXTRA_OUTPUT to control where this image will be written.
     * If the EXTRA_OUTPUT is not present, then a small sized image is returned as a Bitmap
     * object in the extra field. This is useful for applications that only need a small image.
     * If the EXTRA_OUTPUT is present, then the full-sized image will be written to the Uri
     * value of EXTRA_OUTPUT.
     * As of {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this uri can also be supplied through
     * {@link android.content.Intent#setClipData(ClipData)}. If using this approach, you still must
     * supply the uri through the EXTRA_OUTPUT field for compatibility with old applications.
     * If you don't set a ClipData, it will be copied there for you when calling
     * {@link Context#startActivity(Intent)}.
     *
     * <p>Note: if you app targets {@link android.os.Build.VERSION_CODES#M M} and above
     * and declares as using the {@link android.Manifest.permission#CAMERA} permission which
     * is not granted, then attempting to use this action will result in a {@link
     * java.lang.SecurityException}.
     *
     *  @see #EXTRA_OUTPUT
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public final static String ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE";

    /**
     * Intent action that can be sent to have the camera application capture an image and return
     * it when the device is secured (e.g. with a pin, password, pattern, or face unlock).
     * Applications responding to this intent must not expose any personal content like existing
     * photos or videos on the device. The applications should be careful not to share any photo
     * or video with other applications or Internet. The activity should use {@link
     * Activity#setShowWhenLocked} to display on top of the
     * lock screen while secured. There is no activity stack when this flag is used, so
     * launching more than one activity is strongly discouraged.
     * <p>
     * The caller may pass an extra EXTRA_OUTPUT to control where this image will be written.
     * If the EXTRA_OUTPUT is not present, then a small sized image is returned as a Bitmap
     * object in the extra field. This is useful for applications that only need a small image.
     * If the EXTRA_OUTPUT is present, then the full-sized image will be written to the Uri
     * value of EXTRA_OUTPUT.
     * As of {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this uri can also be supplied through
     * {@link android.content.Intent#setClipData(ClipData)}. If using this approach, you still must
     * supply the uri through the EXTRA_OUTPUT field for compatibility with old applications.
     * If you don't set a ClipData, it will be copied there for you when calling
     * {@link Context#startActivity(Intent)}.
     *
     * @see #ACTION_IMAGE_CAPTURE
     * @see #EXTRA_OUTPUT
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_IMAGE_CAPTURE_SECURE =
            "android.media.action.IMAGE_CAPTURE_SECURE";

    /**
     * Standard Intent action that can be sent to have the camera application
     * capture a video and return it.
     * <p>
     * The caller may pass in an extra EXTRA_VIDEO_QUALITY to control the video quality.
     * <p>
     * The caller may pass in an extra EXTRA_OUTPUT to control
     * where the video is written. If EXTRA_OUTPUT is not present the video will be
     * written to the standard location for videos, and the Uri of that location will be
     * returned in the data field of the Uri.
     * As of {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this uri can also be supplied through
     * {@link android.content.Intent#setClipData(ClipData)}. If using this approach, you still must
     * supply the uri through the EXTRA_OUTPUT field for compatibility with old applications.
     * If you don't set a ClipData, it will be copied there for you when calling
     * {@link Context#startActivity(Intent)}.
     *
     * <p>Note: if you app targets {@link android.os.Build.VERSION_CODES#M M} and above
     * and declares as using the {@link android.Manifest.permission#CAMERA} permission which
     * is not granted, then atempting to use this action will result in a {@link
     * java.lang.SecurityException}.
     *
     * @see #EXTRA_OUTPUT
     * @see #EXTRA_VIDEO_QUALITY
     * @see #EXTRA_SIZE_LIMIT
     * @see #EXTRA_DURATION_LIMIT
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public final static String ACTION_VIDEO_CAPTURE = "android.media.action.VIDEO_CAPTURE";

    /**
     * Standard action that can be sent to review the given media file.
     * <p>
     * The launched application is expected to provide a large-scale view of the
     * given media file, while allowing the user to quickly access other
     * recently captured media files.
     * <p>
     * Input: {@link Intent#getData} is URI of the primary media item to
     * initially display.
     *
     * @see #ACTION_REVIEW_SECURE
     * @see #EXTRA_BRIGHTNESS
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public final static String ACTION_REVIEW = "android.provider.action.REVIEW";

    /**
     * Standard action that can be sent to review the given media file when the
     * device is secured (e.g. with a pin, password, pattern, or face unlock).
     * The applications should be careful not to share any media with other
     * applications or Internet. The activity should use
     * {@link Activity#setShowWhenLocked} to display on top of the lock screen
     * while secured. There is no activity stack when this flag is used, so
     * launching more than one activity is strongly discouraged.
     * <p>
     * The launched application is expected to provide a large-scale view of the
     * given primary media file, while only allowing the user to quickly access
     * other media from an explicit secondary list.
     * <p>
     * Input: {@link Intent#getData} is URI of the primary media item to
     * initially display. {@link Intent#getClipData} is the limited list of
     * secondary media items that the user is allowed to review. If
     * {@link Intent#getClipData} is undefined, then no other media access
     * should be allowed.
     *
     * @see #EXTRA_BRIGHTNESS
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public final static String ACTION_REVIEW_SECURE = "android.provider.action.REVIEW_SECURE";

    /**
     * When defined, the launched application is requested to set the given
     * brightness value via
     * {@link android.view.WindowManager.LayoutParams#screenBrightness} to help
     * ensure a smooth transition when launching {@link #ACTION_REVIEW} or
     * {@link #ACTION_REVIEW_SECURE} intents.
     */
    public final static String EXTRA_BRIGHTNESS = "android.provider.extra.BRIGHTNESS";

    /**
     * The name of the Intent-extra used to control the quality of a recorded video. This is an
     * integer property. Currently value 0 means low quality, suitable for MMS messages, and
     * value 1 means high quality. In the future other quality levels may be added.
     */
    public final static String EXTRA_VIDEO_QUALITY = "android.intent.extra.videoQuality";

    /**
     * Specify the maximum allowed size.
     */
    public final static String EXTRA_SIZE_LIMIT = "android.intent.extra.sizeLimit";

    /**
     * Specify the maximum allowed recording duration in seconds.
     */
    public final static String EXTRA_DURATION_LIMIT = "android.intent.extra.durationLimit";

    /**
     * The name of the Intent-extra used to indicate a content resolver Uri to be used to
     * store the requested image or video.
     */
    public final static String EXTRA_OUTPUT = "output";

    /**
      * The string that is used when a media attribute is not known. For example,
      * if an audio file does not have any meta data, the artist and album columns
      * will be set to this value.
      */
    public static final String UNKNOWN_STRING = "<unknown>";

    /**
     * Update the given {@link Uri} to also include any pending media items from
     * calls such as
     * {@link ContentResolver#query(Uri, String[], Bundle, CancellationSignal)}.
     * By default no pending items are returned.
     *
     * @see MediaColumns#IS_PENDING
     * @see MediaStore#setIncludePending(Uri)
     * @see MediaStore#createPending(Context, PendingParams)
     */
    public static @NonNull Uri setIncludePending(@NonNull Uri uri) {
        return uri.buildUpon().appendQueryParameter(PARAM_INCLUDE_PENDING, "1").build();
    }

    /**
     * Update the given {@link Uri} to also include any trashed media items from
     * calls such as
     * {@link ContentResolver#query(Uri, String[], Bundle, CancellationSignal)}.
     * By default no trashed items are returned.
     *
     * @see MediaColumns#IS_TRASHED
     * @see MediaStore#setIncludeTrashed(Uri)
     * @see MediaStore#trash(Context, Uri)
     * @see MediaStore#untrash(Context, Uri)
     */
    public static @NonNull Uri setIncludeTrashed(@NonNull Uri uri) {
        return uri.buildUpon().appendQueryParameter(PARAM_INCLUDE_TRASHED, "1").build();
    }

    /**
     * Update the given {@link Uri} to indicate that the caller requires the
     * original file contents when calling
     * {@link ContentResolver#openFileDescriptor(Uri, String)}.
     * <p>
     * This can be useful when the caller wants to ensure they're backing up the
     * exact bytes of the underlying media, without any Exif redaction being
     * performed.
     * <p>
     * If the original file contents cannot be provided, a
     * {@link UnsupportedOperationException} will be thrown when the returned
     * {@link Uri} is used, such as when the caller doesn't hold
     * {@link android.Manifest.permission#ACCESS_MEDIA_LOCATION}.
     */
    public static @NonNull Uri setRequireOriginal(@NonNull Uri uri) {
        return uri.buildUpon().appendQueryParameter(PARAM_REQUIRE_ORIGINAL, "1").build();
    }

    /**
     * Create a new pending media item using the given parameters. Pending items
     * are expected to have a short lifetime, and owners should either
     * {@link PendingSession#publish()} or {@link PendingSession#abandon()} a
     * pending item within a few hours after first creating it.
     *
     * @return token which can be passed to {@link #openPending(Context, Uri)}
     *         to work with this pending item.
     * @see MediaColumns#IS_PENDING
     * @see MediaStore#setIncludePending(Uri)
     * @see MediaStore#createPending(Context, PendingParams)
     */
    public static @NonNull Uri createPending(@NonNull Context context,
            @NonNull PendingParams params) {
        final Uri.Builder builder = params.insertUri.buildUpon();
        if (!TextUtils.isEmpty(params.primaryDirectory)) {
            builder.appendQueryParameter(PARAM_PRIMARY, params.primaryDirectory);
        }
        if (!TextUtils.isEmpty(params.secondaryDirectory)) {
            builder.appendQueryParameter(PARAM_SECONDARY, params.secondaryDirectory);
        }
        return context.getContentResolver().insert(builder.build(), params.insertValues);
    }

    /**
     * Open a pending media item to make progress on it. You can open a pending
     * item multiple times before finally calling either
     * {@link PendingSession#publish()} or {@link PendingSession#abandon()}.
     *
     * @param uri token which was previously returned from
     *            {@link #createPending(Context, PendingParams)}.
     */
    public static @NonNull PendingSession openPending(@NonNull Context context, @NonNull Uri uri) {
        return new PendingSession(context, uri);
    }

    /**
     * Parameters that describe a pending media item.
     */
    public static class PendingParams {
        /** {@hide} */
        public final Uri insertUri;
        /** {@hide} */
        public final ContentValues insertValues;
        /** {@hide} */
        public String primaryDirectory;
        /** {@hide} */
        public String secondaryDirectory;

        /**
         * Create parameters that describe a pending media item.
         *
         * @param insertUri the {@code content://} Uri where this pending item
         *            should be inserted when finally published. For example, to
         *            publish an image, use
         *            {@link MediaStore.Images.Media#getContentUri(String)}.
         */
        public PendingParams(@NonNull Uri insertUri, @NonNull String displayName,
                @NonNull String mimeType) {
            this.insertUri = Objects.requireNonNull(insertUri);
            final long now = System.currentTimeMillis() / 1000;
            this.insertValues = new ContentValues();
            this.insertValues.put(MediaColumns.DISPLAY_NAME, Objects.requireNonNull(displayName));
            this.insertValues.put(MediaColumns.MIME_TYPE, Objects.requireNonNull(mimeType));
            this.insertValues.put(MediaColumns.DATE_ADDED, now);
            this.insertValues.put(MediaColumns.DATE_MODIFIED, now);
            this.insertValues.put(MediaColumns.IS_PENDING, 1);
            this.insertValues.put(MediaColumns.DATE_EXPIRES,
                    (System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS) / 1000);
        }

        /**
         * Optionally set the primary directory under which this pending item
         * should be persisted. Only specific well-defined directories from
         * {@link Environment} are allowed based on the media type being
         * inserted.
         * <p>
         * For example, when creating pending {@link MediaStore.Images.Media}
         * items, only {@link Environment#DIRECTORY_PICTURES} or
         * {@link Environment#DIRECTORY_DCIM} are allowed.
         * <p>
         * You may leave this value undefined to store the media in a default
         * location. For example, when this value is left undefined, pending
         * {@link MediaStore.Audio.Media} items are stored under
         * {@link Environment#DIRECTORY_MUSIC}.
         */
        public void setPrimaryDirectory(@Nullable String primaryDirectory) {
            this.primaryDirectory = primaryDirectory;
        }

        /**
         * Optionally set the secondary directory under which this pending item
         * should be persisted. Any valid directory name is allowed.
         * <p>
         * You may leave this value undefined to store the media as a direct
         * descendant of the {@link #setPrimaryDirectory(String)} location.
         */
        public void setSecondaryDirectory(@Nullable String secondaryDirectory) {
            this.secondaryDirectory = secondaryDirectory;
        }

        /**
         * Optionally set the Uri from where the file has been downloaded. This is used
         * for files being added to {@link Downloads} table.
         *
         * @see DownloadColumns#DOWNLOAD_URI
         */
        public void setDownloadUri(@Nullable Uri downloadUri) {
            if (downloadUri == null) {
                this.insertValues.remove(DownloadColumns.DOWNLOAD_URI);
            } else {
                this.insertValues.put(DownloadColumns.DOWNLOAD_URI, downloadUri.toString());
            }
        }

        /**
         * Optionally set the Uri indicating HTTP referer of the file. This is used for
         * files being added to {@link Downloads} table.
         *
         * @see DownloadColumns#REFERER_URI
         */
        public void setRefererUri(@Nullable Uri refererUri) {
            if (refererUri == null) {
                this.insertValues.remove(DownloadColumns.REFERER_URI);
            } else {
                this.insertValues.put(DownloadColumns.REFERER_URI, refererUri.toString());
            }
        }
    }

    /**
     * Session actively working on a pending media item. Pending items are
     * expected to have a short lifetime, and owners should either
     * {@link PendingSession#publish()} or {@link PendingSession#abandon()} a
     * pending item within a few hours after first creating it.
     */
    public static class PendingSession implements AutoCloseable {
        /** {@hide} */
        private final Context mContext;
        /** {@hide} */
        private final Uri mUri;

        /** {@hide} */
        public PendingSession(Context context, Uri uri) {
            mContext = Objects.requireNonNull(context);
            mUri = Objects.requireNonNull(uri);
        }

        /**
         * Open the underlying file representing this media item. When a media
         * item is successfully completed, you should
         * {@link ParcelFileDescriptor#close()} and then {@link #publish()} it.
         *
         * @see #notifyProgress(int)
         */
        public @NonNull ParcelFileDescriptor open() throws FileNotFoundException {
            return mContext.getContentResolver().openFileDescriptor(mUri, "rw");
        }

        /**
         * Open the underlying file representing this media item. When a media
         * item is successfully completed, you should
         * {@link OutputStream#close()} and then {@link #publish()} it.
         *
         * @see #notifyProgress(int)
         */
        public @NonNull OutputStream openOutputStream() throws FileNotFoundException {
            return mContext.getContentResolver().openOutputStream(mUri);
        }

        /**
         * Notify of current progress on this pending media item. Gallery
         * applications may choose to surface progress information of this
         * pending item.
         *
         * @param progress a percentage between 0 and 100.
         */
        public void notifyProgress(@IntRange(from = 0, to = 100) int progress) {
            final Uri withProgress = mUri.buildUpon()
                    .appendQueryParameter(PARAM_PROGRESS, Integer.toString(progress)).build();
            mContext.getContentResolver().notifyChange(withProgress, null, 0);
        }

        /**
         * When this media item is successfully completed, call this method to
         * publish and make the final item visible to the user.
         *
         * @return the final {@code content://} Uri representing the newly
         *         published media.
         */
        public @NonNull Uri publish() {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.IS_PENDING, 0);
            values.putNull(MediaColumns.DATE_EXPIRES);
            mContext.getContentResolver().update(mUri, values, null, null);
            return mUri;
        }

        /**
         * When this media item has failed to be completed, call this method to
         * destroy the pending item record and any data related to it.
         */
        public void abandon() {
            mContext.getContentResolver().delete(mUri, null, null);
        }

        @Override
        public void close() {
            // No resources to close, but at least we can inform people that no
            // progress is being actively made.
            notifyProgress(-1);
        }
    }

    /**
     * Mark the given item as being "trashed", meaning it should be deleted at
     * some point in the future. This is a more gentle operation than simply
     * calling {@link ContentResolver#delete(Uri, String, String[])}, which
     * would take effect immediately.
     * <p>
     * This method preserves trashed items for at least 48 hours before erasing
     * them, giving the user a chance to untrash the item.
     *
     * @see MediaColumns#IS_TRASHED
     * @see MediaStore#setIncludeTrashed(Uri)
     * @see MediaStore#trash(Context, Uri)
     * @see MediaStore#untrash(Context, Uri)
     */
    public static void trash(@NonNull Context context, @NonNull Uri uri) {
        trash(context, uri, 48 * DateUtils.HOUR_IN_MILLIS);
    }

    /**
     * Mark the given item as being "trashed", meaning it should be deleted at
     * some point in the future. This is a more gentle operation than simply
     * calling {@link ContentResolver#delete(Uri, String, String[])}, which
     * would take effect immediately.
     * <p>
     * This method preserves trashed items for at least the given timeout before
     * erasing them, giving the user a chance to untrash the item.
     *
     * @see MediaColumns#IS_TRASHED
     * @see MediaStore#setIncludeTrashed(Uri)
     * @see MediaStore#trash(Context, Uri)
     * @see MediaStore#untrash(Context, Uri)
     */
    public static void trash(@NonNull Context context, @NonNull Uri uri,
            @DurationMillisLong long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException();
        }

        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 1);
        values.put(MediaColumns.DATE_EXPIRES,
                (System.currentTimeMillis() + timeoutMillis) / 1000);
        context.getContentResolver().update(uri, values, null, null);
    }

    /**
     * Mark the given item as being "untrashed", meaning it should no longer be
     * deleted as previously requested through {@link #trash(Context, Uri)}.
     *
     * @see MediaColumns#IS_TRASHED
     * @see MediaStore#setIncludeTrashed(Uri)
     * @see MediaStore#trash(Context, Uri)
     * @see MediaStore#untrash(Context, Uri)
     */
    public static void untrash(@NonNull Context context, @NonNull Uri uri) {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 0);
        values.putNull(MediaColumns.DATE_EXPIRES);
        context.getContentResolver().update(uri, values, null, null);
    }

    /**
     * Common fields for most MediaProvider tables
     */
    public interface MediaColumns extends BaseColumns {
        /**
         * Path to the file on disk.
         * <p>
         * Note that apps may not have filesystem permissions to directly access
         * this path. Instead of trying to open this path directly, apps should
         * use {@link ContentResolver#openFileDescriptor(Uri, String)} to gain
         * access.
         * <p>
         * Type: TEXT
         *
         * @deprecated Apps may not have filesystem permissions to directly
         *             access this path. Instead of trying to open this path
         *             directly, apps should use
         *             {@link ContentResolver#openFileDescriptor(Uri, String)}
         *             to gain access. This value will always be {@code NULL}
         *             for apps targeting
         *             {@link android.os.Build.VERSION_CODES#Q} or higher.
         */
        @Deprecated
        public static final String DATA = "_data";

        /**
         * Hash of the file on disk.
         * <p>
         * Contains a 20-byte binary blob which is the SHA-1 hash of the file as
         * persisted on disk. For performance reasons, the hash may not be
         * immediately available, in which case a {@code NULL} value will be
         * returned. If the underlying file is modified, this value will be
         * cleared and recalculated.
         * <p>
         * If you require the hash of a specific item, you can call
         * {@link ContentResolver#canonicalize(Uri)}, which will block until the
         * hash is calculated.
         * <p>
         * Type: BLOB
         */
        public static final String HASH = "_hash";

        /**
         * The size of the file in bytes
         * <P>Type: INTEGER (long)</P>
         */
        public static final String SIZE = "_size";

        /**
         * The display name of the file
         * <P>Type: TEXT</P>
         */
        public static final String DISPLAY_NAME = "_display_name";

        /**
         * The title of the content
         * <P>Type: TEXT</P>
         */
        public static final String TITLE = "title";

        /**
         * The time the file was added to the media provider
         * Units are seconds since 1970.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE_ADDED = "date_added";

        /**
         * The time the file was last modified
         * Units are seconds since 1970.
         * NOTE: This is for internal use by the media scanner.  Do not modify this field.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE_MODIFIED = "date_modified";

        /**
         * The MIME type of the file
         * <P>Type: TEXT</P>
         */
        public static final String MIME_TYPE = "mime_type";

        /**
         * The MTP object handle of a newly transfered file.
         * Used to pass the new file's object handle through the media scanner
         * from MTP to the media provider
         * For internal use only by MTP, media scanner and media provider.
         * <P>Type: INTEGER</P>
         * @hide
         */
        public static final String MEDIA_SCANNER_NEW_OBJECT_ID = "media_scanner_new_object_id";

        /**
         * Non-zero if the media file is drm-protected
         * <P>Type: INTEGER (boolean)</P>
         * @hide
         */
        @UnsupportedAppUsage
        public static final String IS_DRM = "is_drm";

        /**
         * Flag indicating if a media item is pending, and still being inserted
         * by its owner.
         * <p>
         * Type: BOOLEAN
         *
         * @see MediaColumns#IS_PENDING
         * @see MediaStore#setIncludePending(Uri)
         * @see MediaStore#createPending(Context, PendingParams)
         */
        public static final String IS_PENDING = "is_pending";

        /**
         * Flag indicating if a media item is trashed.
         * <p>
         * Type: BOOLEAN
         *
         * @see MediaColumns#IS_TRASHED
         * @see MediaStore#setIncludeTrashed(Uri)
         * @see MediaStore#trash(Context, Uri)
         * @see MediaStore#untrash(Context, Uri)
         */
        public static final String IS_TRASHED = "is_trashed";

        /**
         * The time the file should be considered expired. Units are seconds
         * since 1970. Typically only meaningful in the context of
         * {@link #IS_PENDING} or {@link #IS_TRASHED}.
         * <p>
         * Type: INTEGER
         */
        public static final String DATE_EXPIRES = "date_expires";

        /**
         * The width of the image/video in pixels.
         */
        public static final String WIDTH = "width";

        /**
         * The height of the image/video in pixels.
         */
        public static final String HEIGHT = "height";

        /**
         * Package name that contributed this media. The value may be
         * {@code NULL} if ownership cannot be reliably determined.
         * <p>
         * Type: TEXT
         */
        public static final String OWNER_PACKAGE_NAME = "owner_package_name";
    }

    /**
     * Media provider table containing an index of all files in the media storage,
     * including non-media files.  This should be used by applications that work with
     * non-media file types (text, HTML, PDF, etc) as well as applications that need to
     * work with multiple media file types in a single query.
     */
    public static final class Files {

        /**
         * Get the content:// style URI for the files table on the
         * given volume.
         *
         * @param volumeName the name of the volume to get the URI for
         * @return the URI to the files table on the given volume
         */
        public static Uri getContentUri(String volumeName) {
            return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("file").build();
        }

        /**
         * Get the content:// style URI for a single row in the files table on the
         * given volume.
         *
         * @param volumeName the name of the volume to get the URI for
         * @param rowId the file to get the URI for
         * @return the URI to the files table on the given volume
         */
        public static final Uri getContentUri(String volumeName,
                long rowId) {
            return ContentUris.withAppendedId(getContentUri(volumeName), rowId);
        }

        /**
         * For use only by the MTP implementation.
         * @hide
         */
        @UnsupportedAppUsage
        public static Uri getMtpObjectsUri(String volumeName) {
            return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("object").build();
        }

        /**
         * For use only by the MTP implementation.
         * @hide
         */
        @UnsupportedAppUsage
        public static final Uri getMtpObjectsUri(String volumeName,
                long fileId) {
            return ContentUris.withAppendedId(getMtpObjectsUri(volumeName), fileId);
        }

        /**
         * Used to implement the MTP GetObjectReferences and SetObjectReferences commands.
         * @hide
         */
        @UnsupportedAppUsage
        public static final Uri getMtpReferencesUri(String volumeName,
                long fileId) {
            return getMtpObjectsUri(volumeName, fileId).buildUpon().appendPath("references")
                    .build();
        }

        /**
         * Used to trigger special logic for directories.
         * @hide
         */
        public static final Uri getDirectoryUri(String volumeName) {
            return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("dir").build();
        }

        /** @hide */
        public static final Uri getContentUriForPath(String path) {
            return getContentUri(getVolumeName(new File(path)));
        }

        /**
         * Fields for master table for all media files.
         * Table also contains MediaColumns._ID, DATA, SIZE and DATE_MODIFIED.
         */
        public interface FileColumns extends MediaColumns {
            /**
             * The MTP storage ID of the file
             * <P>Type: INTEGER</P>
             * @hide
             */
            @UnsupportedAppUsage
            public static final String STORAGE_ID = "storage_id";

            /**
             * The MTP format code of the file
             * <P>Type: INTEGER</P>
             * @hide
             */
            @UnsupportedAppUsage
            public static final String FORMAT = "format";

            /**
             * The index of the parent directory of the file
             * <P>Type: INTEGER</P>
             */
            public static final String PARENT = "parent";

            /**
             * The MIME type of the file
             * <P>Type: TEXT</P>
             */
            public static final String MIME_TYPE = "mime_type";

            /**
             * The title of the content
             * <P>Type: TEXT</P>
             */
            public static final String TITLE = "title";

            /**
             * The media type (audio, video, image or playlist)
             * of the file, or 0 for not a media file
             * <P>Type: TEXT</P>
             */
            public static final String MEDIA_TYPE = "media_type";

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file
             * is not an audio, image, video or playlist file.
             */
            public static final int MEDIA_TYPE_NONE = 0;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is an image file.
             */
            public static final int MEDIA_TYPE_IMAGE = 1;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is an audio file.
             */
            public static final int MEDIA_TYPE_AUDIO = 2;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is a video file.
             */
            public static final int MEDIA_TYPE_VIDEO = 3;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is a playlist file.
             */
            public static final int MEDIA_TYPE_PLAYLIST = 4;

            /**
             * Column indicating if the file is part of Downloads collection.
             * @hide
             */
            public static final String IS_DOWNLOAD = "is_download";
        }
    }

    /** @hide */
    public static class ThumbnailConstants {
        public static final int MINI_KIND = 1;
        public static final int FULL_SCREEN_KIND = 2;
        public static final int MICRO_KIND = 3;

        public static final Point MINI_SIZE = new Point(512, 384);
        public static final Point FULL_SCREEN_SIZE = new Point(1024, 786);
        public static final Point MICRO_SIZE = new Point(96, 96);
    }

    /** Column fields for downloaded files used in {@link Downloads} table */
    public interface DownloadColumns extends MediaColumns {
        /**
         * Uri indicating where the file has been downloaded from.
         * <p>
         * Type: TEXT
         */
        String DOWNLOAD_URI = "download_uri";

        /**
         * Uri indicating HTTP referer of {@link #DOWNLOAD_URI}.
         * <p>
         * Type: TEXT
         */
        String REFERER_URI = "referer_uri";

        /**
         * The description of the download.
         * <p>
         * Type: Text
         */
        String DESCRIPTION = "description";
    }

    /**
     * Container for downloaded files.
     *
     * <p>
     * Querying for downloads from this table will return files contributed via
     * {@link PendingSession} and also ones which were downloaded using
     * {@link android.app.DownloadManager} APIs.
     */
    public static final class Downloads implements DownloadColumns {
        private Downloads() {}

        /**
         * The content:// style URI for the internal storage.
         */
        public static final Uri INTERNAL_CONTENT_URI =
                getContentUri("internal");

        /**
         * The content:// style URI for the "primary" external storage
         * volume.
         */
        public static final Uri EXTERNAL_CONTENT_URI =
                getContentUri("external");

        /**
         * The MIME type for this table.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/download";

        /**
         * Regex that matches paths that needs to be considered part of downloads collection.
         * @hide
         */
        public static final Pattern PATTERN_DOWNLOADS_FILE = Pattern.compile(
                "(?i)^/storage/[^/]+/(?:[0-9]+/)?(?:Android/sandbox/[^/]+/)?Download/.+");
        private static final Pattern PATTERN_DOWNLOADS_DIRECTORY = Pattern.compile(
                "(?i)^/storage/[^/]+/(?:[0-9]+/)?(?:Android/sandbox/[^/]+/)?Download/?");

        /**
         * Get the content:// style URI for the downloads table on the
         * given volume.
         *
         * @param volumeName the name of the volume to get the URI for
         * @return the URI to the image media table on the given volume
         */
        public static Uri getContentUri(String volumeName) {
            return AUTHORITY_URI.buildUpon().appendPath(volumeName)
                    .appendPath("downloads").build();
        }

        /** @hide */
        public static Uri getContentUriForPath(@NonNull String path) {
            return getContentUri(getVolumeName(new File(path)));
        }

        /** @hide */
        public static boolean isDownload(@NonNull String path) {
            return PATTERN_DOWNLOADS_FILE.matcher(path).matches();
        }

        /** @hide */
        public static boolean isDownloadDir(@NonNull String path) {
            return PATTERN_DOWNLOADS_DIRECTORY.matcher(path).matches();
        }
    }

    /** {@hide} */
    public static @NonNull String getVolumeName(@NonNull File path) {
        if (FileUtils.contains(Environment.getStorageDirectory(), path)) {
            final StorageManager sm = AppGlobals.getInitialApplication()
                    .getSystemService(StorageManager.class);
            final StorageVolume sv = sm.getStorageVolume(path);
            if (sv != null) {
                if (sv.isPrimary()) {
                    return VOLUME_EXTERNAL;
                } else {
                    return checkArgumentVolumeName(sv.getUuid());
                }
            }
            throw new IllegalStateException("Unknown volume at " + path);
        } else {
            return VOLUME_INTERNAL;
        }
    }

    /**
     * This class is used internally by Images.Thumbnails and Video.Thumbnails, it's not intended
     * to be accessed elsewhere.
     */
    @Deprecated
    private static class InternalThumbnails implements BaseColumns {
        /**
         * Currently outstanding thumbnail requests that can be cancelled.
         */
        @GuardedBy("sPending")
        private static ArrayMap<Uri, CancellationSignal> sPending = new ArrayMap<>();

        /**
         * Make a blocking request to obtain the given thumbnail, generating it
         * if needed.
         *
         * @see #cancelThumbnail(ContentResolver, Uri)
         */
        @Deprecated
        static @Nullable Bitmap getThumbnail(@NonNull ContentResolver cr, @NonNull Uri uri,
                int kind, @Nullable BitmapFactory.Options opts) {
            final Point size;
            if (kind == ThumbnailConstants.MICRO_KIND) {
                size = ThumbnailConstants.MICRO_SIZE;
            } else if (kind == ThumbnailConstants.FULL_SCREEN_KIND) {
                size = ThumbnailConstants.FULL_SCREEN_SIZE;
            } else if (kind == ThumbnailConstants.MINI_KIND) {
                size = ThumbnailConstants.MINI_SIZE;
            } else {
                throw new IllegalArgumentException("Unsupported kind: " + kind);
            }

            CancellationSignal signal = null;
            synchronized (sPending) {
                signal = sPending.get(uri);
                if (signal == null) {
                    signal = new CancellationSignal();
                    sPending.put(uri, signal);
                }
            }

            try {
                return cr.loadThumbnail(uri, Point.convert(size), signal);
            } catch (IOException e) {
                Log.w(TAG, "Failed to obtain thumbnail for " + uri, e);
                return null;
            } finally {
                synchronized (sPending) {
                    sPending.remove(uri);
                }
            }
        }

        /**
         * This method cancels the thumbnail request so clients waiting for
         * {@link #getThumbnail} will be interrupted and return immediately.
         * Only the original process which made the request can cancel their own
         * requests.
         */
        @Deprecated
        static void cancelThumbnail(@NonNull ContentResolver cr, @NonNull Uri uri) {
            synchronized (sPending) {
                final CancellationSignal signal = sPending.get(uri);
                if (signal != null) {
                    signal.cancel();
                }
            }
        }
    }

    /**
     * Contains meta data for all available images.
     */
    public static final class Images {
        public interface ImageColumns extends MediaColumns {
            /**
             * The description of the image
             * <P>Type: TEXT</P>
             */
            public static final String DESCRIPTION = "description";

            /**
             * The picasa id of the image
             * <P>Type: TEXT</P>
             *
             * @deprecated this value was only relevant for images hosted on
             *             Picasa, which are no longer supported.
             */
            @Deprecated
            public static final String PICASA_ID = "picasa_id";

            /**
             * Whether the video should be published as public or private
             * <P>Type: INTEGER</P>
             */
            public static final String IS_PRIVATE = "isprivate";

            /**
             * The latitude where the image was captured.
             * <P>Type: DOUBLE</P>
             *
             * @deprecated location details are no longer indexed for privacy
             *             reasons, and this value is now always {@code null}.
             *             You can still manually obtain location metadata using
             *             {@link ExifInterface#getLatLong(float[])}.
             */
            @Deprecated
            public static final String LATITUDE = "latitude";

            /**
             * The longitude where the image was captured.
             * <P>Type: DOUBLE</P>
             *
             * @deprecated location details are no longer indexed for privacy
             *             reasons, and this value is now always {@code null}.
             *             You can still manually obtain location metadata using
             *             {@link ExifInterface#getLatLong(float[])}.
             */
            @Deprecated
            public static final String LONGITUDE = "longitude";

            /**
             * The date & time that the image was taken in units
             * of milliseconds since jan 1, 1970.
             * <P>Type: INTEGER</P>
             */
            public static final String DATE_TAKEN = "datetaken";

            /**
             * The orientation for the image expressed as degrees.
             * Only degrees 0, 90, 180, 270 will work.
             * <P>Type: INTEGER</P>
             */
            public static final String ORIENTATION = "orientation";

            /**
             * The mini thumb id.
             * <P>Type: INTEGER</P>
             *
             * @deprecated all thumbnails should be obtained via
             *             {@link MediaStore.Images.Thumbnails#getThumbnail}, as this
             *             value is no longer supported.
             */
            @Deprecated
            public static final String MINI_THUMB_MAGIC = "mini_thumb_magic";

            /**
             * The primary bucket ID of this media item. This can be useful to
             * present the user a first-level clustering of related media items.
             * This is a read-only column that is automatically computed.
             * <p>
             * Type: INTEGER
             */
            public static final String BUCKET_ID = "bucket_id";

            /**
             * The primary bucket display name of this media item. This can be
             * useful to present the user a first-level clustering of related
             * media items. This is a read-only column that is automatically
             * computed.
             * <p>
             * Type: TEXT
             */
            public static final String BUCKET_DISPLAY_NAME = "bucket_display_name";

            /**
             * The secondary bucket ID of this media item. This can be useful to
             * present the user a second-level clustering of related media
             * items. This is a read-only column that is automatically computed.
             * <p>
             * Type: INTEGER
             */
            public static final String SECONDARY_BUCKET_ID = "secondary_bucket_id";
        }

        public static final class Media implements ImageColumns {
            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection) {
                return cr.query(uri, projection, null, null, DEFAULT_SORT_ORDER);
            }

            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection,
                    String where, String orderBy) {
                return cr.query(uri, projection, where,
                                             null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
            }

            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection,
                    String selection, String [] selectionArgs, String orderBy) {
                return cr.query(uri, projection, selection,
                        selectionArgs, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
            }

            /**
             * Retrieves an image for the given url as a {@link Bitmap}.
             *
             * @param cr The content resolver to use
             * @param url The url of the image
             * @throws FileNotFoundException
             * @throws IOException
             */
            public static final Bitmap getBitmap(ContentResolver cr, Uri url)
                    throws FileNotFoundException, IOException {
                InputStream input = cr.openInputStream(url);
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                input.close();
                return bitmap;
            }

            /**
             * Insert an image and create a thumbnail for it.
             *
             * @param cr The content resolver to use
             * @param imagePath The path to the image to insert
             * @param name The name of the image
             * @param description The description of the image
             * @return The URL to the newly created image
             * @throws FileNotFoundException
             */
            public static final String insertImage(ContentResolver cr, String imagePath,
                    String name, String description) throws FileNotFoundException {
                // Check if file exists with a FileInputStream
                FileInputStream stream = new FileInputStream(imagePath);
                try {
                    Bitmap bm = BitmapFactory.decodeFile(imagePath);
                    String ret = insertImage(cr, bm, name, description);
                    bm.recycle();
                    return ret;
                } finally {
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
            }

            /**
             * Insert an image and create a thumbnail for it.
             *
             * @param cr The content resolver to use
             * @param source The stream to use for the image
             * @param title The name of the image
             * @param description The description of the image
             * @return The URL to the newly created image, or <code>null</code> if the image failed to be stored
             *              for any reason.
             */
            public static final String insertImage(ContentResolver cr, Bitmap source,
                                                   String title, String description) {
                ContentValues values = new ContentValues();
                values.put(Images.Media.DISPLAY_NAME, title);
                values.put(Images.Media.DESCRIPTION, description);
                values.put(Images.Media.MIME_TYPE, "image/jpeg");

                Uri url = null;
                String stringUrl = null;    /* value to be returned */

                try {
                    url = cr.insert(EXTERNAL_CONTENT_URI, values);

                    if (source != null) {
                        OutputStream imageOut = cr.openOutputStream(url);
                        try {
                            source.compress(Bitmap.CompressFormat.JPEG, 50, imageOut);
                        } finally {
                            imageOut.close();
                        }

                        long id = ContentUris.parseId(url);
                        // Block until we've generated common thumbnails
                        Images.Thumbnails.getThumbnail(cr, id, Images.Thumbnails.MINI_KIND, null);
                        Images.Thumbnails.getThumbnail(cr, id, Images.Thumbnails.MICRO_KIND, null);
                    } else {
                        Log.e(TAG, "Failed to create thumbnail, removing original");
                        cr.delete(url, null, null);
                        url = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to insert image", e);
                    if (url != null) {
                        cr.delete(url, null, null);
                        url = null;
                    }
                }

                if (url != null) {
                    stringUrl = url.toString();
                }

                return stringUrl;
            }

            /**
             * Get the content:// style URI for the image media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the image media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("images")
                        .appendPath("media").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type of of this directory of
             * images.  Note that each entry in this directory will have a standard
             * image MIME type as appropriate -- for example, image/jpeg.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/image";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = ImageColumns.BUCKET_DISPLAY_NAME;
        }

        /**
         * This class provides utility methods to obtain thumbnails for various
         * {@link Images} items.
         */
        public static class Thumbnails implements BaseColumns {
            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection) {
                return cr.query(uri, projection, null, null, DEFAULT_SORT_ORDER);
            }

            public static final Cursor queryMiniThumbnails(ContentResolver cr, Uri uri, int kind,
                    String[] projection) {
                return cr.query(uri, projection, "kind = " + kind, null, DEFAULT_SORT_ORDER);
            }

            public static final Cursor queryMiniThumbnail(ContentResolver cr, long origId, int kind,
                    String[] projection) {
                return cr.query(EXTERNAL_CONTENT_URI, projection,
                        IMAGE_ID + " = " + origId + " AND " + KIND + " = " +
                        kind, null, null);
            }

            /**
             * Cancel any outstanding {@link #getThumbnail} requests, causing
             * them to return by throwing a {@link OperationCanceledException}.
             * <p>
             * This method has no effect on
             * {@link ContentResolver#loadThumbnail} calls, since they provide
             * their own {@link CancellationSignal}.
             *
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static void cancelThumbnailRequest(ContentResolver cr, long origId) {
                final Uri uri = ContentUris.withAppendedId(
                        Images.Media.EXTERNAL_CONTENT_URI, origId);
                InternalThumbnails.cancelThumbnail(cr, uri);
            }

            /**
             * Return thumbnail representing a specific image item. If a
             * thumbnail doesn't exist, this method will block until it's
             * generated. Callers are responsible for their own in-memory
             * caching of returned values.
             *
             * @param imageId the image item to obtain a thumbnail for.
             * @param kind optimal thumbnail size desired.
             * @return decoded thumbnail, or {@code null} if problem was
             *         encountered.
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static Bitmap getThumbnail(ContentResolver cr, long imageId, int kind,
                    BitmapFactory.Options options) {
                final Uri uri = ContentUris.withAppendedId(
                        Images.Media.EXTERNAL_CONTENT_URI, imageId);
                return InternalThumbnails.getThumbnail(cr, uri, kind, options);
            }

            /**
             * Cancel any outstanding {@link #getThumbnail} requests, causing
             * them to return by throwing a {@link OperationCanceledException}.
             * <p>
             * This method has no effect on
             * {@link ContentResolver#loadThumbnail} calls, since they provide
             * their own {@link CancellationSignal}.
             *
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static void cancelThumbnailRequest(ContentResolver cr, long origId,
                    long groupId) {
                cancelThumbnailRequest(cr, origId);
            }

            /**
             * Return thumbnail representing a specific image item. If a
             * thumbnail doesn't exist, this method will block until it's
             * generated. Callers are responsible for their own in-memory
             * caching of returned values.
             *
             * @param imageId the image item to obtain a thumbnail for.
             * @param kind optimal thumbnail size desired.
             * @return decoded thumbnail, or {@code null} if problem was
             *         encountered.
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static Bitmap getThumbnail(ContentResolver cr, long imageId, long groupId,
                    int kind, BitmapFactory.Options options) {
                return getThumbnail(cr, imageId, kind, options);
            }

            /**
             * Get the content:// style URI for the image media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the image media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("images")
                        .appendPath("thumbnails").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = "image_id ASC";

            /**
             * Path to the thumbnail file on disk.
             * <p>
             * Note that apps may not have filesystem permissions to directly
             * access this path. Instead of trying to open this path directly,
             * apps should use
             * {@link ContentResolver#openFileDescriptor(Uri, String)} to gain
             * access.
             * <p>
             * Type: TEXT
             *
             * @deprecated Apps may not have filesystem permissions to directly
             *             access this path. Instead of trying to open this path
             *             directly, apps should use
             *             {@link ContentResolver#loadThumbnail}
             *             to gain access. This value will always be
             *             {@code NULL} for apps targeting
             *             {@link android.os.Build.VERSION_CODES#Q} or higher.
             */
            @Deprecated
            public static final String DATA = "_data";

            /**
             * The original image for the thumbnal
             * <P>Type: INTEGER (ID from Images table)</P>
             */
            public static final String IMAGE_ID = "image_id";

            /**
             * The kind of the thumbnail
             * <P>Type: INTEGER (One of the values below)</P>
             */
            public static final String KIND = "kind";

            public static final int MINI_KIND = ThumbnailConstants.MINI_KIND;
            public static final int FULL_SCREEN_KIND = ThumbnailConstants.FULL_SCREEN_KIND;
            public static final int MICRO_KIND = ThumbnailConstants.MICRO_KIND;
            /**
             * The blob raw data of thumbnail
             * <P>Type: DATA STREAM</P>
             */
            public static final String THUMB_DATA = "thumb_data";

            /**
             * The width of the thumbnal
             * <P>Type: INTEGER (long)</P>
             */
            public static final String WIDTH = "width";

            /**
             * The height of the thumbnail
             * <P>Type: INTEGER (long)</P>
             */
            public static final String HEIGHT = "height";
        }
    }

    /**
     * Container for all audio content.
     */
    public static final class Audio {
        /**
         * Columns for audio file that show up in multiple tables.
         */
        public interface AudioColumns extends MediaColumns {

            /**
             * A non human readable key calculated from the TITLE, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String TITLE_KEY = "title_key";

            /**
             * The duration of the audio file, in ms
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DURATION = "duration";

            /**
             * The position, in ms, playback was at when playback for this file
             * was last stopped.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String BOOKMARK = "bookmark";

            /**
             * The id of the artist who created the audio file, if any
             * <P>Type: INTEGER (long)</P>
             */
            public static final String ARTIST_ID = "artist_id";

            /**
             * The artist who created the audio file, if any
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * The artist credited for the album that contains the audio file
             * <P>Type: TEXT</P>
             * @hide
             */
            public static final String ALBUM_ARTIST = "album_artist";

            /**
             * Whether the song is part of a compilation
             * <P>Type: TEXT</P>
             * @hide
             */
            public static final String COMPILATION = "compilation";

            /**
             * A non human readable key calculated from the ARTIST, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST_KEY = "artist_key";

            /**
             * The composer of the audio file, if any
             * <P>Type: TEXT</P>
             */
            public static final String COMPOSER = "composer";

            /**
             * The id of the album the audio file is from, if any
             * <P>Type: INTEGER (long)</P>
             */
            public static final String ALBUM_ID = "album_id";

            /**
             * The album the audio file is from, if any
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM = "album";

            /**
             * A non human readable key calculated from the ALBUM, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM_KEY = "album_key";

            /**
             * The track number of this song on the album, if any.
             * This number encodes both the track number and the
             * disc number. For multi-disc sets, this number will
             * be 1xxx for tracks on the first disc, 2xxx for tracks
             * on the second disc, etc.
             * <P>Type: INTEGER</P>
             */
            public static final String TRACK = "track";

            /**
             * The year the audio file was recorded, if any
             * <P>Type: INTEGER</P>
             */
            public static final String YEAR = "year";

            /**
             * Non-zero if the audio file is music
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_MUSIC = "is_music";

            /**
             * Non-zero if the audio file is a podcast
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_PODCAST = "is_podcast";

            /**
             * Non-zero if the audio file may be a ringtone
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_RINGTONE = "is_ringtone";

            /**
             * Non-zero if the audio file may be an alarm
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_ALARM = "is_alarm";

            /**
             * Non-zero if the audio file may be a notification sound
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_NOTIFICATION = "is_notification";

            /**
             * Non-zero if the audio file is an audiobook
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_AUDIOBOOK = "is_audiobook";

            /**
             * The genre of the audio file, if any
             * <P>Type: TEXT</P>
             * Does not exist in the database - only used by the media scanner for inserts.
             * @hide
             */
            public static final String GENRE = "genre";

            /**
             * The resource URI of a localized title, if any
             * <P>Type: TEXT</P>
             * Conforms to this pattern:
             *   Scheme: {@link ContentResolver.SCHEME_ANDROID_RESOURCE}
             *   Authority: Package Name of ringtone title provider
             *   First Path Segment: Type of resource (must be "string")
             *   Second Path Segment: Resource ID of title
             * @hide
             */
            public static final String TITLE_RESOURCE_URI = "title_resource_uri";
        }

        /**
         * Converts a name to a "key" that can be used for grouping, sorting
         * and searching.
         * The rules that govern this conversion are:
         * - remove 'special' characters like ()[]'!?.,
         * - remove leading/trailing spaces
         * - convert everything to lowercase
         * - remove leading "the ", "an " and "a "
         * - remove trailing ", the|an|a"
         * - remove accents. This step leaves us with CollationKey data,
         *   which is not human readable
         *
         * @param name The artist or album name to convert
         * @return The "key" for the given name.
         */
        public static String keyFor(String name) {
            if (name != null)  {
                boolean sortfirst = false;
                if (name.equals(UNKNOWN_STRING)) {
                    return "\001";
                }
                // Check if the first character is \001. We use this to
                // force sorting of certain special files, like the silent ringtone.
                if (name.startsWith("\001")) {
                    sortfirst = true;
                }
                name = name.trim().toLowerCase();
                if (name.startsWith("the ")) {
                    name = name.substring(4);
                }
                if (name.startsWith("an ")) {
                    name = name.substring(3);
                }
                if (name.startsWith("a ")) {
                    name = name.substring(2);
                }
                if (name.endsWith(", the") || name.endsWith(",the") ||
                    name.endsWith(", an") || name.endsWith(",an") ||
                    name.endsWith(", a") || name.endsWith(",a")) {
                    name = name.substring(0, name.lastIndexOf(','));
                }
                name = name.replaceAll("[\\[\\]\\(\\)\"'.,?!]", "").trim();
                if (name.length() > 0) {
                    // Insert a separator between the characters to avoid
                    // matches on a partial character. If we ever change
                    // to start-of-word-only matches, this can be removed.
                    StringBuilder b = new StringBuilder();
                    b.append('.');
                    int nl = name.length();
                    for (int i = 0; i < nl; i++) {
                        b.append(name.charAt(i));
                        b.append('.');
                    }
                    name = b.toString();
                    String key = DatabaseUtils.getCollationKey(name);
                    if (sortfirst) {
                        key = "\001" + key;
                    }
                    return key;
               } else {
                    return "";
                }
            }
            return null;
        }

        public static final class Media implements AudioColumns {
            /**
             * Get the content:// style URI for the audio media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("audio")
                        .appendPath("media").build();
            }

            /**
             * Get the content:// style URI for the given audio media file.
             *
             * @deprecated Apps may not have filesystem permissions to directly
             *             access this path.
             */
            public static @Nullable Uri getContentUriForPath(@NonNull String path) {
                return getContentUri(getVolumeName(new File(path)));
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/audio";

            /**
             * The MIME type for an audio track.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/audio";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = TITLE_KEY;

            /**
             * Activity Action: Start SoundRecorder application.
             * <p>Input: nothing.
             * <p>Output: An uri to the recorded sound stored in the Media Library
             * if the recording was successful.
             * May also contain the extra EXTRA_MAX_BYTES.
             * @see #EXTRA_MAX_BYTES
             */
            @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
            public static final String RECORD_SOUND_ACTION =
                    "android.provider.MediaStore.RECORD_SOUND";

            /**
             * The name of the Intent-extra used to define a maximum file size for
             * a recording made by the SoundRecorder application.
             *
             * @see #RECORD_SOUND_ACTION
             */
             public static final String EXTRA_MAX_BYTES =
                    "android.provider.MediaStore.extra.MAX_BYTES";
        }

        /**
         * Columns representing an audio genre
         */
        public interface GenresColumns {
            /**
             * The name of the genre
             * <P>Type: TEXT</P>
             */
            public static final String NAME = "name";
        }

        /**
         * Contains all genres for audio files
         */
        public static final class Genres implements BaseColumns, GenresColumns {
            /**
             * Get the content:// style URI for the audio genres table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio genres table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("audio")
                        .appendPath("genres").build();
            }

            /**
             * Get the content:// style URI for querying the genres of an audio file.
             *
             * @param volumeName the name of the volume to get the URI for
             * @param audioId the ID of the audio file for which to retrieve the genres
             * @return the URI to for querying the genres for the audio file
             * with the given the volume and audioID
             */
            public static Uri getContentUriForAudioId(String volumeName, int audioId) {
                return ContentUris.withAppendedId(Audio.Media.getContentUri(volumeName), audioId)
                        .buildUpon().appendPath("genres").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/genre";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/genre";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = NAME;

            /**
             * Sub-directory of each genre containing all members.
             */
            public static final class Members implements AudioColumns {

                public static final Uri getContentUri(String volumeName, long genreId) {
                    return ContentUris
                            .withAppendedId(Audio.Genres.getContentUri(volumeName), genreId)
                            .buildUpon().appendPath("members").build();
                }

                /**
                 * A subdirectory of each genre containing all member audio files.
                 */
                public static final String CONTENT_DIRECTORY = "members";

                /**
                 * The default sort order for this table
                 */
                public static final String DEFAULT_SORT_ORDER = TITLE_KEY;

                /**
                 * The ID of the audio file
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String AUDIO_ID = "audio_id";

                /**
                 * The ID of the genre
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String GENRE_ID = "genre_id";
            }
        }

        /**
         * Columns representing a playlist
         */
        public interface PlaylistsColumns {
            /**
             * The name of the playlist
             * <P>Type: TEXT</P>
             */
            public static final String NAME = "name";

            /**
             * Path to the playlist file on disk.
             * <p>
             * Note that apps may not have filesystem permissions to directly
             * access this path. Instead of trying to open this path directly,
             * apps should use
             * {@link ContentResolver#openFileDescriptor(Uri, String)} to gain
             * access.
             * <p>
             * Type: TEXT
             *
             * @deprecated Apps may not have filesystem permissions to directly
             *             access this path. Instead of trying to open this path
             *             directly, apps should use
             *             {@link ContentResolver#openFileDescriptor(Uri, String)}
             *             to gain access. This value will always be
             *             {@code NULL} for apps targeting
             *             {@link android.os.Build.VERSION_CODES#Q} or higher.
             */
            @Deprecated
            public static final String DATA = "_data";

            /**
             * The time the file was added to the media provider
             * Units are seconds since 1970.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DATE_ADDED = "date_added";

            /**
             * The time the file was last modified
             * Units are seconds since 1970.
             * NOTE: This is for internal use by the media scanner.  Do not modify this field.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DATE_MODIFIED = "date_modified";
        }

        /**
         * Contains playlists for audio files
         */
        public static final class Playlists implements BaseColumns,
                PlaylistsColumns {
            /**
             * Get the content:// style URI for the audio playlists table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio playlists table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("audio")
                        .appendPath("playlists").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/playlist";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/playlist";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = NAME;

            /**
             * Sub-directory of each playlist containing all members.
             */
            public static final class Members implements AudioColumns {
                public static final Uri getContentUri(String volumeName, long playlistId) {
                    return ContentUris
                            .withAppendedId(Audio.Playlists.getContentUri(volumeName), playlistId)
                            .buildUpon().appendPath("members").build();
                }

                /**
                 * Convenience method to move a playlist item to a new location
                 * @param res The content resolver to use
                 * @param playlistId The numeric id of the playlist
                 * @param from The position of the item to move
                 * @param to The position to move the item to
                 * @return true on success
                 */
                public static final boolean moveItem(ContentResolver res,
                        long playlistId, int from, int to) {
                    Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external",
                            playlistId)
                            .buildUpon()
                            .appendEncodedPath(String.valueOf(from))
                            .appendQueryParameter("move", "true")
                            .build();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, to);
                    return res.update(uri, values, null, null) != 0;
                }

                /**
                 * The ID within the playlist.
                 */
                public static final String _ID = "_id";

                /**
                 * A subdirectory of each playlist containing all member audio
                 * files.
                 */
                public static final String CONTENT_DIRECTORY = "members";

                /**
                 * The ID of the audio file
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String AUDIO_ID = "audio_id";

                /**
                 * The ID of the playlist
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String PLAYLIST_ID = "playlist_id";

                /**
                 * The order of the songs in the playlist
                 * <P>Type: INTEGER (long)></P>
                 */
                public static final String PLAY_ORDER = "play_order";

                /**
                 * The default sort order for this table
                 */
                public static final String DEFAULT_SORT_ORDER = PLAY_ORDER;
            }
        }

        /**
         * Columns representing an artist
         */
        public interface ArtistColumns {
            /**
             * The artist who created the audio file, if any
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * A non human readable key calculated from the ARTIST, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST_KEY = "artist_key";

            /**
             * The number of albums in the database for this artist
             */
            public static final String NUMBER_OF_ALBUMS = "number_of_albums";

            /**
             * The number of albums in the database for this artist
             */
            public static final String NUMBER_OF_TRACKS = "number_of_tracks";
        }

        /**
         * Contains artists for audio files
         */
        public static final class Artists implements BaseColumns, ArtistColumns {
            /**
             * Get the content:// style URI for the artists table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio artists table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("audio")
                        .appendPath("artists").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/artists";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/artist";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = ARTIST_KEY;

            /**
             * Sub-directory of each artist containing all albums on which
             * a song by the artist appears.
             */
            public static final class Albums implements AlbumColumns {
                public static final Uri getContentUri(String volumeName,long artistId) {
                    return ContentUris
                            .withAppendedId(Audio.Artists.getContentUri(volumeName), artistId)
                            .buildUpon().appendPath("albums").build();
                }
            }
        }

        /**
         * Columns representing an album
         */
        public interface AlbumColumns {

            /**
             * The id for the album
             * <P>Type: INTEGER</P>
             */
            public static final String ALBUM_ID = "album_id";

            /**
             * The album on which the audio file appears, if any
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM = "album";

            /**
             * The artist whose songs appear on this album
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * The number of songs on this album
             * <P>Type: INTEGER</P>
             */
            public static final String NUMBER_OF_SONGS = "numsongs";

            /**
             * This column is available when getting album info via artist,
             * and indicates the number of songs on the album by the given
             * artist.
             * <P>Type: INTEGER</P>
             */
            public static final String NUMBER_OF_SONGS_FOR_ARTIST = "numsongs_by_artist";

            /**
             * The year in which the earliest songs
             * on this album were released. This will often
             * be the same as {@link #LAST_YEAR}, but for compilation albums
             * they might differ.
             * <P>Type: INTEGER</P>
             */
            public static final String FIRST_YEAR = "minyear";

            /**
             * The year in which the latest songs
             * on this album were released. This will often
             * be the same as {@link #FIRST_YEAR}, but for compilation albums
             * they might differ.
             * <P>Type: INTEGER</P>
             */
            public static final String LAST_YEAR = "maxyear";

            /**
             * A non human readable key calculated from the ALBUM, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM_KEY = "album_key";

            /**
             * Cached album art.
             * <P>Type: TEXT</P>
             *
             * @deprecated Apps may not have filesystem permissions to directly
             *             access this path. Instead of trying to open this path
             *             directly, apps should use
             *             {@link ContentResolver#loadThumbnail}
             *             to gain access. This value will always be
             *             {@code NULL} for apps targeting
             *             {@link android.os.Build.VERSION_CODES#Q} or higher.
             */
            @Deprecated
            public static final String ALBUM_ART = "album_art";
        }

        /**
         * Contains artists for audio files
         */
        public static final class Albums implements BaseColumns, AlbumColumns {
            /**
             * Get the content:// style URI for the albums table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio albums table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("audio")
                        .appendPath("albums").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/albums";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/album";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = ALBUM_KEY;
        }

        public static final class Radio {
            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/radio";

            // Not instantiable.
            private Radio() { }
        }
    }

    public static final class Video {

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = MediaColumns.DISPLAY_NAME;

        public static final Cursor query(ContentResolver cr, Uri uri, String[] projection) {
            return cr.query(uri, projection, null, null, DEFAULT_SORT_ORDER);
        }

        public interface VideoColumns extends MediaColumns {

            /**
             * The duration of the video file, in ms
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DURATION = "duration";

            /**
             * The artist who created the video file, if any
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * The album the video file is from, if any
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM = "album";

            /**
             * The resolution of the video file, formatted as "XxY"
             * <P>Type: TEXT</P>
             */
            public static final String RESOLUTION = "resolution";

            /**
             * The description of the video recording
             * <P>Type: TEXT</P>
             */
            public static final String DESCRIPTION = "description";

            /**
             * Whether the video should be published as public or private
             * <P>Type: INTEGER</P>
             */
            public static final String IS_PRIVATE = "isprivate";

            /**
             * The user-added tags associated with a video
             * <P>Type: TEXT</P>
             */
            public static final String TAGS = "tags";

            /**
             * The YouTube category of the video
             * <P>Type: TEXT</P>
             */
            public static final String CATEGORY = "category";

            /**
             * The language of the video
             * <P>Type: TEXT</P>
             */
            public static final String LANGUAGE = "language";

            /**
             * The latitude where the video was captured.
             * <P>Type: DOUBLE</P>
             *
             * @deprecated location details are no longer indexed for privacy
             *             reasons, and this value is now always {@code null}.
             *             You can still manually obtain location metadata using
             *             {@link ExifInterface#getLatLong(float[])}.
             */
            @Deprecated
            public static final String LATITUDE = "latitude";

            /**
             * The longitude where the video was captured.
             * <P>Type: DOUBLE</P>
             *
             * @deprecated location details are no longer indexed for privacy
             *             reasons, and this value is now always {@code null}.
             *             You can still manually obtain location metadata using
             *             {@link ExifInterface#getLatLong(float[])}.
             */
            @Deprecated
            public static final String LONGITUDE = "longitude";

            /**
             * The date & time that the video was taken in units
             * of milliseconds since jan 1, 1970.
             * <P>Type: INTEGER</P>
             */
            public static final String DATE_TAKEN = "datetaken";

            /**
             * The mini thumb id.
             * <P>Type: INTEGER</P>
             *
             * @deprecated all thumbnails should be obtained via
             *             {@link MediaStore.Images.Thumbnails#getThumbnail}, as this
             *             value is no longer supported.
             */
            @Deprecated
            public static final String MINI_THUMB_MAGIC = "mini_thumb_magic";

            /**
             * The primary bucket ID of this media item. This can be useful to
             * present the user a first-level clustering of related media items.
             * This is a read-only column that is automatically computed.
             * <p>
             * Type: INTEGER
             */
            public static final String BUCKET_ID = "bucket_id";

            /**
             * The primary bucket display name of this media item. This can be
             * useful to present the user a first-level clustering of related
             * media items. This is a read-only column that is automatically
             * computed.
             * <p>
             * Type: TEXT
             */
            public static final String BUCKET_DISPLAY_NAME = "bucket_display_name";

            /**
             * The secondary bucket ID of this media item. This can be useful to
             * present the user a second-level clustering of related media
             * items. This is a read-only column that is automatically computed.
             * <p>
             * Type: INTEGER
             */
            public static final String SECONDARY_BUCKET_ID = "secondary_bucket_id";

            /**
             * The bookmark for the video. Time in ms. Represents the location in the video that the
             * video should start playing at the next time it is opened. If the value is null or
             * out of the range 0..DURATION-1 then the video should start playing from the
             * beginning.
             * <P>Type: INTEGER</P>
             */
            public static final String BOOKMARK = "bookmark";

            /**
             * The standard of color aspects
             * <P>Type: INTEGER</P>
             * @hide
             */
            public static final String COLOR_STANDARD = "color_standard";

            /**
             * The transfer of color aspects
             * <P>Type: INTEGER</P>
             * @hide
             */
            public static final String COLOR_TRANSFER = "color_transfer";

            /**
             * The range of color aspects
             * <P>Type: INTEGER</P>
             * @hide
             */
            public static final String COLOR_RANGE = "color_range";
        }

        public static final class Media implements VideoColumns {
            /**
             * Get the content:// style URI for the video media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the video media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("video")
                        .appendPath("media").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/video";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = TITLE;
        }

        /**
         * This class provides utility methods to obtain thumbnails for various
         * {@link Video} items.
         */
        public static class Thumbnails implements BaseColumns {
            /**
             * Cancel any outstanding {@link #getThumbnail} requests, causing
             * them to return by throwing a {@link OperationCanceledException}.
             * <p>
             * This method has no effect on
             * {@link ContentResolver#loadThumbnail} calls, since they provide
             * their own {@link CancellationSignal}.
             *
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static void cancelThumbnailRequest(ContentResolver cr, long origId) {
                final Uri uri = ContentUris.withAppendedId(
                        Video.Media.EXTERNAL_CONTENT_URI, origId);
                InternalThumbnails.cancelThumbnail(cr, uri);
            }

            /**
             * Return thumbnail representing a specific video item. If a
             * thumbnail doesn't exist, this method will block until it's
             * generated. Callers are responsible for their own in-memory
             * caching of returned values.
             *
             * @param videoId the video item to obtain a thumbnail for.
             * @param kind optimal thumbnail size desired.
             * @return decoded thumbnail, or {@code null} if problem was
             *         encountered.
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static Bitmap getThumbnail(ContentResolver cr, long videoId, int kind,
                    BitmapFactory.Options options) {
                final Uri uri = ContentUris.withAppendedId(
                        Video.Media.EXTERNAL_CONTENT_URI, videoId);
                return InternalThumbnails.getThumbnail(cr, uri, kind, options);
            }

            /**
             * Cancel any outstanding {@link #getThumbnail} requests, causing
             * them to return by throwing a {@link OperationCanceledException}.
             * <p>
             * This method has no effect on
             * {@link ContentResolver#loadThumbnail} calls, since they provide
             * their own {@link CancellationSignal}.
             *
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static void cancelThumbnailRequest(ContentResolver cr, long videoId,
                    long groupId) {
                cancelThumbnailRequest(cr, videoId);
            }

            /**
             * Return thumbnail representing a specific video item. If a
             * thumbnail doesn't exist, this method will block until it's
             * generated. Callers are responsible for their own in-memory
             * caching of returned values.
             *
             * @param videoId the video item to obtain a thumbnail for.
             * @param kind optimal thumbnail size desired.
             * @return decoded thumbnail, or {@code null} if problem was
             *         encountered.
             * @deprecated Callers should migrate to using
             *             {@link ContentResolver#loadThumbnail}, since it
             *             offers richer control over requested thumbnail sizes
             *             and cancellation behavior.
             */
            @Deprecated
            public static Bitmap getThumbnail(ContentResolver cr, long videoId, long groupId,
                    int kind, BitmapFactory.Options options) {
                return getThumbnail(cr, videoId, kind, options);
            }

            /**
             * Get the content:// style URI for the image media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the image media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return AUTHORITY_URI.buildUpon().appendPath(volumeName).appendPath("video")
                        .appendPath("thumbnails").build();
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = "video_id ASC";

            /**
             * Path to the thumbnail file on disk.
             * <p>
             * Type: TEXT
             *
             * @deprecated Apps may not have filesystem permissions to directly
             *             access this path. Instead of trying to open this path
             *             directly, apps should use
             *             {@link ContentResolver#openFileDescriptor(Uri, String)}
             *             to gain access. This value will always be
             *             {@code NULL} for apps targeting
             *             {@link android.os.Build.VERSION_CODES#Q} or higher.
             */
            @Deprecated
            public static final String DATA = "_data";

            /**
             * The original image for the thumbnal
             * <P>Type: INTEGER (ID from Video table)</P>
             */
            public static final String VIDEO_ID = "video_id";

            /**
             * The kind of the thumbnail
             * <P>Type: INTEGER (One of the values below)</P>
             */
            public static final String KIND = "kind";

            public static final int MINI_KIND = ThumbnailConstants.MINI_KIND;
            public static final int FULL_SCREEN_KIND = ThumbnailConstants.FULL_SCREEN_KIND;
            public static final int MICRO_KIND = ThumbnailConstants.MICRO_KIND;

            /**
             * The width of the thumbnal
             * <P>Type: INTEGER (long)</P>
             */
            public static final String WIDTH = "width";

            /**
             * The height of the thumbnail
             * <P>Type: INTEGER (long)</P>
             */
            public static final String HEIGHT = "height";
        }
    }

    /**
     * Return list of all volume names currently available. This includes a
     * unique name for each shared storage device that is currently mounted.
     * <p>
     * Each name can be passed to APIs like
     * {@link MediaStore.Images.Media#getContentUri(String)} to query media at
     * that location.
     */
    public static @NonNull Set<String> getAllVolumeNames(Context context) {
        final StorageManager sm = context.getSystemService(StorageManager.class);
        final Set<String> volumeNames = new ArraySet<>();
        volumeNames.add(VOLUME_INTERNAL);
        for (VolumeInfo vi : sm.getVolumes()) {
            if (vi.isVisibleForUser(UserHandle.myUserId()) && vi.isMountedReadable()) {
                if (vi.isPrimary()) {
                    volumeNames.add(VOLUME_EXTERNAL);
                } else {
                    volumeNames.add(vi.getFsUuid());
                }
            }
        }
        return volumeNames;
    }

    /**
     * Return the volume name that the given {@link Uri} references.
     */
    public static @NonNull String getVolumeName(@NonNull Uri uri) {
        final List<String> segments = uri.getPathSegments();
        if (uri.getAuthority().equals(AUTHORITY) && segments != null && segments.size() > 0) {
            return segments.get(0);
        } else {
            throw new IllegalArgumentException("Missing volume name: " + uri);
        }
    }

    /** {@hide} */
    public static @NonNull String checkArgumentVolumeName(@NonNull String volumeName) {
        if (TextUtils.isEmpty(volumeName)) {
            throw new IllegalArgumentException();
        }

        if (VOLUME_INTERNAL.equals(volumeName)) {
            return volumeName;
        } else if (VOLUME_EXTERNAL.equals(volumeName)) {
            return volumeName;
        }

        // When not one of the well-known values above, it must be a hex UUID
        for (int i = 0; i < volumeName.length(); i++) {
            final char c = volumeName.charAt(i);
            if (('a' <= c && c <= 'f') || ('A' <= c && c <= 'F')
                    || ('0' <= c && c <= '9') || (c == '-')) {
                continue;
            } else {
                throw new IllegalArgumentException("Invalid volume name: " + volumeName);
            }
        }
        return volumeName;
    }

    /** {@hide} */
    public static @NonNull File getVolumePath(@NonNull String volumeName)
            throws FileNotFoundException {
        if (TextUtils.isEmpty(volumeName)) {
            throw new IllegalArgumentException();
        }

        if (VOLUME_INTERNAL.equals(volumeName)) {
            return Environment.getDataDirectory();
        } else if (VOLUME_EXTERNAL.equals(volumeName)) {
            return Environment.getExternalStorageDirectory();
        }

        final StorageManager sm = AppGlobals.getInitialApplication()
                .getSystemService(StorageManager.class);
        for (VolumeInfo vi : sm.getVolumes()) {
            if (Objects.equals(vi.getFsUuid(), volumeName)) {
                final File path = vi.getPathForUser(UserHandle.myUserId());
                if (path != null) {
                    return path;
                } else {
                    throw new FileNotFoundException("Failed to find path for " + vi);
                }
            }
        }
        throw new FileNotFoundException("Failed to find path for " + volumeName);
    }

    /**
     * Uri for querying the state of the media scanner.
     */
    public static Uri getMediaScannerUri() {
        return AUTHORITY_URI.buildUpon().appendPath("none").appendPath("media_scanner").build();
    }

    /**
     * Name of current volume being scanned by the media scanner.
     */
    public static final String MEDIA_SCANNER_VOLUME = "volume";

    /**
     * Name of the file signaling the media scanner to ignore media in the containing directory
     * and its subdirectories. Developers should use this to avoid application graphics showing
     * up in the Gallery and likewise prevent application sounds and music from showing up in
     * the Music app.
     */
    public static final String MEDIA_IGNORE_FILENAME = ".nomedia";

    /**
     * Get the media provider's version.
     * Applications that import data from the media provider into their own caches
     * can use this to detect that the media provider changed, and reimport data
     * as needed. No other assumptions should be made about the meaning of the version.
     * @param context Context to use for performing the query.
     * @return A version string, or null if the version could not be determined.
     */
    public static String getVersion(Context context) {
        final Uri uri = AUTHORITY_URI.buildUpon().appendPath("none").appendPath("version").build();
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        }
        return null;
    }

    /**
     * Return a {@link DocumentsProvider} Uri that is an equivalent to the given
     * {@link MediaStore} Uri.
     * <p>
     * This allows apps with Storage Access Framework permissions to convert
     * between {@link MediaStore} and {@link DocumentsProvider} Uris that refer
     * to the same underlying item. Note that this method doesn't grant any new
     * permissions; callers must already hold permissions obtained with
     * {@link Intent#ACTION_OPEN_DOCUMENT} or related APIs.
     *
     * @param mediaUri The {@link MediaStore} Uri to convert.
     * @return An equivalent {@link DocumentsProvider} Uri. Returns {@code null}
     *         if no equivalent was found.
     * @see #getMediaUri(Context, Uri)
     */
    public static Uri getDocumentUri(Context context, Uri mediaUri) {
        final ContentResolver resolver = context.getContentResolver();
        final List<UriPermission> uriPermissions = resolver.getPersistedUriPermissions();

        try (ContentProviderClient client = resolver.acquireContentProviderClient(AUTHORITY)) {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, mediaUri);
            in.putParcelableList(DocumentsContract.EXTRA_URI_PERMISSIONS, uriPermissions);
            final Bundle out = client.call(GET_DOCUMENT_URI_CALL, null, in);
            return out.getParcelable(DocumentsContract.EXTRA_URI);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return a {@link MediaStore} Uri that is an equivalent to the given
     * {@link DocumentsProvider} Uri.
     * <p>
     * This allows apps with Storage Access Framework permissions to convert
     * between {@link MediaStore} and {@link DocumentsProvider} Uris that refer
     * to the same underlying item. Note that this method doesn't grant any new
     * permissions; callers must already hold permissions obtained with
     * {@link Intent#ACTION_OPEN_DOCUMENT} or related APIs.
     *
     * @param documentUri The {@link DocumentsProvider} Uri to convert.
     * @return An equivalent {@link MediaStore} Uri. Returns {@code null} if no
     *         equivalent was found.
     * @see #getDocumentUri(Context, Uri)
     */
    public static Uri getMediaUri(Context context, Uri documentUri) {
        final ContentResolver resolver = context.getContentResolver();
        final List<UriPermission> uriPermissions = resolver.getPersistedUriPermissions();

        try (ContentProviderClient client = resolver.acquireContentProviderClient(AUTHORITY)) {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, documentUri);
            in.putParcelableList(DocumentsContract.EXTRA_URI_PERMISSIONS, uriPermissions);
            final Bundle out = client.call(GET_MEDIA_URI_CALL, null, in);
            return out.getParcelable(DocumentsContract.EXTRA_URI);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Calculate size of media contributed by given package under the calling
     * user. The meaning of "contributed" means it won't automatically be
     * deleted when the app is uninstalled.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.CLEAR_APP_USER_DATA)
    public static @BytesLong long getContributedMediaSize(Context context, String packageName,
            UserHandle user) throws IOException {
        final UserManager um = context.getSystemService(UserManager.class);
        if (um.isUserUnlocked(user) && um.isUserRunning(user)) {
            try {
                final ContentResolver resolver = context
                        .createPackageContextAsUser(packageName, 0, user).getContentResolver();
                final Bundle in = new Bundle();
                in.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
                final Bundle out = resolver.call(AUTHORITY, GET_CONTRIBUTED_MEDIA_CALL, null, in);
                return out.getLong(Intent.EXTRA_INDEX);
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            throw new IOException("User " + user + " must be unlocked and running");
        }
    }

    /**
     * Delete all media contributed by given package under the calling user. The
     * meaning of "contributed" means it won't automatically be deleted when the
     * app is uninstalled.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.CLEAR_APP_USER_DATA)
    public static void deleteContributedMedia(Context context, String packageName,
            UserHandle user) throws IOException {
        final UserManager um = context.getSystemService(UserManager.class);
        if (um.isUserUnlocked(user) && um.isUserRunning(user)) {
            try {
                final ContentResolver resolver = context
                        .createPackageContextAsUser(packageName, 0, user).getContentResolver();
                final Bundle in = new Bundle();
                in.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
                resolver.call(AUTHORITY, DELETE_CONTRIBUTED_MEDIA_CALL, null, in);
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            throw new IOException("User " + user + " must be unlocked and running");
        }
    }
}
