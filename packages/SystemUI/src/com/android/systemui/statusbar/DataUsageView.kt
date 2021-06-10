package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.BidiFormatter
import android.text.format.Formatter
import android.text.format.Formatter.BytesResult
import android.util.AttributeSet
import android.widget.TextView

import com.android.settingslib.net.DataUsageController
import com.android.systemui.R

import java.util.concurrent.Executors

class DataUsageView(context: Context, attrs: AttributeSet?) :
    TextView(context, attrs) {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val subManager: SubscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    private var formattedInfo: String? = null
    private var shouldUpdateData = false
    private var shouldUpdateDataTextView = false

    fun updateUsage() {
        shouldUpdateData = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shouldUpdateData) {
            shouldUpdateData = false
            val executor = Executors.newSingleThreadExecutor()
            executor.execute { updateUsageData() }
        }
        if (shouldUpdateDataTextView) {
            shouldUpdateDataTextView = false
            text = formattedInfo
        }
    }

    private fun updateUsageData() {
        val dataController = DataUsageController(context)
        val info: DataUsageController.DataUsageInfo
        val prefix: String
        val suffix: String
        val showDailyDataUsage = Settings.System.getInt(
            context.contentResolver,
            Settings.System.DATA_USAGE_PERIOD, 1
        ) == 0
        if (isWifiConnected) {
            info = if (showDailyDataUsage) {
                dataController.getWifiDailyDataUsageInfo()
            } else {
                dataController.getWifiDataUsageInfo()
            }
            prefix = context.resources.getString(R.string.usage_wifi_prefix)
        } else {
            dataController.setSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
            info = if (showDailyDataUsage) {
                dataController.getDailyDataUsageInfo()
            } else {
                dataController.getDataUsageInfo()
            }
            prefix = getSlotCarrierName()
        }
        suffix = context.resources.getString(
            if (showDailyDataUsage) {
                R.string.usage_data_today
            } else {
                R.string.usage_data
            }
        )
        formattedInfo = prefix + ": " + formatDataUsage(info.usageLevel) + " " + suffix
        shouldUpdateDataTextView = true
    }

    private fun formatDataUsage(byteValue: Long): CharSequence {
        val res: BytesResult = Formatter.formatBytes(
            context.resources, byteValue,
            Formatter.FLAG_IEC_UNITS
        )
        return BidiFormatter.getInstance().unicodeWrap(
            context.getString(
                com.android.internal.R.string.fileSizeSuffix, res.value, res.units
            )
        )
    }

    private fun getSlotCarrierName(): String {
        var result: CharSequence = ""
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        subManager.activeSubscriptionInfoList?.forEach { subInfo ->
            if (subId == subInfo.subscriptionId) {
                result = subInfo.displayName
            }
        }
        return result.toString()
    }

    private val isWifiConnected: Boolean
        get() {
            val network = connectivityManager.activeNetwork
            return if (network != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                false
            }
        }
}
