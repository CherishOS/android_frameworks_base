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

package com.android.settingslib.spa.framework.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.Intent.URI_INTENT_SCHEME
import android.content.UriMatcher
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.android.settingslib.spa.framework.common.ColumnEnum
import com.android.settingslib.spa.framework.common.QueryEnum
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.addUri
import com.android.settingslib.spa.framework.common.getColumns

private const val TAG = "DebugProvider"

/**
 * The content provider to return debug data.
 * One can query the provider result by:
 *   $ adb shell content query --uri content://<AuthorityPath>/<QueryPath>
 * For gallery, AuthorityPath = com.android.spa.gallery.debug
 * Some examples:
 *   $ adb shell content query --uri content://<AuthorityPath>/page_debug
 *   $ adb shell content query --uri content://<AuthorityPath>/entry_debug
 *   $ adb shell content query --uri content://<AuthorityPath>/page_info
 *   $ adb shell content query --uri content://<AuthorityPath>/entry_info
 */
class DebugProvider : ContentProvider() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance
    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        TODO("Implement this to handle requests to delete one or more rows")
    }

    override fun getType(uri: Uri): String? {
        TODO(
            "Implement this to handle requests for the MIME type of the data" +
                "at the given URI"
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        TODO("Implement this to handle requests to insert a new row.")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        TODO("Implement this to handle requests to update one or more rows.")
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "onCreate")
        return true
    }

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        if (info != null) {
            QueryEnum.PAGE_DEBUG_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.ENTRY_DEBUG_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.PAGE_INFO_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.ENTRY_INFO_QUERY.addUri(uriMatcher, info.authority)
        }
        super.attachInfo(context, info)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return try {
            when (uriMatcher.match(uri)) {
                QueryEnum.PAGE_DEBUG_QUERY.queryMatchCode -> queryPageDebug()
                QueryEnum.ENTRY_DEBUG_QUERY.queryMatchCode -> queryEntryDebug()
                QueryEnum.PAGE_INFO_QUERY.queryMatchCode -> queryPageInfo()
                QueryEnum.ENTRY_INFO_QUERY.queryMatchCode -> queryEntryInfo()
                else -> throw UnsupportedOperationException("Unknown Uri $uri")
            }
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Provider querying exception:", e)
            null
        }
    }

    private fun queryPageDebug(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.PAGE_DEBUG_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val command = pageWithEntry.page.createBrowseAdbCommand(
                context,
                spaEnvironment.browseActivityClass
            )
            if (command != null) {
                cursor.newRow().add(ColumnEnum.PAGE_START_ADB.id, command)
            }
        }
        return cursor
    }

    private fun queryEntryDebug(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.ENTRY_DEBUG_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            val command = entry.containerPage()
                .createBrowseAdbCommand(context, spaEnvironment.browseActivityClass, entry.id)
            if (command != null) {
                cursor.newRow().add(ColumnEnum.ENTRY_START_ADB.id, command)
            }
        }
        return cursor
    }

    private fun queryPageInfo(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.PAGE_INFO_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            val intent =
                page.createBrowseIntent(context, spaEnvironment.browseActivityClass) ?: Intent()
            cursor.newRow()
                .add(ColumnEnum.PAGE_ID.id, page.id)
                .add(ColumnEnum.PAGE_NAME.id, page.displayName)
                .add(ColumnEnum.PAGE_ROUTE.id, page.buildRoute())
                .add(ColumnEnum.PAGE_ENTRY_COUNT.id, pageWithEntry.entries.size)
                .add(ColumnEnum.HAS_RUNTIME_PARAM.id, if (page.hasRuntimeParam()) 1 else 0)
                .add(ColumnEnum.PAGE_INTENT_URI.id, intent.toUri(URI_INTENT_SCHEME))
        }
        return cursor
    }

    private fun queryEntryInfo(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.ENTRY_INFO_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            val intent = entry.containerPage()
                .createBrowseIntent(context, spaEnvironment.browseActivityClass, entry.id)
                ?: Intent()
            cursor.newRow()
                .add(ColumnEnum.ENTRY_ID.id, entry.id)
                .add(ColumnEnum.ENTRY_NAME.id, entry.displayName)
                .add(ColumnEnum.ENTRY_ROUTE.id, entry.containerPage().buildRoute())
                .add(ColumnEnum.ENTRY_INTENT_URI.id, intent.toUri(URI_INTENT_SCHEME))
                .add(ColumnEnum.ENTRY_HIERARCHY_PATH.id, entryRepository.getEntryPath(entry.id))
        }
        return cursor
    }
}
