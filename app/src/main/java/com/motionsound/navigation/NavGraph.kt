package com.motionsound.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.motionsound.ui.screens.PlayerScreen
import com.motionsound.ui.screens.SettingsScreen
import com.motionsound.ui.screens.SongListScreen
import com.motionsound.viewmodel.PlayerViewModel

object Routes {
    const val PLAYER = "player"
    const val SONG_LIST = "songlist"
    const val SETTINGS = "settings"
}

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController()
) {
    val viewModel: PlayerViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.PLAYER
    ) {
        composable(Routes.PLAYER) {
            PlayerScreen(
                viewModel = viewModel,
                onNavigateToSongList = {
                    navController.navigate(Routes.SONG_LIST)
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.SONG_LIST) {
            SongListScreen(
                viewModel = viewModel,
                onSongClick = {
                    navController.popBackStack()
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
