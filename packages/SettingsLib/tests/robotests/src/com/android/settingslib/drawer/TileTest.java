package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class TileTest {

    private Tile mTile;

    @Before
    public void setUp() {
        mTile = new Tile();
        mTile.metaData = new Bundle();
    }

    @Test
    public void isPrimaryProfileOnly_profilePrimary_shouldReturnTrue() {
        mTile.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_PRIMARY);
        assertThat(mTile.isPrimaryProfileOnly()).isTrue();
    }

    @Test
    public void isPrimaryProfileOnly_profileAll_shouldReturnFalse() {
        mTile.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_ALL);
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_noExplicitValue_shouldReturnFalse() {
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_nullMetadata_shouldReturnFalse() {
        mTile.metaData = null;
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }
}
