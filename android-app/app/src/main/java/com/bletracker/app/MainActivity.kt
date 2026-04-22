package com.bletracker.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bletracker.app.data.AlertDto
import com.bletracker.app.data.DeviceDto
import com.bletracker.app.data.GeofenceDto
import com.bletracker.app.data.LocationPayload
import com.bletracker.app.data.RelaySnapshotDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class OwnerSection(val title: String) {
    Overview("Overview"),
    Devices("Devices"),
    Alerts("Alerts"),
    Geofences("Geofences"),
    Settings("Settings"),
}

private enum class AuthSection(val title: String) {
    Login("Login"),
    SignUp("Sign Up"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackerTheme {
                TrackerApp()
            }
        }
    }
}

@Composable
private fun TrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = trackerColors,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerApp(vm: MainViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(state.authTokenPresent) {
        if (state.authTokenPresent) {
            vm.refresh()
            if (!context.hasRequiredPermissions()) {
                permissionLauncher.launch(context.requiredPermissions())
            }
        }
    }

    if (state.authTokenPresent) {
        OwnerDashboard(
            state = state,
            onRefresh = vm::refresh,
            onRegisterDevice = vm::registerDevice,
            onRemoveDevice = vm::removeDevice,
            onSetDeviceMonitoring = vm::setDeviceMonitoring,
            onAddGeofence = vm::addGeofence,
            onDeleteGeofence = vm::deleteGeofence,
            onAcknowledgeAlert = vm::acknowledgeAlert,
            onSaveSettings = vm::saveSettings,
            onSetStatus = vm::setStatus,
            onSignOut = vm::signOut,
            onStartMonitoring = {
                if (!context.hasRequiredPermissions()) {
                    permissionLauncher.launch(context.requiredPermissions())
                }
                vm.startMonitoring()
            },
            onStopMonitoring = vm::stopMonitoring,
        )
    } else {
        AuthScreen(
            statusMessage = state.statusMessage,
            onSignIn = vm::signIn,
            onSignUp = vm::signUp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerDashboard(
    state: MainUiState,
    onRefresh: () -> Unit,
    onRegisterDevice: (String, String, String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onSetDeviceMonitoring: (String, Boolean) -> Unit,
    onAddGeofence: (String, String, String, String) -> Unit,
    onDeleteGeofence: (String) -> Unit,
    onAcknowledgeAlert: (String) -> Unit,
    onSaveSettings: (String, Boolean) -> Unit,
    onSetStatus: (String) -> Unit,
    onSignOut: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
) {
    var selectedSection by remember { mutableStateOf(OwnerSection.Overview) }
    var backendBaseUrl by remember(state.backendBaseUrl) { mutableStateOf(state.backendBaseUrl) }
    var scannerAutostartEnabled by remember(state.scannerAutostartEnabled) { mutableStateOf(state.scannerAutostartEnabled) }
    var claimDeviceId by remember { mutableStateOf("0x0000AA01") }
    var claimName by remember { mutableStateOf("My Bag") }
    var manualCode by remember { mutableStateOf("") }
    var geofenceName by remember { mutableStateOf("Home") }
    var geofenceLat by remember { mutableStateOf("") }
    var geofenceLng by remember { mutableStateOf("") }
    var geofenceRadius by remember { mutableStateOf("150") }
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = trackerColors.surface.copy(alpha = 0.92f),
                    titleContentColor = trackerColors.onSurface,
                ),
                title = {
                    Column {
                        Text("BLE Tracker Owner", color = Color.White)
                        Text(
                            state.user?.email ?: "Owner dashboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                    }
                }
            )
        }
    ) { padding ->
        AppBackdrop(modifier = Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                ScrollableTabRow(
                    selectedTabIndex = selectedSection.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = trackerColors.onSurface,
                    edgePadding = 12.dp,
                    indicator = { positions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(positions[selectedSection.ordinal]),
                            color = trackerColors.primary,
                        )
                    },
                    divider = {}
                ) {
                    OwnerSection.entries.forEach { section ->
                        Tab(
                            selected = selectedSection == section,
                            onClick = { selectedSection = section },
                            selectedContentColor = trackerColors.primary,
                            unselectedContentColor = trackerColors.onSurfaceVariant,
                            text = { Text(section.title, fontWeight = FontWeight.SemiBold) },
                        )
                    }
                }

                when (selectedSection) {
                    OwnerSection.Overview -> OverviewSection(
                        state = state,
                        onRefresh = onRefresh,
                        onOpenDevices = { selectedSection = OwnerSection.Devices },
                        onOpenAlerts = { selectedSection = OwnerSection.Alerts },
                        onOpenGeofences = { selectedSection = OwnerSection.Geofences },
                    )

                    OwnerSection.Devices -> DevicesSection(
                        state = state,
                        claimDeviceId = claimDeviceId,
                        onClaimDeviceIdChange = { claimDeviceId = it },
                        claimName = claimName,
                        onClaimNameChange = { claimName = it },
                        manualCode = manualCode,
                        onManualCodeChange = { manualCode = it },
                        onRegister = { onRegisterDevice(claimDeviceId, claimName, manualCode) },
                        onRemoveDevice = onRemoveDevice,
                        onSetDeviceMonitoring = onSetDeviceMonitoring,
                    )

                    OwnerSection.Alerts -> AlertsSection(
                        state = state,
                        onRefresh = onRefresh,
                        onAcknowledge = onAcknowledgeAlert,
                    )

                    OwnerSection.Geofences -> GeofencesSection(
                        state = state,
                        geofenceName = geofenceName,
                        onGeofenceNameChange = { geofenceName = it },
                        geofenceLat = geofenceLat,
                        onGeofenceLatChange = { geofenceLat = it },
                        geofenceLng = geofenceLng,
                        onGeofenceLngChange = { geofenceLng = it },
                        geofenceRadius = geofenceRadius,
                        onGeofenceRadiusChange = { geofenceRadius = it },
                        onUseCurrentLocation = {
                            if (!context.hasRequiredPermissions()) {
                                onSetStatus("Grant location permission first")
                            } else {
                                val currentLocation = context.bestAvailableLocation()
                                if (currentLocation == null) {
                                    onSetStatus("Current location not available yet")
                                } else {
                                    geofenceLat = "%.6f".format(currentLocation.latitude)
                                    geofenceLng = "%.6f".format(currentLocation.longitude)
                                    onSetStatus("Filled current location")
                                }
                            }
                        },
                        onSaveGeofence = {
                            onAddGeofence(geofenceName, geofenceLat, geofenceLng, geofenceRadius)
                        },
                        onDeleteGeofence = onDeleteGeofence,
                    )

                    OwnerSection.Settings -> SettingsSection(
                        state = state,
                        backendBaseUrl = backendBaseUrl,
                        onBackendBaseUrlChange = { backendBaseUrl = it },
                        scannerAutostartEnabled = scannerAutostartEnabled,
                        onScannerAutostartEnabledChange = { scannerAutostartEnabled = it },
                        onSaveSettings = {
                            onSaveSettings(backendBaseUrl, scannerAutostartEnabled)
                        },
                        onRefresh = onRefresh,
                        onSignOut = onSignOut,
                        onToggleMonitoring = { enabled ->
                            if (enabled) onStartMonitoring() else onStopMonitoring()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(
    statusMessage: String,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
) {
    var selectedSection by remember { mutableStateOf(AuthSection.Login) }
    var loginEmail by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var signUpName by remember { mutableStateOf("") }
    var signUpEmail by remember { mutableStateOf("") }
    var signUpPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = trackerColors.surface.copy(alpha = 0.92f),
                    titleContentColor = trackerColors.onSurface,
                ),
                title = {
                    Column {
                        Text("BLE Tracker", color = trackerColors.onSurface)
                        Text(
                            "Login to manage your tracker",
                            style = MaterialTheme.typography.bodySmall,
                            color = trackerColors.onSurfaceVariant,
                        )
                    }
                }
            )
        }
    ) { padding ->
        AppBackdrop(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    HeroCard(
                        title = "Smart Tracker",
                        subtitle = "Track devices, review alerts, and manage geofences from one polished dashboard.",
                        status = localError.ifBlank { statusMessage },
                    )
                }

                item {
                    ScrollableTabRow(
                        selectedTabIndex = selectedSection.ordinal,
                        containerColor = Color.Transparent,
                        contentColor = trackerColors.onSurface,
                        edgePadding = 0.dp,
                        indicator = { positions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(positions[selectedSection.ordinal]),
                                color = trackerColors.primary,
                            )
                        },
                        divider = {}
                    ) {
                        AuthSection.entries.forEach { section ->
                            Tab(
                                selected = selectedSection == section,
                                onClick = {
                                    localError = ""
                                    selectedSection = section
                                },
                                selectedContentColor = trackerColors.primary,
                                unselectedContentColor = trackerColors.onSurfaceVariant,
                                text = { Text(section.title, fontWeight = FontWeight.SemiBold) },
                            )
                        }
                    }
                }

                item {
                    when (selectedSection) {
                        AuthSection.Login -> SectionCard("Login") {
                            AppTextField(
                                value = loginEmail,
                                onValueChange = {
                                    localError = ""
                                    loginEmail = it
                                },
                                label = "Email",
                            )
                            Spacer(Modifier.height(8.dp))
                            AppTextField(
                                value = loginPassword,
                                onValueChange = {
                                    localError = ""
                                    loginPassword = it
                                },
                                label = "Password",
                                password = true,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onSignIn(loginEmail, loginPassword) },
                                colors = primaryButtonColors(),
                            ) {
                                Text("Login")
                            }
                        }

                        AuthSection.SignUp -> SectionCard("Create Account") {
                            AppTextField(
                                value = signUpName,
                                onValueChange = {
                                    localError = ""
                                    signUpName = it
                                },
                                label = "Name",
                            )
                            Spacer(Modifier.height(8.dp))
                            AppTextField(
                                value = signUpEmail,
                                onValueChange = {
                                    localError = ""
                                    signUpEmail = it
                                },
                                label = "Email",
                            )
                            Spacer(Modifier.height(8.dp))
                            AppTextField(
                                value = signUpPassword,
                                onValueChange = {
                                    localError = ""
                                    signUpPassword = it
                                },
                                label = "Password",
                                password = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            AppTextField(
                                value = confirmPassword,
                                onValueChange = {
                                    localError = ""
                                    confirmPassword = it
                                },
                                label = "Confirm Password",
                                password = true,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                when {
                                    signUpPassword != confirmPassword -> {
                                        localError = "Passwords do not match"
                                    }
                                    signUpPassword.length < 6 -> {
                                        localError = "Password must be at least 6 characters"
                                    }
                                    else -> {
                                        onSignUp(signUpName, signUpEmail, signUpPassword)
                                    }
                                }
                            }, colors = primaryButtonColors()) {
                                Text("Create Account")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewSection(
    state: MainUiState,
    onRefresh: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenGeofences: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroCard(
                title = "Owner Overview",
                subtitle = "Watch device health, alerts, and geofence activity from a single live command view.",
                status = state.statusMessage,
            )
        }

        item {
            SectionCard("Status") {
                Text("Owner overview is based on the latest backend device data and live local scan data.", style = MaterialTheme.typography.bodyMedium, color = trackerColors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Session", if (state.authTokenPresent) "Active" else "Missing", Modifier.weight(1f))
                    StatCard("Devices", state.devices.size.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Open Alerts", state.alerts.count { it.status == "open" }.toString(), Modifier.weight(1f))
                    StatCard("Geofences", state.geofences.size.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRefresh, colors = primaryButtonColors()) { Text("Refresh") }
                    TextButton(onClick = onOpenDevices) { Text("Manage Devices") }
                    TextButton(onClick = onOpenAlerts) { Text("View Alerts") }
                }
            }
        }

        item {
            SectionCard("Primary Device") {
                val device = state.devices.firstOrNull()
                if (device == null) {
                    EmptyState("No owned tracker is visible yet.")
                } else {
                    DeviceSummary(device, null)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onOpenGeofences) { Text("Manage Geofences") }
                }
            }
        }

        item {
            SectionCard("Latest Alert") {
                val alert = state.alerts.firstOrNull()
                if (alert == null) {
                    EmptyState("No alerts for this account.")
                } else {
                    AlertSummary(alert)
                }
            }
        }
    }
}

@Composable
private fun DevicesSection(
    state: MainUiState,
    claimDeviceId: String,
    onClaimDeviceIdChange: (String) -> Unit,
    claimName: String,
    onClaimNameChange: (String) -> Unit,
    manualCode: String,
    onManualCodeChange: (String) -> Unit,
    onRegister: () -> Unit,
    onRemoveDevice: (String) -> Unit,
    onSetDeviceMonitoring: (String, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard("Add Device") {
                AppTextField(value = claimDeviceId, onValueChange = onClaimDeviceIdChange, label = "Tracker Device ID")
                Spacer(Modifier.height(8.dp))
                AppTextField(value = claimName, onValueChange = onClaimNameChange, label = "Display Name")
                Spacer(Modifier.height(8.dp))
                AppTextField(value = manualCode, onValueChange = onManualCodeChange, label = "Manual One-Time Code")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRegister, colors = primaryButtonColors()) { Text("Register Device") }
            }
        }

        item { SectionHeader("Owned Devices") }
        if (state.devices.isEmpty()) {
            item { EmptyState("No devices are attached to this owner account.") }
        }
        items(state.devices, key = { it.deviceId }) { device ->
            SectionCard(device.displayName) {
                DeviceSummary(device, state.latestRelaySnapshot)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        onSetDeviceMonitoring(device.deviceId, !device.proximityMonitoringEnabled)
                    }, colors = primaryButtonColors()) {
                        Text(if (device.proximityMonitoringEnabled) "Mark Device Off" else "Resume Device")
                    }
                    TextButton(onClick = { onRemoveDevice(device.deviceId) }) {
                        Text("Remove Device")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsSection(
    state: MainUiState,
    onRefresh: () -> Unit,
    onAcknowledge: (String) -> Unit,
) {
    val openAlerts = state.alerts.filter { it.status == "open" }
    val otherAlerts = state.alerts.filter { it.status != "open" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard("Alert Center") {
                Text(
                    "Open alerts stay at the top so the owner can react quickly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = trackerColors.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefresh, colors = primaryButtonColors()) { Text("Refresh Alerts") }
            }
        }

        item { SectionHeader("Open Alerts") }
        if (openAlerts.isEmpty()) {
            item { EmptyState("No open alerts.") }
        }
        items(openAlerts, key = { it.id }) { alert ->
            SectionCard(alert.type.prettyLabel()) {
                AlertSummary(alert)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onAcknowledge(alert.id) }, colors = primaryButtonColors()) { Text("Acknowledge") }
            }
        }

        item { SectionHeader("Alert History") }
        if (otherAlerts.isEmpty()) {
            item { EmptyState("No historical alerts yet.") }
        }
        items(otherAlerts, key = { it.id }) { alert ->
            SectionCard(alert.type.prettyLabel()) {
                AlertSummary(alert)
            }
        }
    }
}

@Composable
private fun GeofencesSection(
    state: MainUiState,
    geofenceName: String,
    onGeofenceNameChange: (String) -> Unit,
    geofenceLat: String,
    onGeofenceLatChange: (String) -> Unit,
    geofenceLng: String,
    onGeofenceLngChange: (String) -> Unit,
    geofenceRadius: String,
    onGeofenceRadiusChange: (String) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onSaveGeofence: () -> Unit,
    onDeleteGeofence: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard("Add Geofence") {
                AppTextField(value = geofenceName, onValueChange = onGeofenceNameChange, label = "Geofence Name")
                Spacer(Modifier.height(8.dp))
                AppTextField(value = geofenceLat, onValueChange = onGeofenceLatChange, label = "Latitude")
                Spacer(Modifier.height(8.dp))
                AppTextField(value = geofenceLng, onValueChange = onGeofenceLngChange, label = "Longitude")
                Spacer(Modifier.height(8.dp))
                AppTextField(value = geofenceRadius, onValueChange = onGeofenceRadiusChange, label = "Radius Meters")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onUseCurrentLocation, colors = primaryButtonColors()) {
                        Text("Use Current Location")
                    }
                    Button(onClick = onSaveGeofence, colors = primaryButtonColors()) { Text("Save Geofence") }
                }
            }
        }

        item { SectionHeader("Saved Geofences") }
        if (state.geofences.isEmpty()) {
            item { EmptyState("No geofences configured.") }
        }
        items(state.geofences, key = { it.id }) { geofence ->
            SectionCard(geofence.name) {
                KeyValueRow("Center", "${geofence.center.lat}, ${geofence.center.lng}")
                KeyValueRow("Radius", "${geofence.radiusMeters} m")
                KeyValueRow("Status", if (geofence.enabled) "Enabled" else "Disabled")
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { onDeleteGeofence(geofence.id) }) {
                    Text("Delete Geofence")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    state: MainUiState,
    backendBaseUrl: String,
    onBackendBaseUrlChange: (String) -> Unit,
    scannerAutostartEnabled: Boolean,
    onScannerAutostartEnabledChange: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard("Scanner Controls") {
                Text(
                    "Permissions are requested automatically when the app opens or when monitoring is enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Background monitoring")
                    Switch(
                        checked = state.monitoringEnabled,
                        onCheckedChange = onToggleMonitoring,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Resume scan after reboot")
                    Switch(
                        checked = scannerAutostartEnabled,
                        onCheckedChange = onScannerAutostartEnabledChange,
                    )
                }
            }
        }

        item {
            SectionCard("Connection Settings") {
                Text(
                    state.statusMessage.ifBlank { "Waiting for backend sync" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = trackerColors.secondary,
                )
                if (state.lastBackendSyncAt.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TimestampRow("Last Backend Sync", state.lastBackendSyncAt)
                }
                if (state.serverTime.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TimestampRow("Server Time", state.serverTime)
                }
                Spacer(Modifier.height(12.dp))
                AppTextField(value = backendBaseUrl, onValueChange = onBackendBaseUrlChange, label = "Backend URL")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Scanner User ID", state.scannerUserId.ifBlank { "-" })
                KeyValueRow("Owner User ID", state.ownerUserId.ifBlank { "-" })
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSaveSettings, colors = primaryButtonColors()) { Text("Save") }
                    Button(onClick = onRefresh, colors = primaryButtonColors()) { Text("Refresh") }
                    TextButton(onClick = onSignOut) { Text("Sign Out") }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = trackerColors.surface.copy(alpha = 0.92f),
            contentColor = trackerColors.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                content()
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        HorizontalDivider(color = trackerColors.outline.copy(alpha = 0.35f))
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = trackerColors.onSurfaceVariant)
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = trackerColors.primaryContainer.copy(alpha = 0.82f),
            contentColor = trackerColors.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = trackerColors.onPrimaryContainer.copy(alpha = 0.8f))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DeviceSummary(device: DeviceDto, relaySnapshot: RelaySnapshotDto?) {
    val effectiveRelay = relaySnapshot?.takeIf {
        it.deviceId == device.deviceId && System.currentTimeMillis() - it.seenAtEpochMs <= RELAY_SNAPSHOT_FRESH_MS
    }
    val effectiveBagState = effectiveRelay?.bagState
    val effectiveSeenAt = effectiveRelay?.seenAtEpochMs?.toIsoString() ?: device.lastSeenAt
    val effectiveRssi = effectiveRelay?.rssi ?: device.lastRssi

    KeyValueRow("Device ID", device.deviceId)
    KeyValueRow("Owner Status", device.status.prettyLabel())
    KeyValueRow(
        "Autonomous Proximity Alerts",
        if (device.proximityMonitoringEnabled) "Enabled" else "Paused"
    )
    TimestampRow("Last Seen", effectiveSeenAt)
    KeyValueRow("Geofence", device.geofenceState?.prettyLabel() ?: "-")
    KeyValueRow("RSSI", effectiveRssi?.let { "$it dBm" } ?: "-")
    KeyValueRow("Proximity Estimate", effectiveRssi.toProximityLabel())
    device.lastLocation?.let { location ->
        KeyValueRow("Scanner Location", location.prettyLabel())
    } ?: KeyValueRow("Scanner Location", "No location sent")
    device.lastPacket?.let { packet ->
        Spacer(Modifier.height(8.dp))
        Text("Latest Backend Packet", style = MaterialTheme.typography.titleSmall)
        KeyValueRow("Packet Type", packet.packetTypeName.prettyLabel())
        KeyValueRow(
            "Bag State",
            effectiveBagState?.toBagStateLabel() ?: packet.bagStateName.prettyLabel()
        )
        KeyValueRow("Battery", packet.batteryLevelName.prettyLabel())
        KeyValueRow("Sequence", (effectiveRelay?.seqNum ?: packet.seqNum).toString())
        KeyValueRow("Health Flags", packet.healthStatus.toString())
        KeyValueRow("Days Since Change", packet.daysSinceChange.toString())
    }
}

@Composable
private fun AlertSummary(alert: AlertDto) {
    KeyValueRow("Status", alert.status.prettyLabel())
    KeyValueRow("Device", alert.deviceId)
    TimestampRow("Created", alert.createdAt)
    Spacer(Modifier.height(8.dp))
    Text(alert.message, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = trackerColors.onSurfaceVariant)
    }
}

@Composable
private fun TimestampRow(label: String, isoTimestamp: String?) {
    val parts = isoTimestamp.toDisplayDateTime()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(parts.first, style = MaterialTheme.typography.bodyMedium)
            Text(parts.second, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF07111F),
                        Color(0xFF0E1A2F),
                        Color(0xFF151A25),
                    )
                )
            )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            content = content,
        )
    }
}

