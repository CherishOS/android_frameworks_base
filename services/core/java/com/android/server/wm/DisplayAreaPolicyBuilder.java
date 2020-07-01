/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;

import android.annotation.Nullable;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.policy.WindowManagerPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A builder for instantiating a complex {@link DisplayAreaPolicy}
 *
 * <p>Given a set of features (that each target a set of window types), it builds the necessary
 * DisplayArea hierarchy.
 *
 * <p>Example: <br />
 *
 * <pre>
 *     // Feature for targeting everything below the magnification overlay:
 *     new DisplayAreaPolicyBuilder(...)
 *             .addFeature(new Feature.Builder(..., "Magnification")
 *                     .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
 *                     .build())
 *             .build(...)
 *
 *     // Builds a policy with the following hierarchy:
 *      - RootDisplayArea
 *        - Magnification
 *          - DisplayArea.Tokens (Wallpapers are attached here)
 *          - TaskDisplayArea
 *          - DisplayArea.Tokens (windows above Tasks up to IME are attached here)
 *          - ImeContainers
 *          - DisplayArea.Tokens (windows above IME up to TYPE_ACCESSIBILITY_OVERLAY attached here)
 *        - DisplayArea.Tokens (TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY and up are attached here)
 *
 * </pre>
 *
 * // TODO(b/157683117): document more complex scenarios where we need multiple areas per feature.
 */
class DisplayAreaPolicyBuilder {
    @Nullable private HierarchyBuilder mRootHierarchyBuilder;

    /** Defines the root hierarchy. */
    DisplayAreaPolicyBuilder setRootHierarchy(HierarchyBuilder rootHierarchyBuilder) {
        // TODO(b/157683117): Add method to add sub root and root choosing func.
        mRootHierarchyBuilder = rootHierarchyBuilder;
        return this;
    }

    Result build(WindowManagerService wmService) {
        Objects.requireNonNull(mRootHierarchyBuilder,
                "Root must be set for the display area policy.");
        Objects.requireNonNull(mRootHierarchyBuilder.mImeContainer, "Ime must not be null");
        Preconditions.checkCollectionNotEmpty(mRootHierarchyBuilder.mTaskDisplayAreas,
                "TaskDisplayAreas must not be empty");
        mRootHierarchyBuilder.build();
        return new Result(wmService, mRootHierarchyBuilder.mRoot);
    }

    /**
     *  Builder to define {@link Feature} and {@link DisplayArea} hierarchy under a
     * {@link RootDisplayArea}
     */
    static class HierarchyBuilder {
        private static final int LEAF_TYPE_TASK_CONTAINERS = 1;
        private static final int LEAF_TYPE_IME_CONTAINERS = 2;
        private static final int LEAF_TYPE_TOKENS = 0;

        private final RootDisplayArea mRoot;
        private final ArrayList<DisplayAreaPolicyBuilder.Feature> mFeatures = new ArrayList<>();
        private final ArrayList<TaskDisplayArea> mTaskDisplayAreas = new ArrayList<>();
        @Nullable
        private DisplayArea<? extends WindowContainer> mImeContainer;

        HierarchyBuilder(RootDisplayArea root) {
            mRoot = root;
        }

        /** Adds {@link Feature} that applies to layers under this container. */
        HierarchyBuilder addFeature(DisplayAreaPolicyBuilder.Feature feature) {
            mFeatures.add(feature);
            return this;
        }

        /**
         * Sets {@link TaskDisplayArea} that are children of this hierarchy root.
         * {@link DisplayArea} group must have at least one {@link TaskDisplayArea}.
         */
        HierarchyBuilder setTaskDisplayAreas(List<TaskDisplayArea> taskDisplayAreas) {
            mTaskDisplayAreas.clear();
            mTaskDisplayAreas.addAll(taskDisplayAreas);
            return this;
        }

