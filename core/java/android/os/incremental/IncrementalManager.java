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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.DataLoaderParams;
import android.content.pm.IPackageLoadingProgressCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Provides operations to open or create an IncrementalStorage, using IIncrementalService
 * service. Example Usage:
 *
 * <blockquote><pre>
 * IncrementalManager manager = (IncrementalManager) getSystemService(Context.INCREMENTAL_SERVICE);
 * IncrementalStorage storage = manager.openStorage("/path/to/incremental/dir");
 * </pre></blockquote>
 *
 * @hide
 */
@SystemService(Context.INCREMENTAL_SERVICE)
public final class IncrementalManager {
    private static final String TAG = "IncrementalManager";

    private static final String ALLOWED_PROPERTY = "incremental.allowed";

    public static final int CREATE_MODE_TEMPORARY_BIND =
            IIncrementalService.CREATE_MODE_TEMPORARY_BIND;
    public static final int CREATE_MODE_PERMANENT_BIND =
            IIncrementalService.CREATE_MODE_PERMANENT_BIND;
    public static final int CREATE_MODE_CREATE =
            IIncrementalService.CREATE_MODE_CREATE;
    public static final int CREATE_MODE_OPEN_EXISTING =
            IIncrementalService.CREATE_MODE_OPEN_EXISTING;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CREATE_MODE_"}, value = {
            CREATE_MODE_TEMPORARY_BIND,
            CREATE_MODE_PERMANENT_BIND,
            CREATE_MODE_CREATE,
            CREATE_MODE_OPEN_EXISTING,
    })
    public @interface CreateMode {
    }

    private final @Nullable IIncrementalService mService;

    private final LoadingProgressCallbacks mLoadingProgressCallbacks =
            new LoadingProgressCallbacks();

    public IncrementalManager(IIncrementalService service) {
        mService = service;
    }

    /**
     * Opens or create an Incremental File System mounted directory and returns an
     * IncrementalStorage object.
     *
     * @param path                Absolute path to mount Incremental File System on.
     * @param params              IncrementalDataLoaderParams object to configure data loading.
     * @param createMode          Mode for opening an old Incremental File System mount or creating
     *                            a new mount.
     * @return IncrementalStorage object corresponding to the mounted directory.
     */
    @Nullable
    public IncrementalStorage createStorage(@NonNull String path,
            @NonNull DataLoaderParams params,
            @CreateMode int createMode) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(params);
        try {
            final int id = mService.createStorage(path, params.getData(), createMode);
            if (id < 0) {
                return null;
            }
            return new IncrementalStorage(mService, id);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens an existing Incremental File System mounted directory and returns an IncrementalStorage
     * object.
     *
     * @param path Absolute target path that Incremental File System has been mounted on.
     * @return IncrementalStorage object corresponding to the mounted directory.
     */
    @Nullable
    public IncrementalStorage openStorage(@NonNull String path) {
        try {
            final int id = mService.openStorage(path);
            if (id < 0) {
                return null;
            }
            final IncrementalStorage storage = new IncrementalStorage(mService, id);
            return storage;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens or creates an IncrementalStorage that is linked to another IncrementalStorage.
     *
     * @return IncrementalStorage object corresponding to the linked storage.
     */
    @Nullable
    public IncrementalStorage createStorage(@NonNull String path,
            @NonNull IncrementalStorage linkedStorage, @CreateMode int createMode) {
        try {
            final int id = mService.createLinkedStorage(
                    path, linkedStorage.getId(), createMode);
            if (id < 0) {
                return null;
            }
            return new IncrementalStorage(mService, id);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set up an app's code path. The expected outcome of this method is:
     * 1) The actual apk directory under /data/incremental is bind-mounted to the parent directory
     * of {@code afterCodeFile}.
     * 2) All the files under {@code beforeCodeFile} will show up under {@code afterCodeFile}.
     *
     * @param beforeCodeFile Path that is currently bind-mounted and have APKs under it.
     *                       Should no longer have any APKs after this method is called.
     *                       Example: /data/app/vmdl*tmp
     * @param afterCodeFile Path that should will have APKs after this method is called. Its parent
     *                      directory should be bind-mounted to a directory under /data/incremental.
     *                      Example: /data/app/~~[randomStringA]/[packageName]-[randomStringB]
     * @throws IllegalArgumentException
     * @throws IOException
     * TODO(b/147371381): add unit tests
     */
    public void renameCodePath(File beforeCodeFile, File afterCodeFile)
            throws IllegalArgumentException, IOException {
        final File beforeCodeAbsolute = beforeCodeFile.getAbsoluteFile();
        final IncrementalStorage apkStorage = openStorage(beforeCodeAbsolute.toString());
        if (apkStorage == null) {
            throw new IllegalArgumentException("Not an Incremental path: " + beforeCodeAbsolute);
        }
        final String targetStorageDir = afterCodeFile.getAbsoluteFile().getParent();
        final IncrementalStorage linkedApkStorage =
                createStorage(targetStorageDir, apkStorage,
                        IncrementalManager.CREATE_MODE_CREATE
                                | IncrementalManager.CREATE_MODE_PERMANENT_BIND);
        if (linkedApkStorage == null) {
            throw new IOException("Failed to create linked storage at dir: " + targetStorageDir);
        }
        try {
            final String afterCodePathName = afterCodeFile.getName();
            linkFiles(apkStorage, beforeCodeAbsolute, "", linkedApkStorage, afterCodePathName);
            apkStorage.unBind(beforeCodeAbsolute.toString());
        } catch (Exception e) {
            linkedApkStorage.unBind(targetStorageDir);
            throw e;
        }
    }

    /**
     * Recursively set up directories and link all the files from source storage to target storage.
     *
     * @param sourceStorage The storage that has all the files and directories underneath.
     * @param sourceAbsolutePath The absolute path of the directory that holds all files and dirs.
     * @param sourceRelativePath The relative path on the source directory, e.g., "" or "lib".
     * @param targetStorage The target storage that will have the same files and directories.
     * @param targetRelativePath The relative path to the directory on the target storage that
     *                           should have all the files and dirs underneath,
     *                           e.g., "packageName-random".
     * @throws IOException When makeDirectory or makeLink fails on the Incremental File System.
     */
    private void linkFiles(IncrementalStorage sourceStorage, File sourceAbsolutePath,
            String sourceRelativePath, IncrementalStorage targetStorage,
            String targetRelativePath) throws IOException {
        final Path sourceBase = sourceAbsolutePath.toPath().resolve(sourceRelativePath);
        final Path targetRelative = Paths.get(targetRelativePath);
        Files.walkFileTree(sourceAbsolutePath.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                final Path relativeDir = sourceBase.relativize(dir);
                targetStorage.makeDirectory(targetRelative.resolve(relativeDir).toString());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                final Path relativeFile = sourceBase.relativize(file);
                sourceStorage.makeLink(
                        file.toAbsolutePath().toString(), targetStorage,
                        targetRelative.resolve(relativeFile).toString());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Checks if Incremental feature is enabled on this device.
     */
    public static boolean isFeatureEnabled() {
        return nativeIsEnabled();
    }

    /**
     * Checks if device supports V2 calls (e.g. PerUid).
     */
    public static boolean isV2Available() {
        return nativeIsV2Available();
    }

    /**
     * Checks if Incremental installations are allowed.
     * A developer can disable Incremental installations by setting the property.
     */
    public static boolean isAllowed() {
        return isFeatureEnabled() && android.os.SystemProperties.getBoolean(ALLOWED_PROPERTY, true);
    }

    /**
     * Checks if path is mounted on Incremental File System.
     */
    public static boolean isIncrementalPath(@NonNull String path) {
        return nativeIsIncrementalPath(path);
    }

    /**
     * Returns raw signature for file if it's on Incremental File System.
     * Unsafe, use only if you are sure what you are doing.
     */
    public static @Nullable byte[] unsafeGetFileSignature(@NonNull String path) {
        return nativeUnsafeGetFileSignature(path);
    }

    /**
     * Closes a storage specified by the absolute path. If the path is not Incremental, do nothing.
     * Unbinds the target dir and deletes the corresponding storage instance.
     * Deletes the package name and associated storage id from maps.
     */
    public void onPackageRemoved(@NonNull File codeFile) {
        try {
            final String codePath = codeFile.getAbsolutePath();
            final IncrementalStorage storage = openStorage(codePath);
            if (storage == null) {
                return;
            }
            mLoadingProgressCallbacks.cleanUpCallbacks(storage);
            unregisterHealthListener(codePath);

            // Parent since we bind-mount a folder one level above.
            mService.deleteBindMount(storage.getId(), codeFile.getParent());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when a new callback wants to listen to the loading progress of an installed package.
     * Increment the count of callbacks associated to the corresponding storage.
     * Only register storage listener if there hasn't been any existing callback on the storage yet.
     * @param codePath Path of the installed package. This path is on an Incremental Storage.
     * @param callback To report loading progress to.
     * @return True if the package name and associated storage id are valid. False otherwise.
     */
    public boolean registerLoadingProgressCallback(@NonNull String codePath,
            @NonNull IPackageLoadingProgressCallback callback) {
        final IncrementalStorage storage = openStorage(codePath);
        if (storage == null) {
            // storage does not exist, package not installed
            return false;
        }
        return mLoadingProgressCallbacks.registerCallback(storage, callback);
    }

    /**
     * Called to stop all listeners from listening to loading progress of an installed package.
     * @param codePath Path of the installed package
     */
    public void unregisterLoadingProgressCallbacks(@NonNull String codePath) {
        final IncrementalStorage storage = openStorage(codePath);
        if (storage == null) {
            // storage does not exist, package not installed
            return;
        }
        mLoadingProgressCallbacks.cleanUpCallbacks(storage);
    }

    private static class LoadingProgressCallbacks extends IStorageLoadingProgressListener.Stub {
        @GuardedBy("mCallbacks")
        private final SparseArray<RemoteCallbackList<IPackageLoadingProgressCallback>> mCallbacks =
                new SparseArray<>();

        public void cleanUpCallbacks(@NonNull IncrementalStorage storage) {
            final int storageId = storage.getId();
            final RemoteCallbackList<IPackageLoadingProgressCallback> callbacksForStorage;
            synchronized (mCallbacks) {
                callbacksForStorage = mCallbacks.removeReturnOld(storageId);
            }
            if (callbacksForStorage == null) {
                return;
            }
            // Unregister all existing callbacks on this storage
            callbacksForStorage.kill();
            storage.unregisterLoadingProgressListener();
        }

        // TODO(b/165841827): handle reboot and app update
        public boolean registerCallback(@NonNull IncrementalStorage storage,
                @NonNull IPackageLoadingProgressCallback callback) {
            final int storageId = storage.getId();
            synchronized (mCallbacks) {
                RemoteCallbackList<IPackageLoadingProgressCallback> callbacksForStorage =
                        mCallbacks.get(storageId);
                if (callbacksForStorage == null) {
                    callbacksForStorage = new RemoteCallbackList<>();
                    mCallbacks.put(storageId, callbacksForStorage);
                }
                // Registration in RemoteCallbackList needs to be done first, such that when events
                // come from Incremental Service, the callback is already registered
                callbacksForStorage.register(callback);
                if (callbacksForStorage.getRegisteredCallbackCount() > 1) {
                    // already listening for progress for this storage
                    return true;
                }
            }
            return storage.registerLoadingProgressListener(this);
        }

        public boolean unregisterCallback(@NonNull IncrementalStorage storage,
                @NonNull IPackageLoadingProgressCallback callback) {
            final int storageId = storage.getId();
            final RemoteCallbackList<IPackageLoadingProgressCallback> callbacksForStorage;
            synchronized (mCallbacks) {
                callbacksForStorage = mCallbacks.get(storageId);
                if (callbacksForStorage == null) {
                    // no callback has ever been registered on this storage
                    return false;
                }
                if (!callbacksForStorage.unregister(callback)) {
                    // the callback was not registered
                    return false;
                }
                if (callbacksForStorage.getRegisteredCallbackCount() > 0) {
                    // other callbacks are still listening on this storage
                    return true;
                }
                mCallbacks.delete(storageId);
            }
            // stop listening for this storage
            return storage.unregisterLoadingProgressListener();
        }

        @Override
        public void onStorageLoadingProgressChanged(int storageId, float progress) {
            final RemoteCallbackList<IPackageLoadingProgressCallback> callbacksForStorage;
            synchronized (mCallbacks) {
                callbacksForStorage = mCallbacks.get(storageId);
            }
            if (callbacksForStorage == null) {
                // no callback has ever been registered on this storage
                return;
            }
            final int n = callbacksForStorage.beginBroadcast();
            // RemoteCallbackList use ArrayMap internally and it's safe to iterate this way
            for (int i = 0; i < n; i++) {
                final IPackageLoadingProgressCallback callback =
                        callbacksForStorage.getBroadcastItem(i);
                try {
                    callback.onPackageLoadingProgressChanged(progress);
                } catch (RemoteException ignored) {
                }
            }
            callbacksForStorage.finishBroadcast();
        }
    }

    /**
     * Specify the health check params and listener for listening to Incremental Storage health
     * status changes. Notice that this will overwrite the previously registered listener.
     * @param codePath Path of the installed package. This path is on an Incremental Storage.
     * @param healthCheckParams The params for health state change timeouts.
     * @param listener To report health status change.
     * @return True if listener was successfully registered.
     */
    public boolean registerHealthListener(@NonNull String codePath,
            @NonNull StorageHealthCheckParams healthCheckParams,
            @NonNull IStorageHealthListener.Stub listener) {
        final IncrementalStorage storage = openStorage(codePath);
        if (storage == null) {
            // storage does not exist, package not installed
            return false;
        }
        return storage.registerStorageHealthListener(healthCheckParams, listener);
    }

    /**
     * Stop listening to health status changes on an Incremental Storage.
     * @param codePath Path of the installed package. This path is on an Incremental Storage.
     */
    public void unregisterHealthListener(@NonNull String codePath) {
        final IncrementalStorage storage = openStorage(codePath);
        if (storage == null) {
            // storage does not exist, package not installed
            return;
        }
        storage.unregisterStorageHealthListener();
    }

    /* Native methods */
    private static native boolean nativeIsEnabled();
    private static native boolean nativeIsV2Available();
    private static native boolean nativeIsIncrementalPath(@NonNull String path);
    private static native byte[] nativeUnsafeGetFileSignature(@NonNull String path);
}