@Composable
private fun HeroCard(
    title: String,
    subtitle: String,
    status: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF16355C),
                            Color(0xFF184E57),
                            Color(0xFF6B3E1E),
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE5EEF8))
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFE3A3),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = trackerColors.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = trackerColors.surfaceVariant.copy(alpha = 0.38f),
            focusedBorderColor = trackerColors.primary,
            unfocusedBorderColor = trackerColors.outline.copy(alpha = 0.45f),
            focusedTextColor = trackerColors.onSurface,
            unfocusedTextColor = trackerColors.onSurface,
            focusedLabelColor = trackerColors.primary,
            unfocusedLabelColor = trackerColors.onSurfaceVariant,
            cursorColor = trackerColors.primary,
        ),
    )
}

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = trackerColors.primary,
    contentColor = trackerColors.onPrimary,
)

private fun String.prettyLabel(): String =
    lowercase().split('_').joinToString(" ") { token ->
        token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun LocationPayload.prettyLabel(): String {
    val coords = "${"%.5f".format(lat)}, ${"%.5f".format(lng)}"
    val accuracy = accuracyMeters?.let { " (${it.toInt()} m)" }.orEmpty()
    return coords + accuracy
}

private fun Int.toBagStateLabel(): String = if (this == 0) "Closed" else "Open"

private fun Int.toPacketTypeLabel(): String = when (this) {
    0 -> "Heartbeat"
    1 -> "State Change"
    2 -> "Self Test"
    else -> "Unknown"
}

private fun Int?.toProximityLabel(): String = when {
    this == null -> "Unknown"
    this >= -55 -> "Immediate"
    this >= -65 -> "Very Near"
    this >= -75 -> "Near"
    this >= -85 -> "Moderate"
    else -> "Far"
}

private fun Long.toIsoString(): String =
    Instant.ofEpochMilli(this).toString()

private const val RELAY_SNAPSHOT_FRESH_MS = 4_000L

private fun android.content.Context.requiredPermissions(): Array<String> =
    buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

private fun android.content.Context.hasRequiredPermissions(): Boolean =
    requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

@SuppressLint("MissingPermission")
private fun Context.bestAvailableLocation(): Location? {
    val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    return providers
        .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { location -> location.time }
}

private fun String?.toDisplayDateTime(): Pair<String, String> {
    if (this.isNullOrBlank()) {
        return "-" to ""
    }

    return runCatching {
        val zoned = Instant.parse(this).atZone(ZoneId.systemDefault())
        DISPLAY_DATE_FORMAT.format(zoned) to DISPLAY_TIME_FORMAT.format(zoned)
    }.getOrElse {
        this to ""
    }
}

private val DISPLAY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val DISPLAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a")

private val trackerColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF6DD3C7),
    onPrimary = Color(0xFF062A2A),
    primaryContainer = Color(0xFF16464B),
    onPrimaryContainer = Color(0xFFD8FFFA),
    secondary = Color(0xFFF5B86B),
    onSecondary = Color(0xFF382004),
    secondaryContainer = Color(0xFF5A3A12),
    onSecondaryContainer = Color(0xFFFFE6C3),
    background = Color(0xFF07111F),
    onBackground = Color(0xFFF2F6FB),
    surface = Color(0xFF111C2C),
    onSurface = Color(0xFFF2F6FB),
    surfaceVariant = Color(0xFF1A2940),
    onSurfaceVariant = Color(0xFFAAB9CF),
    outline = Color(0xFF52627A),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF3D0007),
)
