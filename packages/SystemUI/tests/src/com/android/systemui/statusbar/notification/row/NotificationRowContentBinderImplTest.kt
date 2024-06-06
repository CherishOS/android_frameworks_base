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
 * limitations under the License
 */
package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.content.Context
import android.os.AsyncTask
import android.os.Build
import android.os.CancellationSignal
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.BindParams
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationCallback
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import com.android.systemui.statusbar.notification.row.shared.HeadsUpStatusBarModel
import com.android.systemui.statusbar.notification.row.shared.NewRemoteViews
import com.android.systemui.statusbar.notification.row.shared.NotificationContentModel
import com.android.systemui.statusbar.notification.row.shared.NotificationRowContentBinderRefactor
import com.android.systemui.statusbar.policy.InflatedSmartReplyState
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder
import com.android.systemui.statusbar.policy.SmartReplyStateInflater
import com.android.systemui.util.concurrency.mockExecutorHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@EnableFlags(NotificationRowContentBinderRefactor.FLAG_NAME)
class NotificationRowContentBinderImplTest : SysuiTestCase() {
    private lateinit var mNotificationInflater: NotificationRowContentBinderImpl
    private lateinit var mBuilder: Notification.Builder
    private lateinit var mRow: ExpandableNotificationRow
    private lateinit var mHelper: NotificationTestHelper

    private var mCache: NotifRemoteViewCache = mock()
    private var mConversationNotificationProcessor: ConversationNotificationProcessor = mock()
    private var mInflatedSmartReplyState: InflatedSmartReplyState = mock()
    private var mInflatedSmartReplies: InflatedSmartReplyViewHolder = mock()
    private var mNotifLayoutInflaterFactoryProvider: NotifLayoutInflaterFactory.Provider = mock()
    private var mHeadsUpStyleProvider: HeadsUpStyleProvider = mock()
    private var mNotifLayoutInflaterFactory: NotifLayoutInflaterFactory = mock()
    private val mSmartReplyStateInflater: SmartReplyStateInflater =
        object : SmartReplyStateInflater {
            override fun inflateSmartReplyViewHolder(
                sysuiContext: Context,
                notifPackageContext: Context,
                entry: NotificationEntry,
                existingSmartReplyState: InflatedSmartReplyState?,
                newSmartReplyState: InflatedSmartReplyState
            ): InflatedSmartReplyViewHolder {
                return mInflatedSmartReplies
            }

