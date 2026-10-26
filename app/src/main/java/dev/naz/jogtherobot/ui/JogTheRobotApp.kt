package dev.naz.jogtherobot.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.naz.jogtherobot.bluetooth.BluetoothController
import dev.naz.jogtherobot.bluetooth.BluetoothEvent
import dev.naz.jogtherobot.bluetooth.ConnectionState
import dev.naz.jogtherobot.ui.screens.HomeScreen
import dev.naz.jogtherobot.ui.screens.JogScreen
import dev.naz.jogtherobot.ui.screens.SavedDevicesScreen
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val JOG = "jog"
    const val SAVED_DEVICES = "saved_devices"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JogTheRobotApp(controller: BluetoothController) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val connectionState by controller.state.collectAsStateWithLifecycle()
    val bluetoothEnabled by controller.bluetoothEnabled.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isConnected = connectionState is ConnectionState.Connected

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // React to connection events coming from the Bluetooth layer.
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                is BluetoothEvent.Connected -> {
                    showMessage("Connected to ${event.device.name}")
                    navController.navigateToJog()
                }

                is BluetoothEvent.ConnectionFailed ->
                    showMessage("Could not connect to ${event.device.name}: ${event.reason}")

                is BluetoothEvent.ConnectionLost -> {
                    showMessage(event.reason)
                    navController.navigateHome()
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Jog The Robot",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
                )
                Text(
                    text = when (val s = connectionState) {
                        is ConnectionState.Connected -> "Connected: ${s.device.name}"
                        is ConnectionState.Connecting -> "Connecting to ${s.device.name}…"
                        is ConnectionState.Disconnected -> "Not connected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp)
                )
                HorizontalDivider()

                DrawerItem("Home", Icons.Filled.Home, currentRoute == Routes.HOME) {
                    navController.navigateHome()
                    scope.launch { drawerState.close() }
                }
                DrawerItem("Jog", Icons.Filled.PlayArrow, currentRoute == Routes.JOG) {
                    if (isConnected) {
                        navController.navigateToJog()
                    } else {
                        showMessage("Connect to a Bluetooth device first")
                    }
                    scope.launch { drawerState.close() }
                }
                DrawerItem(
                    "Show saved devices",
                    Icons.AutoMirrored.Filled.List,
                    currentRoute == Routes.SAVED_DEVICES
                ) {
                    navController.navigate(Routes.SAVED_DEVICES) { launchSingleTop = true }
                    scope.launch { drawerState.close() }
                }
                if (isConnected) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    DrawerItem("Disconnect", Icons.AutoMirrored.Filled.ExitToApp, false) {
                        controller.disconnect()
                        navController.navigateHome()
                        scope.launch { drawerState.close() }
                    }
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (currentRoute) {
                                Routes.JOG -> "Jog"
                                Routes.SAVED_DEVICES -> "Saved devices"
                                else -> "Jog The Robot"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        connectionState = connectionState,
                        bluetoothEnabled = bluetoothEnabled,
                        onShowSavedDevices = {
                            navController.navigate(Routes.SAVED_DEVICES) { launchSingleTop = true }
                        },
                        onOpenJog = { navController.navigateToJog() }
                    )
                }

                composable(Routes.SAVED_DEVICES) {
                    SavedDevicesScreen(
                        controller = controller,
                        connectionState = connectionState,
                        bluetoothEnabled = bluetoothEnabled
                    )
                }

                composable(Routes.JOG) {
                    val state = connectionState
                    if (state is ConnectionState.Connected) {
                        JogScreen(
                            device = state.device,
                            onSend = { command -> controller.send(command) },
                            incoming = controller.incoming,
                            onDisconnect = {
                                controller.disconnect()
                                navController.navigateHome()
                            }
                        )
                    } else {
                        // Guard: the Jog screen is only reachable while connected.
                        // Check the live value so a one-frame-stale UI state doesn't bounce us.
                        LaunchedEffect(Unit) {
                            if (controller.state.value !is ConnectionState.Connected) {
                                navController.navigateHome()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

private fun NavHostController.navigateHome() {
    navigate(Routes.HOME) {
        popUpTo(Routes.HOME) { inclusive = false }
        launchSingleTop = true
    }
}

/** Opens Jog with Home underneath it, so Back goes Jog -> Home. */
private fun NavHostController.navigateToJog() {
    navigate(Routes.JOG) {
        popUpTo(Routes.HOME) { inclusive = false }
        launchSingleTop = true
    }
}
