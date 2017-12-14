/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.DUMP;
import static android.net.IpSecManager.INVALID_RESOURCE_ID;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.content.Context;
import android.net.IIpSecService;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransform;
import android.net.IpSecTransformResponse;
import android.net.IpSecUdpEncapResponse;
import android.net.NetworkUtils;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import libcore.io.IoUtils;

/**
 * A service to manage multiple clients that want to access the IpSec API. The service is
 * responsible for maintaining a list of clients and managing the resources (and related quotas)
 * that each of them own.
 *
 * <p>Synchronization in IpSecService is done on all entrypoints due to potential race conditions at
 * the kernel/xfrm level. Further, this allows the simplifying assumption to be made that only one
 * thread is ever running at a time.
 *
 * @hide
 */
public class IpSecService extends IIpSecService.Stub {
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String NETD_SERVICE_NAME = "netd";
    private static final int[] DIRECTIONS =
            new int[] {IpSecTransform.DIRECTION_OUT, IpSecTransform.DIRECTION_IN};

    private static final int NETD_FETCH_TIMEOUT_MS = 5000; // ms
    private static final int MAX_PORT_BIND_ATTEMPTS = 10;
    private static final InetAddress INADDR_ANY;

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    static final int FREE_PORT_MIN = 1024; // ports 1-1023 are reserved
    static final int PORT_MAX = 0xFFFF; // ports are an unsigned 16-bit integer

    /* Binder context for this service */
    private final Context mContext;

    /** Should be a never-repeating global ID for resources */
    private static AtomicInteger mNextResourceId = new AtomicInteger(0x00FADED0);

    interface IpSecServiceConfiguration {
        INetd getNetdInstance() throws RemoteException;

        static IpSecServiceConfiguration GETSRVINSTANCE =
                new IpSecServiceConfiguration() {
                    @Override
                    public INetd getNetdInstance() throws RemoteException {
                        final INetd netd = NetdService.getInstance();
                        if (netd == null) {
                            throw new RemoteException("Failed to Get Netd Instance");
                        }
                        return netd;
                    }
                };
    }

    private final IpSecServiceConfiguration mSrvConfig;

    /**
     * Interface for user-reference and kernel-resource cleanup.
     *
     * <p>This interface must be implemented for a resource to be reference counted.
     */
    @VisibleForTesting
    public interface IResource {
        /**
         * Invalidates a IResource object, ensuring it is invalid for the purposes of allocating new
         * objects dependent on it.
         *
         * <p>Implementations of this method are expected to remove references to the IResource
         * object from the IpSecService's tracking arrays. The removal from the arrays ensures that
         * the resource is considered invalid for user access or allocation or use in other
         * resources.
         *
         * <p>References to the IResource object may be held by other RefcountedResource objects,
         * and as such, the kernel resources and quota may not be cleaned up.
         */
        void invalidate() throws RemoteException;

        /**
         * Releases underlying resources and related quotas.
         *
         * <p>Implementations of this method are expected to remove all system resources that are
         * tracked by the IResource object. Due to other RefcountedResource objects potentially
         * having references to the IResource object, freeUnderlyingResources may not always be
         * called from releaseIfUnreferencedRecursively().
         */
        void freeUnderlyingResources() throws RemoteException;
    }

    /**
     * RefcountedResource manages references and dependencies in an exclusively acyclic graph.
     *
     * <p>RefcountedResource implements both explicit and implicit resource management. Creating a
     * RefcountedResource object creates an explicit reference that must be freed by calling
     * userRelease(). Additionally, adding this object as a child of another RefcountedResource
     * object will add an implicit reference.
     *
     * <p>Resources are cleaned up when all references, both implicit and explicit, are released
     * (ie, when userRelease() is called and when all parents have called releaseReference() on this
     * object.)
     */
    @VisibleForTesting
    public class RefcountedResource<T extends IResource> implements IBinder.DeathRecipient {
        private final T mResource;
        private final List<RefcountedResource> mChildren;
        int mRefCount = 1; // starts at 1 for user's reference.
        IBinder mBinder;

        RefcountedResource(T resource, IBinder binder, RefcountedResource... children) {
            synchronized (IpSecService.this) {
                this.mResource = resource;
                this.mChildren = new ArrayList<>(children.length);
                this.mBinder = binder;

                for (RefcountedResource child : children) {
                    mChildren.add(child);
                    child.mRefCount++;
                }

                try {
                    mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    binderDied();
                }
            }
        }

