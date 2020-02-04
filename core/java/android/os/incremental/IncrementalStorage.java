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

package android.os.incremental;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Provides operations on an Incremental File System directory, using IncrementalServiceNative.
 * Example usage:
 *
 * <blockquote><pre>
 * IncrementalManager manager = (IncrementalManager) getSystemService(Context.INCREMENTAL_SERVICE);
 * IncrementalStorage storage = manager.openStorage("/path/to/incremental/dir");
 * storage.makeDirectory("subdir");
 * </pre></blockquote>
 *
 * @hide
 */
public final class IncrementalStorage {
    private static final String TAG = "IncrementalStorage";
    private final int mId;
    private final IIncrementalManagerNative mService;


    public IncrementalStorage(@NonNull IIncrementalManagerNative is, int id) {
        mService = is;
        mId = id;
    }

    public int getId() {
        return mId;
    }

    /**
     * Temporarily bind-mounts the current storage directory to a target directory. The bind-mount
     * will NOT be preserved between device reboots.
     *
     * @param targetPath Absolute path to the target directory.
     */
    public void bind(@NonNull String targetPath) throws IOException {
        bind("", targetPath);
    }

    /**
     * Temporarily bind-mounts a subdir under the current storage directory to a target directory.
     * The bind-mount will NOT be preserved between device reboots.
     *
     * @param sourcePath Source path as a relative path under current storage
     *                   directory.
     * @param targetPath Absolute path to the target directory.
     */
    public void bind(@NonNull String sourcePath, @NonNull String targetPath)
            throws IOException {
        try {
            int res = mService.makeBindMount(mId, sourcePath, targetPath,
                    IIncrementalManagerNative.BIND_TEMPORARY);
            if (res < 0) {
                throw new IOException("bind() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }


    /**
     * Permanently bind-mounts the current storage directory to a target directory. The bind-mount
     * WILL be preserved between device reboots.
     *
     * @param targetPath Absolute path to the target directory.
     */
    public void bindPermanent(@NonNull String targetPath) throws IOException {
        bindPermanent("", targetPath);
    }

    /**
     * Permanently bind-mounts a subdir under the current storage directory to a target directory.
     * The bind-mount WILL be preserved between device reboots.
     *
     * @param sourcePath Relative path under the current storage directory.
     * @param targetPath Absolute path to the target directory.
     */
    public void bindPermanent(@NonNull String sourcePath, @NonNull String targetPath)
            throws IOException {
        try {
            int res = mService.makeBindMount(mId, sourcePath, targetPath,
                    IIncrementalManagerNative.BIND_PERMANENT);
            if (res < 0) {
                throw new IOException("bind() permanent failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unbinds a bind mount.
     *
     * @param targetPath Absolute path to the target directory.
     */
    public void unBind(@NonNull String targetPath) throws IOException {
        try {
            int res = mService.deleteBindMount(mId, targetPath);
            if (res < 0) {
                throw new IOException("unbind() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a sub-directory under the current storage directory.
     *
     * @param path Relative path of the sub-directory, e.g., "subdir"
     */
    public void makeDirectory(@NonNull String path) throws IOException {
        try {
            int res = mService.makeDirectory(mId, path);
            if (res < 0) {
                throw new IOException("makeDirectory() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a sub-directory under the current storage directory. If its parent dirs do not exist,
     * create the parent dirs as well.
     *
     * @param path Full path.
     */
    public void makeDirectories(@NonNull String path) throws IOException {
        try {
            int res = mService.makeDirectories(mId, path);
            if (res < 0) {
                throw new IOException("makeDirectory() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a file under the current storage directory.
     *
     * @param path             Relative path of the new file.
     * @param size             Size of the new file in bytes.
     * @param metadata         Metadata bytes.
     */
    public void makeFile(@NonNull String path, long size, @Nullable UUID id,
            @Nullable byte[] metadata, int hashAlgorithm, @Nullable byte[] rootHash,
            @Nullable byte[] additionalData, @Nullable byte[] signature) throws IOException {
        try {
            if (id == null && metadata == null) {
                throw new IOException("File ID and metadata cannot both be null");
            }
            final IncrementalNewFileParams params = new IncrementalNewFileParams();
            params.size = size;
            params.metadata = (metadata == null ? new byte[0] : metadata);
            params.fileId = idToBytes(id);
            if (hashAlgorithm != 0 || signature != null) {
                params.signature = new IncrementalSignature();
                params.signature.hashAlgorithm = hashAlgorithm;
                params.signature.rootHash = rootHash;
                params.signature.additionalData = additionalData;
                params.signature.signature = signature;
            }
            int res = mService.makeFile(mId, path, params);
            if (res != 0) {
                throw new IOException("makeFile() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a file in Incremental storage. The content of the file is mapped from a range inside
     * a source file in the same storage.
     *
     * @param destPath           Target full path.
     * @param sourcePath         Source full path.
     * @param rangeStart         Starting offset (in bytes) in the source file.
     * @param rangeEnd           Ending offset (in bytes) in the source file.
     */
    public void makeFileFromRange(@NonNull String destPath,
            @NonNull String sourcePath, long rangeStart, long rangeEnd) throws IOException {
        try {
            int res = mService.makeFileFromRange(mId, destPath, sourcePath,
                    rangeStart, rangeEnd);
            if (res < 0) {
                throw new IOException("makeFileFromRange() failed, errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a hard-link between two paths, which can be under different storages but in the same
     * Incremental File System.
     *
     * @param sourcePath    The absolute path of the source.
     * @param destStorage   The target storage of the link target.
     * @param destPath      The absolute path of the target.
     */
    public void makeLink(@NonNull String sourcePath, IncrementalStorage destStorage,
            @NonNull String destPath) throws IOException {
        try {
            int res = mService.makeLink(mId, sourcePath, destStorage.getId(),
                    destPath);
            if (res < 0) {
                throw new IOException("makeLink() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes a hard-link under the current storage directory.
     *
     * @param path The absolute path of the target.
     */
    public void unlink(@NonNull String path) throws IOException {
        try {
            int res = mService.unlink(mId, path);
            if (res < 0) {
                throw new IOException("unlink() failed with errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Rename an old file name to a new file name under the current storage directory.
     *
     * @param sourcepath Old file path as a full path to the storage directory.
     * @param destpath   New file path as a full path to the storage directory.
     */
    public void moveFile(@NonNull String sourcepath,
            @NonNull String destpath) throws IOException {
        //TODO(zyy): implement using rename(2) when confirmed that IncFS supports it.
        try {
            int res = mService.makeLink(mId, sourcepath, mId, destpath);
            if (res < 0) {
                throw new IOException("moveFile() failed at makeLink(), errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        try {
            mService.unlink(mId, sourcepath);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Move a directory, which is bind-mounted to a given storage, to a new location. The bind mount
     * will be persistent between reboots.
     *
     * @param sourcePath The old path of the directory as an absolute path.
     * @param destPath   The new path of the directory as an absolute path, expected to already
     *                   exist.
     */
    public void moveDir(@NonNull String sourcePath, @NonNull String destPath) throws IOException {
        if (!new File(destPath).exists()) {
            throw new IOException("moveDir() requires that destination dir already exists.");
        }
        try {
            int res = mService.makeBindMount(mId, sourcePath, destPath,
                    IIncrementalManagerNative.BIND_PERMANENT);
            if (res < 0) {
                throw new IOException("moveDir() failed at making bind mount, errno " + -res);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        try {
            mService.deleteBindMount(mId, sourcePath);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Checks whether a file under the current storage directory is fully loaded.
     *
     * @param path The relative path of the file.
     * @return True if the file is fully loaded.
     */
    public boolean isFileFullyLoaded(@NonNull String path) {
        return isFileRangeLoaded(path, 0, -1);
    }

    /**
     * Checks whether a range in a file if loaded.
     *
     * @param path The relative path of the file.
     * @param start            The starting offset of the range.
     * @param end              The ending offset of the range.
     * @return True if the file is fully loaded.
     */
    public boolean isFileRangeLoaded(@NonNull String path, long start, long end) {
        try {
            return mService.isFileRangeLoaded(mId, path, start, end);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    /**
     * Returns the metadata object of an IncFs File.
     *
     * @param path The relative path of the file.
     * @return Byte array that contains metadata bytes.
     */
    @Nullable
    public byte[] getFileMetadata(@NonNull String path) {
        try {
            return mService.getMetadataByPath(mId, path);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    private static final int UUID_BYTE_SIZE = 16;

    /**
     * Converts UUID to a byte array usable for Incremental API calls
     *
     * @param id The id to convert
     * @return Byte array that contains the same ID.
     */
    @NonNull
    public static byte[] idToBytes(@Nullable UUID id) {
        if (id == null) {
            return new byte[0];
        }
        final ByteBuffer buf = ByteBuffer.wrap(new byte[UUID_BYTE_SIZE]);
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        return buf.array();
    }

    /**
     * Converts UUID from a byte array usable for Incremental API calls
     *
     * @param bytes The id in byte array format, 16 bytes long
     * @return UUID constructed from the byte array.
     */
    @NonNull
    public static UUID bytesToId(byte[] bytes) throws IllegalArgumentException {
        if (bytes.length != UUID_BYTE_SIZE) {
            throw new IllegalArgumentException("Expected array of size " + UUID_BYTE_SIZE
                                               + ", got " + bytes.length);
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        long msb = buf.getLong();
        long lsb = buf.getLong();
        return new UUID(msb, lsb);
    }

    /**
     * Returns the metadata object of an IncFs File.
     *
     * @param id The file id.
     * @return Byte array that contains metadata bytes.
     */
    @Nullable
    public byte[] getFileMetadata(@NonNull UUID id) {
        try {
            final byte[] rawId = idToBytes(id);
            return mService.getMetadataById(mId, rawId);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Informs the data loader service associated with the current storage to start data loader
     *
     * @return True if data loader is successfully started.
     */
    public boolean startLoading() {
        try {
            return mService.startLoading(mId);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }
}
