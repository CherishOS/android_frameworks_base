/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_DEFAULT;
import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN;
import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_WINDOW;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.accessibility.MagnificationAnimationCallback.STUB_ANIMATION_CALLBACK;

import android.accessibilityservice.MagnificationConfig;
import android.annotation.NonNull;
import android.graphics.Region;

/**
 * Processor class for AccessibilityService connection to control magnification on the specified
 * display. This wraps the function of magnification controller.
 *
 * <p>
 * If the magnification config uses {@link DEFAULT_MODE}. This processor will control the current
 * activated magnifier on the display. If there is no magnifier activated, it controls
 * full-screen magnifier by default.
 * </p>
 *
 * <p>
 * If the magnification config uses {@link FULLSCREEN_MODE}. This processor will control
 * full-screen magnifier on the display.
 * </p>
 *
 * <p>
 * If the magnification config uses {@link WINDOW_MODE}. This processor will control
 * the activated window magnifier on the display.
 * </p>
 *
 * @see MagnificationController
 * @see FullScreenMagnificationController
 */
public class MagnificationProcessor {

    private final MagnificationController mController;

    public MagnificationProcessor(MagnificationController controller) {
        mController = controller;
    }

    /**
     * Gets the magnification config of the display.
     *
     * @param displayId The logical display id
     * @return the magnification config
     */
    public @NonNull MagnificationConfig getMagnificationConfig(int displayId) {
        final int mode = getControllingMode(displayId);
        MagnificationConfig.Builder builder = new MagnificationConfig.Builder();
        if (mode == MAGNIFICATION_MODE_FULLSCREEN) {
            final FullScreenMagnificationController fullScreenMagnificationController =
                    mController.getFullScreenMagnificationController();
            builder.setMode(mode)
                    .setScale(fullScreenMagnificationController.getScale(displayId))
                    .setCenterX(fullScreenMagnificationController.getCenterX(displayId))
                    .setCenterY(fullScreenMagnificationController.getCenterY(displayId));
        } else if (mode == MAGNIFICATION_MODE_WINDOW) {
            final WindowMagnificationManager windowMagnificationManager =
                    mController.getWindowMagnificationMgr();
            builder.setMode(mode)
                    .setScale(windowMagnificationManager.getScale(displayId))
                    .setCenterX(windowMagnificationManager.getCenterX(displayId))
                    .setCenterY(windowMagnificationManager.getCenterY(displayId));
        }
        return builder.build();
    }

    /**
     * Sets the magnification config of the display. If animation is disabled, the transition
     * is immediate.
     *
     * @param displayId The logical display id
     * @param config    The magnification config
     * @param animate   {@code true} to animate from the current config or
     *                  {@code false} to set the config immediately
     * @param id        The ID of the service requesting the change
     * @return {@code true} if the magnification spec changed, {@code false} if the spec did not
     * change
     */
    public boolean setMagnificationConfig(int displayId, @NonNull MagnificationConfig config,
            boolean animate, int id) {
        if (transitionModeIfNeeded(displayId, config, animate)) {
            return true;
        }

        int configMode = config.getMode();
        if (configMode == MAGNIFICATION_MODE_DEFAULT) {
            configMode = getControllingMode(displayId);
        }
        if (configMode == MAGNIFICATION_MODE_FULLSCREEN) {
            return setScaleAndCenterForFullScreenMagnification(displayId, config.getScale(),
                    config.getCenterX(), config.getCenterY(),
                    animate, id);
        } else if (configMode == MAGNIFICATION_MODE_WINDOW) {
            return mController.getWindowMagnificationMgr().enableWindowMagnification(displayId,
                    config.getScale(), config.getCenterX(), config.getCenterY(),
                    animate ? STUB_ANIMATION_CALLBACK : null);
        }
        return false;
    }

