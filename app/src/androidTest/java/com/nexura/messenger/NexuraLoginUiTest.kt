package com.nexura.messenger

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class NexuraLoginUiTest {
    private val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val activity = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permission).around(activity)

    @Test
    fun loginScreenRendersPrimaryAuthenticationOptions() {
        activity.onNodeWithText("nexura").assertIsDisplayed()
        activity.onNodeWithText("Продолжить с Google").assertIsDisplayed()
        activity.onNodeWithText("Email").assertIsDisplayed()
        activity.onNodeWithText("Пароль").assertIsDisplayed()
        activity.onNodeWithText("Войти").assertIsDisplayed()
    }

    @Test
    fun registrationToggleShowsNameFieldAndRegistrationAction() {
        activity.onNodeWithText("Создать аккаунт").performClick()
        activity.onNodeWithText("Ваше имя").performScrollTo().assertIsDisplayed()
        activity.onNodeWithText("Как к вам обращаться").performScrollTo().assertIsDisplayed()
        activity.onNodeWithText("Создать аккаунт").performScrollTo().assertIsDisplayed()
    }
}
