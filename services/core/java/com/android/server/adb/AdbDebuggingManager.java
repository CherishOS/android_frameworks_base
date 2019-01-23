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

package com.android.server.adb;

import static com.android.internal.util.dump.DumpUtils.writeStringIfNotNull;

import android.annotation.TestApi;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.debug.AdbProtoEnums;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.adb.AdbDebuggingManagerProto;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;
import android.util.StatsLog;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Provides communication to the Android Debug Bridge daemon to allow, deny, or clear public keysi
 * that are authorized to connect to the ADB service itself.
 */
public class AdbDebuggingManager {
    private static final String TAG = "AdbDebuggingManager";
    private static final boolean DEBUG = false;

    private static final String ADBD_SOCKET = "adbd";
    private static final String ADB_DIRECTORY = "misc/adb";
    // This file contains keys that will always be allowed to connect to the device via adb.
    private static final String ADB_KEYS_FILE = "adb_keys";
    private static final int BUFFER_SIZE = 4096;

    private final Context mContext;
    private final Handler mHandler;
    private AdbDebuggingThread mThread;
    private boolean mAdbEnabled = false;
    private String mFingerprints;
    private String mConnectedKey;
    private String mConfirmComponent;

    public AdbDebuggingManager(Context context) {
        mHandler = new AdbDebuggingHandler(FgThread.get().getLooper());
        mContext = context;
    }

    /**
     * Constructor that accepts the component to be invoked to confirm if the user wants to allow
     * an adb connection from the key.
     */
    @TestApi
    protected AdbDebuggingManager(Context context, String confirmComponent) {
        mHandler = new AdbDebuggingHandler(FgThread.get().getLooper());
        mContext = context;
        mConfirmComponent = confirmComponent;
    }

    class AdbDebuggingThread extends Thread {
        private boolean mStopped;
        private LocalSocket mSocket;
        private OutputStream mOutputStream;
        private InputStream mInputStream;

