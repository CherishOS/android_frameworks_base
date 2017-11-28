/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;

/**
 * App configuration change message.
 * @hide
 */
public class ConfigurationChangeItem extends ClientTransactionItem {

    private final Configuration mConfiguration;

    public ConfigurationChangeItem(Configuration configuration) {
        mConfiguration = new Configuration(configuration);
    }

    @Override
    public void prepare(android.app.ClientTransactionHandler client, IBinder token) {
        client.updatePendingConfiguration(mConfiguration);
    }

    @Override
    public void execute(android.app.ClientTransactionHandler client, IBinder token) {
        client.handleConfigurationChanged(mConfiguration);
    }

    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mConfiguration, flags);
    }

    /** Read from Parcel. */
    private ConfigurationChangeItem(Parcel in) {
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
    }

    public static final Creator<ConfigurationChangeItem> CREATOR =
            new Creator<ConfigurationChangeItem>() {
        public ConfigurationChangeItem createFromParcel(Parcel in) {
            return new ConfigurationChangeItem(in);
        }

        public ConfigurationChangeItem[] newArray(int size) {
            return new ConfigurationChangeItem[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ConfigurationChangeItem other = (ConfigurationChangeItem) o;
        return mConfiguration.equals(other.mConfiguration);
    }

    @Override
    public int hashCode() {
        return mConfiguration.hashCode();
    }
}
