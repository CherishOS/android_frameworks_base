/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.credentials.metrics;

import android.util.IntArray;
import android.util.Log;

import com.android.server.credentials.MetricUtilities;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The central candidate provider metric object that mimics our defined metric setup.
 * Some types are redundant across these metric collectors, but that has debug use-cases as
 * these data-types are available at different moments of the flow (and typically, one can feed
 * into the next).
 * TODO(b/270403549) - iterate on this in V3+
 */
public class CandidatePhaseMetric {

    private static final String TAG = "CandidateProviderMetric";
    // The session id of this provider, default set to -1
    private int mSessionId = -1;
    // Indicates if this provider returned from the query phase, default false
    private boolean mQueryReturned = false;

    // The candidate provider uid
    private int mCandidateUid = -1;

    // Raw timestamp in nanoseconds, will be converted to microseconds for logging

    //For reference, the initial log timestamp when the service started running the API call
    private long mServiceBeganTimeNanoseconds = -1;
    // The moment when the query phase began
    private long mStartQueryTimeNanoseconds = -1;
    // The moment when the query phase ended
    private long mQueryFinishTimeNanoseconds = -1;

    // The status of this particular provider
    private int mProviderQueryStatus = -1;
    // Indicates if an exception was thrown by this provider, false by default
    private boolean mHasException = false;
    // Indicates the number of total entries available. We can also locally store the entries, but
    // cannot emit them in the current split form. TODO(b/271135048) - possibly readjust candidate
    // entries. Also, it may be okay to remove this and instead aggregate from inner counts.
    // Defaults to -1
    private int mNumEntriesTotal = -1;
    // The count of action entries from this provider, defaults to -1
    private int mActionEntryCount = -1;
    // The count of credential entries from this provider, defaults to -1
    private int mCredentialEntryCount = -1;
    // The *type-count* of the credential entries, defaults to -1
    private int mCredentialEntryTypeCount = -1;
    // The count of remote entries from this provider, defaults to -1
    private int mRemoteEntryCount = -1;
    // The count of authentication entries from this provider, defaults to -1
    private int mAuthenticationEntryCount = -1;
    // Gathered to pass on to chosen provider when required
    private final IntArray mAvailableEntries = new IntArray();
    // The *framework only* exception held by this provider, empty string by default
    private String mFrameworkException = "";

    public CandidatePhaseMetric() {
    }

    /* ---------- Latencies ---------- */

    /* -- Timestamps -- */

    public void setServiceBeganTimeNanoseconds(long serviceBeganTimeNanoseconds) {
        mServiceBeganTimeNanoseconds = serviceBeganTimeNanoseconds;
    }

    public void setStartQueryTimeNanoseconds(long startQueryTimeNanoseconds) {
        mStartQueryTimeNanoseconds = startQueryTimeNanoseconds;
    }

    public void setQueryFinishTimeNanoseconds(long queryFinishTimeNanoseconds) {
        mQueryFinishTimeNanoseconds = queryFinishTimeNanoseconds;
    }

    public long getServiceBeganTimeNanoseconds() {
        return mServiceBeganTimeNanoseconds;
    }

    public long getStartQueryTimeNanoseconds() {
        return mStartQueryTimeNanoseconds;
    }

    public long getQueryFinishTimeNanoseconds() {
        return mQueryFinishTimeNanoseconds;
    }

    /* -- Actual time delta latencies (for local utility) -- */

    /**
     * Returns the latency in microseconds for the query phase.
     */
    public int getQueryLatencyMicroseconds() {
        return (int) ((getQueryFinishTimeNanoseconds()
                - getStartQueryTimeNanoseconds()) / 1000);
    }

    /* --- Time Stamp Conversion to Microseconds from Reference --- */