        AdbDebuggingThread() {
            super(TAG);
        }

        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "Entering thread");
            while (true) {
                synchronized (this) {
                    if (mStopped) {
                        if (DEBUG) Slog.d(TAG, "Exiting thread");
                        return;
                    }
                    try {
                        openSocketLocked();
                    } catch (Exception e) {
                        /* Don't loop too fast if adbd dies, before init restarts it */
                        SystemClock.sleep(1000);
                    }
                }
                try {
                    listenToSocket();
                } catch (Exception e) {
                    /* Don't loop too fast if adbd dies, before init restarts it */
                    SystemClock.sleep(1000);
                }
            }
        }

        private void openSocketLocked() throws IOException {
            try {
                LocalSocketAddress address = new LocalSocketAddress(ADBD_SOCKET,
                        LocalSocketAddress.Namespace.RESERVED);
                mInputStream = null;

                if (DEBUG) Slog.d(TAG, "Creating socket");
                mSocket = new LocalSocket();
                mSocket.connect(address);

                mOutputStream = mSocket.getOutputStream();
                mInputStream = mSocket.getInputStream();
            } catch (IOException ioe) {
                closeSocketLocked();
                throw ioe;
            }
        }

        private void listenToSocket() throws IOException {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    int count = mInputStream.read(buffer);
                    // if less than 2 bytes are read the if statements below will throw an
                    // IndexOutOfBoundsException.
                    if (count < 2) {
                        break;
                    }

                    if (buffer[0] == 'P' && buffer[1] == 'K') {
                        String key = new String(Arrays.copyOfRange(buffer, 2, count));
                        Slog.d(TAG, "Received public key: " + key);
                        Message msg = mHandler.obtainMessage(
                                AdbDebuggingHandler.MESSAGE_ADB_CONFIRM);
                        msg.obj = key;
                        mHandler.sendMessage(msg);
                    } else if (buffer[0] == 'D' && buffer[1] == 'C') {
                        Slog.d(TAG, "Received disconnected message");
                        Message msg = mHandler.obtainMessage(
                                AdbDebuggingHandler.MESSAGE_ADB_DISCONNECT);
                        mHandler.sendMessage(msg);
                    } else {
                        Slog.e(TAG, "Wrong message: "
                                + (new String(Arrays.copyOfRange(buffer, 0, 2))));
                        break;
                    }
                }
            } finally {
                synchronized (this) {
                    closeSocketLocked();
                }
            }
        }

        private void closeSocketLocked() {
            if (DEBUG) Slog.d(TAG, "Closing socket");
            try {
                if (mOutputStream != null) {
                    mOutputStream.close();
                    mOutputStream = null;
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed closing output stream: " + e);
            }

            try {
                if (mSocket != null) {
                    mSocket.close();
                    mSocket = null;
                }
            } catch (IOException ex) {
                Slog.e(TAG, "Failed closing socket: " + ex);
            }
        }

        /** Call to stop listening on the socket and exit the thread. */
        void stopListening() {
            synchronized (this) {
                mStopped = true;
                closeSocketLocked();
            }
        }

        void sendResponse(String msg) {
            synchronized (this) {
                if (!mStopped && mOutputStream != null) {
                    try {
                        mOutputStream.write(msg.getBytes());
                    } catch (IOException ex) {
                        Slog.e(TAG, "Failed to write response:", ex);
                    }
                }
            }
        }
    }

    class AdbDebuggingHandler extends Handler {
        // The time to schedule the job to keep the key store updated with a currently connected
        // key. This job is required because a deveoper could keep a device connected to their
        // system beyond the time within which a subsequent connection is allowed. But since the
        // last connection time is only written when a device is connected and disconnected then
        // if the device is rebooted while connected to the development system it would appear as
        // though the adb grant for the system is no longer authorized and the developer would need
        // to manually allow the connection again.
        private static final long UPDATE_KEY_STORE_JOB_INTERVAL = 86400000;

        static final int MESSAGE_ADB_ENABLED = 1;
        static final int MESSAGE_ADB_DISABLED = 2;
        static final int MESSAGE_ADB_ALLOW = 3;
        static final int MESSAGE_ADB_DENY = 4;
        static final int MESSAGE_ADB_CONFIRM = 5;
        static final int MESSAGE_ADB_CLEAR = 6;
        static final int MESSAGE_ADB_DISCONNECT = 7;
        static final int MESSAGE_ADB_PERSIST_KEY_STORE = 8;
        static final int MESSAGE_ADB_UPDATE_KEY_CONNECTION_TIME = 9;

        private AdbKeyStore mAdbKeyStore;

        AdbDebuggingHandler(Looper looper) {
            super(looper);
            mAdbKeyStore = new AdbKeyStore();
        }

        /**
         * Constructor that accepts the AdbDebuggingThread to which responses should be sent
         * and the AdbKeyStore to be used to store the temporary grants.
         */
        @TestApi
        AdbDebuggingHandler(Looper looper, AdbDebuggingThread thread, AdbKeyStore adbKeyStore) {
            super(looper);
            mThread = thread;
            mAdbKeyStore = adbKeyStore;
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_ADB_ENABLED:
                    if (mAdbEnabled) {
                        break;
                    }

                    mAdbEnabled = true;

                    mThread = new AdbDebuggingThread();
                    mThread.start();

                    break;

                case MESSAGE_ADB_DISABLED:
                    if (!mAdbEnabled) {
                        break;
                    }

                    mAdbEnabled = false;

                    if (mThread != null) {
                        mThread.stopListening();
                        mThread = null;
                    }

                    break;

                case MESSAGE_ADB_ALLOW: {
                    String key = (String) msg.obj;
                    String fingerprints = getFingerprints(key);

                    if (!fingerprints.equals(mFingerprints)) {
                        Slog.e(TAG, "Fingerprints do not match. Got "
                                + fingerprints + ", expected " + mFingerprints);
                        break;
                    }

                    boolean alwaysAllow = msg.arg1 == 1;
                    if (mThread != null) {
                        mThread.sendResponse("OK");
                        if (alwaysAllow) {
                            mConnectedKey = key;
                            mAdbKeyStore.setLastConnectionTime(key, System.currentTimeMillis());
                            scheduleJobToUpdateAdbKeyStore();
                        }
                        logAdbConnectionChanged(key, AdbProtoEnums.USER_ALLOWED, alwaysAllow);
                    }
                    break;
                }

                case MESSAGE_ADB_DENY:
                    if (mThread != null) {
                        mThread.sendResponse("NO");
                        logAdbConnectionChanged(null, AdbProtoEnums.USER_DENIED, false);
                    }
                    break;

                case MESSAGE_ADB_CONFIRM: {
                    String key = (String) msg.obj;
                    if ("trigger_restart_min_framework".equals(
                            SystemProperties.get("vold.decrypt"))) {
                        Slog.d(TAG, "Deferring adb confirmation until after vold decrypt");
                        if (mThread != null) {
                            mThread.sendResponse("NO");
                            logAdbConnectionChanged(key, AdbProtoEnums.DENIED_VOLD_DECRYPT, false);
                        }
                        break;
                    }
                    String fingerprints = getFingerprints(key);
                    if ("".equals(fingerprints)) {
                        if (mThread != null) {
                            mThread.sendResponse("NO");
                            logAdbConnectionChanged(key, AdbProtoEnums.DENIED_INVALID_KEY, false);
                        }
                        break;
                    }
                    // Check if the key should be allowed without user interaction.
                    if (mAdbKeyStore.isKeyAuthorized(key)) {
                        if (mThread != null) {
                            mThread.sendResponse("OK");
                            mAdbKeyStore.setLastConnectionTime(key, System.currentTimeMillis());
                            logAdbConnectionChanged(key, AdbProtoEnums.AUTOMATICALLY_ALLOWED, true);
                            mConnectedKey = key;
                            scheduleJobToUpdateAdbKeyStore();
                        }
                    } else {
                        logAdbConnectionChanged(key, AdbProtoEnums.AWAITING_USER_APPROVAL, false);
                        mFingerprints = fingerprints;
                        startConfirmation(key, mFingerprints);
                    }
                    break;
                }

                case MESSAGE_ADB_CLEAR: {
                    deleteKeyFile();
                    mConnectedKey = null;
                    mAdbKeyStore.deleteKeyStore();
                    cancelJobToUpdateAdbKeyStore();
                    break;
                }

                case MESSAGE_ADB_DISCONNECT: {
                    if (mConnectedKey != null) {
                        mAdbKeyStore.setLastConnectionTime(mConnectedKey,
                                System.currentTimeMillis());
                        cancelJobToUpdateAdbKeyStore();
                    }
                    logAdbConnectionChanged(mConnectedKey, AdbProtoEnums.DISCONNECTED,
                            (mConnectedKey != null));
                    mConnectedKey = null;
                    break;
                }

                case MESSAGE_ADB_PERSIST_KEY_STORE: {
                    mAdbKeyStore.persistKeyStore();
                    break;
                }

                case MESSAGE_ADB_UPDATE_KEY_CONNECTION_TIME: {
                    if (mConnectedKey != null) {
                        mAdbKeyStore.setLastConnectionTime(mConnectedKey,
                                System.currentTimeMillis());
                        scheduleJobToUpdateAdbKeyStore();
                    }
                    break;
                }
            }
        }

        private void logAdbConnectionChanged(String key, int state, boolean alwaysAllow) {
            long lastConnectionTime = mAdbKeyStore.getLastConnectionTime(key);
            long authWindow = mAdbKeyStore.getAllowedConnectionTime();
            StatsLog.write(StatsLog.ADB_CONNECTION_CHANGED, lastConnectionTime, authWindow, state,
                    alwaysAllow);
        }
    }

    private String getFingerprints(String key) {
        String hex = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder();
        MessageDigest digester;

        if (key == null) {
            return "";
        }

        try {
            digester = MessageDigest.getInstance("MD5");
        } catch (Exception ex) {
            Slog.e(TAG, "Error getting digester", ex);
            return "";
        }

        byte[] base64_data = key.split("\\s+")[0].getBytes();
        byte[] digest;
        try {
            digest = digester.digest(Base64.decode(base64_data, Base64.DEFAULT));
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "error doing base64 decoding", e);
            return "";
        }
        for (int i = 0; i < digest.length; i++) {
            sb.append(hex.charAt((digest[i] >> 4) & 0xf));
            sb.append(hex.charAt(digest[i] & 0xf));
            if (i < digest.length - 1) {
                sb.append(":");
            }
        }
        return sb.toString();
    }

    private void startConfirmation(String key, String fingerprints) {
        int currentUserId = ActivityManager.getCurrentUser();
        UserInfo userInfo = UserManager.get(mContext).getUserInfo(currentUserId);
        String componentString;
        if (userInfo.isAdmin()) {
            componentString = mConfirmComponent != null
                    ? mConfirmComponent : Resources.getSystem().getString(
                    com.android.internal.R.string.config_customAdbPublicKeyConfirmationComponent);
        } else {
            // If the current foreground user is not the admin user we send a different
            // notification specific to secondary users.
            componentString = Resources.getSystem().getString(
                    R.string.config_customAdbPublicKeyConfirmationSecondaryUserComponent);
        }
        ComponentName componentName = ComponentName.unflattenFromString(componentString);
        if (startConfirmationActivity(componentName, userInfo.getUserHandle(), key, fingerprints)
                || startConfirmationService(componentName, userInfo.getUserHandle(),
                        key, fingerprints)) {
            return;
        }
        Slog.e(TAG, "unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component "
                + componentString + " as an Activity or a Service");
    }

    /**
     * @return true if the componentName led to an Activity that was started.
     */
    private boolean startConfirmationActivity(ComponentName componentName, UserHandle userHandle,
            String key, String fingerprints) {
        PackageManager packageManager = mContext.getPackageManager();
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            try {
                mContext.startActivityAsUser(intent, userHandle);
                return true;
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "unable to start adb whitelist activity: " + componentName, e);
            }
        }
        return false;
    }

    /**
     * @return true if the componentName led to a Service that was started.
     */
    private boolean startConfirmationService(ComponentName componentName, UserHandle userHandle,
            String key, String fingerprints) {
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        try {
            if (mContext.startServiceAsUser(intent, userHandle) != null) {
                return true;
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "unable to start adb whitelist service: " + componentName, e);
        }
        return false;
    }

    private Intent createConfirmationIntent(ComponentName componentName, String key,
            String fingerprints) {
        Intent intent = new Intent();
        intent.setClassName(componentName.getPackageName(), componentName.getClassName());
        intent.putExtra("key", key);
        intent.putExtra("fingerprints", fingerprints);
        return intent;
    }

    /**
     * Returns a new File with the specified name in the adb directory.
     */
    private File getAdbFile(String fileName) {
        File dataDir = Environment.getDataDirectory();
        File adbDir = new File(dataDir, ADB_DIRECTORY);

        if (!adbDir.exists()) {
            Slog.e(TAG, "ADB data directory does not exist");
            return null;
        }

        return new File(adbDir, fileName);
    }

    private File getUserKeyFile() {
        return getAdbFile(ADB_KEYS_FILE);
    }

    private void writeKey(String key) {
        try {
            File keyFile = getUserKeyFile();

            if (keyFile == null) {
                return;
            }

            if (!keyFile.exists()) {
                keyFile.createNewFile();
                FileUtils.setPermissions(keyFile.toString(),
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP, -1, -1);
            }

            FileOutputStream fo = new FileOutputStream(keyFile, true);
            fo.write(key.getBytes());
            fo.write('\n');
            fo.close();
        } catch (IOException ex) {
            Slog.e(TAG, "Error writing key:" + ex);
        }
    }

    private void deleteKeyFile() {
        File keyFile = getUserKeyFile();
        if (keyFile != null) {
            keyFile.delete();
        }
    }

    /**
     * When {@code enabled} is {@code true}, this allows ADB debugging and starts the ADB hanler
     * thread. When {@code enabled} is {@code false}, this disallows ADB debugging and shuts
     * down the handler thread.
     */
    public void setAdbEnabled(boolean enabled) {
        mHandler.sendEmptyMessage(enabled ? AdbDebuggingHandler.MESSAGE_ADB_ENABLED
                                          : AdbDebuggingHandler.MESSAGE_ADB_DISABLED);
    }

    /**
     * Allows the debugging from the endpoint identified by {@code publicKey} either once or
     * always if {@code alwaysAllow} is {@code true}.
     */
    public void allowDebugging(boolean alwaysAllow, String publicKey) {
        Message msg = mHandler.obtainMessage(AdbDebuggingHandler.MESSAGE_ADB_ALLOW);
        msg.arg1 = alwaysAllow ? 1 : 0;
        msg.obj = publicKey;
        mHandler.sendMessage(msg);
    }

    /**
     * Denies debugging connection from the device that last requested to connect.
     */
    public void denyDebugging() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MESSAGE_ADB_DENY);
    }

    /**
     * Clears all previously accepted ADB debugging public keys. Any subsequent request will need
     * to pass through {@link #allowUsbDebugging(boolean, String)} again.
     */
    public void clearDebuggingKeys() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MESSAGE_ADB_CLEAR);
    }

    /**
     * Sends a message to the handler to persist the key store.
     */
    private void sendPersistKeyStoreMessage() {
        Message msg = mHandler.obtainMessage(AdbDebuggingHandler.MESSAGE_ADB_PERSIST_KEY_STORE);
        mHandler.sendMessage(msg);
    }

    /**
     * Schedules a job to update the connection time of the currently connected key. This is
     * intended for cases such as development devices that are left connected to a user's
     * system beyond the window within which a connection is allowed without user interaction.
     * A job should be rescheduled daily so that if the device is rebooted while connected to
     * the user's system the last time in the key store will show within 24 hours which should
     * be within the allowed window.
     */
    private void scheduleJobToUpdateAdbKeyStore() {
        Message message = mHandler.obtainMessage(
                AdbDebuggingHandler.MESSAGE_ADB_UPDATE_KEY_CONNECTION_TIME);
        mHandler.sendMessageDelayed(message, AdbDebuggingHandler.UPDATE_KEY_STORE_JOB_INTERVAL);
    }

    /**
     * Cancels the scheduled job to update the connection time of the currently connected key.
     * This should be invoked once the adb session is disconnected.
     */
    private void cancelJobToUpdateAdbKeyStore() {
        mHandler.removeMessages(AdbDebuggingHandler.MESSAGE_ADB_UPDATE_KEY_CONNECTION_TIME);
    }

    /**
     * Dump the USB debugging state.
     */
    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("connected_to_adb", AdbDebuggingManagerProto.CONNECTED_TO_ADB, mThread != null);
        writeStringIfNotNull(dump, "last_key_received", AdbDebuggingManagerProto.LAST_KEY_RECEVIED,
                mFingerprints);

        try {
            dump.write("user_keys", AdbDebuggingManagerProto.USER_KEYS,
                    FileUtils.readTextFile(new File("/data/misc/adb/adb_keys"), 0, null));
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read user keys", e);
        }

        try {
            dump.write("system_keys", AdbDebuggingManagerProto.SYSTEM_KEYS,
                    FileUtils.readTextFile(new File("/adb_keys"), 0, null));
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read system keys", e);
        }

        dump.end(token);
    }

    /**
     * Handles adb keys for which the user has granted the 'always allow' option. This class ensures
     * these grants are revoked after a period of inactivity as specified in the
     * ADB_ALLOWED_CONNECTION_TIME setting.
     */
    class AdbKeyStore {
        private Map<String, Long> mKeyMap;
        private File mKeyFile;
        private AtomicFile mAtomicKeyFile;
        // This file contains keys that will be allowed to connect without user interaction as long
        // as a subsequent connection occurs within the allowed duration.
        private static final String ADB_TEMP_KEYS_FILE = "adb_temp_keys.xml";
        private static final String XML_TAG_ADB_KEY = "adbKey";
        private static final String XML_ATTRIBUTE_KEY = "key";
        private static final String XML_ATTRIBUTE_LAST_CONNECTION = "lastConnection";

        /**
         * Value returned by {@code getLastConnectionTime} when there is no previously saved
         * connection time for the specified key.
         */
        public static final long NO_PREVIOUS_CONNECTION = 0;

        /**
         * Constructor that uses the default location for the persistent adb key store.
         */
        AdbKeyStore() {
            initKeyFile();
            mKeyMap = getKeyMapFromFile();
        }

        /**
         * Constructor that uses the specified file as the location for the persistent adb key
         * store.
         */
        AdbKeyStore(File keyFile) {
            mKeyFile = keyFile;
            initKeyFile();
            mKeyMap = getKeyMapFromFile();
        }

        /**
         * Initializes the key file that will be used to persist the adb grants.
         */
        private void initKeyFile() {
            if (mKeyFile == null) {
                mKeyFile = getAdbFile(ADB_TEMP_KEYS_FILE);
            }
            // getAdbFile can return null if the adb file cannot be obtained
            if (mKeyFile != null) {
                mAtomicKeyFile = new AtomicFile(mKeyFile);
            }
        }

        /**
         * Returns the key map with the keys and last connection times from the key file.
         */
        private Map<String, Long> getKeyMapFromFile() {
            Map<String, Long> keyMap = new HashMap<String, Long>();
            // if the AtomicFile could not be instantiated before attempt again; if it still fails
            // return an empty key map.
            if (mAtomicKeyFile == null) {
                initKeyFile();
                if (mAtomicKeyFile == null) {
                    Slog.e(TAG, "Unable to obtain the key file, " + mKeyFile + ", for reading");
                    return keyMap;
                }
            }
            if (!mAtomicKeyFile.exists()) {
                return keyMap;
            }
            try (FileInputStream keyStream = mAtomicKeyFile.openRead()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(keyStream, StandardCharsets.UTF_8.name());
                XmlUtils.beginDocument(parser, XML_TAG_ADB_KEY);
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    String tagName = parser.getName();
                    if (tagName == null) {
                        break;
                    } else if (!tagName.equals(XML_TAG_ADB_KEY)) {
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    String key = parser.getAttributeValue(null, XML_ATTRIBUTE_KEY);
                    long connectionTime;
                    try {
                        connectionTime = Long.valueOf(
                                parser.getAttributeValue(null, XML_ATTRIBUTE_LAST_CONNECTION));
                    } catch (NumberFormatException e) {
                        Slog.e(TAG,
                                "Caught a NumberFormatException parsing the last connection time: "
                                        + e);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    keyMap.put(key, connectionTime);
                }
            } catch (IOException | XmlPullParserException e) {
                Slog.e(TAG, "Caught an exception parsing the XML key file: ", e);
            }
            return keyMap;
        }

        /**
         * Writes the key map to the key file.
         */
        public void persistKeyStore() {
            // if there is nothing in the key map then ensure any keys left in the key store files
            // are deleted as well.
            if (mKeyMap.size() == 0) {
                deleteKeyStore();
                return;
            }
            if (mAtomicKeyFile == null) {
                initKeyFile();
                if (mAtomicKeyFile == null) {
                    Slog.e(TAG, "Unable to obtain the key file, " + mKeyFile + ", for writing");
                    return;
                }
            }
            FileOutputStream keyStream = null;
            try {
                XmlSerializer serializer = new FastXmlSerializer();
                keyStream = mAtomicKeyFile.startWrite();
                serializer.setOutput(keyStream, StandardCharsets.UTF_8.name());
                serializer.startDocument(null, true);
                long allowedTime = getAllowedConnectionTime();
                long systemTime = System.currentTimeMillis();
                Iterator keyMapIterator = mKeyMap.entrySet().iterator();
                while (keyMapIterator.hasNext()) {
                    Map.Entry<String, Long> keyEntry = (Map.Entry) keyMapIterator.next();
                    long connectionTime = keyEntry.getValue();
                    if (systemTime < (connectionTime + allowedTime)) {
                        serializer.startTag(null, XML_TAG_ADB_KEY);
                        serializer.attribute(null, XML_ATTRIBUTE_KEY, keyEntry.getKey());
                        serializer.attribute(null, XML_ATTRIBUTE_LAST_CONNECTION,
                                String.valueOf(keyEntry.getValue()));
                        serializer.endTag(null, XML_TAG_ADB_KEY);
                    } else {
                        keyMapIterator.remove();
                    }
                }
                serializer.endDocument();
                mAtomicKeyFile.finishWrite(keyStream);
            } catch (IOException e) {
                Slog.e(TAG, "Caught an exception writing the key map: ", e);
                mAtomicKeyFile.failWrite(keyStream);
            }
        }

        /**
         * Removes all of the entries in the key map and deletes the key file.
         */
        public void deleteKeyStore() {
            mKeyMap.clear();
            if (mAtomicKeyFile == null) {
                return;
            }
            mAtomicKeyFile.delete();
        }

        /**
         * Returns the time of the last connection from the specified key, or {@code
         * NO_PREVIOUS_CONNECTION} if the specified key does not have an active adb grant.
         */
        public long getLastConnectionTime(String key) {
            return mKeyMap.getOrDefault(key, NO_PREVIOUS_CONNECTION);
        }

        /**
         * Sets the time of the last connection for the specified key to the provided time.
         */
        public void setLastConnectionTime(String key, long connectionTime) {
            // Do not set the connection time to a value that is earlier than what was previously
            // stored as the last connection time.
            if (mKeyMap.containsKey(key) && mKeyMap.get(key) >= connectionTime) {
                return;
            }
            mKeyMap.put(key, connectionTime);
            sendPersistKeyStoreMessage();
        }

        /**
         * Returns whether the specified key should be authroized to connect without user
         * interaction. This requires that the user previously connected this device and selected
         * the option to 'Always allow', and the time since the last connection is within the
         * allowed window.
         */
        public boolean isKeyAuthorized(String key) {
            long lastConnectionTime = getLastConnectionTime(key);
            if (lastConnectionTime == NO_PREVIOUS_CONNECTION) {
                return false;
            }
            long allowedConnectionTime = getAllowedConnectionTime();
            // if the allowed connection time is 0 then revert to the previous behavior of always
            // allowing previously granted adb grants.
            if (allowedConnectionTime == 0 || (System.currentTimeMillis() < (lastConnectionTime
                    + allowedConnectionTime))) {
                return true;
            } else {
                // since this key is no longer auhorized remove it from the Map
                removeKey(key);
                return false;
            }
        }

        /**
         * Returns the connection time within which a connection from an allowed key is
         * automatically allowed without user interaction.
         */
        public long getAllowedConnectionTime() {
            return Settings.Global.getLong(mContext.getContentResolver(),
                    Settings.Global.ADB_ALLOWED_CONNECTION_TIME,
                    Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);
        }

        /**
         * Removes the specified key from the key store.
         */
        public void removeKey(String key) {
            if (!mKeyMap.containsKey(key)) {
                return;
            }
            mKeyMap.remove(key);
            sendPersistKeyStoreMessage();
        }
    }
}
