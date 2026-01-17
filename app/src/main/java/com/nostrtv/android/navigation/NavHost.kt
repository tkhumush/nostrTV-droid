package com.nostrtv.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nostrtv.android.ui.home.HomeScreen
import com.nostrtv.android.ui.player.PlayerScreen
import com.nostrtv.android.ui.profile.ProfileScreen
import com.nostrtv.android.viewmodel.HomeViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Player : Screen("player/{streamId}") {
        fun createRoute(streamId: String) = "player/$streamId"
    }
    object Profile : Screen("profile")
    object Login : Screen("login")
}

@Composable
fun NostrTVNavHost(
    navController: NavHostController = rememberNavController()
) {
    // Shared ViewModel for stream data
    val homeViewModel: HomeViewModel = viewModel()
    val streams by homeViewModel.streams.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onStreamClick = { streamId ->
                    navController.navigate(Screen.Player.createRoute(streamId))
                },
                onProfileClick = {
                    navController.navigate(Screen.Profile.route)
                },
                viewModel = homeViewModel
            )
        }

        composable(Screen.Player.route) { backStackEntry ->
            val streamId = backStackEntry.arguments?.getString("streamId") ?: ""
            val stream = streams.find { it.id == streamId }

            PlayerScreen(
                streamId = streamId,
                stream = stream,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Profile.route) {
            ProfileScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
