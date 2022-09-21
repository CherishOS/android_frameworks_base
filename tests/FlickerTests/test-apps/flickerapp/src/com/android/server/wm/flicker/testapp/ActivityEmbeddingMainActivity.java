/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.Log;
import android.view.View;

import androidx.window.extensions.embedding.ActivityEmbeddingComponent;
import androidx.window.extensions.embedding.EmbeddingRule;
import androidx.window.extensions.embedding.SplitPlaceholderRule;

import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper;

import java.util.Set;

/** Main activity of the ActivityEmbedding test app to launch other embedding activities. */
public class ActivityEmbeddingMainActivity extends Activity {
    private static final String TAG = "ActivityEmbeddingMainActivity";
    private static final float DEFAULT_SPLIT_RATIO = 0.5f;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_embedding_main_layout);

        initializeSplitRules();
    }

    /** R.id.launch_placeholder_split_button onClick */
    public void launchPlaceholderSplit(View view) {
        startActivity(
                new Intent().setComponent(
                        ActivityOptions.ActivityEmbedding.PlaceholderPrimaryActivity.COMPONENT
                )
        );
    }

    private void initializeSplitRules() {
        ActivityEmbeddingComponent embeddingComponent =
                ActivityEmbeddingAppHelper.getActivityEmbeddingComponent();
        if (embeddingComponent == null) {
            // Embedding not supported
            Log.d(TAG, "ActivityEmbedding is not supported on this device");
            finish();
            return;
        }

        embeddingComponent.setEmbeddingRules(getSplitRules());
    }

    private Set<EmbeddingRule> getSplitRules() {
        final Set<EmbeddingRule> rules = new ArraySet<>();

        final SplitPlaceholderRule placeholderRule = new SplitPlaceholderRule.Builder(
                new Intent().setComponent(
                        ActivityOptions.ActivityEmbedding.PlaceholderSecondaryActivity.COMPONENT),
                activity -> activity instanceof ActivityEmbeddingPlaceholderPrimaryActivity,
                intent -> intent.getComponent().equals(
                        ActivityOptions.ActivityEmbedding.PlaceholderPrimaryActivity.COMPONENT),
                windowMetrics -> true)
                .setSplitRatio(DEFAULT_SPLIT_RATIO)
                .build();
        rules.add(placeholderRule);
        return rules;
    }
}
