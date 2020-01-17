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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.gsi.GsiProgress;
import android.gsi.IGsiService;
import android.gsi.IGsid;
import android.os.Environment;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.image.IDynamicSystemService;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Slog;

import java.io.File;

/**
 * DynamicSystemService implements IDynamicSystemService. It provides permission check before
 * passing requests to gsid
 */
public class DynamicSystemService extends IDynamicSystemService.Stub implements DeathRecipient {
    private static final String TAG = "DynamicSystemService";
    private static final String NO_SERVICE_ERROR = "no gsiservice";
    private static final int GSID_ROUGH_TIMEOUT_MS = 8192;
    private static final String PATH_DEFAULT = "/data/gsi/";
    private Context mContext;
    private String mInstallPath, mDsuSlot;
    private volatile IGsiService mGsiService;

    DynamicSystemService(Context context) {
        mContext = context;
    }

    private static IGsiService connect(DeathRecipient recipient) throws RemoteException {
        IBinder binder = ServiceManager.getService("gsiservice");
        if (binder == null) {
            return null;
        }
        /**
         * The init will restart gsiservice if it crashed and the proxy object will need to be
         * re-initialized in this case.
         */
        binder.linkToDeath(recipient, 0);

        IGsid gsid = IGsid.Stub.asInterface(binder);
        return gsid.getClient();
    }

    /** implements DeathRecipient */
    @Override
    public void binderDied() {
        Slog.w(TAG, "gsiservice died; reconnecting");
        synchronized (this) {
            mGsiService = null;
        }
    }

    private IGsiService getGsiService() throws RemoteException {
        checkPermission();

        if (!"running".equals(SystemProperties.get("init.svc.gsid"))) {
            SystemProperties.set("ctl.start", "gsid");
        }

        for (int sleepMs = 64; sleepMs <= (GSID_ROUGH_TIMEOUT_MS << 1); sleepMs <<= 1) {
            synchronized (this) {
                if (mGsiService == null) {
                    mGsiService = connect(this);
                }
                if (mGsiService != null) {
                    return mGsiService;
                }
            }

            try {
                Slog.d(TAG, "GsiService is not ready, wait for " + sleepMs + "ms");
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Slog.e(TAG, "Interrupted when waiting for GSID");
                return null;
            }
        }

        throw new RemoteException(NO_SERVICE_ERROR);
    }

    private void checkPermission() {
        if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires MANAGE_DYNAMIC_SYSTEM permission");
        }
    }

    @Override
    public boolean startInstallation(String dsuSlot) throws RemoteException {
        IGsiService service = getGsiService();
        // priority from high to low: sysprop -> sdcard -> /data
        String path = SystemProperties.get("os.aot.path");
        if (path.isEmpty()) {
            final int userId = UserHandle.myUserId();
            final StorageVolume[] volumes =
                    StorageManager.getVolumeList(userId, StorageManager.FLAG_FOR_WRITE);
            for (StorageVolume volume : volumes) {
                if (volume.isEmulated()) continue;
                if (!volume.isRemovable()) continue;
                if (!Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                File sdCard = volume.getPathFile();
                if (sdCard.isDirectory()) {
                    path = new File(sdCard, dsuSlot).getPath();
                    break;
                }
            }
            if (path.isEmpty()) {
                path = PATH_DEFAULT + dsuSlot;
            }
            Slog.i(TAG, "startInstallation -> " + path);
        }
        mInstallPath = path;
        mDsuSlot = dsuSlot;
        if (service.openInstall(path) != 0) {
            Slog.i(TAG, "Failed to open " + path);
            return false;
        }
        return true;
    }

    @Override
    public boolean createPartition(String name, long size, boolean readOnly)
            throws RemoteException {
        IGsiService service = getGsiService();
        if (service.createPartition(name, size, readOnly) != 0) {
            Slog.i(TAG, "Failed to install " + name);
            return false;
        }
        return true;
    }

    @Override
    public boolean finishInstallation() throws RemoteException {
        IGsiService service = getGsiService();
        if (service.closeInstall() != 0) {
            Slog.i(TAG, "Failed to finish installation");
            return false;
        }
        return true;
    }

    @Override
    public GsiProgress getInstallationProgress() throws RemoteException {
        return getGsiService().getInstallProgress();
    }

    @Override
    public boolean abort() throws RemoteException {
        return getGsiService().cancelGsiInstall();
    }

    @Override
    public boolean isInUse() throws RemoteException {
        return SystemProperties.getBoolean("ro.gsid.image_running", false);
    }

    @Override
    public boolean isInstalled() throws RemoteException {
        return getGsiService().isGsiInstalled();
    }

    @Override
    public boolean isEnabled() throws RemoteException {
        return getGsiService().isGsiEnabled();
    }

    @Override
    public boolean remove() throws RemoteException {
        IGsiService gsiService = getGsiService();
        String install_dir = gsiService.getInstalledGsiImageDir();
        return getGsiService().removeGsi();
    }

    @Override
    public boolean setEnable(boolean enable, boolean oneShot) throws RemoteException {
        IGsiService gsiService = getGsiService();
        if (enable) {
            return gsiService.enableGsi(oneShot, mDsuSlot) == 0;
        } else {
            return gsiService.disableGsi();
        }
    }

    @Override
    public boolean setAshmem(ParcelFileDescriptor ashmem, long size) {
        try {
            return getGsiService().setGsiAshmem(ashmem, size);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    @Override
    public boolean submitFromAshmem(long size) {
        try {
            return getGsiService().commitGsiChunkFromAshmem(size);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }
}
