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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bletracker.app.data.AdminRegistrationDto
import com.bletracker.app.data.AlertDto
import com.bletracker.app.data.DeviceDto
import com.bletracker.app.data.GeofenceDto
import kotlinx.coroutines.launch
import com.bletracker.app.data.LocationPayload
import com.bletracker.app.data.RelaySnapshotDto
import com.bletracker.app.data.SensorReadingDto
import com.bletracker.app.data.SensorReadingsPayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class OwnerSection(val title: String) {
    Overview("Overview"),
    Devices("Devices"),
    Alerts("Alerts"),
    Geofences("Geofences"),
    Sensors("Sensors"),
    Settings("Settings"),
}

private enum class AuthSection(val title: String) {
    Login("Login"),
    SignUp("Sign Up"),
}

private enum class EntryMode(val title: String) {
    Owner("Owner"),
    Admin("Admin"),
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
            onRegisterDevice = { deviceId, displayName, code, onResult -> vm.registerDevice(deviceId, displayName, code, onResult) },
            onRemoveDevice = vm::removeDevice,
            onSetDeviceMonitoring = vm::setDeviceMonitoring,
            onAddGeofence = vm::addGeofence,
            onDeleteGeofence = vm::deleteGeofence,
            onAcknowledgeAlert = vm::acknowledgeAlert,
            onSaveSettings = { autostart -> vm.saveSettings(state.backendBaseUrl, autostart, state.googleWebClientId) },
            onSetStatus = vm::setStatus,
            onSignOut = vm::signOut,
            onStartMonitoring = {
                if (!context.hasRequiredPermissions()) {
                    permissionLauncher.launch(context.requiredPermissions())
                }
                vm.startMonitoring()
            },
            onStopMonitoring = vm::stopMonitoring,
            onAddSensorRule = vm::addSensorRule,
            onRemoveSensorRule = vm::removeSensorRule,
        )
    } else if (state.adminMode) {
        AdminDashboard(
            state = state,
            onRegisterTracker = vm::registerTrackerAsAdmin,
            onSignOutAdmin = vm::signOutAdmin,
        )
    } else {
        AuthScreen(
            statusMessage = state.statusMessage,
            googleWebClientId = state.googleWebClientId,
            onSignIn = vm::signIn,
            onSignUp = vm::signUp,
            onGoogleSignIn = vm::signInWithGoogle,
            onSignInAdmin = vm::signInAdmin,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerDashboard(
    state: MainUiState,
    onRefresh: () -> Unit,
    onRegisterDevice: (String, String, String, (Boolean) -> Unit) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onSetDeviceMonitoring: (String, Boolean) -> Unit,
    onAddGeofence: (String, String, String, String) -> Unit,
    onDeleteGeofence: (String) -> Unit,
    onAcknowledgeAlert: (String) -> Unit,
    onSaveSettings: (Boolean) -> Unit,
    onSetStatus: (String) -> Unit,
    onSignOut: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onAddSensorRule: (String, String, String, Double?, Double?) -> Unit,
    onRemoveSensorRule: (String) -> Unit,
) {
    var selectedSection by remember { mutableStateOf(OwnerSection.Overview) }
    var scannerAutostartEnabled by remember(state.scannerAutostartEnabled) { mutableStateOf(state.scannerAutostartEnabled) }
    var claimDeviceId by remember { mutableStateOf("0x0000AA01") }
    var claimName by remember { mutableStateOf("My Bag") }
    var manualCode by remember { mutableStateOf("") }
    var geofenceName by remember { mutableStateOf("Home") }
    var geofenceLat by remember { mutableStateOf("") }
    var geofenceLng by remember { mutableStateOf("") }
    var geofenceRadius by remember { mutableStateOf("150") }
    var targetLocateDeviceId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF131920),
                    titleContentColor = trackerColors.onSurface,
                ),
                title = {
                    Column {
                        Text("BLE Tracker", color = trackerColors.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(
                            state.user?.email ?: "Owner dashboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = trackerColors.onSurfaceVariant,
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
                        onLocateDevice = { targetLocateDeviceId = it },
                    )

                    OwnerSection.Devices -> DevicesSection(
                        state = state,
                        claimDeviceId = claimDeviceId,
                        onClaimDeviceIdChange = { claimDeviceId = it },
                        claimName = claimName,
                        onClaimNameChange = { claimName = it },
                        manualCode = manualCode,
                        onManualCodeChange = { manualCode = it },
                        onRegister = { onDone -> onRegisterDevice(claimDeviceId, claimName, manualCode, onDone) },
                        onRemoveDevice = onRemoveDevice,
                        onSetDeviceMonitoring = onSetDeviceMonitoring,
                        onLocateDevice = { targetLocateDeviceId = it },
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
                        scannerAutostartEnabled = scannerAutostartEnabled,
                        onScannerAutostartEnabledChange = { scannerAutostartEnabled = it },
                        onSaveSettings = {
                            onSaveSettings(scannerAutostartEnabled)
                        },
                        onRefresh = onRefresh,
                        onSignOut = onSignOut,
                        onToggleMonitoring = { enabled ->
                            if (enabled) onStartMonitoring() else onStopMonitoring()
                        },
                    )

                    OwnerSection.Sensors -> SensorsSection(
                        state = state,
                        onAddRule = onAddSensorRule,
                        onRemoveRule = onRemoveSensorRule,
                    )
                }
            }

            if (targetLocateDeviceId != null) {
                AppBackdrop(modifier = Modifier.fillMaxSize()) {
                    LocateSection(
                        state = state,
                        deviceId = targetLocateDeviceId!!,
                        onClose = { targetLocateDeviceId = null }
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
    googleWebClientId: String,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onGoogleSignIn: (String, String) -> Unit,
    onSignInAdmin: (String) -> Unit,
) {
    var entryMode by remember { mutableStateOf(EntryMode.Owner) }
    var selectedSection by remember { mutableStateOf(AuthSection.Login) }
    var loginEmail by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var signUpName by remember { mutableStateOf("") }
    var signUpEmail by remember { mutableStateOf("") }
    var signUpPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var adminSecret by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val googleSignInManager = remember { com.bletracker.app.auth.GoogleSignInManager(context as android.app.Activity) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF131920),
                    titleContentColor = trackerColors.onSurface,
                ),
                title = {
                    Column {
                        Text("BLE Tracker", color = trackerColors.onSurface, fontWeight = FontWeight.SemiBold)
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
                        selectedTabIndex = entryMode.ordinal,
                        containerColor = Color.Transparent,
                        contentColor = trackerColors.onSurface,
                        edgePadding = 0.dp,
                        indicator = { positions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(positions[entryMode.ordinal]),
                                color = trackerColors.secondary,
                            )
                        },
                        divider = {}
                    ) {
                        EntryMode.entries.forEach { mode ->
                            Tab(
                                selected = entryMode == mode,
                                onClick = {
                                    localError = ""
                                    entryMode = mode
                                },
                                selectedContentColor = trackerColors.secondary,
                                unselectedContentColor = trackerColors.onSurfaceVariant,
                                text = { Text(mode.title, fontWeight = FontWeight.SemiBold) },
                            )
                        }
                    }
                }

                item {
                    when (entryMode) {
                        EntryMode.Owner -> SectionCard("Choose Account Access") {
                            Text(
                                "Sign in as an owner to manage claimed trackers, alerts, and geofences.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = trackerColors.onSurfaceVariant,
                            )
                        }

                        EntryMode.Admin -> SectionCard("Admin Access") {
                            Text(
                                "Use the backend admin registration secret to open the dedicated tracker provisioning area.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = trackerColors.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            AppTextField(
                                value = adminSecret,
                                onValueChange = {
                                    localError = ""
                                    adminSecret = it
                                },
                                label = "Admin Secret",
                                password = true,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onSignInAdmin(adminSecret) },
                                colors = primaryButtonColors(),
                            ) {
                                Text("Open Admin Area")
                            }
                        }
                    }
                }

                if (entryMode == EntryMode.Owner) item {
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

                if (entryMode == EntryMode.Owner) item {
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onSignIn(loginEmail, loginPassword) },
                                    colors = primaryButtonColors(),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Login")
                                }
                                Button(
                                    onClick = { 
                                        coroutineScope.launch {
                                            try {
                                                val token = googleSignInManager.signIn(googleWebClientId)
                                                onGoogleSignIn(token, googleWebClientId)
                                            } catch (e: Exception) {
                                                localError = e.message ?: "Google Sign In Failed"
                                            }
                                        }
                                    },
                                    colors = primaryButtonColors(),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Google")
                                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminDashboard(
    state: MainUiState,
    onRegisterTracker: (String, String) -> Unit,
    onSignOutAdmin: () -> Unit,
) {
    var trackerId by remember { mutableStateOf("0x0000AB01") }
    var trackerNote by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF131920),
                    titleContentColor = trackerColors.onSurface,
                ),
                title = {
                    Column {
                        Text("BLE Tracker Admin", color = trackerColors.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Provision trackers and generate owner claim codes",
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
                        title = "Admin Provisioning",
                        subtitle = "Write a tracker device ID, create a one-time owner claim code, and store the registration in the backend.",
                        status = state.statusMessage,
                    )
                }

                item {
                    SectionCard("Tracker Registration") {
                        Text(
                            "Device ID identifies the physical tracker. The generated claim code is what the owner enters later to claim it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = trackerColors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        AppTextField(
                            value = trackerId,
                            onValueChange = { trackerId = it },
                            label = "Tracker Device ID",
                        )
                        Spacer(Modifier.height(8.dp))
                        AppTextField(
                            value = trackerNote,
                            onValueChange = { trackerNote = it },
                            label = "Admin Note",
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { onRegisterTracker(trackerId, trackerNote) },
                                colors = primaryButtonColors(),
                            ) {
                                Text("Generate Claim Code")
                            }
                            TextButton(onClick = onSignOutAdmin) {
                                Text("Exit Admin")
                            }
                        }
                    }
                }

                item {
                    SectionCard("Latest Generated Claim") {
                        val registration = state.latestAdminRegistration
                        if (registration == null) {
                            EmptyState("No tracker has been provisioned in this admin session yet.")
                        } else {
                            AdminRegistrationSummary(registration)
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
    onLocateDevice: (String) -> Unit,
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Scanner", if (state.monitoringEnabled) "Running" else "Stopped", state.monitoringEnabled)
                    StatusPill("Relay", if (state.latestRelaySnapshot != null) "Recent" else "Idle", state.latestRelaySnapshot != null)
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
                    DeviceSummary(device, null, onLocateDevice)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onOpenGeofences) { Text("Manage Geofences") }
                }
            }
        }

        item {
            val alert = state.alerts.firstOrNull()
            if (alert == null) {
                SectionCard("Latest Alert") {
                    EmptyState("No alerts for this account.")
                }
            } else {
                AlertCard("Latest Alert: ${alert.type.prettyLabel()}", alert.type) {
                    AlertSummary(alert)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSection(
    state: MainUiState,
    claimDeviceId: String,
    onClaimDeviceIdChange: (String) -> Unit,
    claimName: String,
    onClaimNameChange: (String) -> Unit,
    manualCode: String,
    onManualCodeChange: (String) -> Unit,
    onRegister: ((Boolean) -> Unit) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onSetDeviceMonitoring: (String, Boolean) -> Unit,
    onLocateDevice: (String) -> Unit,
) {
    var selectedDeviceId by remember(state.devices) {
        mutableStateOf(state.devices.firstOrNull()?.deviceId ?: "")
    }
    var selectorExpanded by remember { mutableStateOf(false) }
    var showAddDevice by remember { mutableStateOf(false) }

    val selectedDevice = state.devices.find { it.deviceId == selectedDeviceId }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Status message ──
        if (state.statusMessage.isNotBlank()) {
            item {
                val isError = state.statusMessage.contains("failed", ignoreCase = true)
                        || state.statusMessage.contains("required", ignoreCase = true)
                        || state.statusMessage.contains("not found", ignoreCase = true)
                        || state.statusMessage.contains("invalid", ignoreCase = true)
                        || state.statusMessage.contains("already", ignoreCase = true)
                val bgColor = if (isError) Color(0x33FF5252) else Color(0x3300E676)
                val textColor = if (isError) Color(0xFFFF5252) else Color(0xFF00E676)
                Text(
                    text = state.statusMessage,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // ── Device selector dropdown ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExposedDropdownMenuBox(
                    expanded = selectorExpanded,
                    onExpandedChange = { selectorExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = selectedDevice?.displayName ?: "No devices",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Select Device") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = selectorExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.6f),
                            unfocusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.35f),
                            focusedBorderColor = trackerColors.primary.copy(alpha = 0.7f),
                            unfocusedBorderColor = trackerColors.outline.copy(alpha = 0.3f),
                            focusedTextColor = trackerColors.onSurface,
                            unfocusedTextColor = trackerColors.onSurface,
                            focusedLabelColor = trackerColors.primary,
                            unfocusedLabelColor = trackerColors.onSurfaceVariant.copy(alpha = 0.7f),
                        ),
                    )
                    ExposedDropdownMenu(
                        expanded = selectorExpanded,
                        onDismissRequest = { selectorExpanded = false },
                        containerColor = trackerColors.surfaceVariant,
                    ) {
                        state.devices.forEach { device ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(device.displayName, color = trackerColors.onSurface)
                                        Text(
                                            device.deviceId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = trackerColors.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedDeviceId = device.deviceId
                                    selectorExpanded = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { showAddDevice = !showAddDevice }) {
                    Text(if (showAddDevice) "Cancel" else "+ Add")
                }
            }
        }

        // ── Collapsible Add-Device form ──
        if (showAddDevice) {
            item {
                SectionCard("Register New Device") {
                    AppTextField(value = claimDeviceId, onValueChange = onClaimDeviceIdChange, label = "Tracker Device ID")
                    Spacer(Modifier.height(6.dp))
                    AppTextField(value = claimName, onValueChange = onClaimNameChange, label = "Display Name")
                    Spacer(Modifier.height(6.dp))
                    AppTextField(value = manualCode, onValueChange = onManualCodeChange, label = "One-Time Claim Code")
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        onRegister { success ->
                            if (success) showAddDevice = false
                        }
                    }, colors = primaryButtonColors()) { Text("Register Device") }
                }
            }
        }

        // ── Selected device detail ──
        if (selectedDevice == null) {
            item { EmptyState("No devices registered. Tap '+ Add' to claim a tracker.") }
        } else {
            val device = selectedDevice
            val effectiveRelay = state.latestRelaySnapshot?.takeIf {
                it.deviceId == device.deviceId && System.currentTimeMillis() - it.seenAtEpochMs <= RELAY_SNAPSHOT_FRESH_MS
            }
            val effectiveBagState = effectiveRelay?.bagState
            val effectiveSeenAt = effectiveRelay?.seenAtEpochMs?.toIsoString() ?: device.lastSeenAt
            val effectiveRssi = effectiveRelay?.rssi ?: device.lastRssi

            // Status pills
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        "Bag",
                        effectiveBagState?.toBagStateLabel()
                            ?: device.lastPacket?.bagStateName?.prettyLabel()
                            ?: "Unknown",
                        effectiveBagState == 0 || device.lastPacket?.bagState == 0,
                    )
                    StatusPill(
                        "Proximity",
                        effectiveRssi.toProximityLabel(),
                        effectiveRssi != null && effectiveRssi >= -75,
                    )
                    StatusPill(
                        "Monitoring",
                        if (device.proximityMonitoringEnabled) "On" else "Off",
                        device.proximityMonitoringEnabled,
                    )
                }
            }

            // Device info table
            item {
                SectionCard("Device Info") {
                    DataRow("Device ID", device.deviceId)
                    DataRow("Display Name", device.displayName)
                    DataRow("Owner Status", device.status.prettyLabel())
                    DataRow("Proximity Alerts", if (device.proximityMonitoringEnabled) "Enabled" else "Paused")
                    DataRow("Geofence", device.geofenceState?.prettyLabel() ?: "–")
                }
            }

            // Connection table
            item {
                SectionCard("Connection") {
                    TimestampRow("Last Seen", effectiveSeenAt)
                    DataRow("RSSI", effectiveRssi?.let { "$it dBm" } ?: "–")
                    DataRow("Proximity", effectiveRssi.toProximityLabel())
                    device.lastLocation?.let { loc ->
                        DataRow("Location", loc.prettyLabel())
                    } ?: DataRow("Location", "Not available")
                }
            }

            // Packet data table
            device.lastPacket?.let { packet ->
                item {
                    SectionCard("Latest Packet") {
                        DataRow("Type", packet.packetTypeName.prettyLabel())
                        DataRow("Bag State", effectiveBagState?.toBagStateLabel() ?: packet.bagStateName.prettyLabel())
                        DataRow("Battery", packet.batteryLevelName.prettyLabel())
                        DataRow("Sequence", (effectiveRelay?.seqNum ?: packet.seqNum).toString())
                        DataRow("Health Flags", packet.healthStatus.toString())
                        DataRow("Days Since Change", packet.daysSinceChange.toString())
                    }
                }

                // Sensor telemetry table
                packet.sensors?.let { sensors ->
                    item {
                        SectionCard("Sensor Telemetry") {
                            NullableMetric("Temperature", sensors.temperatureC?.let { "%.2f °C".format(it) })
                            NullableMetric("Humidity", sensors.humidityRh?.let { "%.2f %%".format(it) })
                            NullableMetric("Light", sensors.lux?.let { "$it lux" })
                            NullableMetric("Vibration", sensors.vibrationScore?.toString())
                            if (sensors.accelXMg != null && sensors.accelYMg != null && sensors.accelZMg != null) {
                                DataRow("Accel", "x=${sensors.accelXMg}  y=${sensors.accelYMg}  z=${sensors.accelZMg} mg")
                            }
                            if (sensors.gyroXDps != null && sensors.gyroYDps != null && sensors.gyroZDps != null) {
                                DataRow("Gyro", "x=${"%.1f".format(sensors.gyroXDps)}  y=${"%.1f".format(sensors.gyroYDps)}  z=${"%.1f".format(sensors.gyroZDps)} °/s")
                            }
                        }
                    }
                }
            }

            // Action buttons
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onLocateDevice(device.deviceId) },
                        colors = primaryButtonColors(),
                    ) { Text("Find Device") }
                    Button(
                        onClick = { onSetDeviceMonitoring(device.deviceId, !device.proximityMonitoringEnabled) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = trackerColors.surfaceVariant,
                            contentColor = trackerColors.onSurface,
                        ),
                    ) { Text(if (device.proximityMonitoringEnabled) "Pause Alerts" else "Resume Alerts") }
                    TextButton(onClick = { onRemoveDevice(device.deviceId) }) {
                        Text("Remove", color = trackerColors.error)
                    }
                }
            }
        }
    }
}

