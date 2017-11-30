/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Rlog;
import android.text.TextUtils;

import java.util.Objects;

/**
 * CellIdentity is to represent a unique LTE cell
 */
public final class CellIdentityLte implements Parcelable {

    private static final String LOG_TAG = "CellIdentityLte";
    private static final boolean DBG = false;

    // 28-bit cell identity
    private final int mCi;
    // physical cell id 0..503
    private final int mPci;
    // 16-bit tracking area code
    private final int mTac;
    // 18-bit Absolute RF Channel Number
    private final int mEarfcn;
    // 3-digit Mobile Country Code in string format
    private final String mMccStr;
    // 2 or 3-digit Mobile Network Code in string format
    private final String mMncStr;
    // long alpha Operator Name String or Enhanced Operator Name String
    private final String mAlphaLong;
    // short alpha Operator Name String or Enhanced Operator Name String
    private final String mAlphaShort;

    /**
     * @hide
     */
    public CellIdentityLte() {
        mCi = Integer.MAX_VALUE;
        mPci = Integer.MAX_VALUE;
        mTac = Integer.MAX_VALUE;
        mEarfcn = Integer.MAX_VALUE;
        mMccStr = null;
        mMncStr = null;
        mAlphaLong = null;
        mAlphaShort = null;
    }

    /**
     *
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param ci 28-bit Cell Identity
     * @param pci Physical Cell Id 0..503
     * @param tac 16-bit Tracking Area Code
     *
     * @hide
     */
    public CellIdentityLte (int mcc, int mnc, int ci, int pci, int tac) {
        this(ci, pci, tac, Integer.MAX_VALUE, String.valueOf(mcc), String.valueOf(mnc), null, null);
    }

    /**
     *
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param ci 28-bit Cell Identity
     * @param pci Physical Cell Id 0..503
     * @param tac 16-bit Tracking Area Code
     * @param earfcn 18-bit LTE Absolute RF Channel Number
     *
     * @hide
     */
    public CellIdentityLte (int mcc, int mnc, int ci, int pci, int tac, int earfcn) {
        this(ci, pci, tac, earfcn, String.valueOf(mcc), String.valueOf(mnc), null, null);
    }

    /**
     *
     * @param ci 28-bit Cell Identity
     * @param pci Physical Cell Id 0..503
     * @param tac 16-bit Tracking Area Code
     * @param earfcn 18-bit LTE Absolute RF Channel Number
     * @param mccStr 3-digit Mobile Country Code in string format
     * @param mncStr 2 or 3-digit Mobile Network Code in string format
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     *
     * @hide
     */
    public CellIdentityLte (int ci, int pci, int tac, int earfcn, String mccStr,
                            String mncStr, String alphal, String alphas) {
        mCi = ci;
        mPci = pci;
        mTac = tac;
        mEarfcn = earfcn;

        // Only allow INT_MAX if unknown string mcc/mnc
        if (mccStr == null || mccStr.matches("^[0-9]{3}$")) {
            mMccStr = mccStr;
        } else if (mccStr.isEmpty() || mccStr.equals(String.valueOf(Integer.MAX_VALUE))) {
            // If the mccStr is empty or unknown, set it as null.
            mMccStr = null;
        } else {
            // TODO: b/69384059 Should throw IllegalArgumentException for the invalid MCC format
            // after the bug got fixed.
            mMccStr = null;
            log("invalid MCC format: " + mccStr);
        }

        if (mncStr == null || mncStr.matches("^[0-9]{2,3}$")) {
            mMncStr = mncStr;
        } else if (mncStr.isEmpty() || mncStr.equals(String.valueOf(Integer.MAX_VALUE))) {
            // If the mncStr is empty or unknown, set it as null.
            mMncStr = null;
        } else {
            // TODO: b/69384059 Should throw IllegalArgumentException for the invalid MNC format
            // after the bug got fixed.
            mMncStr = null;
            log("invalid MNC format: " + mncStr);
        }

        mAlphaLong = alphal;
        mAlphaShort = alphas;
    }

    private CellIdentityLte(CellIdentityLte cid) {
        this(cid.mCi, cid.mPci, cid.mTac, cid.mEarfcn, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort);
    }

