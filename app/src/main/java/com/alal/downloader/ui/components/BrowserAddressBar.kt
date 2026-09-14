package com.alal.downloader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.alal.downloader.ui.theme.PillShape

@Composable
internal fun BrowserAddressBar(
    pageUrl: String,
    incognito: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {},
) {
    var addressText by remember { mutableStateOf(TextFieldValue(pageUrl)) }
    var isFocused by remember { mutableStateOf(false) }
    var focusText by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(pageUrl, isFocused) {
        if (!isFocused) addressText = TextFieldValue(pageUrl)
    }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            withFrameNanos { }
            if (isFocused && addressText.text == focusText && addressText.composition == null) {
                addressText = addressText.copy(selection = TextRange(0, addressText.text.length))
            }
        }
    }
    fun dismiss() { focus.clearFocus(); keyboard?.hide() }
    fun submit() {
        val input = addressText.text
        dismiss()
        onNavigate(input)
    }
    BackHandler(enabled = isFocused) { dismiss() }

    Row(
        modifier.fillMaxWidth().height(46.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, PillShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (incognito) Icon(Icons.Outlined.VisibilityOff, "Incognito", Modifier.size(15.dp))
        BasicTextField(
            value = addressText,
            onValueChange = { addressText = it },
            readOnly = false,
            enabled = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.weight(1f).testTag("browser-address")
                .onFocusChanged {
                    if (it.isFocused && !isFocused) {
                        addressText = TextFieldValue(pageUrl, TextRange(0, pageUrl.length))
                        focusText = pageUrl
                    }
                    isFocused = it.isFocused
                }
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
                        if (it.type == KeyEventType.KeyUp) submit()
                        true
                    } else false
                },
        )
        if (isFocused) IconButton(
            onClick = { addressText = TextFieldValue("") },
            modifier = Modifier.size(30.dp).focusProperties { canFocus = false },
        ) {
            Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp))
        }
        trailingContent()
    }
}