/** A cleaner data row for table-style device info display. */
@Composable
private fun DataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodySmall,
            color = trackerColors.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
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
            AlertCard(alert.type.prettyLabel(), alert.type) {
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
            AlertCard(alert.type.prettyLabel(), alert.type) {
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
            val accent = if (geofence.enabled) Color(0xFF6E9A80) else Color(0xFF5A6270) // sage-green when active, neutral-grey when disabled
            AccentStripCard(title = geofence.name, accent = accent) {
                val statusColor = if (geofence.enabled) Color(0xFF6E9A80) else Color(0xFFC27272)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, RoundedCornerShape(4.dp))
                    )
                    Text(
                        if (geofence.enabled) "Active" else "Disabled",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                KeyValueRow("Center", "%.4f, %.4f".format(geofence.center.lat, geofence.center.lng))
                KeyValueRow("Radius", "${geofence.radiusMeters} m")
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onDeleteGeofence(geofence.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33C27272),
                        contentColor = Color(0xFFC27272),
                    ),
                ) { Text("Delete Geofence") }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    state: MainUiState,
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
                    "Keep this phone available as a relay scanner. It uploads nearby BLE tracker sightings to the backend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = trackerColors.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Scanner", if (state.monitoringEnabled) "Running" else "Stopped", state.monitoringEnabled)
                    StatusPill("Boot", if (scannerAutostartEnabled) "Auto" else "Manual", scannerAutostartEnabled)
                }
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
            SectionCard("Sync Status") {
                val statusColor = when {
                    state.statusMessage.contains("failed", ignoreCase = true) ||
                    state.statusMessage.contains("error", ignoreCase = true) -> Color(0xFFC27272)
                    state.statusMessage.contains("expired", ignoreCase = true) -> Color(0xFFCC9B5A)
                    else -> trackerColors.secondary
                }
                Text(
                    state.statusMessage.ifBlank { "Waiting for backend sync" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        onSaveSettings()
                        onRefresh()
                    }, colors = primaryButtonColors()) { Text("Save & Refresh") }
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = trackerColors.surface.copy(alpha = 0.88f),
            contentColor = trackerColors.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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

/** Alert-specific card with a subtle coloured left-edge accent strip. */
@Composable
private fun AlertCard(
    title: String,
    alertType: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = alertAccentColor(alertType)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = trackerColors.surface.copy(alpha = 0.88f),
            contentColor = trackerColors.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Accent strip
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

/** Reusable accent-strip card used for geofence items, sensor rules, and sensor history. */
@Composable
private fun AccentStripCard(
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = trackerColors.surface.copy(alpha = 0.88f),
            contentColor = trackerColors.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Accent strip
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

/** Map sensor type identifiers to curated accent colours. */
private fun sensorRuleAccentColor(sensorType: String): Color = when {
    sensorType.contains("temperature", ignoreCase = true) -> Color(0xFFCC9B5A)  // warm amber
    sensorType.contains("humidity", ignoreCase = true)     -> Color(0xFF7A9EB5)  // muted steel
    sensorType.contains("lux", ignoreCase = true)          -> Color(0xFF8B7EB0)  // muted lavender
    sensorType.contains("vibration", ignoreCase = true)    -> Color(0xFFB07D5B)  // terracotta
    sensorType.contains("accel", ignoreCase = true)        -> Color(0xFFC27272)  // dusty rose
    sensorType.contains("gyro", ignoreCase = true)         -> Color(0xFF6E9A80)  // muted sage
    else                                                    -> Color(0xFF98A4B3)  // neutral
}

@Composable
private fun SectionHeader(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = trackerColors.onSurface,
        )
        HorizontalDivider(color = trackerColors.outline.copy(alpha = 0.2f))
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A2230),
            contentColor = trackerColors.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = trackerColors.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DeviceSummary(device: DeviceDto, relaySnapshot: RelaySnapshotDto?, onLocateDevice: (String) -> Unit) {
    val effectiveRelay = relaySnapshot?.takeIf {
        it.deviceId == device.deviceId && System.currentTimeMillis() - it.seenAtEpochMs <= RELAY_SNAPSHOT_FRESH_MS
    }
    val effectiveBagState = effectiveRelay?.bagState
    val effectiveSeenAt = effectiveRelay?.seenAtEpochMs?.toIsoString() ?: device.lastSeenAt
    val effectiveRssi = effectiveRelay?.rssi ?: device.lastRssi

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill("Bag", effectiveBagState?.toBagStateLabel() ?: device.lastPacket?.bagStateName?.prettyLabel() ?: "Unknown", effectiveBagState == 0 || device.lastPacket?.bagState == 0)
        StatusPill("Proximity", effectiveRssi.toProximityLabel(), effectiveRssi != null && effectiveRssi >= -75)
    }
    Spacer(Modifier.height(10.dp))
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
    
    Spacer(Modifier.height(12.dp))
    Button(onClick = { onLocateDevice(device.deviceId) }, colors = primaryButtonColors()) {
        Text("Precision Find Device")
    }
    
    device.lastPacket?.let { packet ->
        Spacer(Modifier.height(8.dp))
        Text("Latest Packet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        KeyValueRow("Packet Type", packet.packetTypeName.prettyLabel())
        KeyValueRow(
            "Bag State",
            effectiveBagState?.toBagStateLabel() ?: packet.bagStateName.prettyLabel()
        )
        KeyValueRow("Power", packet.batteryLevelName.prettyLabel())
        KeyValueRow("Sequence", (effectiveRelay?.seqNum ?: packet.seqNum).toString())
        KeyValueRow("Health Flags", packet.healthStatus.toString())
        KeyValueRow("Days Since Change", packet.daysSinceChange.toString())
        packet.sensors?.let { sensors ->
            Spacer(Modifier.height(8.dp))
            SensorTechnicalPanel(sensors)
        }
    }
}

@Composable
private fun SensorTechnicalPanel(sensors: SensorReadingsPayload) {
    Text("Sensor Telemetry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    NullableMetric("Temperature", sensors.temperatureC?.let { "%.2f °C".format(it) })
    NullableMetric("Humidity", sensors.humidityRh?.let { "%.2f %%".format(it) })
    NullableMetric("Light", sensors.lux?.let { "$it lux" })
    NullableMetric("Vibration Score", sensors.vibrationScore?.toString())
    if (sensors.accelXMg != null && sensors.accelYMg != null && sensors.accelZMg != null) {
        AxisRow("Acceleration", sensors.accelXMg, sensors.accelYMg, sensors.accelZMg, "mg")
    }
    if (sensors.gyroXDps != null && sensors.gyroYDps != null && sensors.gyroZDps != null) {
        AxisRow(
            "Gyroscope",
            "%.2f".format(sensors.gyroXDps),
            "%.2f".format(sensors.gyroYDps),
            "%.2f".format(sensors.gyroZDps),
            "deg/s",
        )
    }
}

@Composable
private fun SensorReadingSummary(reading: SensorReadingDto) {
    KeyValueRow("Device ID", reading.deviceId)
    TimestampRow("Seen At", reading.seenAt)
    KeyValueRow("Scanner", reading.scannerUserId ?: "-")
    KeyValueRow("RSSI", reading.rssi?.let { "$it dBm" } ?: "-")
    Spacer(Modifier.height(8.dp))
    SensorTechnicalPanel(reading.sensors)
}

@Composable
private fun NullableMetric(label: String, value: String?) {
    value?.let { KeyValueRow(label, it) }
}

@Composable
private fun AxisRow(label: String, x: Any, y: Any, z: Any, unit: String) {
    KeyValueRow(label, "x=$x, y=$y, z=$z $unit")
}

@Composable
private fun StatusPill(label: String, value: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (positive) trackerColors.primaryContainer.copy(alpha = 0.9f) else trackerColors.secondaryContainer.copy(alpha = 0.78f),
        contentColor = if (positive) trackerColors.onPrimaryContainer else trackerColors.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = trackerColors.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AlertSummary(alert: AlertDto) {
    val accent = alertAccentColor(alert.type)
    val statusColor = when (alert.status) {
        "open" -> accent
        "acknowledged" -> Color(0xFF7A9EB5)
        else -> Color(0xFF5A6270)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, RoundedCornerShape(4.dp))
        )
        Text(
            alert.status.prettyLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(4.dp))
    KeyValueRow("Device", alert.deviceId)
    TimestampRow("Created", alert.createdAt)
    Spacer(Modifier.height(8.dp))
    Text(alert.message, style = MaterialTheme.typography.bodyMedium, color = trackerColors.onSurfaceVariant)
}

@Composable
private fun AdminRegistrationSummary(registration: AdminRegistrationDto) {
    KeyValueRow("Tracker Device ID", registration.deviceId)
    KeyValueRow("Registration ID", registration.id)
    TimestampRow("Created", registration.createdAt)
    if (registration.note.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Admin Note", registration.note)
    }
    Spacer(Modifier.height(12.dp))
    Text("Owner Claim Code", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = trackerColors.primaryContainer.copy(alpha = 0.86f),
            contentColor = trackerColors.onPrimaryContainer,
        ),
    ) {
        Text(
            text = registration.manualCode,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Share this one-time code with the owner. They will use it together with the tracker device ID to claim the tracker.",
        style = MaterialTheme.typography.bodyMedium,
        color = trackerColors.onSurfaceVariant,
    )
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            value,
            modifier = Modifier
                .padding(start = 16.dp)
                .widthIn(max = 220.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = trackerColors.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
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
                        Color(0xFF0C1117),
                        Color(0xFF101820),
                        Color(0xFF131920),
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161D26)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE4E8ED),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF98A4B3),
            )
            if (status.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = trackerColors.secondary,
                    fontWeight = FontWeight.Medium,
                )
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
        shape = RoundedCornerShape(10.dp),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.6f),
            unfocusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.35f),
            focusedBorderColor = trackerColors.primary.copy(alpha = 0.7f),
            unfocusedBorderColor = trackerColors.outline.copy(alpha = 0.3f),
            focusedTextColor = trackerColors.onSurface,
            unfocusedTextColor = trackerColors.onSurface,
            focusedLabelColor = trackerColors.primary,
            unfocusedLabelColor = trackerColors.onSurfaceVariant.copy(alpha = 0.7f),
            cursorColor = trackerColors.primary,
        ),
    )
}

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = trackerColors.primary.copy(alpha = 0.85f),
    contentColor = Color(0xFF0C1117),
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