            override fun inflateSmartReplyState(entry: NotificationEntry): InflatedSmartReplyState {
                return mInflatedSmartReplyState
            }
        }

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        mBuilder =
            Notification.Builder(mContext, "no-id")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .setStyle(Notification.BigTextStyle().bigText("big text"))
        mHelper = NotificationTestHelper(mContext, mDependency)
        val row = mHelper.createRow(mBuilder.build())
        mRow = spy(row)
        whenever(mNotifLayoutInflaterFactoryProvider.provide(any(), any()))
            .thenReturn(mNotifLayoutInflaterFactory)
        mNotificationInflater =
            NotificationRowContentBinderImpl(
                mCache,
                mock(),
                mConversationNotificationProcessor,
                mock(),
                mSmartReplyStateInflater,
                mNotifLayoutInflaterFactoryProvider,
                mHeadsUpStyleProvider,
                mock()
            )
    }

    @Test
    fun testIncreasedHeadsUpBeingUsed() {
        val params = BindParams()
        params.usesIncreasedHeadsUpHeight = true
        val builder = spy(mBuilder)
        mNotificationInflater.inflateNotificationViews(
            mRow.entry,
            mRow,
            params,
            true /* inflateSynchronously */,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL,
            builder,
            mContext,
            mSmartReplyStateInflater
        )
        verify(builder).createHeadsUpContentView(true)
    }

    @Test
    fun testIncreasedHeightBeingUsed() {
        val params = BindParams()
        params.usesIncreasedHeight = true
        val builder = spy(mBuilder)
        mNotificationInflater.inflateNotificationViews(
            mRow.entry,
            mRow,
            params,
            true /* inflateSynchronously */,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL,
            builder,
            mContext,
            mSmartReplyStateInflater
        )
        verify(builder).createContentView(true)
    }

    @Test
    fun testInflationCallsUpdated() {
        inflateAndWait(
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL,
            mRow
        )
        verify(mRow).onNotificationUpdated()
    }

    @Test
    fun testInflationOnlyInflatesSetFlags() {
        inflateAndWait(
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP,
            mRow
        )
        Assert.assertNotNull(mRow.privateLayout.headsUpChild)
        verify(mRow).onNotificationUpdated()
    }

    @Test
    fun testInflationThrowsErrorDoesntCallUpdated() {
        mRow.privateLayout.removeAllViews()
        mRow.entry.sbn.notification.contentView =
            RemoteViews(mContext.packageName, R.layout.status_bar)
        inflateAndWait(
            true /* expectingException */,
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL,
            mRow
        )
        Assert.assertTrue(mRow.privateLayout.childCount == 0)
        verify(mRow, times(0)).onNotificationUpdated()
    }

    @Test
    fun testAsyncTaskRemoved() {
        mRow.entry.abortTask()
        inflateAndWait(
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL,
            mRow
        )
        verify(mRow).onNotificationUpdated()
    }

    @Test
    fun testRemovedNotInflated() {
        mRow.setRemoved()
        mNotificationInflater.setInflateSynchronously(true)
        mNotificationInflater.bindContent(
            mRow.entry,
            mRow,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL,
            BindParams(),
            false /* forceInflate */,
            null /* callback */
        )
        Assert.assertNull(mRow.entry.runningTask)
    }

    @Test
    @Ignore("b/345418902")
    fun testInflationIsRetriedIfAsyncFails() {
        val headsUpStatusBarModel = HeadsUpStatusBarModel("private", "public")
        val result =
            NotificationRowContentBinderImpl.InflationProgress(
                packageContext = mContext,
                remoteViews = NewRemoteViews(),
                contentModel = NotificationContentModel(headsUpStatusBarModel)
            )
        val countDownLatch = CountDownLatch(1)
        NotificationRowContentBinderImpl.applyRemoteView(
            AsyncTask.SERIAL_EXECUTOR,
            inflateSynchronously = false,
            isMinimized = false,
            result = result,
            reInflateFlags = NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED,
            inflationId = 0,
            remoteViewCache = mock(),
            entry = mRow.entry,
            row = mRow,
            isNewView = true, /* isNewView */
            remoteViewClickHandler = { _, _, _ -> true },
            callback =
                object : InflationCallback {
                    override fun handleInflationException(entry: NotificationEntry, e: Exception) {
                        countDownLatch.countDown()
                        throw RuntimeException("No Exception expected")
                    }

                    override fun onAsyncInflationFinished(entry: NotificationEntry) {
                        countDownLatch.countDown()
                    }
                },
            parentLayout = mRow.privateLayout,
            existingView = null,
            existingWrapper = null,
            runningInflations = HashMap(),
            applyCallback =
                object : NotificationRowContentBinderImpl.ApplyCallback() {
                    override fun setResultView(v: View) {}

                    override val remoteView: RemoteViews
                        get() =
                            AsyncFailRemoteView(
                                mContext.packageName,
                                com.android.systemui.tests.R.layout.custom_view_dark
                            )
                },
            logger = mock(),
        )
        Assert.assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun doesntReapplyDisallowedRemoteView() {
        mBuilder.setStyle(Notification.MediaStyle())
        val mediaView = mBuilder.createContentView()
        mBuilder.setStyle(Notification.DecoratedCustomViewStyle())
        mBuilder.setCustomContentView(
            RemoteViews(context.packageName, com.android.systemui.tests.R.layout.custom_view_dark)
        )
        val decoratedMediaView = mBuilder.createContentView()
        Assert.assertFalse(
            "The decorated media style doesn't allow a view to be reapplied!",
            NotificationRowContentBinderImpl.canReapplyRemoteView(mediaView, decoratedMediaView)
        )
    }

    @Test
    @Ignore("b/345418902")
    fun testUsesSameViewWhenCachedPossibleToReuse() {
        // GIVEN a cached view.
        val contractedRemoteView = mBuilder.createContentView()
        whenever(
                mCache.hasCachedView(
                    mRow.entry,
                    NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
                )
            )
            .thenReturn(true)
        whenever(
                mCache.getCachedView(
                    mRow.entry,
                    NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
                )
            )
            .thenReturn(contractedRemoteView)

        // GIVEN existing bound view with same layout id.
        val view = contractedRemoteView.apply(mContext, null /* parent */)
        mRow.privateLayout.setContractedChild(view)

        // WHEN inflater inflates
        inflateAndWait(
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED,
            mRow
        )

        // THEN the view should be re-used
        Assert.assertEquals(
            "Binder inflated a new view even though the old one was cached and usable.",
            view,
            mRow.privateLayout.contractedChild
        )
    }

    @Test
    fun testInflatesNewViewWhenCachedNotPossibleToReuse() {
        // GIVEN a cached remote view.
        val contractedRemoteView = mBuilder.createHeadsUpContentView()
        whenever(
                mCache.hasCachedView(
                    mRow.entry,
                    NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
                )
            )
            .thenReturn(true)
        whenever(
                mCache.getCachedView(
                    mRow.entry,
                    NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED
                )
            )
            .thenReturn(contractedRemoteView)

        // GIVEN existing bound view with different layout id.
        val view: View = TextView(mContext)
        mRow.privateLayout.setContractedChild(view)

        // WHEN inflater inflates
        inflateAndWait(
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED,
            mRow
        )

        // THEN the view should be a new view
        Assert.assertNotEquals(
            "Binder (somehow) used the same view when inflating.",
            view,
            mRow.privateLayout.contractedChild
        )
    }

    @Test
    fun testInflationCachesCreatedRemoteView() {
        // WHEN inflater inflates
        inflateAndWait(
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED,
            mRow
        )

        // THEN inflater informs cache of the new remote view
        verify(mCache)
            .putCachedView(
                eq(mRow.entry),
                eq(NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED),
                any()
            )
    }

    @Test
    fun testUnbindRemovesCachedRemoteView() {
        // WHEN inflated unbinds content
        mNotificationInflater.unbindContent(
            mRow.entry,
            mRow,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP
        )

        // THEN inflated informs cache to remove remote view
        verify(mCache)
            .removeCachedView(
                eq(mRow.entry),
                eq(NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP)
            )
    }

    @Test
    fun testNotificationViewHeightTooSmallFailsValidation() {
        val validationError =
            getValidationError(
                measuredHeightDp = 5f,
                targetSdk = Build.VERSION_CODES.R,
                contentView = mock(),
            )
        Assert.assertNotNull(validationError)
    }

    @Test
    fun testNotificationViewHeightPassesForNewerSDK() {
        val validationError =
            getValidationError(
                measuredHeightDp = 5f,
                targetSdk = Build.VERSION_CODES.S,
                contentView = mock(),
            )
        Assert.assertNull(validationError)
    }

    @Test
    fun testNotificationViewHeightPassesForTemplatedViews() {
        val validationError =
            getValidationError(
                measuredHeightDp = 5f,
                targetSdk = Build.VERSION_CODES.R,
                contentView = null,
            )
        Assert.assertNull(validationError)
    }

    @Test
    fun testNotificationViewPassesValidation() {
        val validationError =
            getValidationError(
                measuredHeightDp = 20f,
                targetSdk = Build.VERSION_CODES.R,
                contentView = mock(),
            )
        Assert.assertNull(validationError)
    }

    private fun getValidationError(
        measuredHeightDp: Float,
        targetSdk: Int,
        contentView: RemoteViews?
    ): String? {
        val view: View = mock()
        whenever(view.measuredHeight)
            .thenReturn(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        measuredHeightDp,
                        mContext.resources.displayMetrics
                    )
                    .toInt()
            )
        mRow.entry.targetSdk = targetSdk
        mRow.entry.sbn.notification.contentView = contentView
        return NotificationRowContentBinderImpl.isValidView(view, mRow.entry, mContext.resources)
    }

    @Test
    fun testInvalidNotificationDoesNotInvokeCallback() {
        mRow.privateLayout.removeAllViews()
        mRow.entry.sbn.notification.contentView =
            RemoteViews(
                mContext.packageName,
                com.android.systemui.tests.R.layout.invalid_notification_height
            )
        inflateAndWait(
            true,
            mNotificationInflater,
            NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL,
            mRow
        )
        Assert.assertEquals(0, mRow.privateLayout.childCount.toLong())
        verify(mRow, times(0)).onNotificationUpdated()
    }

    private class ExceptionHolder {
        var mException: Exception? = null

        fun setException(exception: Exception?) {
            mException = exception
        }
    }

    private class AsyncFailRemoteView(packageName: String?, layoutId: Int) :
        RemoteViews(packageName, layoutId) {
        var mHandler = mockExecutorHandler { p0 -> p0.run() }

        override fun apply(context: Context, parent: ViewGroup): View {
            return super.apply(context, parent)
        }

        override fun applyAsync(
            context: Context,
            parent: ViewGroup,
            executor: Executor,
            listener: OnViewAppliedListener,
            handler: InteractionHandler?
        ): CancellationSignal {
            mHandler.post { listener.onError(RuntimeException("Failed to inflate async")) }
            return CancellationSignal()
        }

        override fun applyAsync(
            context: Context,
            parent: ViewGroup,
            executor: Executor,
            listener: OnViewAppliedListener
        ): CancellationSignal {
            return applyAsync(context, parent, executor, listener, null)
        }
    }

    companion object {
        private fun inflateAndWait(
            inflater: NotificationRowContentBinderImpl,
            @InflationFlag contentToInflate: Int,
            row: ExpandableNotificationRow
        ) {
            inflateAndWait(false /* expectingException */, inflater, contentToInflate, row)
        }

        private fun inflateAndWait(
            expectingException: Boolean,
            inflater: NotificationRowContentBinderImpl,
            @InflationFlag contentToInflate: Int,
            row: ExpandableNotificationRow,
        ) {
            val countDownLatch = CountDownLatch(1)
            val exceptionHolder = ExceptionHolder()
            inflater.setInflateSynchronously(true)
            val callback: InflationCallback =
                object : InflationCallback {
                    override fun handleInflationException(entry: NotificationEntry, e: Exception) {
                        if (!expectingException) {
                            exceptionHolder.setException(e)
                        }
                        countDownLatch.countDown()
                    }

                    override fun onAsyncInflationFinished(entry: NotificationEntry) {
                        if (expectingException) {
                            exceptionHolder.setException(
                                RuntimeException(
                                    "Inflation finished even though there should be an error"
                                )
                            )
                        }
                        countDownLatch.countDown()
                    }
                }
            inflater.bindContent(
                row.entry,
                row,
                contentToInflate,
                BindParams(),
                false /* forceInflate */,
                callback /* callback */
            )
            Assert.assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS))
            exceptionHolder.mException?.let { throw it }
        }
    }
}