    /**
     * Returns {@code true} if transition magnification mode needed. And it is no need to transition
     * mode when the controlling mode is unchanged or the controlling magnifier is not activated.
     */
    private boolean transitionModeIfNeeded(int displayId, MagnificationConfig config,
            boolean animate) {
        int currentMode = getControllingMode(displayId);
        if (currentMode == config.getMode()
                || !mController.hasDisableMagnificationCallback(displayId)) {
            return false;
        }
        mController.transitionMagnificationConfigMode(displayId, config, animate);
        return true;
    }

    /**
     * Returns the magnification scale. If an animation is in progress,
     * this reflects the end state of the animation.
     *
     * @param displayId The logical display id.
     * @return the scale
     */
    public float getScale(int displayId) {
        int mode = getControllingMode(displayId);
        if (mode == MAGNIFICATION_MODE_FULLSCREEN) {
            return mController.getFullScreenMagnificationController().getScale(displayId);
        } else if (mode == MAGNIFICATION_MODE_WINDOW) {
            return mController.getWindowMagnificationMgr().getScale(displayId);
        }
        return 0;
    }

    /**
     * Returns the magnification center in X coordinate of the controlling magnification mode.
     * If the service can control magnification but fullscreen magnifier is not registered, it will
     * register the magnifier for this call then unregister the magnifier finally to make the
     * magnification center correct.
     *
     * @param displayId The logical display id
     * @param canControlMagnification Whether the service can control magnification
     * @return the X coordinate
     */
    public float getCenterX(int displayId, boolean canControlMagnification) {
        int mode = getControllingMode(displayId);
        if (mode == MAGNIFICATION_MODE_FULLSCREEN) {
            boolean registeredJustForThisCall = registerDisplayMagnificationIfNeeded(displayId,
                    canControlMagnification);
            try {
                return mController.getFullScreenMagnificationController().getCenterX(displayId);
            } finally {
                if (registeredJustForThisCall) {
                    unregister(displayId);
                }
            }
        } else if (mode == MAGNIFICATION_MODE_WINDOW) {
            return mController.getWindowMagnificationMgr().getCenterX(displayId);
        }
        return 0;
    }

    /**
     * Returns the magnification center in Y coordinate of the controlling magnification mode.
     * If the service can control magnification but fullscreen magnifier is not registered, it will
     * register the magnifier for this call then unregister the magnifier finally to make the
     * magnification center correct.
     *
     * @param displayId The logical display id
     * @param canControlMagnification Whether the service can control magnification
     * @return the Y coordinate
     */
    public float getCenterY(int displayId, boolean canControlMagnification) {
        int mode = getControllingMode(displayId);
        if (mode == MAGNIFICATION_MODE_FULLSCREEN) {
            boolean registeredJustForThisCall = registerDisplayMagnificationIfNeeded(displayId,
                    canControlMagnification);
            try {
                return mController.getFullScreenMagnificationController().getCenterY(displayId);
            } finally {
                if (registeredJustForThisCall) {
                    unregister(displayId);
                }
            }
        } else if (mode == MAGNIFICATION_MODE_WINDOW) {
            return mController.getWindowMagnificationMgr().getCenterY(displayId);
        }
        return 0;
    }

    /**
     * Return the magnification bounds of the current controlling magnification on the given
     * display. If the magnifier is not enabled, it returns an empty region.
     * If the service can control magnification but fullscreen magnifier is not registered, it will
     * register the magnifier for this call then unregister the magnifier finally to make
     * the magnification region correct.
     *
     * @param displayId The logical display id
     * @param outRegion the region to populate
     * @param canControlMagnification Whether the service can control magnification
     * @return outRegion the magnification bounds of full-screen magnifier or the magnification
     * source bounds of window magnifier
     */
    public Region getMagnificationRegion(int displayId, @NonNull Region outRegion,
            boolean canControlMagnification) {
        int mode = getControllingMode(displayId);
        if (mode == MAGNIFICATION_MODE_FULLSCREEN) {
            getFullscreenMagnificationRegion(displayId, outRegion, canControlMagnification);
        } else if (mode == MAGNIFICATION_MODE_WINDOW) {
            mController.getWindowMagnificationMgr().getMagnificationSourceBounds(displayId,
                    outRegion);
        }
        return outRegion;
    }

