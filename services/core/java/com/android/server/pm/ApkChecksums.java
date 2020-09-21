/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.Checksum.PARTIAL_MERKLE_ROOT_1M_SHA256;
import static android.content.pm.Checksum.PARTIAL_MERKLE_ROOT_1M_SHA512;
import static android.content.pm.Checksum.WHOLE_MD5;
import static android.content.pm.Checksum.WHOLE_MERKLE_ROOT_4K_SHA256;
import static android.content.pm.Checksum.WHOLE_SHA1;
import static android.content.pm.Checksum.WHOLE_SHA256;
import static android.content.pm.Checksum.WHOLE_SHA512;
import static android.content.pm.PackageManager.EXTRA_CHECKSUMS;
import static android.content.pm.PackageParser.APK_FILE_EXTENSION;
import static android.util.apk.ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256;
import static android.util.apk.ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512;
import static android.util.apk.ApkSigningBlockUtils.CONTENT_DIGEST_VERITY_CHUNKED_SHA256;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApkChecksum;
import android.content.pm.Checksum;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.SystemClock;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalStorage;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.apk.ApkSignatureSchemeV2Verifier;
import android.util.apk.ApkSignatureSchemeV3Verifier;
import android.util.apk.ApkSignatureSchemeV4Verifier;
import android.util.apk.ApkSignatureVerifier;
import android.util.apk.ApkSigningBlockUtils;
import android.util.apk.ByteBufferFactory;
import android.util.apk.SignatureInfo;
import android.util.apk.SignatureNotFoundException;
import android.util.apk.VerityBuilder;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.security.VerityUtils;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides checksums for APK.
 */
public class ApkChecksums {
    static final String TAG = "ApkChecksums";

    private static final String DIGESTS_FILE_EXTENSION = ".digests";

    // MessageDigest algorithms.
    static final String ALGO_MD5 = "MD5";
    static final String ALGO_SHA1 = "SHA1";
    static final String ALGO_SHA256 = "SHA256";
    static final String ALGO_SHA512 = "SHA512";

    /**
     * Check back in 1 second after we detected we needed to wait for the APK to be fully available.
     */
    private static final long PROCESS_REQUIRED_CHECKSUMS_DELAY_MILLIS = 1000;

    /**
     * 24 hours timeout to wait till all files are loaded.
     */
    private static final long PROCESS_REQUIRED_CHECKSUMS_TIMEOUT_MILLIS = 1000 * 3600 * 24;

