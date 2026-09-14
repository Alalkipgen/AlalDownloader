package com.alal.downloader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.alal.downloader.core.data.HistoryEntry
import com.alal.downloader.ui.theme.IconShape
import com.alal.downloader.ui.theme.PillShape

@Composable
internal fun BrowserAddressBar(
    pageUrl: String,
    suggestions: List<HistoryEntry>,
    incognito: Boolean,
    onQueryChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {},
) {
    var addressText by remember { mutableStateOf(TextFieldValue()) }
    var isFocused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val queryChanged by rememberUpdatedState(onQueryChange)
    LaunchedEffect(addressText.text, isFocused) { queryChanged(if (isFocused) addressText.text else "") }
    DisposableEffect(Unit) { onDispose { queryChanged("") } }
    fun dismiss() { focus.clearFocus(); keyboard?.hide() }
    fun navigate(value: String) { dismiss(); onNavigate(value) }
    BackHandler(enabled = isFocused) { dismiss() }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(46.dp).background(MaterialTheme.colorScheme.surfaceVariant, PillShape).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (incognito) Icon(Icons.Outlined.VisibilityOff, "Incognito", Modifier.size(15.dp))
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = if (isFocused) addressText else TextFieldValue(pageUrl),
                    onValueChange = { addressText = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                    keyboardActions = KeyboardActions(onGo = { navigate(addressText.text) }),
                    modifier = Modifier.fillMaxWidth().testTag("browser-address").focusRequester(requester).onFocusChanged {
                        if (it.isFocused && !isFocused) {
                            addressText = TextFieldValue(pageUrl, TextRange(0, pageUrl.length))
                        }
                        isFocused = it.isFocused
                    },
                )
                if (!isFocused) Text(
                    pageUrl.ifEmpty { "Search or type URL" },
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("browser-address-display").clickable { requester.requestFocus(); keyboard?.show() },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFocused) IconButton(onClick = { addressText = TextFieldValue("") }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp))
            }
            trailingContent()
        }
        Box(Modifier.fillMaxWidth()) {
            if (isFocused && addressText.text.trim().length >= 2) Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { dismiss() },
                properties = PopupProperties(focusable = false),
            ) {
                Surface(Modifier.fillMaxWidth().heightIn(max = 300.dp), shape = IconShape, tonalElevation = 3.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        suggestions.forEach { entry ->
                            Row(Modifier.fillMaxWidth().clickable { navigate(entry.url) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                HistoryFavicon(entry.faviconUrl)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(entry.host, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                Icon(Icons.Outlined.History, "From history", Modifier.size(18.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().clickable {
                            navigate("https://www.google.com/search?q=" + java.net.URLEncoder.encode(addressText.text, "UTF-8"))
                        }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Search, null)
                            Text("Search Google for ${addressText.text}", Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}