        /**
         * If the Binder object dies, this function is called to free the system resources that are
         * being tracked by this record and to subsequently release this record for garbage
         * collection
         */
        @Override
        public void binderDied() {
            synchronized (IpSecService.this) {
                try {
                    userRelease();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to release resource: " + e);
                }
            }
        }

        public T getResource() {
            return mResource;
        }

        /**
         * Unlinks from binder and performs IpSecService resource cleanup (removes from resource
         * arrays)
         *
         * <p>If this method has been previously called, the RefcountedResource's binder field will
         * be null, and the method will return without performing the cleanup a second time.
         *
         * <p>Note that calling this function does not imply that kernel resources will be freed at
         * this time, or that the related quota will be returned. Such actions will only be
         * performed upon the reference count reaching zero.
         */
        @GuardedBy("IpSecService.this")
        public void userRelease() throws RemoteException {
            // Prevent users from putting reference counts into a bad state by calling
            // userRelease() multiple times.
            if (mBinder == null) {
                return;
            }

            mBinder.unlinkToDeath(this, 0);
            mBinder = null;

            mResource.invalidate();

            releaseReference();
        }

        /**
         * Removes a reference to this resource. If the resultant reference count is zero, the
         * underlying resources are freed, and references to all child resources are also dropped
         * recursively (resulting in them freeing their resources and children, etcetera)
         *
         * <p>This method also sets the reference count to an invalid value (-1) to signify that it
         * has been fully released. Any subsequent calls to this method will result in an
         * IllegalStateException being thrown due to resource already having been previously
         * released
         */
        @VisibleForTesting
        @GuardedBy("IpSecService.this")
        public void releaseReference() throws RemoteException {
            mRefCount--;

            if (mRefCount > 0) {
                return;
            } else if (mRefCount < 0) {
                throw new IllegalStateException(
                        "Invalid operation - resource has already been released.");
            }

            // Cleanup own resources
            mResource.freeUnderlyingResources();

            // Cleanup child resources as needed
            for (RefcountedResource<? extends IResource> child : mChildren) {
                child.releaseReference();
            }

            // Enforce that resource cleanup can only be called once
            // By decrementing the refcount (from 0 to -1), the next call will throw an
            // IllegalStateException - it has already been released fully.
            mRefCount--;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mResource=")
                    .append(mResource)
                    .append(", mRefCount=")
                    .append(mRefCount)
                    .append(", mChildren=")
                    .append(mChildren)
                    .append("}")
                    .toString();
        }
    }

    /* Very simple counting class that looks much like a counting semaphore */
    @VisibleForTesting
    static class ResourceTracker {
        private final int mMax;
        int mCurrent;

        ResourceTracker(int max) {
            mMax = max;
            mCurrent = 0;
        }

        boolean isAvailable() {
            return (mCurrent < mMax);
        }

        void take() {
            if (!isAvailable()) {
                Log.wtf(TAG, "Too many resources allocated!");
            }
            mCurrent++;
        }

        void give() {
            if (mCurrent <= 0) {
                Log.wtf(TAG, "We've released this resource too many times");
            }
            mCurrent--;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mCurrent=")
                    .append(mCurrent)
                    .append(", mMax=")
                    .append(mMax)
                    .append("}")
                    .toString();
        }
    }

    @VisibleForTesting
    static final class UserRecord {
        /* Type names */
        public static final String TYPENAME_SPI = "SecurityParameterIndex";
        public static final String TYPENAME_TRANSFORM = "IpSecTransform";
        public static final String TYPENAME_ENCAP_SOCKET = "UdpEncapSocket";

        /* Maximum number of each type of resource that a single UID may possess */
        public static final int MAX_NUM_ENCAP_SOCKETS = 2;
        public static final int MAX_NUM_TRANSFORMS = 4;
        public static final int MAX_NUM_SPIS = 8;

        final RefcountedResourceArray<SpiRecord> mSpiRecords =
                new RefcountedResourceArray<>(TYPENAME_SPI);
        final ResourceTracker mSpiQuotaTracker = new ResourceTracker(MAX_NUM_SPIS);

        final RefcountedResourceArray<TransformRecord> mTransformRecords =
                new RefcountedResourceArray<>(TYPENAME_TRANSFORM);
        final ResourceTracker mTransformQuotaTracker = new ResourceTracker(MAX_NUM_TRANSFORMS);

