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

package com.android.server.pm.pkg.parsing;

import static com.android.server.pm.pkg.parsing.ParsingPackageUtils.RIGID_PARSER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.XmlResourceParser;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.util.Parcelling;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** @hide **/
public class ParsingUtils {

    public static final String TAG = "PackageParsing";

    public static final String ANDROID_RES_NAMESPACE = "http://schemas.android.com/apk/res/android";

    public static final int DEFAULT_MIN_SDK_VERSION = 1;
    public static final int DEFAULT_TARGET_SDK_VERSION = 0;

    public static final int NOT_SET = -1;

    @Nullable
    public static String buildClassName(String pkg, CharSequence clsSeq) {
        if (clsSeq == null || clsSeq.length() <= 0) {
            return null;
        }
        String cls = clsSeq.toString();
        char c = cls.charAt(0);
        if (c == '.') {
            return pkg + cls;
        }
        if (cls.indexOf('.') < 0) {
            StringBuilder b = new StringBuilder(pkg);
            b.append('.');
            b.append(cls);
            return b.toString();
        }
        return cls;
    }

    @NonNull
    public static ParseResult unknownTag(String parentTag, ParsingPackage pkg,
            XmlResourceParser parser, ParseInput input) throws IOException, XmlPullParserException {
        if (RIGID_PARSER) {
            return input.error("Bad element under " + parentTag + ": " + parser.getName());
        }
        Slog.w(TAG, "Unknown element under " + parentTag + ": "
                + parser.getName() + " at " + pkg.getBaseApkPath() + " "
                + parser.getPositionDescription());
        XmlUtils.skipCurrentTag(parser);
        return input.success(null); // Type doesn't matter
    }

    /**
     * Use with {@link Parcel#writeTypedList(List)}
     *
     * @see Parcel#createTypedArrayList(Parcelable.Creator)
     */
    @NonNull
    public static <Interface, Impl extends Interface> List<Interface> createTypedInterfaceList(
            @NonNull Parcel parcel, @NonNull Parcelable.Creator<Impl> creator) {
        int size = parcel.readInt();
        if (size < 0) {
            return new ArrayList<>();
        }
        ArrayList<Interface> list = new ArrayList<Interface>(size);
        while (size > 0) {
            list.add(parcel.readTypedObject(creator));
            size--;
        }
        return list;
    }

    public static class StringPairListParceler implements
            Parcelling<List<Pair<String, ParsedIntentInfo>>> {

        @Override
        public void parcel(List<Pair<String, ParsedIntentInfo>> item, Parcel dest,
                int parcelFlags) {
            if (item == null) {
                dest.writeInt(-1);
                return;
            }

            final int size = item.size();
            dest.writeInt(size);

            for (int index = 0; index < size; index++) {
                Pair<String, ParsedIntentInfo> pair = item.get(index);
                dest.writeString(pair.first);
                dest.writeParcelable(pair.second, parcelFlags);
            }
        }

        @Override
        public List<Pair<String, ParsedIntentInfo>> unparcel(Parcel source) {
            int size = source.readInt();
            if (size == -1) {
                return null;
            }

            if (size == 0) {
                return new ArrayList<>(0);
            }

            final List<Pair<String, ParsedIntentInfo>> list = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                list.add(Pair.create(source.readString(), source.readParcelable(
                        ParsedIntentInfoImpl.class.getClassLoader(), ParsedIntentInfo.class)));
            }

            return list;
        }
    }
}
