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

package android.media.tv.interactive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to specify meta information of a TV interactive app.
 * @hide
 */
public final class TvIAppInfo implements Parcelable {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvIAppInfo";

    private final ResolveInfo mService;
    private final String mId;
    private List<String> mTypes = new ArrayList<>();

    private TvIAppInfo(ResolveInfo service, String id, List<String> types) {
        mService = service;
        mId = id;
        mTypes = types;
    }

    protected TvIAppInfo(Parcel in) {
        mService = ResolveInfo.CREATOR.createFromParcel(in);
        mId = in.readString();
        in.readStringList(mTypes);
    }

    public static final Creator<TvIAppInfo> CREATOR = new Creator<TvIAppInfo>() {
        @Override
        public TvIAppInfo createFromParcel(Parcel in) {
            return new TvIAppInfo(in);
        }

        @Override
        public TvIAppInfo[] newArray(int size) {
            return new TvIAppInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mService.writeToParcel(dest, flags);
        dest.writeString(mId);
        dest.writeStringList(mTypes);
    }

    public String getId() {
        return mId;
    }

    /**
     * Returns the component of the TV IApp service.
     * @hide
     */
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Returns the information of the service that implements this TV IApp service.
     */
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * A convenience builder for creating {@link TvIAppInfo} objects.
     */
    public static final class Builder {
        private static final String XML_START_TAG_NAME = "tv-iapp";
        private final Context mContext;
        private final ResolveInfo mResolveInfo;
        private final List<String> mTypes = new ArrayList<>();

        /**
         * Constructs a new builder for {@link TvIAppInfo}.
         *
         * @param context A Context of the application package implementing this class.
         * @param component The name of the application component to be used for the
         *                  {@link TvIAppService}.
         */
        public Builder(Context context, ComponentName component) {
            if (context == null) {
                throw new IllegalArgumentException("context cannot be null.");
            }
            Intent intent = new Intent(TvIAppService.SERVICE_INTERFACE).setComponent(component);
            mResolveInfo = context.getPackageManager().resolveService(intent,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
            if (mResolveInfo == null) {
                throw new IllegalArgumentException("Invalid component. Can't find the service.");
            }
            mContext = context;
        }

        /**
         * Creates a {@link TvIAppInfo} instance with the specified fields. Most of the information
         * is obtained by parsing the AndroidManifest and {@link TvIAppService#SERVICE_META_DATA}
         * for the {@link TvIAppService} this TV IApp implements.
         *
         * @return TvIAppInfo containing information about this TV IApp service.
         */
        public TvIAppInfo build() {
            ComponentName componentName = new ComponentName(mResolveInfo.serviceInfo.packageName,
                    mResolveInfo.serviceInfo.name);
            String id;
            id = generateIAppServiceId(componentName);
            parseServiceMetadata();
            return new TvIAppInfo(mResolveInfo, id, mTypes);
        }

        private static String generateIAppServiceId(ComponentName name) {
            return name.flattenToShortString();
        }

        private void parseServiceMetadata() {
            ServiceInfo si = mResolveInfo.serviceInfo;
            PackageManager pm = mContext.getPackageManager();
            try (XmlResourceParser parser =
                         si.loadXmlMetaData(pm, TvIAppService.SERVICE_META_DATA)) {
                if (parser == null) {
                    throw new IllegalStateException("No " + TvIAppService.SERVICE_META_DATA
                            + " meta-data found for " + si.name);
                }

                Resources res = pm.getResourcesForApplication(si.applicationInfo);
                AttributeSet attrs = Xml.asAttributeSet(parser);

                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && type != XmlPullParser.START_TAG) {
                    // move to the START_TAG
                }

                String nodeName = parser.getName();
                if (!XML_START_TAG_NAME.equals(nodeName)) {
                    throw new IllegalStateException("Meta-data does not start with "
                            + XML_START_TAG_NAME + " tag for " + si.name);
                }

                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.TvIAppService);
                CharSequence[] types = sa.getTextArray(
                        com.android.internal.R.styleable.TvIAppService_supportedTypes);
                for (CharSequence cs : types) {
                    mTypes.add(cs.toString());
                }

                sa.recycle();
            } catch (IOException | XmlPullParserException e) {
                throw new IllegalStateException(
                        "Failed reading meta-data for " + si.packageName, e);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalStateException("No resources found for " + si.packageName, e);
            }
        }
    }
}