        final RefcountedResourceArray<EncapSocketRecord> mEncapSocketRecords =
                new RefcountedResourceArray<>(TYPENAME_ENCAP_SOCKET);
        final ResourceTracker mSocketQuotaTracker = new ResourceTracker(MAX_NUM_ENCAP_SOCKETS);

        void removeSpiRecord(int resourceId) {
            mSpiRecords.remove(resourceId);
        }

        void removeTransformRecord(int resourceId) {
            mTransformRecords.remove(resourceId);
        }

        void removeEncapSocketRecord(int resourceId) {
            mEncapSocketRecords.remove(resourceId);
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mSpiQuotaTracker=")
                    .append(mSpiQuotaTracker)
                    .append(", mTransformQuotaTracker=")
                    .append(mTransformQuotaTracker)
                    .append(", mSocketQuotaTracker=")
                    .append(mSocketQuotaTracker)
                    .append(", mSpiRecords=")
                    .append(mSpiRecords)
                    .append(", mTransformRecords=")
                    .append(mTransformRecords)
                    .append(", mEncapSocketRecords=")
                    .append(mEncapSocketRecords)
                    .append("}")
                    .toString();
        }
    }

    @VisibleForTesting
    static final class UserResourceTracker {
        private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();

        /** Never-fail getter that populates the list of UIDs as-needed */
        public UserRecord getUserRecord(int uid) {
            checkCallerUid(uid);

            UserRecord r = mUserRecords.get(uid);
            if (r == null) {
                r = new UserRecord();
                mUserRecords.put(uid, r);
            }
            return r;
        }

        /** Safety method; guards against access of other user's UserRecords */
        private void checkCallerUid(int uid) {
            if (uid != Binder.getCallingUid()
                    && android.os.Process.SYSTEM_UID != Binder.getCallingUid()) {
                throw new SecurityException("Attempted access of unowned resources");
            }
        }

        @Override
        public String toString() {
            return mUserRecords.toString();
        }
    }

    @VisibleForTesting final UserResourceTracker mUserResourceTracker = new UserResourceTracker();

    /**
     * The KernelResourceRecord class provides a facility to cleanly and reliably track system
     * resources. It relies on a provided resourceId that should uniquely identify the kernel
     * resource. To use this class, the user should implement the invalidate() and
     * freeUnderlyingResources() methods that are responsible for cleaning up IpSecService resource
     * tracking arrays and kernel resources, respectively
     */
    private abstract class KernelResourceRecord implements IResource {
        final int pid;
        final int uid;
        protected final int mResourceId;

        KernelResourceRecord(int resourceId) {
            super();
            if (resourceId == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Resource ID must not be INVALID_RESOURCE_ID");
            }
            mResourceId = resourceId;
            pid = Binder.getCallingPid();
            uid = Binder.getCallingUid();

            getResourceTracker().take();
        }

        @Override
        public abstract void invalidate() throws RemoteException;

        /** Convenience method; retrieves the user resource record for the stored UID. */
        protected UserRecord getUserRecord() {
            return mUserResourceTracker.getUserRecord(uid);
        }

        @Override
        public abstract void freeUnderlyingResources() throws RemoteException;

