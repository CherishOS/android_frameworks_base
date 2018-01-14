/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RecoveryServiceMetadataEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.UserMetadataEntry;

/**
 * Helper for creating the recoverable key database.
 */
class RecoverableKeyStoreDbHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "recoverablekeystore.db";

    private static final String SQL_CREATE_KEYS_ENTRY =
            "CREATE TABLE " + KeysEntry.TABLE_NAME + "( "
                    + KeysEntry._ID + " INTEGER PRIMARY KEY,"
                    + KeysEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_UID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_ALIAS + " TEXT,"
                    + KeysEntry.COLUMN_NAME_NONCE + " BLOB,"
                    + KeysEntry.COLUMN_NAME_WRAPPED_KEY + " BLOB,"
                    + KeysEntry.COLUMN_NAME_GENERATION_ID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_LAST_SYNCED_AT + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_RECOVERY_STATUS + " INTEGER,"
                    + "UNIQUE(" + KeysEntry.COLUMN_NAME_UID + ","
                    + KeysEntry.COLUMN_NAME_ALIAS + "))";

    private static final String SQL_CREATE_USER_METADATA_ENTRY =
            "CREATE TABLE " + UserMetadataEntry.TABLE_NAME + "( "
                    + UserMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + UserMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER UNIQUE,"
                    + UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID + " INTEGER)";

    private static final String SQL_CREATE_RECOVERY_SERVICE_METADATA_ENTRY =
            "CREATE TABLE " + RecoveryServiceMetadataEntry.TABLE_NAME + " ("
                    + RecoveryServiceMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SNAPSHOT_VERSION + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SHOULD_CREATE_SNAPSHOT + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY + " BLOB,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SECRET_TYPES + " TEXT,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_COUNTER_ID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMS + " BLOB,"
                    + "UNIQUE("
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID  + ","
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + "))";

    private static final String SQL_DELETE_KEYS_ENTRY =
            "DROP TABLE IF EXISTS " + KeysEntry.TABLE_NAME;

    private static final String SQL_DELETE_USER_METADATA_ENTRY =
            "DROP TABLE IF EXISTS " + UserMetadataEntry.TABLE_NAME;

    private static final String SQL_DELETE_RECOVERY_SERVICE_METADATA_ENTRY =
            "DROP TABLE IF EXISTS " + RecoveryServiceMetadataEntry.TABLE_NAME;

    RecoverableKeyStoreDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_KEYS_ENTRY);
        db.execSQL(SQL_CREATE_USER_METADATA_ENTRY);
        db.execSQL(SQL_CREATE_RECOVERY_SERVICE_METADATA_ENTRY);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DELETE_KEYS_ENTRY);
        db.execSQL(SQL_DELETE_USER_METADATA_ENTRY);
        db.execSQL(SQL_DELETE_RECOVERY_SERVICE_METADATA_ENTRY);
        onCreate(db);
    }
}
