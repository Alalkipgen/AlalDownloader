package com.alal.downloader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import com.alal.downloader.feature.browser.BrowserPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowserAddressBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun typingSurvivesPageUpdatesAndNavigatesOnlyOnGo() {
        val pageUrl = mutableStateOf("https://www.google.com/")
        val navigations = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    BrowserAddressBar(pageUrl.value, false, { navigations.add(BrowserPolicy.address(it)) })
                }
            }
        }
        compose.onNodeWithTag("browser-address").performTouchInput { click() }
        val field = compose.onNodeWithTag("browser-address")
        field.assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, pageUrl.value.length)))
        "notion android".forEach { field.performTextInput(it.toString()) }
        field.assertTextEquals("notion android").assertIsFocused()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            pageUrl.value = "https://www.google.com/?loading=1"
        }
        field.assertTextEquals("notion android").assertIsFocused()
        field.performImeAction()
        compose.runOnIdle {
            assertEquals(listOf("https://www.google.com/search?q=notion+android"), navigations)
            pageUrl.value = navigations.single()
        }
        field.assertIsNotFocused().assertTextEquals("https://www.google.com/search?q=notion+android")
        compose.onNodeWithTag("browser-address").performTouchInput { click() }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, pageUrl.value.length)))
        field.performTextInput("new draft")
        compose.runOnIdle { pageUrl.value = "https://www.google.com/?finished=1" }
        field.assertTextEquals("new draft")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        field.assertIsNotFocused().assertTextEquals("https://www.google.com/?finished=1")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun clearKeepsFocusAndHardwareEnterSubmitsLink() {
        val navigations = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                BrowserAddressBar("https://www.google.com/", false, { navigations.add(BrowserPolicy.address(it)) })
            }
        }
        val field = compose.onNodeWithTag("browser-address")
        field.performTouchInput { click() }
        field.assertIsEnabled().assertIsFocused()
        field.performTextClearance()
        field.assertTextEquals("").assertIsFocused()
        field.performTextInput("temporary draft")
        compose.onNodeWithContentDescription("Clear").performTouchInput { click() }
        field.assertTextEquals("").assertIsFocused()
        field.performTextInput("https://example.com/")
        field.assertTextEquals("https://example.com/").assertIsFocused()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
        field.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf("https://example.com/"), navigations) }
        field.assertIsNotFocused()
    }
}