        /** Sets IME container as a child of this hierarchy root. */
        HierarchyBuilder setImeContainer(DisplayArea<? extends WindowContainer> imeContainer) {
            mImeContainer = imeContainer;
            return this;
        }

        /** Builds the {@link DisplayArea} hierarchy below root. */
        private void build() {
            final WindowManagerPolicy policy = mRoot.mWmService.mPolicy;
            final int maxWindowLayerCount = policy.getMaxWindowLayer();
            final DisplayArea.Tokens[] displayAreaForLayer =
                    new DisplayArea.Tokens[maxWindowLayerCount];
            final Map<Feature, List<DisplayArea<? extends WindowContainer>>> featureAreas =
                    new ArrayMap<>(mFeatures.size());
            for (int i = 0; i < mFeatures.size(); i++) {
                featureAreas.put(mFeatures.get(i), new ArrayList<>());
            }

            // This method constructs the layer hierarchy with the following properties:
            // (1) Every feature maps to a set of DisplayAreas
            // (2) After adding a window, for every feature the window's type belongs to,
            //     it is a descendant of one of the corresponding DisplayAreas of the feature.
            // (3) Z-order is maintained, i.e. if z-range(area) denotes the set of layers of windows
            //     within a DisplayArea:
            //      for every pair of DisplayArea siblings (a,b), where a is below b, it holds that
            //      max(z-range(a)) <= min(z-range(b))
            //
            // The algorithm below iteratively creates such a hierarchy:
            //  - Initially, all windows are attached to the root.
            //  - For each feature we create a set of DisplayAreas, by looping over the layers
            //    - if the feature does apply to the current layer, we need to find a DisplayArea
            //      for it to satisfy (2)
            //      - we can re-use the previous layer's area if:
            //         the current feature also applies to the previous layer, (to satisfy (3))
            //         and the last feature that applied to the previous layer is the same as
            //           the last feature that applied to the current layer (to satisfy (2))
            //      - otherwise we create a new DisplayArea below the last feature that applied
            //        to the current layer

            PendingArea[] areaForLayer = new PendingArea[maxWindowLayerCount];
            final PendingArea root = new PendingArea(null, 0, null);
            Arrays.fill(areaForLayer, root);

            // Create DisplayAreas to cover all defined features.
            final int size = mFeatures.size();
            for (int i = 0; i < size; i++) {
                // Traverse the features with the order they are defined, so that the early defined
                // feature will be on the top in the hierarchy.
                final Feature feature = mFeatures.get(i);
                PendingArea featureArea = null;
                for (int layer = 0; layer < maxWindowLayerCount; layer++) {
                    if (feature.mWindowLayers[layer]) {
                        // This feature will be applied to this window layer.
                        //
                        // We need to find a DisplayArea for it:
                        // We can reuse the existing one if it was created for this feature for the
                        // previous layer AND the last feature that applied to the previous layer is
                        // the same as the feature that applied to the current layer (so they are ok
                        // to share the same parent DisplayArea).
                        if (featureArea == null || featureArea.mParent != areaForLayer[layer]) {
                            // No suitable DisplayArea:
                            // Create a new one under the previous area (as parent) for this layer.
                            featureArea = new PendingArea(feature, layer, areaForLayer[layer]);
                            areaForLayer[layer].mChildren.add(featureArea);
                        }
                        areaForLayer[layer] = featureArea;
                    } else {
                        // This feature won't be applied to this window layer. If it needs to be
                        // applied to the next layer, we will need to create a new DisplayArea for
                        // that.
                        featureArea = null;
                    }
                }
            }

            // Create Tokens as leaf for every layer.
            PendingArea leafArea = null;
            int leafType = LEAF_TYPE_TOKENS;
            for (int layer = 0; layer < maxWindowLayerCount; layer++) {
                int type = typeOfLayer(policy, layer);
                // Check whether we can reuse the same Tokens with the previous layer. This happens
                // if the previous layer is the same type as the current layer AND there is no
                // feature that applies to only one of them.
                if (leafArea == null || leafArea.mParent != areaForLayer[layer]
                        || type != leafType) {
                    // Create a new Tokens for this layer.
                    leafArea = new PendingArea(null /* feature */, layer, areaForLayer[layer]);
                    areaForLayer[layer].mChildren.add(leafArea);
                    leafType = type;
                    if (leafType == LEAF_TYPE_TASK_CONTAINERS) {
                        // We use the passed in TaskDisplayAreas for task container type of layer.
                        // Skip creating Tokens even if there is no TDA.
                        addTaskDisplayAreasToLayer(areaForLayer[layer]);
                        leafArea.mSkipTokens = true;
                    } else if (leafType == LEAF_TYPE_IME_CONTAINERS) {
                        // We use the passed in ImeContainer for ime container type of layer.
                        // Skip creating Tokens even if there is no ime container.
                        leafArea.mExisting = mImeContainer;
                        leafArea.mSkipTokens = true;
                    }
                }
                leafArea.mMaxLayer = layer;
            }
            root.computeMaxLayer();

            // We built a tree of PendingAreas above with all the necessary info to represent the
            // hierarchy, now create and attach real DisplayAreas to the root.
            root.instantiateChildren(mRoot, displayAreaForLayer, 0, featureAreas);

            // Notify the root that we have finished attaching all the DisplayAreas. Cache all the
            // feature related collections there for fast access.
            mRoot.onHierarchyBuilt(mFeatures, displayAreaForLayer, featureAreas, mTaskDisplayAreas);
        }

