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

package android.net.vcn.persistablebundleutils;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.net.ipsec.ike.IkeSaProposal;
import android.net.ipsec.ike.IkeSessionParams;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.List;
import java.util.Objects;

/**
 * Abstract utility class to convert IkeSessionParams to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class IkeSessionParamsUtils {
    private static final String SERVER_HOST_NAME_KEY = "SERVER_HOST_NAME_KEY";
    private static final String SA_PROPOSALS_KEY = "SA_PROPOSALS_KEY";
    private static final String LOCAL_ID_KEY = "LOCAL_ID_KEY";
    private static final String REMOTE_ID_KEY = "REMOTE_ID_KEY";
    private static final String RETRANS_TIMEOUTS_KEY = "RETRANS_TIMEOUTS_KEY";
    private static final String HARD_LIFETIME_SEC_KEY = "HARD_LIFETIME_SEC_KEY";
    private static final String SOFT_LIFETIME_SEC_KEY = "SOFT_LIFETIME_SEC_KEY";
    private static final String DPD_DELAY_SEC_KEY = "DPD_DELAY_SEC_KEY";
    private static final String NATT_KEEPALIVE_DELAY_SEC_KEY = "NATT_KEEPALIVE_DELAY_SEC_KEY";

    /** Serializes an IkeSessionParams to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(@NonNull IkeSessionParams params) {
        if (params.getConfiguredNetwork() != null || params.getIke3gppExtension() != null) {
            throw new IllegalStateException(
                    "Cannot convert a IkeSessionParams with a caller configured network or with"
                            + " 3GPP extension enabled");
        }

        final PersistableBundle result = new PersistableBundle();

        result.putString(SERVER_HOST_NAME_KEY, params.getServerHostname());

        final PersistableBundle saProposalBundle =
                PersistableBundleUtils.fromList(
                        params.getSaProposals(), IkeSaProposalUtils::toPersistableBundle);
        result.putPersistableBundle(SA_PROPOSALS_KEY, saProposalBundle);

        result.putPersistableBundle(
                LOCAL_ID_KEY,
                IkeIdentificationUtils.toPersistableBundle(params.getLocalIdentification()));
        result.putPersistableBundle(
                REMOTE_ID_KEY,
                IkeIdentificationUtils.toPersistableBundle(params.getRemoteIdentification()));

        result.putIntArray(RETRANS_TIMEOUTS_KEY, params.getRetransmissionTimeoutsMillis());
        result.putInt(HARD_LIFETIME_SEC_KEY, params.getHardLifetimeSeconds());
        result.putInt(SOFT_LIFETIME_SEC_KEY, params.getSoftLifetimeSeconds());
        result.putInt(DPD_DELAY_SEC_KEY, params.getDpdDelaySeconds());
        result.putInt(NATT_KEEPALIVE_DELAY_SEC_KEY, params.getNattKeepAliveDelaySeconds());

        // TODO: Handle authentication configuration, configuration requests and IKE options.

        return result;
    }

    /** Constructs an IkeSessionParams by deserializing a PersistableBundle. */
    @NonNull
    public static IkeSessionParams fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle is null");

        final IkeSessionParams.Builder builder = new IkeSessionParams.Builder();

        builder.setServerHostname(in.getString(SERVER_HOST_NAME_KEY));

        PersistableBundle proposalBundle = in.getPersistableBundle(SA_PROPOSALS_KEY);
        Objects.requireNonNull(in, "SA Proposals was null");
        List<IkeSaProposal> saProposals =
                PersistableBundleUtils.toList(
                        proposalBundle, IkeSaProposalUtils::fromPersistableBundle);
        for (IkeSaProposal proposal : saProposals) {
            builder.addSaProposal(proposal);
        }

        builder.setLocalIdentification(
                IkeIdentificationUtils.fromPersistableBundle(
                        in.getPersistableBundle(LOCAL_ID_KEY)));
        builder.setRemoteIdentification(
                IkeIdentificationUtils.fromPersistableBundle(
                        in.getPersistableBundle(REMOTE_ID_KEY)));

        builder.setRetransmissionTimeoutsMillis(in.getIntArray(RETRANS_TIMEOUTS_KEY));
        builder.setLifetimeSeconds(
                in.getInt(HARD_LIFETIME_SEC_KEY), in.getInt(SOFT_LIFETIME_SEC_KEY));
        builder.setDpdDelaySeconds(in.getInt(DPD_DELAY_SEC_KEY));
        builder.setNattKeepAliveDelaySeconds(in.getInt(NATT_KEEPALIVE_DELAY_SEC_KEY));

        // TODO: Handle authentication configuration, configuration requests and IKE options.

        return builder.build();
    }
}
