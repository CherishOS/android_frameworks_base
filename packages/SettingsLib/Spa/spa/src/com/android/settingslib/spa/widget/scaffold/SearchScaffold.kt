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

package com.android.settingslib.spa.widget.scaffold

import androidx.activity.compose.BackHandler
import androidx.appcompat.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settingslib.spa.framework.compose.hideKeyboardAction
import com.android.settingslib.spa.framework.compose.horizontalValues
import com.android.settingslib.spa.framework.theme.SettingsOpacity
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

/**
 * A [Scaffold] which content is can be full screen, and with a search feature built-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (bottomPadding: Dp, searchQuery: State<String>) -> Unit,
) {
    val viewModel: SearchScaffoldViewModel = viewModel()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SearchableTopAppBar(
                title = title,
                actions = actions,
                scrollBehavior = scrollBehavior,
                searchQuery = viewModel.searchQuery,
            ) { viewModel.searchQuery = it }
        },
    ) { paddingValues ->
        Box(
            Modifier
                .padding(paddingValues.horizontalValues())
                .padding(top = paddingValues.calculateTopPadding())
                .fillMaxSize(),
        ) {
            content(
                bottomPadding = paddingValues.calculateBottomPadding(),
                searchQuery = remember {
                    derivedStateOf { viewModel.searchQuery?.text ?: "" }
                },
            )
        }
    }
}

internal class SearchScaffoldViewModel : ViewModel() {
    var searchQuery: TextFieldValue? by mutableStateOf(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchableTopAppBar(
    title: String,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    searchQuery: TextFieldValue?,
    onSearchQueryChange: (TextFieldValue?) -> Unit,
) {
    if (searchQuery != null) {
        SearchTopAppBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onClose = { onSearchQueryChange(null) },
            actions = actions,
        )
    } else {
        SettingsTopAppBar(title, scrollBehavior) {
            SearchAction {
                scrollBehavior.collapse()
                onSearchQueryChange(TextFieldValue())
            }
            actions()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopAppBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = SettingsTheme.colorScheme.surfaceHeader) {
        TopAppBar(
            title = { SearchBox(query, onQueryChange) },
            modifier = Modifier.statusBarsPadding(),
            navigationIcon = { CollapseAction(onClose) },
            actions = {
                if (query.text.isNotEmpty()) {
                    ClearAction { onQueryChange(TextFieldValue()) }
                }
                actions()
            },
            colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = Color.Transparent),
        )
    }
    BackHandler { onClose() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBox(query: TextFieldValue, onQueryChange: (TextFieldValue) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val textStyle = MaterialTheme.typography.bodyLarge
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = textStyle,
        placeholder = {
            Text(
                text = stringResource(R.string.abc_search_hint),
                modifier = Modifier.alpha(SettingsOpacity.Hint),
                style = textStyle,
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = hideKeyboardAction()),
        singleLine = true,
        colors = TextFieldDefaults.textFieldColors(
            containerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
}

@Preview
@Composable
private fun SearchTopAppBarPreview() {
    SettingsTheme {
        SearchTopAppBar(query = TextFieldValue(), onQueryChange = {}, onClose = {}) {}
    }
}

@Preview
@Composable
private fun SearchScaffoldPreview() {
    SettingsTheme {
        SearchScaffold(title = "App notifications") { _, _ ->
            Column {
                Preference(object : PreferenceModel {
                    override val title = "Item 1"
                })
                Preference(object : PreferenceModel {
                    override val title = "Item 2"
                })
            }
        }
    }
}
