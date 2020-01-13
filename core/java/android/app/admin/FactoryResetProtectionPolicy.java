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

package android.app.admin;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The factory reset protection policy determines which accounts can unlock a device that
 * has gone through untrusted factory reset.
 * <p>
 * Only a device owner or profile owner of an organization-owned device can set a factory
 * reset protection policy for the device by calling the {@code DevicePolicyManager} method
 * {@link DevicePolicyManager#setFactoryResetProtectionPolicy(ComponentName,
 * FactoryResetProtectionPolicy)}}.
 *
 * @see DevicePolicyManager#setFactoryResetProtectionPolicy
 * @see DevicePolicyManager#getFactoryResetProtectionPolicy
 */
public final class FactoryResetProtectionPolicy implements Parcelable {

    private static final String LOG_TAG = "FactoryResetProtectionPolicy";

    private static final String KEY_FACTORY_RESET_PROTECTION_ACCOUNT =
            "factory_reset_protection_account";
    private static final String KEY_FACTORY_RESET_PROTECTION_DISABLED =
            "factory_reset_protection_disabled";
    private static final String ATTR_VALUE = "value";

    private final List<String> mFactoryResetProtectionAccounts;
    private final boolean mFactoryResetProtectionDisabled;

    private FactoryResetProtectionPolicy(List<String> factoryResetProtectionAccounts,
            boolean factoryResetProtectionDisabled) {
        mFactoryResetProtectionAccounts = factoryResetProtectionAccounts;
        mFactoryResetProtectionDisabled = factoryResetProtectionDisabled;
    }

    /**
     * Get the list of accounts that can provision a device which has been factory reset.
     */
    public @NonNull List<String> getFactoryResetProtectionAccounts() {
        return mFactoryResetProtectionAccounts;
    }

    /**
     * Return whether factory reset protection for the device is disabled or not.
     */
    public boolean isFactoryResetProtectionDisabled() {
        return mFactoryResetProtectionDisabled;
    }

    /**
     * Builder class for {@link FactoryResetProtectionPolicy} objects.
     */
    public static class Builder {
        private List<String> mFactoryResetProtectionAccounts;
        private boolean mFactoryResetProtectionDisabled;

        /**
         * Initialize a new Builder to construct a {@link FactoryResetProtectionPolicy}.
         */
        public Builder() {
        };

        /**
         * Sets which accounts can unlock a device that has been factory reset.
         * <p>
         * Once set, the consumer unlock flow will be disabled and only accounts in this list
         * can unlock factory reset protection after untrusted factory reset.
         * <p>
         * It's up to the FRP management agent to interpret the {@code String} as account it
         * supports. Please consult their relevant documentation for details.
         *
         * @param factoryResetProtectionAccounts list of accounts.
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setFactoryResetProtectionAccounts(
                @NonNull List<String> factoryResetProtectionAccounts) {
            mFactoryResetProtectionAccounts = new ArrayList<>(factoryResetProtectionAccounts);
            return this;
        }

        /**
         * Sets whether factory reset protection is disabled or not.
         * <p>
         * Once disabled, factory reset protection will not kick in all together when the device
         * goes through untrusted factory reset. This applies to both the consumer unlock flow and
         * the admin account overrides via {@link #setFactoryResetProtectionAccounts}
         *
         * @param factoryResetProtectionDisabled Whether the policy is disabled or not.
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setFactoryResetProtectionDisabled(boolean factoryResetProtectionDisabled) {
            mFactoryResetProtectionDisabled = factoryResetProtectionDisabled;
            return this;
        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder}
         *
         * @return a new {@link FactoryResetProtectionPolicy} object.
         */
        @NonNull
        public FactoryResetProtectionPolicy build() {
            return new FactoryResetProtectionPolicy(mFactoryResetProtectionAccounts,
                    mFactoryResetProtectionDisabled);
        }
    }

    @Override
    public String toString() {
        return "FactoryResetProtectionPolicy{"
                + "mFactoryResetProtectionAccounts=" + mFactoryResetProtectionAccounts
                + ", mFactoryResetProtectionDisabled=" + mFactoryResetProtectionDisabled
                + '}';
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, @Nullable int flags) {
        int accountsCount = mFactoryResetProtectionAccounts.size();
        dest.writeInt(accountsCount);
        for (String account: mFactoryResetProtectionAccounts) {
            dest.writeString(account);
        }
        dest.writeBoolean(mFactoryResetProtectionDisabled);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<FactoryResetProtectionPolicy> CREATOR =
            new Creator<FactoryResetProtectionPolicy>() {

                @Override
                public FactoryResetProtectionPolicy createFromParcel(Parcel in) {
                    List<String> factoryResetProtectionAccounts = new ArrayList<>();
                    int accountsCount = in.readInt();
                    for (int i = 0; i < accountsCount; i++) {
                        factoryResetProtectionAccounts.add(in.readString());
                    }
                    boolean factoryResetProtectionDisabled = in.readBoolean();

                    return new FactoryResetProtectionPolicy(factoryResetProtectionAccounts,
                            factoryResetProtectionDisabled);
                }

                @Override
                public FactoryResetProtectionPolicy[] newArray(int size) {
                    return new FactoryResetProtectionPolicy[size];
                }
    };

    /**
     * Restore a previously saved FactoryResetProtectionPolicy from XML.
     * <p>
     * No validation is required on the reconstructed policy since the XML was previously
     * created by the system server from a validated policy.
     * @hide
     */
    @Nullable
    public static FactoryResetProtectionPolicy readFromXml(@NonNull XmlPullParser parser) {
        try {
            boolean factoryResetProtectionDisabled = Boolean.parseBoolean(
                    parser.getAttributeValue(null, KEY_FACTORY_RESET_PROTECTION_DISABLED));

            List<String> factoryResetProtectionAccounts = new ArrayList<>();
            int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != END_DOCUMENT
                    && (type != END_TAG || parser.getDepth() > outerDepth)) {
                if (type == END_TAG || type == TEXT) {
                    continue;
                }
                if (!parser.getName().equals(KEY_FACTORY_RESET_PROTECTION_ACCOUNT)) {
                    continue;
                }
                factoryResetProtectionAccounts.add(
                        parser.getAttributeValue(null, ATTR_VALUE));
            }

            return new FactoryResetProtectionPolicy(factoryResetProtectionAccounts,
                    factoryResetProtectionDisabled);
        } catch (XmlPullParserException | IOException e) {
            Log.w(LOG_TAG, "Reading from xml failed", e);
        }
        return null;
    }

    /**
     * @hide
     */
    public void writeToXml(@NonNull XmlSerializer out) throws IOException {
        out.attribute(null, KEY_FACTORY_RESET_PROTECTION_DISABLED,
                Boolean.toString(mFactoryResetProtectionDisabled));
        for (String account : mFactoryResetProtectionAccounts) {
            out.startTag(null, KEY_FACTORY_RESET_PROTECTION_ACCOUNT);
            out.attribute(null, ATTR_VALUE, account);
            out.endTag(null, KEY_FACTORY_RESET_PROTECTION_ACCOUNT);
        }
    }

}