private fun String.toCompactTime(): String =
    runCatching {
        DISPLAY_TIME_FORMAT.format(Instant.parse(this).atZone(ZoneId.systemDefault()))
    }.getOrDefault(this)

private val DISPLAY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val DISPLAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a")

private val trackerColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8ABFB8),
    onPrimary = Color(0xFF0A1F1F),
    primaryContainer = Color(0xFF1A3338),
    onPrimaryContainer = Color(0xFFCDE8E3),
    secondary = Color(0xFFCBA879),
    onSecondary = Color(0xFF2A1E0C),
    secondaryContainer = Color(0xFF3A2E1A),
    onSecondaryContainer = Color(0xFFE8D6BC),
    background = Color(0xFF0C1117),
    onBackground = Color(0xFFE4E8ED),
    surface = Color(0xFF131920),
    onSurface = Color(0xFFE4E8ED),
    surfaceVariant = Color(0xFF1C232D),
    onSurfaceVariant = Color(0xFF98A4B3),
    outline = Color(0xFF465060),
    error = Color(0xFFCF6679),
    onError = Color(0xFF2D0A12),
)

// Subtle, muted accent colors per alert type
private fun alertAccentColor(type: String): Color = when {
    type.contains("BAG_OPENED", ignoreCase = true)        -> Color(0xFFCC9B5A)  // warm amber
    type.contains("BAG_CLOSED", ignoreCase = true)        -> Color(0xFF6E9A80)  // muted sage
    type.contains("PROXIMITY_LOST", ignoreCase = true)    -> Color(0xFFC27272)  // dusty rose
    type.contains("PROXIMITY_RESTORED", ignoreCase = true)-> Color(0xFF6E9A80)  // muted sage
    type.contains("GEOFENCE_EXIT", ignoreCase = true)     -> Color(0xFFB07D5B)  // terracotta
    type.contains("GEOFENCE_ENTRY", ignoreCase = true)    -> Color(0xFF6E9A80)  // muted sage
    type.contains("SENSOR_RULE", ignoreCase = true)       -> Color(0xFF8B7EB0)  // muted lavender
    type.contains("SELF_TEST", ignoreCase = true)         -> Color(0xFFCC9B5A)  // warm amber
    type.contains("STATE_CHANGE", ignoreCase = true)      -> Color(0xFF7A9EB5)  // muted steel
    else                                                   -> Color(0xFF98A4B3)  // neutral
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorsSection(
    state: MainUiState,
    onAddRule: (String, String, String, Double?, Double?) -> Unit,
    onRemoveRule: (String) -> Unit,
) {
    var ruleName by remember { mutableStateOf("") }
    var sensorType by remember { mutableStateOf("temperatureC") }
    var minVal by remember { mutableStateOf("") }
    var maxVal by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("temperatureC", "humidityRh", "lux", "vibrationScore", "accelXMg", "accelYMg", "accelZMg", "gyroXDps", "gyroYDps", "gyroZDps")

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard("Add Sensor Rule") {
                Text("Receive a global alert if a tracker's sensor reading falls outside your allowed range.", style = MaterialTheme.typography.bodyMedium, color = trackerColors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                AppTextField(value = ruleName, onValueChange = { ruleName = it }, label = "Rule Name")
                Spacer(Modifier.height(8.dp))
                var deviceExpanded by remember { mutableStateOf(false) }
                var deviceId by remember { mutableStateOf(state.devices.firstOrNull()?.deviceId ?: "") }
                if (state.devices.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = deviceExpanded,
                        onExpandedChange = { deviceExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Device") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.6f),
                                unfocusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.35f),
                                focusedBorderColor = trackerColors.primary.copy(alpha = 0.7f),
                                unfocusedBorderColor = trackerColors.outline.copy(alpha = 0.3f),
                                focusedTextColor = trackerColors.onSurface,
                                unfocusedTextColor = trackerColors.onSurface,
                                focusedLabelColor = trackerColors.primary,
                                unfocusedLabelColor = trackerColors.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = deviceExpanded,
                            onDismissRequest = { deviceExpanded = false },
                            containerColor = trackerColors.surfaceVariant,
                        ) {
                            state.devices.forEach { device ->
                                DropdownMenuItem(
                                    text = { Text(device.displayName, color = trackerColors.onSurface) },
                                    onClick = {
                                        deviceId = device.deviceId
                                        deviceExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = sensorType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sensor Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.6f),
                            unfocusedContainerColor = Color(0xFF1A2230).copy(alpha = 0.35f),
                            focusedBorderColor = trackerColors.primary.copy(alpha = 0.7f),
                            unfocusedBorderColor = trackerColors.outline.copy(alpha = 0.3f),
                            focusedTextColor = trackerColors.onSurface,
                            unfocusedTextColor = trackerColors.onSurface,
                            focusedLabelColor = trackerColors.primary,
                            unfocusedLabelColor = trackerColors.onSurfaceVariant.copy(alpha = 0.7f),
                            cursorColor = trackerColors.primary,
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        containerColor = trackerColors.surfaceVariant,
                    ) {
                        options.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption, color = trackerColors.onSurface) },
                                onClick = {
                                    sensorType = selectionOption
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        AppTextField(value = minVal, onValueChange = { minVal = it }, label = "Min Value")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        AppTextField(value = maxVal, onValueChange = { maxVal = it }, label = "Max Value")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    if (deviceId.isNotBlank()) {
                        onAddRule(deviceId, ruleName, sensorType, minVal.toDoubleOrNull(), maxVal.toDoubleOrNull())
                    }
                }, colors = primaryButtonColors()) { Text("Save Rule") }
            }
        }
        item { SectionHeader("Active Rules") }
        if (state.sensorRules.isEmpty()) {
            item { EmptyState("No sensor rules configured.") }
        }
        items(state.sensorRules, key = { it.id }) { rule ->
            val accent = sensorRuleAccentColor(rule.sensorType)
            AccentStripCard(title = rule.name, accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(accent, RoundedCornerShape(4.dp))
                    )
                    Text(
                        rule.sensorType.prettyLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                KeyValueRow("Device", rule.deviceId)
                KeyValueRow("Min Allowed", rule.minValue?.toString() ?: "–")
                KeyValueRow("Max Allowed", rule.maxValue?.toString() ?: "–")
                TimestampRow("Created", rule.createdAt)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onRemoveRule(rule.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33C27272),
                        contentColor = Color(0xFFC27272),
                    ),
                ) { Text("Delete Rule") }
            }
        }

        
        item { SectionHeader("Sensor History") }
        if (state.sensorReadings.isEmpty()) {
            item { EmptyState("No sensor history yet. Send fake packets to populate this timeline.") }
        }
        items(state.sensorReadings.take(25), key = { it.id }) { reading ->
            AccentStripCard(
                title = "${reading.deviceId} • ${reading.seenAt.toCompactTime()}",
                accent = Color(0xFF7A9EB5), // muted steel
            ) {
                SensorReadingSummary(reading)
            }
        }
    }
}

