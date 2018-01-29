/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.media.update.ApiLoader;
import android.media.update.MediaItem2Provider;
import android.os.Bundle;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class with information on a single media item with the metadata information.
 * Media item are application dependent so we cannot guarantee that they contain the right values.
 * <p>
 * When it's sent to a controller or browser, it's anonymized and data descriptor wouldn't be sent.
 * <p>
 * This object isn't a thread safe.
 * @hide
 */
public class MediaItem2 {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag=true, value = { FLAG_BROWSABLE, FLAG_PLAYABLE })
    public @interface Flags { }

    /**
     * Flag: Indicates that the item has children of its own.
     */
    public static final int FLAG_BROWSABLE = 1 << 0;

    /**
     * Flag: Indicates that the item is playable.
     * <p>
     * The id of this item may be passed to
     * {@link MediaController2#playFromMediaId(String, Bundle)}
     */
    public static final int FLAG_PLAYABLE = 1 << 1;

    private final MediaItem2Provider mProvider;

    /**
     * Create a new media item.
     *
     * @param mediaId id of this item. It must be unique whithin this app
     * @param metadata metadata with the media id.
     * @param flags The flags for this item.
     */
    public MediaItem2(@NonNull Context context, @NonNull String mediaId,
            @NonNull DataSourceDesc dsd, @Nullable MediaMetadata2 metadata,
            @Flags int flags) {
        mProvider = ApiLoader.getProvider(context).createMediaItem2Provider(
                context, this, mediaId, dsd, metadata, flags);
    }

    /**
     * Create a new media item
     * @hide
     */
    @SystemApi
    public MediaItem2(MediaItem2Provider provider) {
        mProvider = provider;
    }

    /**
     * Return this object as a bundle to share between processes.
     *
     * @return a new bundle instance
     */
    public Bundle toBundle() {
        // TODO(jaewan): Fill here when we rebase.
        return mProvider.toBundle_impl();
    }

    public static MediaItem2 fromBundle(Context context, Bundle bundle) {
        return ApiLoader.getProvider(context).fromBundle_MediaItem2(context, bundle);
    }

    public String toString() {
        return mProvider.toString_impl();
    }

    /**
     * Gets the flags of the item.
     */
    public @Flags int getFlags() {
        return mProvider.getFlags_impl();
    }

    /**
     * Returns whether this item is browsable.
     * @see #FLAG_BROWSABLE
     */
    public boolean isBrowsable() {
        return mProvider.isBrowsable_impl();
    }

    /**
     * Returns whether this item is playable.
     * @see #FLAG_PLAYABLE
     */
    public boolean isPlayable() {
        return mProvider.isPlayable_impl();
    }

    /**
     * Set a metadata. Metadata shouldn't be {@code null} and its id should be match
     * with this instance's id.
     *
     * @param metadata metadata to update
     */
    public void setMetadata(@NonNull MediaMetadata2 metadata) {
        mProvider.setMetadata_impl(metadata);
    }

    /**
     * Returns the metadata of the media.
     */
    public @NonNull MediaMetadata2 getMetadata() {
        return mProvider.getMetadata_impl();
    }

    /**
     * Returns the media id in the {@link MediaMetadata2} for this item.
     * @see MediaMetadata2#METADATA_KEY_MEDIA_ID
     */
    public @Nullable String getMediaId() {
        return mProvider.getMediaId_impl();
    }

    /**
     * Return the {@link DataSourceDesc}
     * <p>
     * Can be {@code null} if the MediaItem2 came from another process and anonymized
     *
     * @return data source descriptor
     */
    public @Nullable DataSourceDesc getDataSourceDesc() {
        return mProvider.getDataSourceDesc_impl();
    }
}
