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

package com.android.server.integrity.model;

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a complex formula consisting of other simple and complex formulas.
 *
 * <p>Instances of this class are immutable.
 *
 * @hide
 */
@SystemApi
@VisibleForTesting
public final class OpenFormula implements Formula, Parcelable {
    private static final String TAG = "OpenFormula";

    @IntDef(
            value = {
                    AND, OR, NOT,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Connector {}

    /** Boolean AND operator. */
    public static final int AND = 0;

    /** Boolean OR operator. */
    public static final int OR = 1;

    /** Boolean NOT operator. */
    public static final int NOT = 2;

    private final @Connector int mConnector;
    private final @NonNull List<Formula> mFormulas;

    @NonNull
    public static final Creator<OpenFormula> CREATOR =
            new Creator<OpenFormula>() {
                @Override
                public OpenFormula createFromParcel(Parcel in) {
                    return new OpenFormula(in);
                }

                @Override
                public OpenFormula[] newArray(int size) {
                    return new OpenFormula[size];
                }
            };

    /**
     * Create a new formula from operator and operands.
     *
     * @throws IllegalArgumentException if the number of operands is not matching the requirements
     *     for that operator (at least 2 for {@link #AND} and {@link #OR}, 1 for {@link #NOT}).
     */
    public OpenFormula(@Connector int connector, @NonNull List<Formula> formulas) {
        checkArgument(isValidConnector(connector),
                String.format("Unknown connector: %d", connector));
        validateFormulas(connector, formulas);
        this.mConnector = connector;
        this.mFormulas = Collections.unmodifiableList(formulas);
    }

    OpenFormula(Parcel in) {
        mConnector = in.readInt();
        int length = in.readInt();
        checkArgument(length >= 0, "Must have non-negative length. Got " + length);
        mFormulas = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            mFormulas.add(Formula.readFromParcel(in));
        }
        validateFormulas(mConnector, mFormulas);
    }

    public @Connector int getConnector() {
        return mConnector;
    }

    @NonNull
    public List<Formula> getFormulas() {
        return mFormulas;
    }

    @Override
    public boolean isSatisfied(@NonNull AppInstallMetadata appInstallMetadata) {
        switch (mConnector) {
            case NOT:
                return !mFormulas.get(0).isSatisfied(appInstallMetadata);
            case AND:
                return mFormulas.stream()
                        .allMatch(formula -> formula.isSatisfied(appInstallMetadata));
            case OR:
                return mFormulas.stream()
                        .anyMatch(formula -> formula.isSatisfied(appInstallMetadata));
            default:
                Slog.i(TAG, "Unknown connector " + mConnector);
                return false;
        }
    }

    @Override
    public int getTag() {
        return Formula.OPEN_FORMULA_TAG;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (mFormulas.size() == 1) {
            sb.append(String.format("%s ", connectorToString(mConnector)));
            sb.append(mFormulas.get(0).toString());
        } else {
            for (int i = 0; i < mFormulas.size(); i++) {
                if (i > 0) {
                    sb.append(String.format(" %s ", connectorToString(mConnector)));
                }
                sb.append(mFormulas.get(i).toString());
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OpenFormula that = (OpenFormula) o;
        return mConnector == that.mConnector && mFormulas.equals(that.mFormulas);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mConnector, mFormulas);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mConnector);
        dest.writeInt(mFormulas.size());
        for (Formula formula : mFormulas) {
            Formula.writeToParcel(formula, dest, flags);
        }
    }

    private static void validateFormulas(@Connector int connector, List<Formula> formulas) {
        switch (connector) {
            case AND:
            case OR:
                checkArgument(
                        formulas.size() >= 2,
                        String.format(
                                "Connector %s must have at least 2 formulas",
                                connectorToString(connector)));
                break;
            case NOT:
                checkArgument(
                        formulas.size() == 1,
                        String.format(
                                "Connector %s must have 1 formula only",
                                connectorToString(connector)));
                break;
        }
    }

    private static String connectorToString(int connector) {
        switch (connector) {
            case AND:
                return "AND";
            case OR:
                return "OR";
            case NOT:
                return "NOT";
            default:
                throw new IllegalArgumentException("Unknown connector " + connector);
        }
    }

    private static boolean isValidConnector(int connector) {
        return connector == AND
                || connector == OR
                || connector == NOT;
    }
}
