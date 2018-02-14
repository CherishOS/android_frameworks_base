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
 * limitations under the License.
 */

package android.app.admin;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Pair;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A class that represents a local system update policy set by the device owner.
 *
 * @see DevicePolicyManager#setSystemUpdatePolicy
 * @see DevicePolicyManager#getSystemUpdatePolicy
 */
public class SystemUpdatePolicy implements Parcelable {
    private static final String TAG = "SystemUpdatePolicy";

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_INSTALL_AUTOMATIC,
            TYPE_INSTALL_WINDOWED,
            TYPE_POSTPONE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SystemUpdatePolicyType {}

    /**
     * Unknown policy type, used only internally.
     */
    private static final int TYPE_UNKNOWN = -1;

    /**
     * Install system update automatically as soon as one is available.
     */
    public static final int TYPE_INSTALL_AUTOMATIC = 1;

    /**
     * Install system update automatically within a daily maintenance window. An update can be
     * delayed for a maximum of 30 days, after which the policy will no longer be effective and the
     * system will revert back to its normal behavior as if no policy were set.
     *
     * <p>After this policy expires, resetting it to any policy other than
     * {@link #TYPE_INSTALL_AUTOMATIC} will produce no effect, as the 30-day maximum delay has
     * already been used up.
     * The {@link #TYPE_INSTALL_AUTOMATIC} policy will still take effect to install the delayed
     * system update immediately.
     *
     * <p>Re-applying this policy or changing it to {@link #TYPE_POSTPONE} within the 30-day period
     * will <i>not</i> extend policy expiration.
     * However, the expiration will be recalculated when a new system update is made available.
     */
    public static final int TYPE_INSTALL_WINDOWED = 2;

    /**
     * Incoming system updates (except for security updates) will be blocked for a maximum of 30
     * days, after which the policy will no longer be effective and the system will revert back to
     * its normal behavior as if no policy were set.
     *
     * <p><b>Note:</b> security updates (e.g. monthly security patches) may <i>not</i> be affected
     * by this policy, depending on the policy set by the device manufacturer and carrier.
     *
     * <p>After this policy expires, resetting it to any policy other than
     * {@link #TYPE_INSTALL_AUTOMATIC} will produce no effect, as the 30-day maximum delay has
     * already been used up.
     * The {@link #TYPE_INSTALL_AUTOMATIC} policy will still take effect to install the delayed
     * system update immediately.
     *
     * <p>Re-applying this policy or changing it to {@link #TYPE_INSTALL_WINDOWED} within the 30-day
     * period will <i>not</i> extend policy expiration.
     * However, the expiration will be recalculated when a new system update is made available.
     */
    public static final int TYPE_POSTPONE = 3;

    /**
     * Incoming system updates (including security updates) should be blocked. This flag is not
     * exposed to third-party apps (and any attempt to set it will raise exceptions). This is used
     * to represent the current installation option type to the privileged system update clients,
     * for example to indicate OTA freeze is currently in place or when system is outside a daily
     * maintenance window.
     *
     * @see InstallationOption
     * @hide
     */
    @SystemApi
    public static final int TYPE_PAUSE = 4;

    private static final String KEY_POLICY_TYPE = "policy_type";
    private static final String KEY_INSTALL_WINDOW_START = "install_window_start";
    private static final String KEY_INSTALL_WINDOW_END = "install_window_end";
    private static final String KEY_FREEZE_TAG = "freeze";
    private static final String KEY_FREEZE_START = "start";
    private static final String KEY_FREEZE_END = "end";

    /**
     * The upper boundary of the daily maintenance window: 24 * 60 minutes.
     */
    private static final int WINDOW_BOUNDARY = 24 * 60;

    /**
     * The maximum length of a single freeze period: 90  days.
     */
    static final int FREEZE_PERIOD_MAX_LENGTH = 90;

