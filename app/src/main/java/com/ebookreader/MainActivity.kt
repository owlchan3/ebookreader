package com.ebookreader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ebookreader.di.Injector
import com.ebookreader.ui.navigation.AppNavGraph
import com.ebookreader.ui.navigation.Screen
import com.ebookreader.ui.navigation.bottomNavItems
import com.ebookreader.ui.navigation.recommendNavItem
import com.ebookreader.ui.theme.EBookReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeDecomposeIntent(intent)
        enableEdgeToEdge()
        setContent {
            EBookReaderTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDecomposeIntent(intent)
    }

    private fun consumeDecomposeIntent(intent: Intent?) {
        val bookId = intent?.getLongExtra("decompose_book_id", -1L) ?: -1L
        if (bookId > 0) {
            val bookType = intent?.getStringExtra("decompose_book_type") ?: "general"
            pendingDecompose = bookId to bookType
        }
    }

    companion object {
        @Volatile
        var pendingDecompose: Pair<Long, String>? = null
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 从拆书通知栏进入：直接导航到拆书页
    LaunchedEffect(Unit) {
        MainActivity.pendingDecompose?.let { (bookId, bookType) ->
            MainActivity.pendingDecompose = null
            navController.navigate(Screen.Decompose.createRoute(bookId, bookType))
        }
    }

    val context = LocalContext.current
    var recommendEnabled by remember {
        mutableStateOf(Injector.apiKeyManager().isRecommendEnabled())
    }
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences("ai_plugin_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "recommend_enabled") {
                recommendEnabled = prefs.getBoolean(key, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val navItems = buildList {
        if (recommendEnabled) add(recommendNavItem)
        addAll(bottomNavItems)
    }

    val showBottomBar = navItems.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == item.route) item.selectedIcon
                                    else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        AppNavGraph(
            navController = navController,
            modifier = Modifier.padding(padding),
        )
    }
}