    private void getFullscreenMagnificationRegion(int displayId, @NonNull Region outRegion,
            boolean canControlMagnification) {
        boolean registeredJustForThisCall = registerDisplayMagnificationIfNeeded(displayId,
                canControlMagnification);
        try {
            mController.getFullScreenMagnificationController().getMagnificationRegion(displayId,
                    outRegion);
        } finally {
            if (registeredJustForThisCall) {
                unregister(displayId);
            }
        }
    }

    private boolean setScaleAndCenterForFullScreenMagnification(int displayId, float scale,
            float centerX, float centerY,
            boolean animate, int id) {
        if (!isRegistered(displayId)) {
            register(displayId);
        }
        return mController.getFullScreenMagnificationController().setScaleAndCenter(
                displayId,
                scale,
                centerX, centerY, animate, id);
    }

    /**
     * Resets the magnification on the given display. The reset mode could be full-screen or
     * window if it is activated.
     *
     * @param displayId The logical display id.
     * @param animate   {@code true} to animate the transition, {@code false}
     *                  to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     * the spec did not change
     */
    public boolean reset(int displayId, boolean animate) {
        int mode = getControllingMode(displayId);
        if (mode == MAGNIFICATION_MODE_FULLSCREEN) {
            return mController.getFullScreenMagnificationController().reset(displayId, animate);
        } else if (mode == MAGNIFICATION_MODE_WINDOW) {
            return mController.getWindowMagnificationMgr().reset(displayId);
        }
        return false;
    }

    /**
     * {@link FullScreenMagnificationController#resetIfNeeded(int, boolean)}
     */
    // TODO: support window magnification
    public void resetAllIfNeeded(int connectionId) {
        mController.getFullScreenMagnificationController().resetAllIfNeeded(connectionId);
    }

    /**
     * {@link FullScreenMagnificationController#isMagnifying(int)}
     * {@link WindowMagnificationManager#isWindowMagnifierEnabled(int)}
     */
    public boolean isMagnifying(int displayId) {
        int mode = getControllingMode(displayId);
        if (mode == MAGNIFICATION_MODE_FULLSCREEN) {
            return mController.getFullScreenMagnificationController().isMagnifying(displayId);
        } else if (mode == MAGNIFICATION_MODE_WINDOW) {
            return mController.getWindowMagnificationMgr().isWindowMagnifierEnabled(displayId);
        }
        return false;
    }

    /**
     * Returns the current controlling magnification mode on the given display.
     * If there is no magnifier activated, it fallbacks to the last activated mode.
     * And the last activated mode is {@link FULLSCREEN_MODE} by default.
     *
     * @param displayId The logical display id
     */
    public int getControllingMode(int displayId) {
        if (mController.isActivated(displayId,
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW)) {
            return MAGNIFICATION_MODE_WINDOW;
        } else if (mController.isActivated(displayId,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN)) {
            return MAGNIFICATION_MODE_FULLSCREEN;
        } else {
            return (mController.getLastActivatedMode() == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW)
                    ? MAGNIFICATION_MODE_WINDOW
                    : MAGNIFICATION_MODE_FULLSCREEN;
        }
    }

    private boolean registerDisplayMagnificationIfNeeded(int displayId,
            boolean canControlMagnification) {
        if (!isRegistered(displayId) && canControlMagnification) {
            register(displayId);
            return true;
        }
        return false;
    }

    private boolean isRegistered(int displayId) {
        return mController.getFullScreenMagnificationController().isRegistered(displayId);
    }

    /**
     * {@link FullScreenMagnificationController#register(int)}
     */
    private void register(int displayId) {
        mController.getFullScreenMagnificationController().register(displayId);
    }

    /**
     * {@link FullScreenMagnificationController#unregister(int)} (int)}
     */
    private void unregister(int displayId) {
        mController.getFullScreenMagnificationController().unregister(displayId);
    }
}