    /**
     * The minimum allowed time between two adjacent freeze period (from the end of the first
     * freeze period to the start of the second freeze period, both exclusive): 60 days.
     */
    static final int FREEZE_PERIOD_MIN_SEPARATION = 60;


    /**
     * An exception class that represents various validation errors thrown from
     * {@link SystemUpdatePolicy#setFreezePeriods} and
     * {@link DevicePolicyManager#setSystemUpdatePolicy}
     */
    public static final class ValidationFailedException extends IllegalArgumentException
            implements Parcelable {

        /** @hide */
        @IntDef(prefix = { "ERROR_" }, value = {
                ERROR_NONE,
                ERROR_DUPLICATE_OR_OVERLAP,
                ERROR_NEW_FREEZE_PERIOD_TOO_LONG,
                ERROR_NEW_FREEZE_PERIOD_TOO_CLOSE,
                ERROR_COMBINED_FREEZE_PERIOD_TOO_LONG,
                ERROR_COMBINED_FREEZE_PERIOD_TOO_CLOSE,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface ValidationFailureType {}

        /** @hide */
        public static final int ERROR_NONE = 0;

        /**
         * The freeze periods contains duplicates, periods that overlap with each
         * other or periods whose start and end joins.
         */
        public static final int ERROR_DUPLICATE_OR_OVERLAP = 1;

        /**
         * There exists at least one freeze period whose length exceeds 90 days.
         */
        public static final int ERROR_NEW_FREEZE_PERIOD_TOO_LONG = 2;

        /**
         * There exists some freeze period which starts within 60 days of the preceding period's
         * end time.
         */
        public static final int ERROR_NEW_FREEZE_PERIOD_TOO_CLOSE = 3;

        /**
         * The device has been in a freeze period and when combining with the new freeze period
         * to be set, it will result in the total freeze period being longer than 90 days.
         */
        public static final int ERROR_COMBINED_FREEZE_PERIOD_TOO_LONG = 4;

        /**
         * The device has been in a freeze period and some new freeze period to be set is less
         * than 60 days from the end of the last freeze period the device went through.
         */
        public static final int ERROR_COMBINED_FREEZE_PERIOD_TOO_CLOSE = 5;

        @ValidationFailureType
        private final int mErrorCode;

        private ValidationFailedException(int errorCode, String message) {
            super(message);
            mErrorCode = errorCode;
        }

        /**
         * Returns the type of validation error associated with this exception.
         */
        public @ValidationFailureType int getErrorCode() {
            return mErrorCode;
        }

        /** @hide */
        public static ValidationFailedException duplicateOrOverlapPeriods() {
            return new ValidationFailedException(ERROR_DUPLICATE_OR_OVERLAP,
                    "Found duplicate or overlapping periods");
        }

        /** @hide */
        public static ValidationFailedException freezePeriodTooLong(String message) {
            return new ValidationFailedException(ERROR_NEW_FREEZE_PERIOD_TOO_LONG, message);
        }

        /** @hide */
        public static ValidationFailedException freezePeriodTooClose(String message) {
            return new ValidationFailedException(ERROR_NEW_FREEZE_PERIOD_TOO_CLOSE, message);
        }

        /** @hide */
        public static ValidationFailedException combinedPeriodTooLong(String message) {
            return new ValidationFailedException(ERROR_COMBINED_FREEZE_PERIOD_TOO_LONG, message);
        }

        /** @hide */
        public static ValidationFailedException combinedPeriodTooClose(String message) {
            return new ValidationFailedException(ERROR_COMBINED_FREEZE_PERIOD_TOO_CLOSE, message);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mErrorCode);
            dest.writeString(getMessage());
        }

        public static final Parcelable.Creator<ValidationFailedException> CREATOR =
                new Parcelable.Creator<ValidationFailedException>() {
            @Override
            public ValidationFailedException createFromParcel(Parcel source) {
                return new ValidationFailedException(source.readInt(), source.readString());
            }

            @Override
            public ValidationFailedException[] newArray(int size) {
                return new ValidationFailedException[size];
            }

        };
    }

    @SystemUpdatePolicyType
    private int mPolicyType;

    private int mMaintenanceWindowStart;
    private int mMaintenanceWindowEnd;

    private final ArrayList<FreezeInterval> mFreezePeriods;

    private SystemUpdatePolicy() {
        mPolicyType = TYPE_UNKNOWN;
        mFreezePeriods = new ArrayList<>();
    }

    /**
     * Create a policy object and set it to install update automatically as soon as one is
     * available.
     *
     * @see #TYPE_INSTALL_AUTOMATIC
     */
    public static SystemUpdatePolicy createAutomaticInstallPolicy() {
        SystemUpdatePolicy policy = new SystemUpdatePolicy();
        policy.mPolicyType = TYPE_INSTALL_AUTOMATIC;
        return policy;
    }

    /**
     * Create a policy object and set it to: new system update will only be installed automatically
     * when the system clock is inside a daily maintenance window. If the start and end times are
     * the same, the window is considered to include the <i>whole 24 hours</i>. That is, updates can
     * install at any time. If the given window in invalid, an {@link IllegalArgumentException}
     * will be thrown. If start time is later than end time, the window is considered spanning
     * midnight (i.e. the end time denotes a time on the next day). The maintenance window will last
     * for 30 days, after which the system will revert back to its normal behavior as if no policy
     * were set.
     *
     * @param startTime the start of the maintenance window, measured as the number of minutes from
     *            midnight in the device's local time. Must be in the range of [0, 1440).
     * @param endTime the end of the maintenance window, measured as the number of minutes from
     *            midnight in the device's local time. Must be in the range of [0, 1440).
     * @see #TYPE_INSTALL_WINDOWED
     */
    public static SystemUpdatePolicy createWindowedInstallPolicy(int startTime, int endTime) {
        if (startTime < 0 || startTime >= WINDOW_BOUNDARY
                || endTime < 0 || endTime >= WINDOW_BOUNDARY) {
            throw new IllegalArgumentException("startTime and endTime must be inside [0, 1440)");
        }
        SystemUpdatePolicy policy = new SystemUpdatePolicy();
        policy.mPolicyType = TYPE_INSTALL_WINDOWED;
        policy.mMaintenanceWindowStart = startTime;
        policy.mMaintenanceWindowEnd = endTime;
        return policy;
    }

    /**
     * Create a policy object and set it to block installation for a maximum period of 30 days.
     * After expiration the system will revert back to its normal behavior as if no policy were
     * set.
     *
     * <p><b>Note: </b> security updates (e.g. monthly security patches) will <i>not</i> be affected
     * by this policy.
     *
     * @see #TYPE_POSTPONE
     */
    public static SystemUpdatePolicy createPostponeInstallPolicy() {
        SystemUpdatePolicy policy = new SystemUpdatePolicy();
        policy.mPolicyType = TYPE_POSTPONE;
        return policy;
    }

    /**
     * Returns the type of system update policy.
     *
     * @return an integer, either one of {@link #TYPE_INSTALL_AUTOMATIC},
     * {@link #TYPE_INSTALL_WINDOWED} and {@link #TYPE_POSTPONE}, or -1 if no policy has been set.
     */
    @SystemUpdatePolicyType
    public int getPolicyType() {
        return mPolicyType;
    }

    /**
     * Get the start of the maintenance window.
     *
     * @return the start of the maintenance window measured as the number of minutes from midnight,
     * or -1 if the policy does not have a maintenance window.
     */
    public int getInstallWindowStart() {
        if (mPolicyType == TYPE_INSTALL_WINDOWED) {
            return mMaintenanceWindowStart;
        } else {
            return -1;
        }
    }

    /**
     * Get the end of the maintenance window.
     *
     * @return the end of the maintenance window measured as the number of minutes from midnight,
     * or -1 if the policy does not have a maintenance window.
     */
    public int getInstallWindowEnd() {
        if (mPolicyType == TYPE_INSTALL_WINDOWED) {
            return mMaintenanceWindowEnd;
        } else {
            return -1;
        }
    }

    /**
     * Return if this object represents a valid policy with:
     * 1. Correct type
     * 2. Valid maintenance window if applicable
     * 3. Valid freeze periods
     * @hide
     */
    public boolean isValid() {
        try {
            validateType();
            validateFreezePeriods();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validate the type and maintenance window (if applicable) of this policy object,
     * throws {@link IllegalArgumentException} if it's invalid.
     * @hide
     */
    public void validateType() {
        if (mPolicyType == TYPE_INSTALL_AUTOMATIC || mPolicyType == TYPE_POSTPONE) {
            return;
        } else if (mPolicyType == TYPE_INSTALL_WINDOWED) {
            if (!(mMaintenanceWindowStart >= 0 && mMaintenanceWindowStart < WINDOW_BOUNDARY
                    && mMaintenanceWindowEnd >= 0 && mMaintenanceWindowEnd < WINDOW_BOUNDARY)) {
                throw new IllegalArgumentException("Invalid maintenance window");
            }
        } else {
            throw new IllegalArgumentException("Invalid system update policy type.");
        }
    }

    /**
     * Configure a list of freeze periods on top of the current policy. When the device's clock is
     * within any of the freeze periods, all incoming system updates including security patches will
     * be blocked and cannot be installed. When the device is outside the freeze periods, the normal
     * policy behavior will apply.
     * <p>
     * Each freeze period is defined by a starting and finishing date (both inclusive). Since the
     * freeze period repeats annually, both of these dates are simply represented by integers
     * counting the number of days since year start, similar to {@link LocalDate#getDayOfYear()}. We
     * do not consider leap year when handling freeze period so the valid range of the integer is
     * always [1,365] (see last section for more details on leap year). If the finishing date is
     * smaller than the starting date, the freeze period is considered to be spanning across
     * year-end.
     * <p>
     * Each individual freeze period is allowed to be at most 90 days long, and adjacent freeze
     * periods need to be at least 60 days apart. Also, the list of freeze periods should not
     * contain duplicates or overlap with each other. If any of these conditions is not met, a
     * {@link ValidationFailedException} will be thrown.
     * <p>
     * Handling of leap year: we do not consider leap year when handling freeze period, in
     * particular,
     * <ul>
     * <li>When a freeze period is defined by the day of year, February 29th does not count as one
     * day, so day 59 is February 28th while day 60 is March 1st.</li>
     * <li>When applying freeze period behavior to the device, a system clock of February 29th is
     * treated as if it were February 28th</li>
     * <li>When calculating the number of days of a freeze period or separation between two freeze
     * periods, February 29th is also ignored and not counted as one day.</li>
     * </ul>
     *
     * @param freezePeriods the list of freeze periods
     * @throws ValidationFailedException if the supplied freeze periods do not meet the
     *         requirement set above
     * @return this instance
     */
    public SystemUpdatePolicy setFreezePeriods(List<Pair<Integer, Integer>> freezePeriods) {
        List<FreezeInterval> newPeriods = freezePeriods.stream().map(
                p -> new FreezeInterval(p.first, p.second)).collect(Collectors.toList());
        FreezeInterval.validatePeriods(newPeriods);
        mFreezePeriods.clear();
        mFreezePeriods.addAll(newPeriods);
        return this;
    }

    /**
     * Returns the list of freeze periods previously set on this system update policy object.
     *
     * @return the list of freeze periods, or an empty list if none was set.
     */
    public List<Pair<Integer, Integer>> getFreezePeriods() {
        List<Pair<Integer, Integer>> result = new ArrayList<>(mFreezePeriods.size());
        for (FreezeInterval interval : mFreezePeriods) {
            result.add(new Pair<>(interval.mStartDay, interval.mEndDay));
        }
        return result;
    }

    /**
     * Returns the real calendar dates of the current freeze period, or null if the device
     * is not in a freeze period at the moment.
     * @hide
     */
    public Pair<LocalDate, LocalDate> getCurrentFreezePeriod(LocalDate now) {
        for (FreezeInterval interval : mFreezePeriods) {
            if (interval.contains(now)) {
                return interval.toCurrentOrFutureRealDates(now);
            }
        }
        return null;
    }

    /**
     * Returns time (in milliseconds) until the start of the next freeze period, assuming now
     * is not within a freeze period.
     */
    private long timeUntilNextFreezePeriod(long now) {
        List<FreezeInterval> sortedPeriods = FreezeInterval.canonicalizeIntervals(mFreezePeriods);
        LocalDate nowDate = millisToDate(now);
        LocalDate nextFreezeStart = null;
        for (FreezeInterval interval : sortedPeriods) {
            if (interval.after(nowDate)) {
                nextFreezeStart = interval.toCurrentOrFutureRealDates(nowDate).first;
                break;
            } else if (interval.contains(nowDate)) {
                throw new IllegalArgumentException("Given date is inside a freeze period");
            }
        }
        if (nextFreezeStart == null) {
            // If no interval is after now, then it must be the one that starts at the beginning
            // of next year
            nextFreezeStart = sortedPeriods.get(0).toCurrentOrFutureRealDates(nowDate).first;
        }
        return dateToMillis(nextFreezeStart) - now;
    }

    /** @hide */
    public void validateFreezePeriods() {
        FreezeInterval.validatePeriods(mFreezePeriods);
    }

    /** @hide */
    public void validateAgainstPreviousFreezePeriod(LocalDate prevPeriodStart,
            LocalDate prevPeriodEnd, LocalDate now) {
        FreezeInterval.validateAgainstPreviousFreezePeriod(mFreezePeriods, prevPeriodStart,
                prevPeriodEnd, now);
    }

    /**
     * An installation option represents how system update clients should act on incoming system
     * updates and how long this action is valid for, given the current system update policy. Its
     * action could be one of the following
     * <ul>
     * <li> {@code TYPE_INSTALL_AUTOMATIC} system updates should be installed immedately and without
     * user intervention as soon as they become available.
     * <li> {@code TYPE_POSTPONE} system updates should be postponed for a maximum of 30 days
     * <li> {@code TYPE_PAUSE} system updates should be postponed indefinitely until further notice
     * </ul>
     *
     * The effective time measures how long this installation option is valid for from the queried
     * time, in milliseconds.
     *
     * This is an internal API for system update clients.
     * @hide
     */
    @SystemApi
    public static class InstallationOption {
        private final int mType;
        private long mEffectiveTime;

        InstallationOption(int type, long effectiveTime) {
            this.mType = type;
            this.mEffectiveTime = effectiveTime;
        }

        public int getType() {
            return mType;
        }

        public long getEffectiveTime() {
            return mEffectiveTime;
        }

        /** @hide */
        protected void limitEffectiveTime(long otherTime) {
            mEffectiveTime = Long.min(mEffectiveTime, otherTime);
        }
    }

    /**
     * Returns the installation option at the specified time, under the current
     * {@code SystemUpdatePolicy} object. This is a convenience method for system update clients
     * so they can instantiate this policy at any given time and find out what to do with incoming
     * system updates, without the need of examining the overall policy structure.
     *
     * Normally the system update clients will query the current installation option by calling this
     * method with the current timestamp, and act on the returned option until its effective time
     * lapses. It can then query the latest option using a new timestamp. It should also listen
     * for {@code DevicePolicyManager#ACTION_SYSTEM_UPDATE_POLICY_CHANGED} broadcast, in case the
     * whole policy is updated.
     *
     * @param when At what time the intallation option is being queried, specified in number of
           milliseonds since the epoch.
     * @see InstallationOption
     * @hide
     */
    @SystemApi
    public InstallationOption getInstallationOptionAt(long when) {
        LocalDate whenDate = millisToDate(when);
        Pair<LocalDate, LocalDate> current = getCurrentFreezePeriod(whenDate);
        if (current != null) {
            return new InstallationOption(TYPE_PAUSE,
                    dateToMillis(roundUpLeapDay(current.second).plusDays(1)) - when);
        }
        // We are not within a freeze period, query the underlying policy.
        // But also consider the start of the next freeze period, which might
        // reduce the effective time of the current installation option
        InstallationOption option = getInstallationOptionRegardlessFreezeAt(when);
        if (mFreezePeriods.size() > 0) {
            option.limitEffectiveTime(timeUntilNextFreezePeriod(when));
        }
        return option;
    }

    private InstallationOption getInstallationOptionRegardlessFreezeAt(long when) {
        if (mPolicyType == TYPE_INSTALL_AUTOMATIC || mPolicyType == TYPE_POSTPONE) {
            return new InstallationOption(mPolicyType, Long.MAX_VALUE);
        } else if (mPolicyType == TYPE_INSTALL_WINDOWED) {
            Calendar query = Calendar.getInstance();
            query.setTimeInMillis(when);
            // Calculate the number of milliseconds since midnight of the time specified by when
            long whenMillis = TimeUnit.HOURS.toMillis(query.get(Calendar.HOUR_OF_DAY))
                    + TimeUnit.MINUTES.toMillis(query.get(Calendar.MINUTE))
                    + TimeUnit.SECONDS.toMillis(query.get(Calendar.SECOND))
                    + query.get(Calendar.MILLISECOND);
            long windowStartMillis = TimeUnit.MINUTES.toMillis(mMaintenanceWindowStart);
            long windowEndMillis = TimeUnit.MINUTES.toMillis(mMaintenanceWindowEnd);
            final long dayInMillis = TimeUnit.DAYS.toMillis(1);

            if ((windowStartMillis <= whenMillis && whenMillis <= windowEndMillis)
                    || ((windowStartMillis > windowEndMillis)
                    && (windowStartMillis <= whenMillis || whenMillis <= windowEndMillis))) {
                return new InstallationOption(TYPE_INSTALL_AUTOMATIC,
                        (windowEndMillis - whenMillis + dayInMillis) % dayInMillis);
            } else {
                return new InstallationOption(TYPE_PAUSE,
                        (windowStartMillis - whenMillis + dayInMillis) % dayInMillis);
            }
        } else {
            throw new RuntimeException("Unknown policy type");
        }
    }

    private static LocalDate roundUpLeapDay(LocalDate date) {
        if (date.isLeapYear() && date.getMonthValue() == 2 && date.getDayOfMonth() == 28) {
            return date.plusDays(1);
        } else {
            return date;
        }
    }

    /** Convert a timestamp since epoch to a LocalDate using default timezone, truncating
     * the hour/min/seconds part.
     */
    private static LocalDate millisToDate(long when) {
        return Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Returns the timestamp since epoch of a LocalDate, assuming the time is 00:00:00.
     */
    private static long dateToMillis(LocalDate when) {
        return LocalDateTime.of(when, LocalTime.MIN).atZone(ZoneId.systemDefault()).toInstant()
                .toEpochMilli();
    }

    @Override
    public String toString() {
        return String.format("SystemUpdatePolicy (type: %d, windowStart: %d, windowEnd: %d, "
                + "freezes: [%s])",
                mPolicyType, mMaintenanceWindowStart, mMaintenanceWindowEnd,
                mFreezePeriods.stream().map(n -> n.toString()).collect(Collectors.joining(",")));
    }

    @SystemApi
    @Override
    public int describeContents() {
        return 0;
    }

    @SystemApi
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPolicyType);
        dest.writeInt(mMaintenanceWindowStart);
        dest.writeInt(mMaintenanceWindowEnd);
        int freezeCount = mFreezePeriods.size();
        dest.writeInt(freezeCount);
        for (int i = 0; i < freezeCount; i++) {
            FreezeInterval interval = mFreezePeriods.get(i);
            dest.writeInt(interval.mStartDay);
            dest.writeInt(interval.mEndDay);
        }
    }

    @SystemApi
    public static final Parcelable.Creator<SystemUpdatePolicy> CREATOR =
            new Parcelable.Creator<SystemUpdatePolicy>() {

                @Override
                public SystemUpdatePolicy createFromParcel(Parcel source) {
                    SystemUpdatePolicy policy = new SystemUpdatePolicy();
                    policy.mPolicyType = source.readInt();
                    policy.mMaintenanceWindowStart = source.readInt();
                    policy.mMaintenanceWindowEnd = source.readInt();
                    int freezeCount = source.readInt();
                    policy.mFreezePeriods.ensureCapacity(freezeCount);
                    for (int i = 0; i < freezeCount; i++) {
                        policy.mFreezePeriods.add(
                                new FreezeInterval(source.readInt(), source.readInt()));
                    }
                    return policy;
                }

                @Override
                public SystemUpdatePolicy[] newArray(int size) {
                    return new SystemUpdatePolicy[size];
                }
    };

    /**
     * Restore a previously saved SystemUpdatePolicy from XML. No need to validate
     * the reconstructed policy since the XML is supposed to be created by the
     * system server from a validated policy object previously.
     * @hide
     */
    public static SystemUpdatePolicy restoreFromXml(XmlPullParser parser) {
        try {
            SystemUpdatePolicy policy = new SystemUpdatePolicy();
            String value = parser.getAttributeValue(null, KEY_POLICY_TYPE);
            if (value != null) {
                policy.mPolicyType = Integer.parseInt(value);

                value = parser.getAttributeValue(null, KEY_INSTALL_WINDOW_START);
                if (value != null) {
                    policy.mMaintenanceWindowStart = Integer.parseInt(value);
                }
                value = parser.getAttributeValue(null, KEY_INSTALL_WINDOW_END);
                if (value != null) {
                    policy.mMaintenanceWindowEnd = Integer.parseInt(value);
                }

                int outerDepth = parser.getDepth();
                int type;
                while ((type = parser.next()) != END_DOCUMENT
                        && (type != END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == END_TAG || type == TEXT) {
                        continue;
                    }
                    if (!parser.getName().equals(KEY_FREEZE_TAG)) {
                        continue;
                    }
                    policy.mFreezePeriods.add(new FreezeInterval(
                            Integer.parseInt(parser.getAttributeValue(null, KEY_FREEZE_START)),
                            Integer.parseInt(parser.getAttributeValue(null, KEY_FREEZE_END))));
                }
                return policy;
            }
        } catch (NumberFormatException | XmlPullParserException | IOException e) {
            // Fail through
            Log.w(TAG, "Load xml failed", e);
        }
        return null;
    }

    /**
     * @hide
     */
    public void saveToXml(XmlSerializer out) throws IOException {
        out.attribute(null, KEY_POLICY_TYPE, Integer.toString(mPolicyType));
        out.attribute(null, KEY_INSTALL_WINDOW_START, Integer.toString(mMaintenanceWindowStart));
        out.attribute(null, KEY_INSTALL_WINDOW_END, Integer.toString(mMaintenanceWindowEnd));
        for (int i = 0; i < mFreezePeriods.size(); i++) {
            FreezeInterval interval = mFreezePeriods.get(i);
            out.startTag(null, KEY_FREEZE_TAG);
            out.attribute(null, KEY_FREEZE_START, Integer.toString(interval.mStartDay));
            out.attribute(null, KEY_FREEZE_END, Integer.toString(interval.mEndDay));
            out.endTag(null, KEY_FREEZE_TAG);
        }
    }
}

