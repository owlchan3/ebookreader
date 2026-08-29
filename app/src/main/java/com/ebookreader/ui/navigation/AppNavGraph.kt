package com.ebookreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ebookreader.di.Injector
import com.ebookreader.ui.bookshelf.BookshelfScreen
import com.ebookreader.ui.chat.ChatScreen
import com.ebookreader.ui.detail.BookDetailScreen
import com.ebookreader.ui.reader.ReaderScreen
import com.ebookreader.ui.tags.TagManagementScreen
import com.ebookreader.ui.stats.StatsScreen
import com.ebookreader.ui.settings.SettingsScreen
import com.ebookreader.ui.licenses.LicensesScreen
import com.ebookreader.ui.help.HelpScreen
import com.ebookreader.ui.recommend.RecommendScreen
import com.ebookreader.ui.decompose.DecomposeScreen

@Composable
fun AppNavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Screen.Bookshelf.route,
        modifier = modifier,
    ) {
        composable(Screen.Recommend.route) {
            RecommendScreen(
                onBookClick = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                },
            )
        }
        composable(Screen.Bookshelf.route) {
            BookshelfScreen(
                onBookClick = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                },
                onBookRead = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
            )
        }
        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            BookDetailScreen(
                bookId = bookId,
                onReadClick = { navController.navigate(Screen.Reader.createRoute(bookId)) },
                onBack = { navController.popBackStack() },
                onBookClick = { otherBookId ->
                    navController.navigate(Screen.BookDetail.createRoute(otherBookId))
                },
                onDecomposeClick = { bookType ->
                    navController.navigate(Screen.Decompose.createRoute(bookId, bookType))
                },
            )
        }
        composable(
            route = Screen.Reader.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
                onChatClick = {
                    navController.navigate(Screen.Chat.createRoute(bookId))
                },
            )
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            // Guard: reject navigation if AI plugin is disabled
            if (!Injector.apiKeyManager().isEnabled()) {
                navController.popBackStack()
                return@composable
            }
            ChatScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Screen.Decompose.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("bookType") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            val bookType = backStackEntry.arguments?.getString("bookType") ?: "general"
            if (!Injector.apiKeyManager().isEnabled()) {
                navController.popBackStack()
                return@composable
            }
            DecomposeScreen(
                bookId = bookId,
                bookType = bookType,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.Tags.route) {
            TagManagementScreen()
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToStats = { navController.navigate(Screen.Stats.route) },
                onNavigateToLicenses = { navController.navigate(Screen.Licenses.route) },
                onNavigateToHelp = { navController.navigate(Screen.Help.route) },
            )
        }
        composable(Screen.Stats.route) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Licenses.route) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Help.route) {
            HelpScreen(onBack = { navController.popBackStack() })
        }
    }
}
