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

package com.android.systemui.controls.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.IBinder
import android.service.controls.Control
import android.service.controls.TokenProvider
import android.service.controls.actions.ControlAction
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.Space
import android.widget.TextView
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.management.ControlsProviderSelectorActivity
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.R

import dagger.Lazy

import java.text.Collator

import javax.inject.Inject
import javax.inject.Singleton

// TEMP CODE for MOCK
private const val TOKEN = "https://www.googleapis.com/auth/assistant"
private const val SCOPE = "oauth2:" + TOKEN
private var tokenProviderConnection: TokenProviderConnection? = null
class TokenProviderConnection(
    val cc: ControlsController,
    val context: Context,
    val structure: StructureInfo?
) : ServiceConnection {
    private var mTokenProvider: TokenProvider? = null

    override fun onServiceConnected(cName: ComponentName, binder: IBinder) {
        Thread({
            Log.i(ControlsUiController.TAG, "TokenProviderConnection connected")
            mTokenProvider = TokenProvider.Stub.asInterface(binder)

            val mLastAccountName = mTokenProvider?.getAccountName()

            if (mLastAccountName == null || mLastAccountName.isEmpty()) {
                Log.e(ControlsUiController.TAG, "NO ACCOUNT IS SET. Open HomeMock app")
            } else {
                mTokenProvider?.setAuthToken(getAuthToken(mLastAccountName))
                structure?.let {
                    cc.subscribeToFavorites(it)
                }
            }
        }, "TokenProviderThread").start()
    }

    override fun onServiceDisconnected(cName: ComponentName) {
        mTokenProvider = null
    }

    fun getAuthToken(accountName: String): String? {
        val am = AccountManager.get(context)
        val accounts = am.getAccountsByType("com.google")
        if (accounts == null || accounts.size == 0) {
            Log.w(ControlsUiController.TAG, "No com.google accounts found")
            return null
        }

        var account: Account? = null
        for (a in accounts) {
            if (a.name.equals(accountName)) {
                account = a
                break
            }
        }

        if (account == null) {
            account = accounts[0]
        }

        try {
            return am.blockingGetAuthToken(account!!, SCOPE, true)
        } catch (e: Throwable) {
            Log.e(ControlsUiController.TAG, "Error getting auth token", e)
            return null
        }
    }
}

private data class ControlKey(val componentName: ComponentName, val controlId: String)