    /**
     * Unit tests will instantiate, extend and/or mock to mock dependencies / behaviors.
     *
     * NOTE: All getters should return the same instance for every call.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static class Injector {

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        interface Producer<T> {
            /** Produce an instance of type {@link T} */
            T produce();
        }

        private final Producer<Context> mContext;
        private final Producer<Handler> mHandlerProducer;
        private final Producer<IncrementalManager> mIncrementalManagerProducer;

        Injector(Producer<Context> context, Producer<Handler> handlerProducer,
                Producer<IncrementalManager> incrementalManagerProducer) {
            mContext = context;
            mHandlerProducer = handlerProducer;
            mIncrementalManagerProducer = incrementalManagerProducer;
        }

        public Context getContext() {
            return mContext.produce();
        }

        public Handler getHandler() {
            return mHandlerProducer.produce();
        }

        public IncrementalManager getIncrementalManager() {
            return mIncrementalManagerProducer.produce();
        }
    }

    /**
     * Return the digests path associated with the given code path
     * (replaces '.apk' extension with '.digests')
     *
     * @throws IllegalArgumentException if the code path is not an .apk.
     */
    public static String buildDigestsPathForApk(String codePath) {
        if (!PackageParser.isApkPath(codePath)) {
            throw new IllegalStateException("Code path is not an apk " + codePath);
        }
        return codePath.substring(0, codePath.length() - APK_FILE_EXTENSION.length())
                + DIGESTS_FILE_EXTENSION;
    }

    /**
     * Search for the digests file associated with the given target file.
     * If it exists, the method returns the digests file; otherwise it returns null.
     */
    public static File findDigestsForFile(File targetFile) {
        String digestsPath = buildDigestsPathForApk(targetFile.getAbsolutePath());
        File digestsFile = new File(digestsPath);
        return digestsFile.exists() ? digestsFile : null;
    }

    /**
     * Serialize checksums to the stream in binary format.
     */
    public static void writeChecksums(OutputStream os, ApkChecksum[] checksums)
            throws IOException, CertificateException {
        try (DataOutputStream dos = new DataOutputStream(os)) {
            dos.writeInt(checksums.length);
            for (ApkChecksum checksum : checksums) {
                final String splitName = checksum.getSplitName();
                if (splitName == null) {
                    dos.writeInt(-1);
                } else {
                    dos.writeInt(splitName.length());
                    dos.writeUTF(splitName);
                }

                dos.writeInt(checksum.getKind());

                final byte[] valueBytes = checksum.getValue();
                dos.writeInt(valueBytes.length);
                dos.write(valueBytes);

                final String packageName = checksum.getSourcePackageName();
                if (packageName == null) {
                    dos.writeInt(-1);
                } else {
                    dos.writeInt(packageName.length());
                    dos.writeUTF(packageName);
                }

                final Certificate cert = checksum.getSourceCertificate();
                final byte[] certBytes = (cert == null) ? null : cert.getEncoded();
                if (certBytes == null) {
                    dos.writeInt(-1);
                } else {
                    dos.writeInt(certBytes.length);
                    dos.write(certBytes);
                }
            }
        }
    }

    /**
     * Deserialize array of checksums previously stored in
     * {@link #writeChecksums(File, ApkChecksum[])}.
     */
    private static ApkChecksum[] readChecksums(File file) throws IOException {
        try (InputStream is = new FileInputStream(file);
             DataInputStream dis = new DataInputStream(is)) {
            final int size = dis.readInt();
            ApkChecksum[] checksums = new ApkChecksum[size];
            for (int i = 0; i < size; ++i) {
                final String splitName;
                if (dis.readInt() < 0) {
                    splitName = null;
                } else {
                    splitName = dis.readUTF();
                }

                final int kind = dis.readInt();

                final byte[] valueBytes = new byte[dis.readInt()];
                dis.read(valueBytes);

                final String packageName;
                if (dis.readInt() < 0) {
                    packageName = null;
                } else {
                    packageName = dis.readUTF();
                }

                final byte[] certBytes;
                final int certBytesLength = dis.readInt();
                if (certBytesLength < 0) {
                    certBytes = null;
                } else {
                    certBytes = new byte[certBytesLength];
                    dis.read(certBytes);
                }
                checksums[i] = new ApkChecksum(splitName, new Checksum(kind, valueBytes),
                        packageName, certBytes);
            }
            return checksums;
        }
    }


    /**
     * Fetch or calculate checksums for the collection of files.
     *
     * @param filesToChecksum   split name, null for base and File to fetch checksums for
     * @param optional          mask to fetch readily available checksums
     * @param required          mask to forcefully calculate if not available
     * @param trustedInstallers array of certificate to trust, two specific cases:
     *                          null - trust anybody,
     *                          [] - trust nobody.
     * @param statusReceiver    to receive the resulting checksums
     */
    public static void getChecksums(List<Pair<String, File>> filesToChecksum,
            @Checksum.Kind int optional,
            @Checksum.Kind int required,
            @Nullable Certificate[] trustedInstallers,
            @NonNull IntentSender statusReceiver,
            @NonNull Injector injector) {
        List<Map<Integer, ApkChecksum>> result = new ArrayList<>(filesToChecksum.size());
        for (int i = 0, size = filesToChecksum.size(); i < size; ++i) {
            final String split = filesToChecksum.get(i).first;
            final File file = filesToChecksum.get(i).second;
            Map<Integer, ApkChecksum> checksums = new ArrayMap<>();
            result.add(checksums);

            try {
                getAvailableApkChecksums(split, file, optional | required, trustedInstallers,
                        checksums);
            } catch (Throwable e) {
                Slog.e(TAG, "Preferred checksum calculation error", e);
            }
        }

        long startTime = SystemClock.uptimeMillis();
        processRequiredChecksums(filesToChecksum, result, required, statusReceiver, injector,
                startTime);
    }

    private static void processRequiredChecksums(List<Pair<String, File>> filesToChecksum,
            List<Map<Integer, ApkChecksum>> result,
            @Checksum.Kind int required,
            @NonNull IntentSender statusReceiver,
            @NonNull Injector injector,
            long startTime) {
        final boolean timeout =
                SystemClock.uptimeMillis() - startTime >= PROCESS_REQUIRED_CHECKSUMS_TIMEOUT_MILLIS;
        List<ApkChecksum> allChecksums = new ArrayList<>();
        for (int i = 0, size = filesToChecksum.size(); i < size; ++i) {
            final String split = filesToChecksum.get(i).first;
            final File file = filesToChecksum.get(i).second;
            Map<Integer, ApkChecksum> checksums = result.get(i);

            try {
                if (!timeout || required != 0) {
                    if (needToWait(file, required, checksums, injector)) {
                        // Not ready, come back later.
                        injector.getHandler().postDelayed(() -> {
                            processRequiredChecksums(filesToChecksum, result, required,
                                    statusReceiver, injector, startTime);
                        }, PROCESS_REQUIRED_CHECKSUMS_DELAY_MILLIS);
                        return;
                    }

                    getRequiredApkChecksums(split, file, required, checksums);
                }
                allChecksums.addAll(checksums.values());
            } catch (Throwable e) {
                Slog.e(TAG, "Required checksum calculation error", e);
            }
        }

        final Intent intent = new Intent();
        intent.putExtra(EXTRA_CHECKSUMS,
                allChecksums.toArray(new ApkChecksum[allChecksums.size()]));

        try {
            statusReceiver.sendIntent(injector.getContext(), 1, intent, null, null);
        } catch (IntentSender.SendIntentException e) {
            Slog.w(TAG, e);
        }
    }

    /**
     * Fetch readily available checksums - enforced by kernel or provided by Installer.
     *
     * @param split             split name, null for base
     * @param file              to fetch checksums for
     * @param kinds             mask to fetch checksums
     * @param trustedInstallers array of certificate to trust, two specific cases:
     *                          null - trust anybody,
     *                          [] - trust nobody.
     * @param checksums         resulting checksums
     */
    private static void getAvailableApkChecksums(String split, File file,
            @Checksum.Kind int kinds,
            @Nullable Certificate[] trustedInstallers,
            Map<Integer, ApkChecksum> checksums) {
        final String filePath = file.getAbsolutePath();

        // Always available: FSI or IncFs.
        if (isRequired(WHOLE_MERKLE_ROOT_4K_SHA256, kinds, checksums)) {
            // Hashes in fs-verity and IncFS are always verified.
            ApkChecksum checksum = extractHashFromFS(split, filePath);
            if (checksum != null) {
                checksums.put(checksum.getKind(), checksum);
            }
        }

        // System enforced: v2/v3.
        if (isRequired(PARTIAL_MERKLE_ROOT_1M_SHA256, kinds, checksums) || isRequired(
                PARTIAL_MERKLE_ROOT_1M_SHA512, kinds, checksums)) {
            Map<Integer, ApkChecksum> v2v3checksums = extractHashFromV2V3Signature(
                    split, filePath, kinds);
            if (v2v3checksums != null) {
                checksums.putAll(v2v3checksums);
            }
        }

        if (trustedInstallers == null || trustedInstallers.length > 0) {
            final File digestsFile = new File(buildDigestsPathForApk(filePath));
            if (digestsFile.exists()) {
                try {
                    final ApkChecksum[] digests = readChecksums(digestsFile);
                    final Set<Signature> trusted = convertToSet(trustedInstallers);
                    for (ApkChecksum digest : digests) {
                        if (isRequired(digest.getKind(), kinds, checksums) && isTrusted(digest,
                                trusted)) {
                            checksums.put(digest.getKind(), digest);
                        }
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Error reading .digests", e);
                } catch (CertificateEncodingException e) {
                    Slog.e(TAG, "Error encoding trustedInstallers", e);
                }
            }
        }
    }

    /**
     * Whether the file is available for checksumming or we need to wait.
     */
    private static boolean needToWait(File file,
            @Checksum.Kind int kinds,
            Map<Integer, ApkChecksum> checksums,
            @NonNull Injector injector) throws IOException {
        if (!isRequired(WHOLE_MERKLE_ROOT_4K_SHA256, kinds, checksums)
                && !isRequired(WHOLE_MD5, kinds, checksums)
                && !isRequired(WHOLE_SHA1, kinds, checksums)
                && !isRequired(WHOLE_SHA256, kinds, checksums)
                && !isRequired(WHOLE_SHA512, kinds, checksums)
                && !isRequired(PARTIAL_MERKLE_ROOT_1M_SHA256, kinds, checksums)
                && !isRequired(PARTIAL_MERKLE_ROOT_1M_SHA512, kinds, checksums)) {
            return false;
        }

        final String filePath = file.getAbsolutePath();
        if (!IncrementalManager.isIncrementalPath(filePath)) {
            return false;
        }

        IncrementalManager manager = injector.getIncrementalManager();
        if (manager == null) {
            Slog.e(TAG, "IncrementalManager is missing.");
            return false;
        }
        IncrementalStorage storage = manager.openStorage(filePath);
        if (storage == null) {
            Slog.e(TAG, "IncrementalStorage is missing for a path on IncFs: " + filePath);
            return false;
        }

        return !storage.isFileFullyLoaded(filePath);
    }

    /**
     * Fetch or calculate checksums for the specific file.
     *
     * @param split     split name, null for base
     * @param file      to fetch checksums for
     * @param kinds     mask to forcefully calculate if not available
     * @param checksums resulting checksums
     */
    private static void getRequiredApkChecksums(String split, File file,
            @Checksum.Kind int kinds,
            Map<Integer, ApkChecksum> checksums) {
        final String filePath = file.getAbsolutePath();

        // Manually calculating required checksums if not readily available.
        if (isRequired(WHOLE_MERKLE_ROOT_4K_SHA256, kinds, checksums)) {
            try {
                byte[] generatedRootHash = VerityBuilder.generateFsVerityRootHash(
                        filePath, /*salt=*/null,
                        new ByteBufferFactory() {
                            @Override
                            public ByteBuffer create(int capacity) {
                                return ByteBuffer.allocate(capacity);
                            }
                        });
                checksums.put(WHOLE_MERKLE_ROOT_4K_SHA256,
                        new ApkChecksum(split, WHOLE_MERKLE_ROOT_4K_SHA256, generatedRootHash));
            } catch (IOException | NoSuchAlgorithmException | DigestException e) {
                Slog.e(TAG, "Error calculating WHOLE_MERKLE_ROOT_4K_SHA256", e);
            }
        }

        calculateChecksumIfRequested(checksums, split, file, kinds, WHOLE_MD5);
        calculateChecksumIfRequested(checksums, split, file, kinds, WHOLE_SHA1);
        calculateChecksumIfRequested(checksums, split, file, kinds, WHOLE_SHA256);
        calculateChecksumIfRequested(checksums, split, file, kinds, WHOLE_SHA512);

        calculatePartialChecksumsIfRequested(checksums, split, file, kinds);
    }

    private static boolean isRequired(@Checksum.Kind int kind,
            @Checksum.Kind int kinds, Map<Integer, ApkChecksum> checksums) {
        if ((kinds & kind) == 0) {
            return false;
        }
        if (checksums.containsKey(kind)) {
            return false;
        }
        return true;
    }

    /**
     * Signature class provides a fast way to compare certificates using their hashes.
     * The hash is exactly the same as in X509/Certificate.
     */
    private static Set<Signature> convertToSet(@Nullable Certificate[] array) throws
            CertificateEncodingException {
        if (array == null) {
            return null;
        }
        final Set<Signature> set = new ArraySet<>(array.length);
        for (Certificate item : array) {
            set.add(new Signature(item.getEncoded()));
        }
        return set;
    }

    private static boolean isTrusted(ApkChecksum checksum, Set<Signature> trusted) {
        if (trusted == null) {
            return true;
        }
        final Signature signature = new Signature(checksum.getSourceCertificateBytes());
        return trusted.contains(signature);
    }

    private static ApkChecksum extractHashFromFS(String split, String filePath) {
        // verity first
        {
            byte[] hash = VerityUtils.getFsverityRootHash(filePath);
            if (hash != null) {
                return new ApkChecksum(split, WHOLE_MERKLE_ROOT_4K_SHA256, hash);
            }
        }
        // v4 next
        try {
            ApkSignatureSchemeV4Verifier.VerifiedSigner signer =
                    ApkSignatureSchemeV4Verifier.extractCertificates(filePath);
            byte[] hash = signer.contentDigests.getOrDefault(CONTENT_DIGEST_VERITY_CHUNKED_SHA256,
                    null);
            if (hash != null) {
                return new ApkChecksum(split, WHOLE_MERKLE_ROOT_4K_SHA256, hash);
            }
        } catch (SignatureNotFoundException e) {
            // Nothing
        } catch (SecurityException e) {
            Slog.e(TAG, "V4 signature error", e);
        }
        return null;
    }

    private static Map<Integer, ApkChecksum> extractHashFromV2V3Signature(
            String split, String filePath, int kinds) {
        Map<Integer, byte[]> contentDigests = null;
        try {
            contentDigests = ApkSignatureVerifier.verifySignaturesInternal(filePath,
                    PackageParser.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2,
                    false).contentDigests;
        } catch (PackageParser.PackageParserException e) {
            if (!(e.getCause() instanceof SignatureNotFoundException)) {
                Slog.e(TAG, "Signature verification error", e);
            }
        }

        if (contentDigests == null) {
            return null;
        }

        Map<Integer, ApkChecksum> checksums = new ArrayMap<>();
        if ((kinds & PARTIAL_MERKLE_ROOT_1M_SHA256) != 0) {
            byte[] hash = contentDigests.getOrDefault(CONTENT_DIGEST_CHUNKED_SHA256, null);
            if (hash != null) {
                checksums.put(PARTIAL_MERKLE_ROOT_1M_SHA256,
                        new ApkChecksum(split, PARTIAL_MERKLE_ROOT_1M_SHA256, hash));
            }
        }
        if ((kinds & PARTIAL_MERKLE_ROOT_1M_SHA512) != 0) {
            byte[] hash = contentDigests.getOrDefault(CONTENT_DIGEST_CHUNKED_SHA512, null);
            if (hash != null) {
                checksums.put(PARTIAL_MERKLE_ROOT_1M_SHA512,
                        new ApkChecksum(split, PARTIAL_MERKLE_ROOT_1M_SHA512, hash));
            }
        }
        return checksums;
    }

    private static String getMessageDigestAlgoForChecksumKind(int kind)
            throws NoSuchAlgorithmException {
        switch (kind) {
            case WHOLE_MD5:
                return ALGO_MD5;
            case WHOLE_SHA1:
                return ALGO_SHA1;
            case WHOLE_SHA256:
                return ALGO_SHA256;
            case WHOLE_SHA512:
                return ALGO_SHA512;
            default:
                throw new NoSuchAlgorithmException("Invalid checksum kind: " + kind);
        }
    }

    private static void calculateChecksumIfRequested(Map<Integer, ApkChecksum> checksums,
            String split, File file, int required, int kind) {
        if ((required & kind) != 0 && !checksums.containsKey(kind)) {
            final byte[] checksum = getApkChecksum(file, kind);
            if (checksum != null) {
                checksums.put(kind, new ApkChecksum(split, kind, checksum));
            }
        }
    }

    private static byte[] getApkChecksum(File file, int kind) {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            byte[] dataBytes = new byte[512 * 1024];
            int nread = 0;

            final String algo = getMessageDigestAlgoForChecksumKind(kind);
            MessageDigest md = MessageDigest.getInstance(algo);
            while ((nread = bis.read(dataBytes)) != -1) {
                md.update(dataBytes, 0, nread);
            }

            return md.digest();
        } catch (IOException e) {
            Slog.e(TAG, "Error reading " + file.getAbsolutePath() + " to compute hash.", e);
            return null;
        } catch (NoSuchAlgorithmException e) {
            Slog.e(TAG, "Device does not support MessageDigest algorithm", e);
            return null;
        }
    }

    private static int[] getContentDigestAlgos(boolean needSignatureSha256,
            boolean needSignatureSha512) {
        if (needSignatureSha256 && needSignatureSha512) {
            // Signature block present, but no digests???
            return new int[]{CONTENT_DIGEST_CHUNKED_SHA256, CONTENT_DIGEST_CHUNKED_SHA512};
        } else if (needSignatureSha256) {
            return new int[]{CONTENT_DIGEST_CHUNKED_SHA256};
        } else {
            return new int[]{CONTENT_DIGEST_CHUNKED_SHA512};
        }
    }

    private static int getChecksumKindForContentDigestAlgo(int contentDigestAlgo) {
        switch (contentDigestAlgo) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                return PARTIAL_MERKLE_ROOT_1M_SHA256;
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return PARTIAL_MERKLE_ROOT_1M_SHA512;
            default:
                return -1;
        }
    }

    private static void calculatePartialChecksumsIfRequested(Map<Integer, ApkChecksum> checksums,
            String split, File file, int required) {
        boolean needSignatureSha256 =
                (required & PARTIAL_MERKLE_ROOT_1M_SHA256) != 0 && !checksums.containsKey(
                        PARTIAL_MERKLE_ROOT_1M_SHA256);
        boolean needSignatureSha512 =
                (required & PARTIAL_MERKLE_ROOT_1M_SHA512) != 0 && !checksums.containsKey(
                        PARTIAL_MERKLE_ROOT_1M_SHA512);
        if (!needSignatureSha256 && !needSignatureSha512) {
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            SignatureInfo signatureInfo = null;
            try {
                signatureInfo = ApkSignatureSchemeV3Verifier.findSignature(raf);
            } catch (SignatureNotFoundException e) {
                try {
                    signatureInfo = ApkSignatureSchemeV2Verifier.findSignature(raf);
                } catch (SignatureNotFoundException ee) {
                }
            }
            if (signatureInfo == null) {
                Slog.e(TAG, "V2/V3 signatures not found in " + file.getAbsolutePath());
                return;
            }

            final int[] digestAlgos = getContentDigestAlgos(needSignatureSha256,
                    needSignatureSha512);
            byte[][] digests = ApkSigningBlockUtils.computeContentDigestsPer1MbChunk(digestAlgos,
                    raf.getFD(), signatureInfo);
            for (int i = 0, size = digestAlgos.length; i < size; ++i) {
                int checksumKind = getChecksumKindForContentDigestAlgo(digestAlgos[i]);
                if (checksumKind != -1) {
                    checksums.put(checksumKind, new ApkChecksum(split, checksumKind, digests[i]));
                }
            }
        } catch (IOException | DigestException e) {
            Slog.e(TAG, "Error computing hash.", e);
        }
    }
}
