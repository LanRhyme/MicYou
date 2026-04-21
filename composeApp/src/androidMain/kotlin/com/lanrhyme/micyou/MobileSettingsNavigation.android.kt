package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private const val ROUTE_HOME = "mobile_home"
private const val ROUTE_SETTINGS = "mobile_settings"

@Composable
actual fun MobileSettingsNavigation(
    homeContent: @Composable (onOpenSettings: () -> Unit) -> Unit,
    settingsContent: @Composable (onClose: () -> Unit) -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME
    ) {
        composable(ROUTE_HOME) {
            homeContent {
                navController.navigate(ROUTE_SETTINGS) {
                    launchSingleTop = true
                }
            }
        }
        composable(ROUTE_SETTINGS) {
            settingsContent {
                navController.popBackStack()
            }
        }
    }
}