@Singleton
class ControlsUiControllerImpl @Inject constructor (
    val controlsController: Lazy<ControlsController>,
    val context: Context,
    @Main val uiExecutor: DelayableExecutor,
    @Background val bgExecutor: DelayableExecutor,
    val controlsListingController: Lazy<ControlsListingController>,
    @Main val sharedPreferences: SharedPreferences
) : ControlsUiController {

    companion object {
        private const val PREF_COMPONENT = "controls_component"
        private const val PREF_STRUCTURE = "controls_structure"

        private val EMPTY_COMPONENT = ComponentName("", "")
        private val EMPTY_STRUCTURE = StructureInfo(
            EMPTY_COMPONENT,
            "",
            mutableListOf<ControlInfo>()
        )
    }

    private var selectedStructure: StructureInfo = EMPTY_STRUCTURE
    private lateinit var allStructures: List<StructureInfo>
    private val controlsById = mutableMapOf<ControlKey, ControlWithState>()
    private val controlViewsById = mutableMapOf<ControlKey, ControlViewHolder>()
    private lateinit var parent: ViewGroup
    private lateinit var lastItems: List<SelectionItem>
    private var popup: ListPopupWindow? = null
    private var activeDialog: Dialog? = null
    private val addControlsItem: SelectionItem

    init {
        val addDrawable = context.getDrawable(R.drawable.ic_add).apply {
            setTint(context.resources.getColor(R.color.control_secondary_text, null))
        }
        addControlsItem = SelectionItem(
            context.resources.getString(R.string.controls_providers_title),
            "",
            addDrawable,
            EMPTY_COMPONENT
        )
    }

    override val available: Boolean
        get() = controlsController.get().available

    private lateinit var listingCallback: ControlsListingController.ControlsListingCallback

    private fun createCallback(
        onResult: (List<SelectionItem>) -> Unit
    ): ControlsListingController.ControlsListingCallback {
        return object : ControlsListingController.ControlsListingCallback {
            override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
                bgExecutor.execute {
                    val collator = Collator.getInstance(context.resources.configuration.locales[0])
                    val localeComparator = compareBy<ControlsServiceInfo, CharSequence>(collator) {
                        it.loadLabel()
                    }

                    val mList = serviceInfos.toMutableList()
                    mList.sortWith(localeComparator)
                    lastItems = mList.map {
                        SelectionItem(it.loadLabel(), "", it.loadIcon(), it.componentName)
                    }
                    uiExecutor.execute {
                        onResult(lastItems)
                    }
                }
            }
        }
    }

    override fun show(parent: ViewGroup) {
        Log.d(ControlsUiController.TAG, "show()")
        this.parent = parent

        allStructures = controlsController.get().getFavorites()
        selectedStructure = loadPreference(allStructures)

        if (selectedStructure.controls.isEmpty() && allStructures.size <= 1) {
            // only show initial view if there are really no favorites across any structure
            listingCallback = createCallback(::showInitialSetupView)
        } else {
            selectedStructure.controls.map {
                ControlWithState(selectedStructure.componentName, it, null)
            }.associateByTo(controlsById) {
                ControlKey(selectedStructure.componentName, it.ci.controlId)
            }
            listingCallback = createCallback(::showControlsView)
        }

        controlsListingController.get().addCallback(listingCallback)

        // Temp code to pass auth
        tokenProviderConnection = TokenProviderConnection(controlsController.get(), context,
                selectedStructure)

        val serviceIntent = Intent()
        serviceIntent.setComponent(ComponentName("com.android.systemui.home.mock",
                "com.android.systemui.home.mock.AuthService"))
        if (!context.bindService(serviceIntent, tokenProviderConnection!!,
                Context.BIND_AUTO_CREATE)) {
            controlsController.get().subscribeToFavorites(selectedStructure)
        }
    }

    private fun showInitialSetupView(items: List<SelectionItem>) {
        parent.removeAllViews()

        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_no_favorites, parent, true)

        val viewGroup = parent.requireViewById(R.id.controls_no_favorites_group) as ViewGroup
        viewGroup.setOnClickListener(launchSelectorActivityListener(context))

        val iconRowGroup = parent.requireViewById(R.id.controls_icon_row) as ViewGroup
        items.forEach {
            val imageView = inflater.inflate(R.layout.controls_icon, viewGroup, false) as ImageView
            imageView.setContentDescription(it.getTitle())
            imageView.setImageDrawable(it.icon)
            iconRowGroup.addView(imageView)
        }
    }

    private fun launchSelectorActivityListener(context: Context): (View) -> Unit {
        return { _ ->
            val closeDialog = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            context.sendBroadcast(closeDialog)

            val i = Intent()
            i.setComponent(ComponentName(context, ControlsProviderSelectorActivity::class.java))
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    private fun showControlsView(items: List<SelectionItem>) {
        parent.removeAllViews()
        controlViewsById.clear()

        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.controls_with_favorites, parent, true)

        val listView = parent.requireViewById(R.id.global_actions_controls_list) as ViewGroup
        var lastRow: ViewGroup = createRow(inflater, listView)
        selectedStructure.controls.forEach {
            if (lastRow.getChildCount() == 2) {
                lastRow = createRow(inflater, listView)
            }
            val item = inflater.inflate(
                R.layout.controls_base_item, lastRow, false) as ViewGroup
            lastRow.addView(item)
            val cvh = ControlViewHolder(item, controlsController.get(), uiExecutor, bgExecutor)
            val key = ControlKey(selectedStructure.componentName, it.controlId)
            cvh.bindData(controlsById.getValue(key))
            controlViewsById.put(key, cvh)
        }

        // add spacer if necessary to keep control size consistent
        if ((selectedStructure.controls.size % 2) == 1) {
            lastRow.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
        }

        val itemsByComponent = items.associateBy { it.componentName }
        var adapter = ItemAdapter(context, R.layout.controls_spinner_item).apply {
            val listItems = allStructures.mapNotNull {
                itemsByComponent.get(it.componentName)?.copy(structure = it.structure)
            }

            addAll(listItems + addControlsItem)
        }

        /*
         * Default spinner widget does not work with the window type required
         * for this dialog. Use a textView with the ListPopupWindow to achieve
         * a similar effect
         */
        val item = adapter.findSelectionItem(selectedStructure) ?: adapter.getItem(0)
        parent.requireViewById<TextView>(R.id.app_or_structure_spinner).apply {
            setText(item.getTitle())
            // override the default color on the dropdown drawable
            (getBackground() as LayerDrawable).getDrawable(1)
                .setTint(context.resources.getColor(R.color.control_spinner_dropdown, null))
        }
        parent.requireViewById<ImageView>(R.id.app_icon).apply {
            setContentDescription(item.getTitle())
            setImageDrawable(item.icon)
        }
        val anchor = parent.requireViewById<ViewGroup>(R.id.controls_header)
        anchor.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                popup = ListPopupWindow(
                    ContextThemeWrapper(context, R.style.Control_ListPopupWindow))
                popup?.apply {
                    setWindowLayoutType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
                    setAnchorView(anchor)
                    setAdapter(adapter)
                    setModal(true)
                    setOnItemClickListener(object : AdapterView.OnItemClickListener {
                        override fun onItemClick(
                            parent: AdapterView<*>,
                            view: View,
                            pos: Int,
                            id: Long
                        ) {
                            val listItem = parent.getItemAtPosition(pos) as SelectionItem
                            this@ControlsUiControllerImpl.switchAppOrStructure(listItem)
                            dismiss()
                        }
                    })
                    // need to call show() first in order to construct the listView
                    show()
                    getListView()?.apply {
                        setDividerHeight(
                            context.resources.getDimensionPixelSize(R.dimen.control_list_divider))
                        setDivider(
                            context.resources.getDrawable(R.drawable.controls_list_divider))
                    }
                    show()
                }
            }
        })
    }

    private fun loadPreference(structures: List<StructureInfo>): StructureInfo {
        if (structures.isEmpty()) return EMPTY_STRUCTURE

        val component = sharedPreferences.getString(PREF_COMPONENT, null)?.let {
            ComponentName.unflattenFromString(it)
        } ?: EMPTY_COMPONENT
        val structure = sharedPreferences.getString(PREF_STRUCTURE, "")

        return structures.firstOrNull {
            component == it.componentName && structure == it.structure
        } ?: structures.get(0)
    }

    private fun updatePreferences(si: StructureInfo) {
        sharedPreferences.edit()
            .putString(PREF_COMPONENT, si.componentName.flattenToString())
            .putString(PREF_STRUCTURE, si.structure.toString())
            .commit()
    }

    private fun switchAppOrStructure(item: SelectionItem) {
        if (item == addControlsItem) {
            launchSelectorActivityListener(context)(parent)
        } else {
            val newSelection = allStructures.first {
                it.structure == item.structure && it.componentName == item.componentName
            }

            if (newSelection != selectedStructure) {
                selectedStructure = newSelection
                updatePreferences(selectedStructure)
                controlsListingController.get().removeCallback(listingCallback)
                show(parent)
            }
        }
    }

    override fun hide() {
        Log.d(ControlsUiController.TAG, "hide()")
        popup?.dismiss()
        activeDialog?.dismiss()

        controlsController.get().unsubscribe()
        context.unbindService(tokenProviderConnection)
        tokenProviderConnection = null

        parent.removeAllViews()
        controlsById.clear()
        controlViewsById.clear()
        controlsListingController.get().removeCallback(listingCallback)
    }

    override fun onRefreshState(componentName: ComponentName, controls: List<Control>) {
        Log.d(ControlsUiController.TAG, "onRefreshState()")
        controls.forEach { c ->
            controlsById.get(ControlKey(componentName, c.getControlId()))?.let {
                Log.d(ControlsUiController.TAG, "onRefreshState() for id: " + c.getControlId())
                val cws = ControlWithState(componentName, it.ci, c)
                val key = ControlKey(componentName, c.getControlId())
                controlsById.put(key, cws)

                uiExecutor.execute {
                    controlViewsById.get(key)?.bindData(cws)
                }
            }
        }
    }

    override fun onActionResponse(componentName: ComponentName, controlId: String, response: Int) {
        val key = ControlKey(componentName, controlId)
        uiExecutor.execute {
            controlViewsById.get(key)?.let { cvh ->
                when (response) {
                    ControlAction.RESPONSE_CHALLENGE_PIN -> {
                        activeDialog = ChallengeDialogs.createPinDialog(cvh)
                        activeDialog?.show()
                    }
                    else -> cvh.actionResponse(response)
                }
            }
        }
    }

    private fun createRow(inflater: LayoutInflater, listView: ViewGroup): ViewGroup {
        val row = inflater.inflate(R.layout.controls_row, listView, false) as ViewGroup
        listView.addView(row)
        return row
    }
}

private data class SelectionItem(
    val appName: CharSequence,
    val structure: CharSequence,
    val icon: Drawable,
    val componentName: ComponentName
) {
    fun getTitle() = if (structure.isEmpty()) { appName } else { structure }
}

private class ItemAdapter(
    val parentContext: Context,
    val resource: Int
) : ArrayAdapter<SelectionItem>(parentContext, resource) {

    val layoutInflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position)
        val view = convertView ?: layoutInflater.inflate(resource, parent, false)
        view.requireViewById<TextView>(R.id.controls_spinner_item).apply {
            setText(item.getTitle())
        }
        view.requireViewById<ImageView>(R.id.app_icon).apply {
            setContentDescription(item.getTitle())
            setImageDrawable(item.icon)
        }
        return view
    }

    fun findSelectionItem(si: StructureInfo): SelectionItem? {
        var i = 0
        while (i < getCount()) {
            val item = getItem(i)
            if (item.componentName == si.componentName &&
                item.structure == si.structure) {
                return item
            }
            i++
        }
        return null
    }
}
