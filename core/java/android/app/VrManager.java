package android.app;


import android.annotation.SystemApi;
import android.content.ComponentName;
import android.os.RemoteException;
import android.service.vr.IVrManager;

import java.io.FileDescriptor;

/**
 * Used to control aspects of a devices Virtual Reality (VR) capabilities.
 * <p>
 * You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 * @hide
 */
@SystemApi
public class VrManager {
    private final IVrManager mService;

    /**
     * {@hide}
     */
    public VrManager(IVrManager service) {
        mService = service;
    }

    /**
     * Sets the persistent VR mode state of a device. When a device is in persistent VR mode it will
     * remain in VR mode even if the foreground does not specify Vr mode being enabled. Mainly used
     * by VR viewers to indicate that a device is placed in a VR viewer.
     *
     * <p>Requires {@link android.Manifest.permission#ACCESS_VR_MANAGER} permission.</p>
     *
     * @see Activity#setVrModeEnabled(boolean, ComponentName)
     * @param enabled true if the device should be placed in persistent VR mode.
     */
    public void setPersistentVrModeEnabled(boolean enabled) {
        try {
            mService.setPersistentVrModeEnabled(enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the resolution and DPI of the compatibility virtual display used to display 2D
     * applications in VR mode.
     *
     * <p>Requires {@link android.Manifest.permission#ACCESS_VR_MANAGER} permission.</p>
     *
     * @param {@link android.app.CompatibilityDisplayProperties} properties to be set to the
     * virtual display for 2D applications in VR mode.
     *
     * {@hide}
     */
    public void setCompatibilityDisplayProperties(
            CompatibilityDisplayProperties compatDisplayProp) {
        try {
            mService.setCompatibilityDisplayProperties(compatDisplayProp);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Initiate connection for system controller data.
     *
     * @param fd Controller data file descriptor.
     *
     * {@hide}
     */
    public void connectController(FileDescriptor fd) {
        try {
            mService.connectController(fd);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sever connection for system controller data.
     *
     * {@hide}
     */
    public void disconnectController() {
        try {
            mService.disconnectController();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
}