@Composable
private fun LocateSection(
    state: MainUiState,
    deviceId: String,
    onClose: () -> Unit
) {
    val device = state.devices.find { it.deviceId == deviceId } ?: return
    val relay = state.latestRelaySnapshot?.takeIf { it.deviceId == deviceId && System.currentTimeMillis() - it.seenAtEpochMs <= 4000L }
    val rssi = relay?.rssi
    
    val distanceRatio = when {
        rssi == null -> 0f
        rssi >= -50 -> 1f
        rssi <= -90 -> 0.1f
        else -> (rssi + 90) / 40f
    }
    
    val ringColor = when {
        rssi == null -> Color(0xFF465060)
        distanceRatio > 0.8f -> trackerColors.primary
        distanceRatio > 0.4f -> trackerColors.onSurfaceVariant
        else -> Color(0xFF465060)
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (rssi != null) 1.5f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween((1000 * (1.1f - distanceRatio)).toInt().coerceAtLeast(300), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Precision Finding", style = MaterialTheme.typography.headlineSmall, color = trackerColors.onSurface, fontWeight = FontWeight.SemiBold)
        Text(device.displayName, style = MaterialTheme.typography.bodyMedium, color = trackerColors.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))
        
        Box(
            modifier = Modifier.size(220.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = ringColor.copy(alpha = 0.15f),
                    radius = size.minDimension / 2 * pulseScale
                )
                drawCircle(
                    color = ringColor.copy(alpha = 0.6f),
                    radius = size.minDimension / 4
                )
            }
            Text(
                text = rssi?.let { "$it dBm" } ?: "Searching…",
                color = trackerColors.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(Modifier.height(32.dp))
        val proximityLabel = rssi.toProximityLabel()
        Text(proximityLabel, style = MaterialTheme.typography.titleMedium, color = ringColor)
        
        Spacer(Modifier.height(32.dp))
        Button(onClick = onClose, colors = primaryButtonColors()) { Text("Close") }
    }
}
