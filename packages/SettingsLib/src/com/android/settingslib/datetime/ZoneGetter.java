/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.settingslib.datetime;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.icu.text.TimeZoneNames;
import android.support.v4.text.BidiFormatter;
import android.support.v4.text.TextDirectionHeuristicsCompat;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.TtsSpan;
import android.util.Log;
import android.view.View;

import com.android.settingslib.R;

import org.xmlpull.v1.XmlPullParserException;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * ZoneGetter is the utility class to get time zone and zone list, and both of them have display
 * name in time zone. In this class, we will keep consistency about display names for all
 * the methods.
 *
 * The display name chosen for each zone entry depends on whether the zone is one associated
 * with the country of the user's chosen locale. For "local" zones we prefer the "long name"
 * (e.g. "Europe/London" -> "British Summer Time" for people in the UK). For "non-local"
 * zones we prefer the exemplar location (e.g. "Europe/London" -> "London" for English
 * speakers from outside the UK). This heuristic is based on the fact that people are
 * typically familiar with their local timezones and exemplar locations don't always match
 * modern-day expectations for people living in the country covered. Large countries like
 * China that mostly use a single timezone (olson id: "Asia/Shanghai") may not live near
 * "Shanghai" and prefer the long name over the exemplar location. The only time we don't
 * follow this policy for local zones is when Android supplies multiple olson IDs to choose
 * from and the use of a zone's long name leads to ambiguity. For example, at the time of
 * writing Android lists 5 olson ids for Australia which collapse to 2 different zone names
 * in winter but 4 different zone names in summer. The ambiguity leads to the users
 * selecting the wrong olson ids.
 *
 */
public class ZoneGetter {
    private static final String TAG = "ZoneGetter";

    public static final String KEY_ID = "id";  // value: String

    /**
     * @deprecated Use {@link #KEY_DISPLAY_LABEL} instead.
     */
    @Deprecated
    public static final String KEY_DISPLAYNAME = "name";  // value: String

    public static final String KEY_DISPLAY_LABEL = "display_label"; // value: CharSequence

    /**
     * @deprecated Use {@link #KEY_OFFSET_LABEL} instead.
     */
    @Deprecated
    public static final String KEY_GMT = "gmt";  // value: String
    public static final String KEY_OFFSET = "offset";  // value: int (Integer)
    public static final String KEY_OFFSET_LABEL = "offset_label";  // value: CharSequence

    private static final String XMLTAG_TIMEZONE = "timezone";

    public static CharSequence getTimeZoneOffsetAndName(Context context, TimeZone tz, Date now) {
        Locale locale = Locale.getDefault();
        CharSequence gmtText = getGmtOffsetText(context, locale, tz, now);
        TimeZoneNames timeZoneNames = TimeZoneNames.getInstance(locale);
        String zoneNameString = getZoneLongName(timeZoneNames, tz, now);
        if (zoneNameString == null) {
            return gmtText;
        }

        // We don't use punctuation here to avoid having to worry about localizing that too!
        return TextUtils.concat(gmtText, " ", zoneNameString);
    }

