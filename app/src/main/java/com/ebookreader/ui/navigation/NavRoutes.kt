package com.ebookreader.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    data object Recommend : Screen("recommend")
    data object Bookshelf : Screen("bookshelf")
    data object BookDetail : Screen("book_detail/{bookId}") {
        fun createRoute(bookId: Long) = "book_detail/$bookId"
    }
    data object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: Long) = "reader/$bookId"
    }
    data object Tags : Screen("tags")
    data object Settings : Screen("settings")
    data object Chat : Screen("chat/{bookId}") {
        fun createRoute(bookId: Long) = "chat/$bookId"
    }
    data object Decompose : Screen("decompose/{bookId}/{bookType}") {
        fun createRoute(bookId: Long, bookType: String) = "decompose/$bookId/$bookType"
    }
    data object Stats : Screen("stats")
    data object Licenses : Screen("licenses")
    data object Help : Screen("help")
}

data class BottomNavItem(
    val label: String,
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem("书库", Screen.Bookshelf.route, Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem("书架", Screen.Tags.route, Icons.AutoMirrored.Filled.LibraryBooks, Icons.AutoMirrored.Outlined.LibraryBooks),
    BottomNavItem("设置", Screen.Settings.route, Icons.Filled.Settings, Icons.Outlined.Settings),
)

val recommendNavItem = BottomNavItem(
    "推荐",
    Screen.Recommend.route,
    Icons.Filled.AutoAwesome,
    Icons.Outlined.AutoAwesome,
)