        /** Adds all {@link TaskDisplayArea} to the specified layer */
        private void addTaskDisplayAreasToLayer(PendingArea parentPendingArea) {
            final int count = mTaskDisplayAreas.size();
            for (int i = 0; i < count; i++) {
                PendingArea leafArea =
                        new PendingArea(null /* feature */, APPLICATION_LAYER, parentPendingArea);
                leafArea.mExisting = mTaskDisplayAreas.get(i);
                leafArea.mMaxLayer = APPLICATION_LAYER;
                parentPendingArea.mChildren.add(leafArea);
            }
        }

        private static int typeOfLayer(WindowManagerPolicy policy, int layer) {
            if (layer == APPLICATION_LAYER) {
                return LEAF_TYPE_TASK_CONTAINERS;
            } else if (layer == policy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD)
                    || layer == policy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD_DIALOG)) {
                return LEAF_TYPE_IME_CONTAINERS;
            } else {
                return LEAF_TYPE_TOKENS;
            }
        }
    }

    /** Supplier interface to provide a new created {@link DisplayArea}. */
    interface NewDisplayAreaSupplier {
        DisplayArea create(WindowManagerService wms, DisplayArea.Type type, String name,
                int featureId);
    }

    /**
     * A feature that requires {@link DisplayArea DisplayArea(s)}.
     */
    static class Feature {
        private final String mName;
        private final int mId;
        private final boolean[] mWindowLayers;
        private final NewDisplayAreaSupplier mNewDisplayAreaSupplier;

        private Feature(String name, int id, boolean[] windowLayers,
                NewDisplayAreaSupplier newDisplayAreaSupplier) {
            mName = name;
            mId = id;
            mWindowLayers = windowLayers;
            mNewDisplayAreaSupplier = newDisplayAreaSupplier;
        }

        /**
         * Returns the id of the feature.
         *
         * Must be unique among the features added to a {@link DisplayAreaPolicyBuilder}.
         *
         * @see android.window.DisplayAreaOrganizer#FEATURE_SYSTEM_FIRST
         * @see android.window.DisplayAreaOrganizer#FEATURE_VENDOR_FIRST
         */
        public int getId() {
            return mId;
        }

        @Override
        public String toString() {
            return "Feature(\"" + mName + "\", " + mId + '}';
        }

        static class Builder {
            private final WindowManagerPolicy mPolicy;
            private final String mName;
            private final int mId;
            private final boolean[] mLayers;
            private NewDisplayAreaSupplier mNewDisplayAreaSupplier = DisplayArea::new;

            /**
             * Builds a new feature that applies to a set of window types as specified by the
             * builder methods.
             *
             * <p>The set of types is updated iteratively in the order of the method invocations.
             * For example, {@code all().except(TYPE_STATUS_BAR)} expresses that a feature should
             * apply to all types except TYPE_STATUS_BAR.
             *
             * The builder starts out with the feature not applying to any types.
             *
             * @param name the name of the feature.
             * @param id of the feature. {@see Feature#getId}
             */
            Builder(WindowManagerPolicy policy, String name, int id) {
                mPolicy = policy;
                mName = name;
                mId = id;
                mLayers = new boolean[mPolicy.getMaxWindowLayer()];
            }

            /**
             * Set that the feature applies to all window types.
             */
            Builder all() {
                Arrays.fill(mLayers, true);
                return this;
            }

            /**
             * Set that the feature applies to the given window types.
             */
            Builder and(int... types) {
                for (int i = 0; i < types.length; i++) {
                    int type = types[i];
                    set(type, true);
                }
                return this;
            }

            /**
             * Set that the feature does not apply to the given window types.
             */
            Builder except(int... types) {
                for (int i = 0; i < types.length; i++) {
                    int type = types[i];
                    set(type, false);
                }
                return this;
            }

            /**
             * Set that the feature applies window types that are layerd at or below the layer of
             * the given window type.
             */
            Builder upTo(int typeInclusive) {
                final int max = layerFromType(typeInclusive, false);
                for (int i = 0; i < max; i++) {
                    mLayers[i] = true;
                }
                set(typeInclusive, true);
                return this;
            }

            /**
             * Sets the function to create new {@link DisplayArea} for this feature. By default, it
             * uses {@link DisplayArea}'s constructor.
             */
            Builder setNewDisplayAreaSupplier(NewDisplayAreaSupplier newDisplayAreaSupplier) {
                mNewDisplayAreaSupplier = newDisplayAreaSupplier;
                return this;
            }

            Feature build() {
                return new Feature(mName, mId, mLayers.clone(), mNewDisplayAreaSupplier);
            }

            private void set(int type, boolean value) {
                mLayers[layerFromType(type, true)] = value;
                if (type == TYPE_APPLICATION_OVERLAY) {
                    mLayers[layerFromType(type, true)] = value;
                    mLayers[layerFromType(TYPE_SYSTEM_ALERT, false)] = value;
                    mLayers[layerFromType(TYPE_SYSTEM_OVERLAY, false)] = value;
                    mLayers[layerFromType(TYPE_SYSTEM_ERROR, false)] = value;
                }
            }

            private int layerFromType(int type, boolean internalWindows) {
                return mPolicy.getWindowLayerFromTypeLw(type, internalWindows);
            }
        }
    }

    static class Result extends DisplayAreaPolicy {

        Result(WindowManagerService wmService, RootDisplayArea root) {
            super(wmService, root);
        }

        @Override
        public void addWindow(WindowToken token) {
            DisplayArea.Tokens area = findAreaForToken(token);
            area.addChild(token);
        }

        @VisibleForTesting
        DisplayArea.Tokens findAreaForToken(WindowToken token) {
            // TODO(b/157683117): Choose root/sub root from OEM provided func.
            return mRoot.findAreaForToken(token);
        }

        @VisibleForTesting
        List<Feature> getFeatures() {
            // TODO(b/157683117): Also get feature from sub root.
            return new ArrayList<>(mRoot.mFeatures);
        }

        @Override
        public List<DisplayArea<? extends WindowContainer>> getDisplayAreas(int featureId) {
            // TODO(b/157683117): Also get display areas from sub root.
            List<Feature> features = getFeatures();
            for (int i = 0; i < features.size(); i++) {
                Feature feature = features.get(i);
                if (feature.mId == featureId) {
                    return new ArrayList<>(mRoot.mFeatureToDisplayAreas.get(feature));
                }
            }
            return new ArrayList<>();
        }

        @Override
        public int getTaskDisplayAreaCount() {
            // TODO(b/157683117): Also add TDA from sub root.
            return mRoot.mTaskDisplayAreas.size();
        }

        @Override
        public TaskDisplayArea getTaskDisplayAreaAt(int index) {
            // TODO(b/157683117): Get TDA from root/sub root based on their z-order.
            return mRoot.mTaskDisplayAreas.get(index);
        }
    }

    static class PendingArea {
        final int mMinLayer;
        final ArrayList<PendingArea> mChildren = new ArrayList<>();
        final Feature mFeature;
        final PendingArea mParent;
        int mMaxLayer;

        /** If not {@code null}, use this instead of creating a {@link DisplayArea.Tokens}. */
        @Nullable DisplayArea mExisting;

        /**
         * Whether to skip creating a {@link DisplayArea.Tokens} if {@link #mExisting} is
         * {@code null}.
         *
         * This will be set for {@link HierarchyBuilder#LEAF_TYPE_IME_CONTAINERS} and
         * {@link HierarchyBuilder#LEAF_TYPE_TASK_CONTAINERS}, because we don't want to create
         * {@link DisplayArea.Tokens} for them even if they are not set.
         */
        boolean mSkipTokens = false;

        PendingArea(Feature feature, int minLayer, PendingArea parent) {
            mMinLayer = minLayer;
            mFeature = feature;
            mParent = parent;
        }

        int computeMaxLayer() {
            for (int i = 0; i < mChildren.size(); i++) {
                mMaxLayer = Math.max(mMaxLayer, mChildren.get(i).computeMaxLayer());
            }
            return mMaxLayer;
        }

        void instantiateChildren(DisplayArea<DisplayArea> parent, DisplayArea.Tokens[] areaForLayer,
                int level, Map<Feature, List<DisplayArea<? extends WindowContainer>>> areas) {
            mChildren.sort(Comparator.comparingInt(pendingArea -> pendingArea.mMinLayer));
            for (int i = 0; i < mChildren.size(); i++) {
                final PendingArea child = mChildren.get(i);
                final DisplayArea area = child.createArea(parent, areaForLayer);
                if (area == null) {
                    // TaskDisplayArea and ImeContainer can be set at different hierarchy, so it can
                    // be null.
                    continue;
                }
                parent.addChild(area, WindowContainer.POSITION_TOP);
                if (child.mFeature != null) {
                    areas.get(child.mFeature).add(area);
                }
                child.instantiateChildren(area, areaForLayer, level + 1, areas);
            }
        }

        @Nullable
        private DisplayArea createArea(DisplayArea<DisplayArea> parent,
                DisplayArea.Tokens[] areaForLayer) {
            if (mExisting != null) {
                return mExisting;
            }
            if (mSkipTokens) {
                return null;
            }
            DisplayArea.Type type;
            if (mMinLayer > APPLICATION_LAYER) {
                type = DisplayArea.Type.ABOVE_TASKS;
            } else if (mMaxLayer < APPLICATION_LAYER) {
                type = DisplayArea.Type.BELOW_TASKS;
            } else {
                type = DisplayArea.Type.ANY;
            }
            if (mFeature == null) {
                final DisplayArea.Tokens leaf = new DisplayArea.Tokens(parent.mWmService, type,
                        "Leaf:" + mMinLayer + ":" + mMaxLayer);
                for (int i = mMinLayer; i <= mMaxLayer; i++) {
                    areaForLayer[i] = leaf;
                }
                return leaf;
            } else {
                return mFeature.mNewDisplayAreaSupplier.create(parent.mWmService, type,
                        mFeature.mName + ":" + mMinLayer + ":" + mMaxLayer, mFeature.mId);
            }
        }
    }
}