    public static List<Map<String, Object>> getZonesList(Context context) {
        final Locale locale = Locale.getDefault();
        final Date now = new Date();
        final TimeZoneNames timeZoneNames = TimeZoneNames.getInstance(locale);
        final ZoneGetterData data = new ZoneGetterData(context);

        // Work out whether the display names we would show by default would be ambiguous.
        final boolean useExemplarLocationForLocalNames =
                shouldUseExemplarLocationForLocalNames(data, timeZoneNames);

        // Generate the list of zone entries to return.
        List<Map<String, Object>> zones = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < data.zoneCount; i++) {
            TimeZone tz = data.timeZones[i];
            CharSequence gmtOffsetText = data.gmtOffsetTexts[i];

            CharSequence displayName = getTimeZoneDisplayName(data, timeZoneNames,
                    useExemplarLocationForLocalNames, tz, data.olsonIdsToDisplay[i]);
            if (TextUtils.isEmpty(displayName)) {
                displayName = gmtOffsetText;
            }

            int offsetMillis = tz.getOffset(now.getTime());
            Map<String, Object> displayEntry =
                    createDisplayEntry(tz, gmtOffsetText, displayName, offsetMillis);
            zones.add(displayEntry);
        }
        return zones;
    }

    private static Map<String, Object> createDisplayEntry(
            TimeZone tz, CharSequence gmtOffsetText, CharSequence displayName, int offsetMillis) {
        Map<String, Object> map = new HashMap<>();
        map.put(KEY_ID, tz.getID());
        map.put(KEY_DISPLAYNAME, displayName.toString());
        map.put(KEY_DISPLAY_LABEL, displayName);
        map.put(KEY_GMT, gmtOffsetText.toString());
        map.put(KEY_OFFSET_LABEL, gmtOffsetText);
        map.put(KEY_OFFSET, offsetMillis);
        return map;
    }

    private static List<String> readTimezonesToDisplay(Context context) {
        List<String> olsonIds = new ArrayList<String>();
        try (XmlResourceParser xrp = context.getResources().getXml(R.xml.timezones)) {
            while (xrp.next() != XmlResourceParser.START_TAG) {
                continue;
            }
            xrp.next();
            while (xrp.getEventType() != XmlResourceParser.END_TAG) {
                while (xrp.getEventType() != XmlResourceParser.START_TAG) {
                    if (xrp.getEventType() == XmlResourceParser.END_DOCUMENT) {
                        return olsonIds;
                    }
                    xrp.next();
                }
                if (xrp.getName().equals(XMLTAG_TIMEZONE)) {
                    String olsonId = xrp.getAttributeValue(0);
                    olsonIds.add(olsonId);
                }
                while (xrp.getEventType() != XmlResourceParser.END_TAG) {
                    xrp.next();
                }
                xrp.next();
            }
        } catch (XmlPullParserException xppe) {
            Log.e(TAG, "Ill-formatted timezones.xml file");
        } catch (java.io.IOException ioe) {
            Log.e(TAG, "Unable to read timezones.xml file");
        }
        return olsonIds;
    }

    private static boolean shouldUseExemplarLocationForLocalNames(ZoneGetterData data,
            TimeZoneNames timeZoneNames) {
        final Set<CharSequence> localZoneNames = new HashSet<>();
        final Date now = new Date();
        for (int i = 0; i < data.zoneCount; i++) {
            final String olsonId = data.olsonIdsToDisplay[i];
            if (data.localZoneIds.contains(olsonId)) {
                final TimeZone tz = data.timeZones[i];
                CharSequence displayName = getZoneLongName(timeZoneNames, tz, now);
                if (displayName == null) {
                    displayName = data.gmtOffsetTexts[i];
                }
                final boolean nameIsUnique = localZoneNames.add(displayName);
                if (!nameIsUnique) {
                    return true;
                }
            }
        }

        return false;
    }

    private static CharSequence getTimeZoneDisplayName(ZoneGetterData data,
            TimeZoneNames timeZoneNames, boolean useExemplarLocationForLocalNames, TimeZone tz,
            String olsonId) {
        final Date now = new Date();
        final boolean isLocalZoneId = data.localZoneIds.contains(olsonId);
        final boolean preferLongName = isLocalZoneId && !useExemplarLocationForLocalNames;
        String displayName;

        if (preferLongName) {
            displayName = getZoneLongName(timeZoneNames, tz, now);
        } else {
            // Canonicalize the zone ID for ICU. It will only return valid strings for zone IDs
            // that match ICUs zone IDs (which are similar but not guaranteed the same as those
            // in timezones.xml). timezones.xml and related files uses the IANA IDs. ICU IDs are
            // stable and IANA IDs have changed over time so they have drifted.
            // See http://bugs.icu-project.org/trac/ticket/13070 / http://b/36469833.
            String canonicalZoneId = android.icu.util.TimeZone.getCanonicalID(tz.getID());
            if (canonicalZoneId == null) {
                canonicalZoneId = tz.getID();
            }
            displayName = timeZoneNames.getExemplarLocationName(canonicalZoneId);
            if (displayName == null || displayName.isEmpty()) {
                // getZoneExemplarLocation can return null. Fall back to the long name.
                displayName = getZoneLongName(timeZoneNames, tz, now);
            }
        }

        return displayName;
    }

    /**
     * Returns the long name for the timezone for the given locale at the time specified.
     * Can return {@code null}.
     */
    private static String getZoneLongName(TimeZoneNames names, TimeZone tz, Date now) {
        final TimeZoneNames.NameType nameType =
                tz.inDaylightTime(now) ? TimeZoneNames.NameType.LONG_DAYLIGHT
                        : TimeZoneNames.NameType.LONG_STANDARD;
        return names.getDisplayName(tz.getID(), nameType, now.getTime());
    }

    private static void appendWithTtsSpan(SpannableStringBuilder builder, CharSequence content,
            TtsSpan span) {
        int start = builder.length();
        builder.append(content);
        builder.setSpan(span, start, builder.length(), 0);
    }

    private static String twoDigits(int input) {
        StringBuilder builder = new StringBuilder(3);
        if (input < 0) builder.append('-');
        String string = Integer.toString(Math.abs(input));
        if (string.length() == 1) builder.append("0");
        builder.append(string);
        return builder.toString();
    }

    /**
     * Get the GMT offset text label for the given time zone, in the format "GMT-08:00". This will
     * also add TTS spans to give hints to the text-to-speech engine for the type of data it is.
     *
     * @param context The context which the string is displayed in.
     * @param locale The locale which the string is displayed in. This should be the same as the
     *               locale of the context.
     * @param tz Time zone to get the GMT offset from.
     * @param now The current time, used to tell whether daylight savings is active.
     * @return A CharSequence suitable for display as the offset label of {@code tz}.
     */
    private static CharSequence getGmtOffsetText(Context context, Locale locale, TimeZone tz,
            Date now) {
        SpannableStringBuilder builder = new SpannableStringBuilder();

        appendWithTtsSpan(builder, "GMT",
                new TtsSpan.TextBuilder(context.getString(R.string.time_zone_gmt)).build());

        int offsetMillis = tz.getOffset(now.getTime());
        if (offsetMillis >= 0) {
            appendWithTtsSpan(builder, "+", new TtsSpan.VerbatimBuilder("+").build());
        }

        final int offsetHours = (int) (offsetMillis / DateUtils.HOUR_IN_MILLIS);
        appendWithTtsSpan(builder, twoDigits(offsetHours),
                new TtsSpan.MeasureBuilder().setNumber(offsetHours).setUnit("hour").build());

        builder.append(":");

        final int offsetMinutes = (int) (offsetMillis / DateUtils.MINUTE_IN_MILLIS);
        final int offsetMinutesRemaining = Math.abs(offsetMinutes) % 60;
        appendWithTtsSpan(builder, twoDigits(offsetMinutesRemaining),
                new TtsSpan.MeasureBuilder().setNumber(offsetMinutesRemaining)
                        .setUnit("minute").build());

        CharSequence gmtText = new SpannableString(builder);

        // Ensure that the "GMT+" stays with the "00:00" even if the digits are RTL.
        final BidiFormatter bidiFormatter = BidiFormatter.getInstance();
        boolean isRtl = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL;
        gmtText = bidiFormatter.unicodeWrap(gmtText,
                isRtl ? TextDirectionHeuristicsCompat.RTL : TextDirectionHeuristicsCompat.LTR);
        return gmtText;
    }

    private static final class ZoneGetterData {
        public final String[] olsonIdsToDisplay;
        public final CharSequence[] gmtOffsetTexts;
        public final TimeZone[] timeZones;
        public final Set<String> localZoneIds;
        public final int zoneCount;

        public ZoneGetterData(Context context) {
            final Locale locale = Locale.getDefault();
            final Date now = new Date();
            final List<String> olsonIdsToDisplayList = readTimezonesToDisplay(context);

            // Load all the data needed to display time zones
            zoneCount = olsonIdsToDisplayList.size();
            olsonIdsToDisplay = new String[zoneCount];
            timeZones = new TimeZone[zoneCount];
            gmtOffsetTexts = new CharSequence[zoneCount];
            for (int i = 0; i < zoneCount; i++) {
                final String olsonId = olsonIdsToDisplayList.get(i);
                olsonIdsToDisplay[i] = olsonId;
                final TimeZone tz = TimeZone.getTimeZone(olsonId);
                timeZones[i] = tz;
                gmtOffsetTexts[i] = getGmtOffsetText(context, locale, tz, now);
            }

            // Create a lookup of local zone IDs.
            localZoneIds = new HashSet<String>();
            for (String olsonId : libcore.icu.TimeZoneNames.forLocale(locale)) {
                localZoneIds.add(olsonId);
            }
        }
    }
}
