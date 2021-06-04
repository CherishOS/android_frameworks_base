package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkTemplate
import android.net.wifi.WifiManager
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
    private val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
        if (isWifiConnected) {
            val template: NetworkTemplate
            val wifiInfo = wifiManager.connectionInfo
            template = if (wifiInfo.hiddenSSID || wifiInfo.ssid == WifiManager.UNKNOWN_SSID) {
                NetworkTemplate.buildTemplateWifiWildcard()
            } else {
                NetworkTemplate.buildTemplateWifi(wifiInfo.ssid)
            }
            info = dataController.getDataUsageInfo(template)
            prefix = context.resources.getString(R.string.usage_wifi_prefix)
        } else {
            dataController.setSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
            info = dataController.getDataUsageInfo()
            prefix = context.resources.getString(R.string.usage_data_prefix)
        }
        formattedInfo = prefix + ": " + formatDataUsage(info.usageLevel) + " "
                + context.resources.getString(R.string.usage_data)
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