    /**
     * We collect raw timestamps in nanoseconds for ease of collection. However, given the scope
     * of our logging timeframe, and size considerations of the metric, we require these to give us
     * the microsecond timestamps from the start reference point.
     *
     * @param specificTimestamp the timestamp to consider, must be greater than the reference
     * @return the microsecond integer timestamp from service start to query began
     */
    public int getTimestampFromReferenceStartMicroseconds(long specificTimestamp) {
        if (specificTimestamp < mServiceBeganTimeNanoseconds) {
            Log.i(TAG, "The timestamp is before service started, falling back to default int");
            return MetricUtilities.DEFAULT_INT_32;
        }
        return (int) ((specificTimestamp
                - mServiceBeganTimeNanoseconds) / 1000);
    }

    /* ------------- Provider Query Status ------------ */

    public void setProviderQueryStatus(int providerQueryStatus) {
        mProviderQueryStatus = providerQueryStatus;
    }

    public int getProviderQueryStatus() {
        return mProviderQueryStatus;
    }

    /* -------------- Candidate Uid ---------------- */

    public void setCandidateUid(int candidateUid) {
        mCandidateUid = candidateUid;
    }

    public int getCandidateUid() {
        return mCandidateUid;
    }

    /* -------------- Session Id ---------------- */

    public void setSessionId(int sessionId) {
        mSessionId = sessionId;
    }

    public int getSessionId() {
        return mSessionId;
    }

    /* -------------- Query Returned Status ---------------- */

    public void setQueryReturned(boolean queryReturned) {
        mQueryReturned = queryReturned;
    }

    public boolean isQueryReturned() {
        return mQueryReturned;
    }

    /* -------------- Has Exception Status ---------------- */

    public void setHasException(boolean hasException) {
        mHasException = hasException;
    }

    public boolean isHasException() {
        return mHasException;
    }

    /* -------------- Number of Entries ---------------- */

    public void setNumEntriesTotal(int numEntriesTotal) {
        mNumEntriesTotal = numEntriesTotal;
    }

    public int getNumEntriesTotal() {
        return mNumEntriesTotal;
    }

    /* -------------- Count of Action Entries ---------------- */

    public void setActionEntryCount(int actionEntryCount) {
        mActionEntryCount = actionEntryCount;
    }

    public int getActionEntryCount() {
        return mActionEntryCount;
    }

    /* -------------- Count of Credential Entries ---------------- */

    public void setCredentialEntryCount(int credentialEntryCount) {
        mCredentialEntryCount = credentialEntryCount;
    }

    public int getCredentialEntryCount() {
        return mCredentialEntryCount;
    }

    /* -------------- Count of Credential Entry Types ---------------- */

    public void setCredentialEntryTypeCount(int credentialEntryTypeCount) {
        mCredentialEntryTypeCount = credentialEntryTypeCount;
    }

    public int getCredentialEntryTypeCount() {
        return mCredentialEntryTypeCount;
    }

    /* -------------- Count of Remote Entries ---------------- */

    public void setRemoteEntryCount(int remoteEntryCount) {
        mRemoteEntryCount = remoteEntryCount;
    }

    public int getRemoteEntryCount() {
        return mRemoteEntryCount;
    }

    /* -------------- Count of Authentication Entries ---------------- */

    public void setAuthenticationEntryCount(int authenticationEntryCount) {
        mAuthenticationEntryCount = authenticationEntryCount;
    }

    public int getAuthenticationEntryCount() {
        return mAuthenticationEntryCount;
    }

    /* -------------- The Entries Gathered ---------------- */

    /**
     * Allows adding an entry record to this metric collector, which can then be propagated to
     * the final phase to retain information on the data available to the candidate.
     *
     * @param e the entry enum collected by the candidate provider associated with this metric
     *          collector
     */
    public void addEntry(EntryEnum e) {
        mAvailableEntries.add(e.getMetricCode());
    }

    /**
     * Returns a safely copied list of the entries captured by this metric collector associated
     * with a particular candidate provider.
     *
     * @return the full collection of entries encountered by the candidate provider associated with
     * this metric
     */
    public List<Integer> getAvailableEntries() {
        return Arrays.stream(mAvailableEntries.toArray()).boxed().collect(Collectors.toList());
    }

    /* ------ Framework Exception for this Candidate ------ */

    public void setFrameworkException(String frameworkException) {
        mFrameworkException = frameworkException;
    }

    public String getFrameworkException() {
        return mFrameworkException;
    }
}
