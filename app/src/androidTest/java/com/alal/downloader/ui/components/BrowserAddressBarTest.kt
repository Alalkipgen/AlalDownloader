package com.alal.downloader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import com.alal.downloader.core.data.HistoryEntry
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
                    BrowserAddressBar(pageUrl.value, emptyList(), false, {}, { navigations.add(BrowserPolicy.address(it)) })
                }
            }
        }
        compose.onNodeWithTag("browser-address-display").performClick()
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
        compose.onNodeWithTag("browser-address-display").performClick()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, pageUrl.value.length)))
        field.performTextInput("new draft")
        compose.runOnIdle { pageUrl.value = "https://www.google.com/?finished=1" }
        field.assertTextEquals("new draft")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        field.assertIsNotFocused().assertTextEquals("https://www.google.com/?finished=1")
    }

    @Test fun nonFocusableSuggestionsKeepTypingAndNavigateOnSelection() {
        val navigations = mutableListOf<String>()
        val entry = HistoryEntry(url = "https://notion.so/", title = "Notion saved page", host = "notion.so", visitedAt = 1)
        compose.setContent {
            MaterialTheme { BrowserAddressBar("https://www.google.com/", listOf(entry), false, {}, { navigations.add(it) }) }
        }
        compose.onNodeWithTag("browser-address-display").performClick()
        val field = compose.onNodeWithTag("browser-address")
        field.performTextInput("no")
        compose.onNodeWithText(entry.title).assertIsDisplayed()
        field.performTextInput("tion android")
        field.assertIsFocused().assertTextEquals("notion android")
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
        compose.onNodeWithText(entry.title).performClick()
        compose.runOnIdle { assertEquals(listOf(entry.url), navigations) }
        field.assertIsNotFocused()
    }
}