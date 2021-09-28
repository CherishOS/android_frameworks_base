package com.android.systemui.statusbar.notification.stack

import android.annotation.IntDef

/**
 * For now, declare the available notification buckets (sections) here so that other
 * presentation code can decide what to do based on an entry's buckets
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
        prefix = ["BUCKET_"],
        value = [
            BUCKET_UNKNOWN, BUCKET_MEDIA_CONTROLS, BUCKET_HEADS_UP, BUCKET_FOREGROUND_SERVICE,
            BUCKET_PEOPLE, BUCKET_ALERTING, BUCKET_SILENT
        ]
)
annotation class PriorityBucket

const val BUCKET_UNKNOWN = 0
const val BUCKET_MEDIA_CONTROLS = 1
const val BUCKET_HEADS_UP = 2
const val BUCKET_FOREGROUND_SERVICE = 3
const val BUCKET_PEOPLE = 4
const val BUCKET_ALERTING = 5
const val BUCKET_SILENT = 6
