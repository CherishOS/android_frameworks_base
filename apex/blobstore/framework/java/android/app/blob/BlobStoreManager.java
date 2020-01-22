/*
 * Copyright 2019 The Android Open Source Project
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
package android.app.blob;

import android.annotation.BytesLong;
import android.annotation.CallbackExecutor;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.IdRes;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.RemoteException;

import com.android.internal.util.function.pooled.PooledLambda;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides access to the blob store managed by the system.
 *
 * Apps can publish data blobs which might be useful for other apps on the device to be
 * managed by the system and apps that would like to access these data blobs can do so
 * by addressing them via their cryptographically secure hashes.
 *
 * TODO: More documentation.
 */
@SystemService(Context.BLOB_STORE_SERVICE)
public class BlobStoreManager {
    /** @hide */
    public static final int COMMIT_RESULT_SUCCESS = 0;
    /** @hide */
    public static final int COMMIT_RESULT_ERROR = 1;

    private final Context mContext;
    private final IBlobStoreManager mService;

    /** @hide */
    public BlobStoreManager(@NonNull Context context, @NonNull IBlobStoreManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Create a new session using the given {@link BlobHandle}, returning a unique id
     * that represents the session. Once created, the session can be opened
     * multiple times across multiple device boots.
     *
     * <p> The system may automatically destroy sessions that have not been
     * finalized (either committed or abandoned) within a reasonable period of
     * time, typically about a week.
     *
     * @param blobHandle the {@link BlobHandle} identifier for which a new session
     *                   needs to be created.
     * @return positive, non-zero unique id that represents the created session.
     *         This id remains consistent across device reboots until the
     *         session is finalized. IDs are not reused during a given boot.
     *
     * @throws IOException when there is an I/O error while creating the session.
     * @throws SecurityException when the caller is not allowed to create a session, such
     *                           as when called from an Instant app.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     * @throws IllegalStateException when a new session could not be created, such as when the
     *                               caller is trying to create too many sessions or when the
     *                               device is running low on space.
     */
    public @IntRange(from = 1) long createSession(@NonNull BlobHandle blobHandle)
            throws IOException {
        try {
            return mService.createSession(blobHandle, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open an existing session to actively perform work.
     *
     * @param sessionId a unique id obtained via {@link #createSession(BlobHandle)} that
     *                  represents a particular session.
     * @return the {@link Session} object corresponding to the {@code sessionId}.
     *
     * @throws IOException when there is an I/O error while opening the session.
     * @throws SecurityException when the caller does not own the session, or
     *                           the session does not exist or is invalid.
     */
    public @NonNull Session openSession(@IntRange(from = 1) long sessionId) throws IOException {
        try {
            return new Session(mService.openSession(sessionId, mContext.getOpPackageName()));
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete an existing session and any data that was written to that session so far.
     *
     * @param sessionId a unique id obtained via {@link #createSession(BlobHandle)} that
     *                  represents a particular session.
     *
     * @throws IOException when there is an I/O error while deleting the session.
     * @throws SecurityException when the caller does not own the session, or
     *                           the session does not exist or is invalid.
     */
    public void deleteSession(@IntRange(from = 1) long sessionId) throws IOException {
        try {
            mService.deleteSession(sessionId, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens an existing blob for reading from the blob store managed by the system.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller
     *                   wants to access.
     * @return a {@link ParcelFileDescriptor} that can be used to read the blob content.
     *
     * @throws IOException when there is an I/O while opening the blob for read.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     */
    public @NonNull ParcelFileDescriptor openBlob(@NonNull BlobHandle blobHandle)
            throws IOException {
        try {
            return mService.openBlob(blobHandle, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Acquire a lease to the blob represented by {@code blobHandle}. This lease indicates to the
     * system that the caller wants the blob to be kept around.
     *
     * <p> Any active leases will be automatically released when the blob's expiry time
     * ({@link BlobHandle#getExpiryTimeMillis()}) is elapsed.
     *
     * <p> This lease information is persisted and calling this more than once will result in
     * latest lease overriding any previous lease.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   acquire a lease for.
     * @param descriptionResId the resource id for a short description string that can be surfaced
     *                         to the user explaining what the blob is used for.
     * @param leaseExpiryTimeMillis the time in milliseconds after which the lease can be
     *                              automatically released, in {@link System#currentTimeMillis()}
     *                              timebase. If its value is {@code 0}, then the behavior of this
     *                              API is identical to {@link #acquireLease(BlobHandle, int)}
     *                              where clients have to explicitly call
     *                              {@link #releaseLease(BlobHandle)} when they don't
     *                              need the blob anymore.
     *
     * @throws IOException when there is an I/O error while acquiring a lease to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid or
     *                                  if the {@code leaseExpiryTimeMillis} is greater than the
     *                                  {@link BlobHandle#getExpiryTimeMillis()}.
     * @throws IllegalStateException when a lease could not be acquired, such as when the
     *                               caller is trying to acquire too many leases.
     *
     * @see {@link #acquireLease(BlobHandle, int)}
     */
    public void acquireLease(@NonNull BlobHandle blobHandle, @IdRes int descriptionResId,
            @CurrentTimeMillisLong long leaseExpiryTimeMillis) throws IOException {
        try {
            mService.acquireLease(blobHandle, descriptionResId, leaseExpiryTimeMillis,
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Acquire a lease to the blob represented by {@code blobHandle}. This lease indicates to the
     * system that the caller wants the blob to be kept around.
     *
     * <p> This is similar to {@link #acquireLease(BlobHandle, int, long)} except clients don't
     * have to specify the lease expiry time upfront using this API and need to explicitly
     * release the lease using {@link #releaseLease(BlobHandle)} when they no longer like to keep
     * a blob around.
     *
     * <p> Any active leases will be automatically released when the blob's expiry time
     * ({@link BlobHandle#getExpiryTimeMillis()}) is elapsed.
     *
     * <p> This lease information is persisted and calling this more than once will result in
     * latest lease overriding any previous lease.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   acquire a lease for.
     * @param descriptionResId the resource id for a short description string that can be surfaced
     *                         to the user explaining what the blob is used for.
     *
     * @throws IOException when there is an I/O error while acquiring a lease to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     * @throws IllegalStateException when a lease could not be acquired, such as when the
     *                               caller is trying to acquire too many leases.
     *
     * @see {@link #acquireLease(BlobHandle, int, long)}
     */
    public void acquireLease(@NonNull BlobHandle blobHandle, @IdRes int descriptionResId)
            throws IOException {
        acquireLease(blobHandle, descriptionResId, 0);
    }

    /**
     * Release all active leases to the blob represented by {@code blobHandle} which are
     * currently held by the caller.
     *
     * @param blobHandle the {@link BlobHandle} representing the blob that the caller wants to
     *                   release the leases for.
     *
     * @throws IOException when there is an I/O error while releasing the releases to the blob.
     * @throws SecurityException when the blob represented by the {@code blobHandle} does not
     *                           exist or the caller does not have access to it.
     * @throws IllegalArgumentException when {@code blobHandle} is invalid.
     */
    public void releaseLease(@NonNull BlobHandle blobHandle) throws IOException {
        try {
            mService.releaseLease(blobHandle, mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Represents an ongoing session of a blob's contribution to the blob store managed by the
     * system.
     *
     * <p> Clients that want to contribute a blob need to first create a {@link Session} using
     * {@link #createSession(BlobHandle)} and once the session is created, clients can open and
     * close this session multiple times using {@link #openSession(long)} and
     * {@link Session#close()} before committing it using
     * {@link Session#commit(Executor, Consumer)}, at which point system will take
     * ownership of the blob and the client can no longer make any modifications to the blob's
     * content.
     */
    public static class Session implements Closeable {
        private final IBlobStoreSession mSession;

        private Session(@NonNull IBlobStoreSession session) {
            mSession = session;
        }

        /**
         * Opens a file descriptor to write a blob into the session.
         *
         * <p> The returned file descriptor will start writing data at the requested offset
         * in the underlying file, which can be used to resume a partially
         * written file. If a valid file length is specified, the system will
         * preallocate the underlying disk space to optimize placement on disk.
         * It is strongly recommended to provide a valid file length when known.
         *
         * @param offsetBytes offset into the file to begin writing at, or 0 to
         *                    start at the beginning of the file.
         * @param lengthBytes total size of the file being written, used to
         *                    preallocate the underlying disk space, or -1 if unknown.
         *                    The system may clear various caches as needed to allocate
         *                    this space.
         *
         * @return a {@link ParcelFileDescriptor} for writing to the blob file.
         *
         * @throws IOException when there is an I/O error while opening the file to write.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to write to the file after it is
         *                               abandoned (using {@link #abandon()})
         *                               or committed (using {@link #commit})
         *                               or closed (using {@link #close()}).
         */
        public @NonNull ParcelFileDescriptor openWrite(@BytesLong long offsetBytes,
                @BytesLong long lengthBytes) throws IOException {
            try {
                final ParcelFileDescriptor pfd = mSession.openWrite(offsetBytes, lengthBytes);
                pfd.seekTo(offsetBytes);
                return pfd;
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Opens a file descriptor to read the blob content already written into this session.
         *
         * @return a {@link ParcelFileDescriptor} for reading from the blob file.
         *
         * @throws IOException when there is an I/O error while opening the file to read.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to read the file after it is
         *                               abandoned (using {@link #abandon()})
         *                               or closed (using {@link #close()}).
         */
        public @NonNull ParcelFileDescriptor openRead() throws IOException {
            try {
                return mSession.openRead();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Gets the size of the blob file that was written to the session so far.
         *
         * @return the size of the blob file so far.
         *
         * @throws IOException when there is an I/O error while opening the file to read.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to get the file size after it is
         *                               abandoned (using {@link #abandon()})
         *                               or closed (using {@link #close()}).
         */
        public @BytesLong long getSize() throws IOException {
            try {
                return mSession.getSize();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Close this session. It can be re-opened for writing/reading if it has not been
         * abandoned (using {@link #abandon}) or closed (using {@link #commit}).
         *
         * @throws IOException when there is an I/O error while closing the session.
         * @throws SecurityException when the caller is not the owner of the session.
         */
        public void close() throws IOException {
            try {
                mSession.close();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Abandon this session and delete any data that was written to this session so far.
         *
         * @throws IOException when there is an I/O error while abandoning the session.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to abandon a session which was
         *                               already finalized.
         */
        public void abandon() throws IOException {
            try {
                mSession.abandon();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Allow {@code packageName} with a particular signing certificate to access this blob
         * data once it is committed using a {@link BlobHandle} representing the blob.
         *
         * <p> This needs to be called before committing the blob using
         * {@link #commit(Executor, Consumer)}.
         *
         * @param packageName the name of the package which should be allowed to access the blob.
         * @param certificate the input bytes representing a certificate of type
         *                    {@link android.content.pm.PackageManager#CERT_INPUT_SHA256}.
         *
         * @throws IOException when there is an I/O error while changing the access.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to change access for a blob which is
         *                               already committed.
         */
        public void allowPackageAccess(@NonNull String packageName, @NonNull byte[] certificate)
                throws IOException {
            try {
                mSession.allowPackageAccess(packageName, certificate);
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns {@code true} if access has been allowed for a {@code packageName} using either
         * {@link #allowPackageAccess(String, byte[])}.
         * Otherwise, {@code false}.
         *
         * @param packageName the name of the package to check the access for.
         * @param certificate the input bytes representing a certificate of type
         *                    {@link android.content.pm.PackageManager#CERT_INPUT_SHA256}.
         *
         * @throws IOException when there is an I/O error while getting the access type.
         * @throws IllegalStateException when the caller tries to get access type from a session
         *                               which is closed or abandoned.
         */
        public boolean isPackageAccessAllowed(@NonNull String packageName,
                @NonNull byte[] certificate) throws IOException {
            try {
                return mSession.isPackageAccessAllowed(packageName, certificate);
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Allow packages which are signed with the same certificate as the caller to access this
         * blob data once it is committed using a {@link BlobHandle} representing the blob.
         *
         * <p> This needs to be called before committing the blob using
         * {@link #commit(Executor, Consumer)}.
         *
         * @throws IOException when there is an I/O error while changing the access.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to change access for a blob which is
         *                               already committed.
         */
        public void allowSameSignatureAccess() throws IOException {
            try {
                mSession.allowSameSignatureAccess();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns {@code true} if access has been allowed for packages signed with the same
         * certificate as the caller by using {@link #allowSameSignatureAccess()}.
         * Otherwise, {@code false}.
         *
         * @throws IOException when there is an I/O error while getting the access type.
         * @throws IllegalStateException when the caller tries to get access type from a session
         *                               which is closed or abandoned.
         */
        public boolean isSameSignatureAccessAllowed() throws IOException {
            try {
                return mSession.isSameSignatureAccessAllowed();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Allow any app on the device to access this blob data once it is committed using
         * a {@link BlobHandle} representing the blob.
         *
         * <p><strong>Note:</strong> This is only meant to be used from libraries and SDKs where
         * the apps which we want to allow access is not known ahead of time.
         * If a blob is being committed to be shared with a particular set of apps, it is highly
         * recommended to use {@link #allowPackageAccess(String, byte[])} instead.
         *
         * <p> This needs to be called before committing the blob using
         * {@link #commit(Executor, Consumer)}.
         *
         * @throws IOException when there is an I/O error while changing the access.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalStateException when the caller tries to change access for a blob which is
         *                               already committed.
         */
        public void allowPublicAccess() throws IOException {
            try {
                mSession.allowPublicAccess();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns {@code true} if public access has been allowed by using
         * {@link #allowPublicAccess()}. Otherwise, {@code false}.
         *
         * @throws IOException when there is an I/O error while getting the access type.
         * @throws IllegalStateException when the caller tries to get access type from a session
         *                               which is closed or abandoned.
         */
        public boolean isPublicAccessAllowed() throws IOException {
            try {
                return mSession.isPublicAccessAllowed();
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Commit the file that was written so far to this session to the blob store maintained by
         * the system.
         *
         * <p> Once this method is called, the session is finalized and no additional
         * mutations can be performed on the session. If the device reboots
         * before the session has been finalized, you may commit the session again.
         *
         * <p> Note that this commit operation will fail if the hash of the data written so far
         * to this session does not match with the one used for
         * {@link BlobHandle#createWithSha256(byte[], CharSequence, long, String)}  BlobHandle}
         * associated with this session.
         *
         * <p> Committing the same data more than once will result in replacing the corresponding
         * access mode (via calling one of {@link #allowPackageAccess(String, byte[])},
         * {@link #allowSameSignatureAccess()}, etc) with the latest one.
         *
         * @param executor the executor on which result callback will be invoked.
         * @param resultCallback a callback to receive the commit result. when the result is
         *                       {@code 0}, it indicates success. Otherwise, failure.
         *
         * @throws IOException when there is an I/O error while committing the session.
         * @throws SecurityException when the caller is not the owner of the session.
         * @throws IllegalArgumentException when the passed parameters are not valid.
         * @throws IllegalStateException when the caller tries to commit a session which was
         *                               already finalized.
         */
        public void commit(@NonNull @CallbackExecutor Executor executor,
                @NonNull Consumer<Integer> resultCallback) throws IOException {
            try {
                mSession.commit(new IBlobCommitCallback.Stub() {
                    public void onResult(int result) {
                        executor.execute(PooledLambda.obtainRunnable(
                                Consumer::accept, resultCallback, result));
                    }
                });
            } catch (ParcelableException e) {
                e.maybeRethrow(IOException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