        /** Get the resource tracker for this resource */
        protected abstract ResourceTracker getResourceTracker();

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mResourceId=")
                    .append(mResourceId)
                    .append(", pid=")
                    .append(pid)
                    .append(", uid=")
                    .append(uid)
                    .append("}")
                    .toString();
        }
    };

    // TODO: Move this to right after RefcountedResource. With this here, Gerrit was showing many
    // more things as changed.
    /**
     * Thin wrapper over SparseArray to ensure resources exist, and simplify generic typing.
     *
     * <p>RefcountedResourceArray prevents null insertions, and throws an IllegalArgumentException
     * if a key is not found during a retrieval process.
     */
    static class RefcountedResourceArray<T extends IResource> {
        SparseArray<RefcountedResource<T>> mArray = new SparseArray<>();
        private final String mTypeName;

        public RefcountedResourceArray(String typeName) {
            this.mTypeName = typeName;
        }

        /**
         * Accessor method to get inner resource object.
         *
         * @throws IllegalArgumentException if no resource with provided key is found.
         */
        T getResourceOrThrow(int key) {
            return getRefcountedResourceOrThrow(key).getResource();
        }

        /**
         * Accessor method to get reference counting wrapper.
         *
         * @throws IllegalArgumentException if no resource with provided key is found.
         */
        RefcountedResource<T> getRefcountedResourceOrThrow(int key) {
            RefcountedResource<T> resource = mArray.get(key);
            if (resource == null) {
                throw new IllegalArgumentException(
                        String.format("No such %s found for given id: %d", mTypeName, key));
            }

            return resource;
        }

        void put(int key, RefcountedResource<T> obj) {
            checkNotNull(obj, "Null resources cannot be added");
            mArray.put(key, obj);
        }

        void remove(int key) {
            mArray.remove(key);
        }

        @Override
        public String toString() {
            return mArray.toString();
        }
    }

    private final class TransformRecord extends KernelResourceRecord {
        private final IpSecConfig mConfig;
        private final SpiRecord[] mSpis;
        private final EncapSocketRecord mSocket;

        TransformRecord(
                int resourceId, IpSecConfig config, SpiRecord[] spis, EncapSocketRecord socket) {
            super(resourceId);
            mConfig = config;
            mSpis = spis;
            mSocket = socket;
        }

        public IpSecConfig getConfig() {
            return mConfig;
        }

        public SpiRecord getSpiRecord(int direction) {
            return mSpis[direction];
        }

        /** always guarded by IpSecService#this */
        @Override
        public void freeUnderlyingResources() {
            for (int direction : DIRECTIONS) {
                int spi = mSpis[direction].getSpi();
                try {
                    mSrvConfig
                            .getNetdInstance()
                            .ipSecDeleteSecurityAssociation(
                                    mResourceId,
                                    direction,
                                    mConfig.getLocalAddress(),
                                    mConfig.getRemoteAddress(),
                                    spi);
                } catch (ServiceSpecificException e) {
                    // FIXME: get the error code and throw is at an IOException from Errno Exception
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to delete SA with ID: " + mResourceId);
                }
            }

            getResourceTracker().give();
        }

        @Override
        public void invalidate() throws RemoteException {
            getUserRecord().removeTransformRecord(mResourceId);
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mTransformQuotaTracker;
        }

        @Override
        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            strBuilder
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSocket=")
                    .append(mSocket)
                    .append(", mSpis[OUT].mResourceId=")
                    .append(mSpis[IpSecTransform.DIRECTION_OUT].mResourceId)
                    .append(", mSpis[IN].mResourceId=")
                    .append(mSpis[IpSecTransform.DIRECTION_IN].mResourceId)
                    .append(", mConfig=")
                    .append(mConfig)
                    .append("}");
            return strBuilder.toString();
        }
    }

    private final class SpiRecord extends KernelResourceRecord {
        private final int mDirection;
        private final String mLocalAddress;
        private final String mRemoteAddress;
        private int mSpi;

        private boolean mOwnedByTransform = false;

        SpiRecord(
                int resourceId,
                int direction,
                String localAddress,
                String remoteAddress,
                int spi) {
            super(resourceId);
            mDirection = direction;
            mLocalAddress = localAddress;
            mRemoteAddress = remoteAddress;
            mSpi = spi;
        }

        /** always guarded by IpSecService#this */
        @Override
        public void freeUnderlyingResources() {
            if (mOwnedByTransform) {
                Log.d(TAG, "Cannot release Spi " + mSpi + ": Currently locked by a Transform");
                // Because SPIs are "handed off" to transform, objects, they should never be
                // freed from the SpiRecord once used in a transform. (They refer to the same SA,
                // thus ownership and responsibility for freeing these resources passes to the
                // Transform object). Thus, we should let the user free them without penalty once
                // they are applied in a Transform object.
                return;
            }

            try {
                mSrvConfig
                        .getNetdInstance()
                        .ipSecDeleteSecurityAssociation(
                                mResourceId, mDirection, mLocalAddress, mRemoteAddress, mSpi);
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to delete SPI reservation with ID: " + mResourceId);
            }

            mSpi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;

            getResourceTracker().give();
        }

        public int getSpi() {
            return mSpi;
        }

        public void setOwnedByTransform() {
            if (mOwnedByTransform) {
                // Programming error
                throw new IllegalStateException("Cannot own an SPI twice!");
            }

            mOwnedByTransform = true;
        }

        @Override
        public void invalidate() throws RemoteException {
            getUserRecord().removeSpiRecord(mResourceId);
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mSpiQuotaTracker;
        }

        @Override
        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            strBuilder
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSpi=")
                    .append(mSpi)
                    .append(", mDirection=")
                    .append(mDirection)
                    .append(", mLocalAddress=")
                    .append(mLocalAddress)
                    .append(", mRemoteAddress=")
                    .append(mRemoteAddress)
                    .append(", mOwnedByTransform=")
                    .append(mOwnedByTransform)
                    .append("}");
            return strBuilder.toString();
        }
    }

    private final class EncapSocketRecord extends KernelResourceRecord {
        private FileDescriptor mSocket;
        private final int mPort;

        EncapSocketRecord(int resourceId, FileDescriptor socket, int port) {
            super(resourceId);
            mSocket = socket;
            mPort = port;
        }

        /** always guarded by IpSecService#this */
        @Override
        public void freeUnderlyingResources() {
            Log.d(TAG, "Closing port " + mPort);
            IoUtils.closeQuietly(mSocket);
            mSocket = null;

            getResourceTracker().give();
        }

        public int getPort() {
            return mPort;
        }

        public FileDescriptor getSocket() {
            return mSocket;
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mSocketQuotaTracker;
        }

        @Override
        public void invalidate() {
            getUserRecord().removeEncapSocketRecord(mResourceId);
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSocket=")
                    .append(mSocket)
                    .append(", mPort=")
                    .append(mPort)
                    .append("}")
                    .toString();
        }
    }

    /**
     * Constructs a new IpSecService instance
     *
     * @param context Binder context for this service
     */
    private IpSecService(Context context) {
        this(context, IpSecServiceConfiguration.GETSRVINSTANCE);
    }

    static IpSecService create(Context context) throws InterruptedException {
        final IpSecService service = new IpSecService(context);
        service.connectNativeNetdService();
        return service;
    }

    /** @hide */
    @VisibleForTesting
    public IpSecService(Context context, IpSecServiceConfiguration config) {
        mContext = context;
        mSrvConfig = config;
    }

    public void systemReady() {
        if (isNetdAlive()) {
            Slog.d(TAG, "IpSecService is ready");
        } else {
            Slog.wtf(TAG, "IpSecService not ready: failed to connect to NetD Native Service!");
        }
    }

    private void connectNativeNetdService() {
        // Avoid blocking the system server to do this
        new Thread() {
            @Override
            public void run() {
                synchronized (IpSecService.this) {
                    NetdService.get(NETD_FETCH_TIMEOUT_MS);
                }
            }
        }.start();
    }

    synchronized boolean isNetdAlive() {
        try {
            final INetd netd = mSrvConfig.getNetdInstance();
            if (netd == null) {
                return false;
            }
            return netd.isAlive();
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Checks that the provided InetAddress is valid for use in an IPsec SA. The address must not be
     * a wildcard address and must be in a numeric form such as 1.2.3.4 or 2001::1.
     */
    private static void checkInetAddress(String inetAddress) {
        if (TextUtils.isEmpty(inetAddress)) {
            throw new IllegalArgumentException("Unspecified address");
        }

        InetAddress checkAddr = NetworkUtils.numericToInetAddress(inetAddress);

        if (checkAddr.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Inappropriate wildcard address: " + inetAddress);
        }
    }

    /**
     * Checks the user-provided direction field and throws an IllegalArgumentException if it is not
     * DIRECTION_IN or DIRECTION_OUT
     */
    private static void checkDirection(int direction) {
        switch (direction) {
            case IpSecTransform.DIRECTION_OUT:
            case IpSecTransform.DIRECTION_IN:
                return;
        }
        throw new IllegalArgumentException("Invalid Direction: " + direction);
    }

    /** Get a new SPI and maintain the reservation in the system server */
    @Override
    public synchronized IpSecSpiResponse allocateSecurityParameterIndex(
            int direction, String remoteAddress, int requestedSpi, IBinder binder)
            throws RemoteException {
        checkDirection(direction);
        checkInetAddress(remoteAddress);
        /* requestedSpi can be anything in the int range, so no check is needed. */
        checkNotNull(binder, "Null Binder passed to allocateSecurityParameterIndex");

        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        int resourceId = mNextResourceId.getAndIncrement();

        int spi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        String localAddress = "";

        try {
            if (!userRecord.mSpiQuotaTracker.isAvailable()) {
                return new IpSecSpiResponse(
                        IpSecManager.Status.RESOURCE_UNAVAILABLE, INVALID_RESOURCE_ID, spi);
            }
            spi =
                    mSrvConfig
                            .getNetdInstance()
                            .ipSecAllocateSpi(
                                    resourceId,
                                    direction,
                                    localAddress,
                                    remoteAddress,
                                    requestedSpi);
            Log.d(TAG, "Allocated SPI " + spi);
            userRecord.mSpiRecords.put(
                    resourceId,
                    new RefcountedResource<SpiRecord>(
                            new SpiRecord(resourceId, direction, localAddress, remoteAddress, spi),
                            binder));
        } catch (ServiceSpecificException e) {
            // TODO: Add appropriate checks when other ServiceSpecificException types are supported
            return new IpSecSpiResponse(
                    IpSecManager.Status.SPI_UNAVAILABLE, INVALID_RESOURCE_ID, spi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new IpSecSpiResponse(IpSecManager.Status.OK, resourceId, spi);
    }

    /* This method should only be called from Binder threads. Do not call this from
     * within the system server as it will crash the system on failure.
     */
    private void releaseResource(RefcountedResourceArray resArray, int resourceId)
            throws RemoteException {

        resArray.getRefcountedResourceOrThrow(resourceId).userRelease();
    }

    /** Release a previously allocated SPI that has been registered with the system server */
    @Override
    public synchronized void releaseSecurityParameterIndex(int resourceId) throws RemoteException {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mSpiRecords, resourceId);
    }

    /**
     * This function finds and forcibly binds to a random system port, ensuring that the port cannot
     * be unbound.
     *
     * <p>A socket cannot be un-bound from a port if it was bound to that port by number. To select
     * a random open port and then bind by number, this function creates a temp socket, binds to a
     * random port (specifying 0), gets that port number, and then uses is to bind the user's UDP
     * Encapsulation Socket forcibly, so that it cannot be un-bound by the user with the returned
     * FileHandle.
     *
     * <p>The loop in this function handles the inherent race window between un-binding to a port
     * and re-binding, during which the system could *technically* hand that port out to someone
     * else.
     */
    private int bindToRandomPort(FileDescriptor sockFd) throws IOException {
        for (int i = MAX_PORT_BIND_ATTEMPTS; i > 0; i--) {
            try {
                FileDescriptor probeSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                Os.bind(probeSocket, INADDR_ANY, 0);
                int port = ((InetSocketAddress) Os.getsockname(probeSocket)).getPort();
                Os.close(probeSocket);
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
                return port;
            } catch (ErrnoException e) {
                // Someone miraculously claimed the port just after we closed probeSocket.
                if (e.errno == OsConstants.EADDRINUSE) {
                    continue;
                }
                throw e.rethrowAsIOException();
            }
        }
        throw new IOException("Failed " + MAX_PORT_BIND_ATTEMPTS + " attempts to bind to a port");
    }

    /**
     * Open a socket via the system server and bind it to the specified port (random if port=0).
     * This will return a PFD to the user that represent a bound UDP socket. The system server will
     * cache the socket and a record of its owner so that it can and must be freed when no longer
     * needed.
     */
    @Override
    public synchronized IpSecUdpEncapResponse openUdpEncapsulationSocket(int port, IBinder binder)
            throws RemoteException {
        if (port != 0 && (port < FREE_PORT_MIN || port > PORT_MAX)) {
            throw new IllegalArgumentException(
                    "Specified port number must be a valid non-reserved UDP port");
        }
        checkNotNull(binder, "Null Binder passed to openUdpEncapsulationSocket");

        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        int resourceId = mNextResourceId.getAndIncrement();
        FileDescriptor sockFd = null;
        try {
            if (!userRecord.mSocketQuotaTracker.isAvailable()) {
                return new IpSecUdpEncapResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
            }

            sockFd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

            if (port != 0) {
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
            } else {
                port = bindToRandomPort(sockFd);
            }
            // This code is common to both the unspecified and specified port cases
            Os.setsockoptInt(
                    sockFd,
                    OsConstants.IPPROTO_UDP,
                    OsConstants.UDP_ENCAP,
                    OsConstants.UDP_ENCAP_ESPINUDP);

            userRecord.mEncapSocketRecords.put(
                    resourceId,
                    new RefcountedResource<EncapSocketRecord>(
                            new EncapSocketRecord(resourceId, sockFd, port), binder));
            return new IpSecUdpEncapResponse(IpSecManager.Status.OK, resourceId, port, sockFd);
        } catch (IOException | ErrnoException e) {
            IoUtils.closeQuietly(sockFd);
        }
        // If we make it to here, then something has gone wrong and we couldn't open a socket.
        // The only reasonable condition that would cause that is resource unavailable.
        return new IpSecUdpEncapResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
    }

    /** close a socket that has been been allocated by and registered with the system server */
    @Override
    public synchronized void closeUdpEncapsulationSocket(int resourceId) throws RemoteException {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mEncapSocketRecords, resourceId);
    }

    /**
     * Checks an IpSecConfig parcel to ensure that the contents are sane and throws an
     * IllegalArgumentException if they are not.
     */
    private void checkIpSecConfig(IpSecConfig config) {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());

        if (config.getLocalAddress() == null) {
            throw new IllegalArgumentException("Invalid null Local InetAddress");
        }

        if (config.getRemoteAddress() == null) {
            throw new IllegalArgumentException("Invalid null Remote InetAddress");
        }

        switch (config.getMode()) {
            case IpSecTransform.MODE_TRANSPORT:
                if (!config.getLocalAddress().isEmpty()) {
                    throw new IllegalArgumentException("Non-empty Local Address");
                }
                // Must be valid, and not a wildcard
                checkInetAddress(config.getRemoteAddress());
                break;
            case IpSecTransform.MODE_TUNNEL:
                break;
            default:
                throw new IllegalArgumentException(
                        "Invalid IpSecTransform.mode: " + config.getMode());
        }

        switch (config.getEncapType()) {
            case IpSecTransform.ENCAP_NONE:
                break;
            case IpSecTransform.ENCAP_ESPINUDP:
            case IpSecTransform.ENCAP_ESPINUDP_NON_IKE:
                // Retrieve encap socket record; will throw IllegalArgumentException if not found
                userRecord.mEncapSocketRecords.getResourceOrThrow(
                        config.getEncapSocketResourceId());

                int port = config.getEncapRemotePort();
                if (port <= 0 || port > 0xFFFF) {
                    throw new IllegalArgumentException("Invalid remote UDP port: " + port);
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid Encap Type: " + config.getEncapType());
        }

        for (int direction : DIRECTIONS) {
            IpSecAlgorithm crypt = config.getEncryption(direction);
            IpSecAlgorithm auth = config.getAuthentication(direction);
            IpSecAlgorithm authenticatedEncryption = config.getAuthenticatedEncryption(direction);
            if (authenticatedEncryption == null && crypt == null && auth == null) {
                throw new IllegalArgumentException(
                        "No Encryption or Authentication algorithms specified");
            } else if (authenticatedEncryption != null && (auth != null || crypt != null)) {
                throw new IllegalArgumentException(
                        "Authenticated Encryption is mutually"
                                + " exclusive with other Authentication or Encryption algorithms");
            }

            // Retrieve SPI record; will throw IllegalArgumentException if not found
            userRecord.mSpiRecords.getResourceOrThrow(config.getSpiResourceId(direction));
        }
    }

    /**
     * Create a transport mode transform, which represent two security associations (one in each
     * direction) in the kernel. The transform will be cached by the system server and must be freed
     * when no longer needed. It is possible to free one, deleting the SA from underneath sockets
     * that are using it, which will result in all of those sockets becoming unable to send or
     * receive data.
     */
    @Override
    public synchronized IpSecTransformResponse createTransportModeTransform(
            IpSecConfig c, IBinder binder) throws RemoteException {
        checkIpSecConfig(c);
        checkNotNull(binder, "Null Binder passed to createTransportModeTransform");
        int resourceId = mNextResourceId.getAndIncrement();

        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());

        // Avoid resizing by creating a dependency array of min-size 3 (1 UDP encap + 2 SPIs)
        List<RefcountedResource> dependencies = new ArrayList<>(3);

        if (!userRecord.mTransformQuotaTracker.isAvailable()) {
            return new IpSecTransformResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
        }
        SpiRecord[] spis = new SpiRecord[DIRECTIONS.length];

        int encapType, encapLocalPort = 0, encapRemotePort = 0;
        EncapSocketRecord socketRecord = null;
        encapType = c.getEncapType();
        if (encapType != IpSecTransform.ENCAP_NONE) {
            RefcountedResource<EncapSocketRecord> refcountedSocketRecord =
                    userRecord.mEncapSocketRecords.getRefcountedResourceOrThrow(
                            c.getEncapSocketResourceId());
            dependencies.add(refcountedSocketRecord);

            socketRecord = refcountedSocketRecord.getResource();
            encapLocalPort = socketRecord.getPort();
            encapRemotePort = c.getEncapRemotePort();
        }

        for (int direction : DIRECTIONS) {
            IpSecAlgorithm auth = c.getAuthentication(direction);
            IpSecAlgorithm crypt = c.getEncryption(direction);
            IpSecAlgorithm authCrypt = c.getAuthenticatedEncryption(direction);

            RefcountedResource<SpiRecord> refcountedSpiRecord =
                    userRecord.mSpiRecords.getRefcountedResourceOrThrow(
                            c.getSpiResourceId(direction));
            dependencies.add(refcountedSpiRecord);

            spis[direction] = refcountedSpiRecord.getResource();
            int spi = spis[direction].getSpi();
            try {
                mSrvConfig
                        .getNetdInstance()
                        .ipSecAddSecurityAssociation(
                                resourceId,
                                c.getMode(),
                                direction,
                                c.getLocalAddress(),
                                c.getRemoteAddress(),
                                (c.getNetwork() != null) ? c.getNetwork().getNetworkHandle() : 0,
                                spi,
                                (auth != null) ? auth.getName() : "",
                                (auth != null) ? auth.getKey() : new byte[] {},
                                (auth != null) ? auth.getTruncationLengthBits() : 0,
                                (crypt != null) ? crypt.getName() : "",
                                (crypt != null) ? crypt.getKey() : new byte[] {},
                                (crypt != null) ? crypt.getTruncationLengthBits() : 0,
                                (authCrypt != null) ? authCrypt.getName() : "",
                                (authCrypt != null) ? authCrypt.getKey() : new byte[] {},
                                (authCrypt != null) ? authCrypt.getTruncationLengthBits() : 0,
                                encapType,
                                encapLocalPort,
                                encapRemotePort);
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
                return new IpSecTransformResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
            }
        }
        // Both SAs were created successfully, time to construct a record and lock it away
        userRecord.mTransformRecords.put(
                resourceId,
                new RefcountedResource<TransformRecord>(
                        new TransformRecord(resourceId, c, spis, socketRecord),
                        binder,
                        dependencies.toArray(new RefcountedResource[dependencies.size()])));
        return new IpSecTransformResponse(IpSecManager.Status.OK, resourceId);
    }

    /**
     * Delete a transport mode transform that was previously allocated by + registered with the
     * system server. If this is called on an inactive (or non-existent) transform, it will not
     * return an error. It's safe to de-allocate transforms that may have already been deleted for
     * other reasons.
     */
    @Override
    public synchronized void deleteTransportModeTransform(int resourceId) throws RemoteException {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mTransformRecords, resourceId);
    }

    /**
     * Apply an active transport mode transform to a socket, which will apply the IPsec security
     * association as a correspondent policy to the provided socket
     */
    @Override
    public synchronized void applyTransportModeTransform(
            ParcelFileDescriptor socket, int resourceId) throws RemoteException {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());

        // Get transform record; if no transform is found, will throw IllegalArgumentException
        TransformRecord info = userRecord.mTransformRecords.getResourceOrThrow(resourceId);

        // TODO: make this a function.
        if (info.pid != getCallingPid() || info.uid != getCallingUid()) {
            throw new SecurityException("Only the owner of an IpSec Transform may apply it!");
        }

        IpSecConfig c = info.getConfig();
        try {
            for (int direction : DIRECTIONS) {
                mSrvConfig
                        .getNetdInstance()
                        .ipSecApplyTransportModeTransform(
                                socket.getFileDescriptor(),
                                resourceId,
                                direction,
                                c.getLocalAddress(),
                                c.getRemoteAddress(),
                                info.getSpiRecord(direction).getSpi());
            }
        } catch (ServiceSpecificException e) {
            // FIXME: get the error code and throw is at an IOException from Errno Exception
        }
    }

    /**
     * Remove a transport mode transform from a socket, applying the default (empty) policy. This
     * will ensure that NO IPsec policy is applied to the socket (would be the equivalent of
     * applying a policy that performs no IPsec). Today the resourceId parameter is passed but not
     * used: reserved for future improved input validation.
     */
    @Override
    public synchronized void removeTransportModeTransform(ParcelFileDescriptor socket, int resourceId)
            throws RemoteException {
        try {
            mSrvConfig
                    .getNetdInstance()
                    .ipSecRemoveTransportModeTransform(socket.getFileDescriptor());
        } catch (ServiceSpecificException e) {
            // FIXME: get the error code and throw is at an IOException from Errno Exception
        }
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("IpSecService dump:");
        pw.println("NetdNativeService Connection: " + (isNetdAlive() ? "alive" : "dead"));
        pw.println();

        pw.println("mUserResourceTracker:");
        pw.println(mUserResourceTracker);
    }
}
