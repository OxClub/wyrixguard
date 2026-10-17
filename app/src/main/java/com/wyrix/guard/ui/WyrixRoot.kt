package com.wyrix.guard.ui
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wyrix.guard.ui.screens.OnboardingScreen
import com.wyrix.guard.ui.screens.SplashScreen
import com.wyrix.guard.ui.vm.MainViewModel

@Composable
fun WyrixRoot() {
    val vm: MainViewModel = hiltViewModel()
    val s by vm.ui.collectAsStateWithLifecycle()
    val locations by vm.locations.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val unsynced by vm.unsynced.collectAsStateWithLifecycle()
    var splash by rememberSaveable { mutableStateOf(true) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    when {
        splash -> SplashScreen(onFinished = { splash = false })
        !s.configured -> NotConfiguredScreen()
        !s.onboarded -> OnboardingScreen(onDone = vm::finishOnboarding)
        !s.signedIn -> AuthScreen(vm, s)
        !s.enrolled -> EnrollScreen(vm, s)
        else -> {
            val nav = rememberNavController()
            val route = nav.currentBackStackEntryAsState().value?.destination?.route
            val tabs = listOf(Triple("home", "Home", Icons.Filled.Home), Triple("map", "Map", Icons.Filled.Map),
                Triple("history", "History", Icons.Filled.History), Triple("alerts", "Alerts", Icons.Filled.Notifications),
                Triple("more", "More", Icons.Filled.MoreHoriz))
            Scaffold(bottomBar = {
                NavigationBar {
                    tabs.forEach { (r, label, icon) ->
                        NavigationBarItem(selected = route == r, label = { Text(label) }, icon = { Icon(icon, contentDescription = label) },
                            onClick = { nav.navigate(r) { popUpTo("home"); launchSingleTop = true } })
                    }
                }
            }) { pad ->
                NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
                    composable("home") { HomeScreen(vm, s, locations.firstOrNull(), unsynced) }
                    composable("map") { MapScreen(locations) }
                    composable("history") { HistoryScreen(vm, locations) }
                    composable("alerts") { AlertsScreen(events, simOnly = false) }
                    composable("more") { MoreScreen(onOpen = { nav.navigate(it) }) }
                    composable("sim") { AlertsScreen(events, simOnly = true) }
                    composable("geofences") { GeofencesScreen(vm, s) }
                    composable("photos") { PhotoEvidenceScreen(vm, s) }
                    composable("reports") { ReportsScreen(vm, s) }
                    composable("contacts") { ContactsScreen(vm, s) }
                    composable("privacy") { PrivacyScreen(vm, s) }
                    composable("plan") { PlanScreen(vm, s) }
                    composable("settings") { SettingsScreen(vm, s) }
                }
            }
        }
    }
}