    CellIdentityLte copy() {
        return new CellIdentityLte(this);
    }

    /**
     * @return 3-digit Mobile Country Code, 0..999, Integer.MAX_VALUE if unknown
     * @deprecated Use {@link #getMccStr} instead.
     */
    @Deprecated
    public int getMcc() {
        return (mMccStr != null) ? Integer.valueOf(mMccStr) : Integer.MAX_VALUE;
    }

    /**
     * @return 2 or 3-digit Mobile Network Code, 0..999, Integer.MAX_VALUE if unknown
     * @deprecated Use {@link #getMncStr} instead.
     */
    @Deprecated
    public int getMnc() {
        return (mMncStr != null) ? Integer.valueOf(mMncStr) : Integer.MAX_VALUE;
    }

    /**
     * @return 28-bit Cell Identity, Integer.MAX_VALUE if unknown
     */
    public int getCi() {
        return mCi;
    }

    /**
     * @return Physical Cell Id 0..503, Integer.MAX_VALUE if unknown
     */
    public int getPci() {
        return mPci;
    }

    /**
     * @return 16-bit Tracking Area Code, Integer.MAX_VALUE if unknown
     */
    public int getTac() {
        return mTac;
    }

    /**
     * @return 18-bit Absolute RF Channel Number, Integer.MAX_VALUE if unknown
     */
    public int getEarfcn() {
        return mEarfcn;
    }

    /**
     * @return Mobile Country Code in string format, null if unknown
     */
    public String getMccStr() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string format, null if unknown
     */
    public String getMncStr() {
        return mMncStr;
    }

    /**
     * @return a 5 or 6 character string (MCC+MNC), null if any field is unknown
     */
    public String getMobileNetworkOperator() {
        return (mMccStr == null || mMncStr == null) ? null : mMccStr + mMncStr;
    }

    /**
     * @return The long alpha tag associated with the current scan result (may be the operator
     * name string or extended operator name string). May be null if unknown.
     */
    public CharSequence getOperatorAlphaLong() {
        return mAlphaLong;
    }

    /**
     * @return The short alpha tag associated with the current scan result (may be the operator
     * name string or extended operator name string).  May be null if unknown.
     */
    public CharSequence getOperatorAlphaShort() {
        return mAlphaShort;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMccStr, mMncStr, mCi, mPci, mTac, mAlphaLong, mAlphaShort);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof CellIdentityLte)) {
            return false;
        }

        CellIdentityLte o = (CellIdentityLte) other;
        return mCi == o.mCi &&
                mPci == o.mPci &&
                mTac == o.mTac &&
                mEarfcn == o.mEarfcn &&
                TextUtils.equals(mMccStr, o.mMccStr) &&
                TextUtils.equals(mMncStr, o.mMncStr) &&
                TextUtils.equals(mAlphaLong, o.mAlphaLong) &&
                TextUtils.equals(mAlphaShort, o.mAlphaShort);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CellIdentityLte:{");
        sb.append(" mCi="); sb.append(mCi);
        sb.append(" mPci="); sb.append(mPci);
        sb.append(" mTac="); sb.append(mTac);
        sb.append(" mEarfcn="); sb.append(mEarfcn);
        sb.append(" mMcc="); sb.append(mMccStr);
        sb.append(" mMnc="); sb.append(mMncStr);
        sb.append(" mAlphaLong="); sb.append(mAlphaLong);
        sb.append(" mAlphaShort="); sb.append(mAlphaShort);
        sb.append("}");

        return sb.toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mCi);
        dest.writeInt(mPci);
        dest.writeInt(mTac);
        dest.writeInt(mEarfcn);
        dest.writeString(mMccStr);
        dest.writeString(mMncStr);
        dest.writeString(mAlphaLong);
        dest.writeString(mAlphaShort);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityLte(Parcel in) {
        this(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readString(),
                in.readString(), in.readString(), in.readString());

        if (DBG) log("CellIdentityLte(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityLte> CREATOR =
            new Creator<CellIdentityLte>() {
                @Override
                public CellIdentityLte createFromParcel(Parcel in) {
                    return new CellIdentityLte(in);
                }

                @Override
                public CellIdentityLte[] newArray(int size) {
                    return new CellIdentityLte[size];
                }
            };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}