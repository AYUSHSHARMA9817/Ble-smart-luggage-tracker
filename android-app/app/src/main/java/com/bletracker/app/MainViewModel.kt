package com.bletracker.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bletracker.app.data.AdminRegistrationDto
import com.bletracker.app.data.AlertDto
import com.bletracker.app.data.BackendApi
import com.bletracker.app.data.DeviceDto
import com.bletracker.app.data.GeofenceDto
import com.bletracker.app.data.Prefs
import com.bletracker.app.data.RelaySnapshotDto
import com.bletracker.app.data.UserDto
import com.bletracker.app.data.alertTitle
import com.bletracker.app.scanner.BleScanService
import com.bletracker.app.scanner.NotificationFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val user: UserDto? = null,
    val backendBaseUrl: String = "",
    val scannerUserId: String = "",
    val ownerUserId: String = "",
    val authTokenPresent: Boolean = false,
    val adminMode: Boolean = false,
    val scannerAutostartEnabled: Boolean = false,
    val devices: List<DeviceDto> = emptyList(),
    val alerts: List<AlertDto> = emptyList(),
    val geofences: List<GeofenceDto> = emptyList(),
    val latestAdminRegistration: AdminRegistrationDto? = null,
    val latestRelaySnapshot: RelaySnapshotDto? = null,
    val serverTime: String = "",
    val lastBackendSyncAt: String = "",
    val monitoringEnabled: Boolean = false,
    val statusMessage: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val api = BackendApi(prefs)
    private var autoRefreshJob: Job? = null

    private val _uiState = MutableStateFlow(
        MainUiState(
            backendBaseUrl = prefs.backendBaseUrl,
            scannerUserId = prefs.scannerUserId,
            ownerUserId = prefs.ownerUserId,
            authTokenPresent = prefs.authToken.isNotBlank(),
            adminMode = prefs.adminRegistrationSecret.isNotBlank(),
            scannerAutostartEnabled = prefs.scannerAutostartEnabled,
            devices = prefs.cachedDevices,
            latestAdminRegistration = prefs.latestAdminRegistration,
            latestRelaySnapshot = prefs.latestRelaySnapshot,
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        if (prefs.authToken.isNotBlank()) {
            startAutoRefresh()
        }
    }

    fun saveSettings(
        backendBaseUrl: String,
        scannerAutostartEnabled: Boolean,
    ) {
        prefs.backendBaseUrl = backendBaseUrl
        prefs.scannerAutostartEnabled = scannerAutostartEnabled
        _uiState.update {
            it.copy(
                backendBaseUrl = prefs.backendBaseUrl,
                scannerUserId = prefs.scannerUserId,
                ownerUserId = prefs.ownerUserId,
                scannerAutostartEnabled = prefs.scannerAutostartEnabled,
                statusMessage = "Settings saved",
                latestRelaySnapshot = prefs.latestRelaySnapshot,
            )
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshOnce()
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Email and password are required") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val auth = api.signIn(email.trim(), password)
                onAuthSuccess(auth.user.id, auth.authToken, auth.user, "Signed in")
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Sign in failed") }
            }
        }
    }

    fun signUp(name: String, email: String, password: String) {
        if (name.isBlank() || email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Name, email, and password are required") }
            return
        }

        if (password.length < 6) {
            _uiState.update { it.copy(statusMessage = "Password must be at least 6 characters") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val auth = api.signUp(name.trim(), email.trim(), password)
                onAuthSuccess(auth.user.id, auth.authToken, auth.user, "Account ready")
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Sign up failed") }
            }
        }
    }

    fun signInAdmin(adminSecret: String) {
        if (adminSecret.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Admin secret is required") }
            return
        }

        prefs.adminRegistrationSecret = adminSecret
        _uiState.update {
            it.copy(
                adminMode = true,
                latestAdminRegistration = null,
                statusMessage = "Admin access enabled",
            )
        }
    }

    fun signOutAdmin() {
        prefs.adminRegistrationSecret = ""
        _uiState.update {
            it.copy(
                adminMode = false,
                latestAdminRegistration = null,
                statusMessage = "Admin access closed",
            )
        }
    }

    fun registerTrackerAsAdmin(deviceId: String, note: String) {
        val adminSecret = prefs.adminRegistrationSecret
        if (adminSecret.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Log in as admin first") }
            return
        }
        if (deviceId.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Tracker device ID is required") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.createAdminRegistration(
                    adminSecret = adminSecret,
                    deviceId = deviceId.trim(),
                    manualCode = "",
                    note = note.trim(),
                )
            }.onSuccess { registration ->
                prefs.latestAdminRegistration = registration
                _uiState.update {
                    it.copy(
                        latestAdminRegistration = registration,
                        statusMessage = "Tracker registration created",
                    )
                }
            }.onFailure { error ->
                prefs.latestAdminRegistration = null
                _uiState.update {
                    it.copy(
                        latestAdminRegistration = null,
                        statusMessage = error.message ?: "Admin tracker registration failed",
                    )
                }
            }
        }
    }

    fun registerDevice(deviceId: String, displayName: String, manualCode: String) {
        if (prefs.authToken.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Sign in first") }
            return
        }

        if (deviceId.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Tracker device ID is required") }
            return
        }

        if (displayName.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Display name is required") }
            return
        }

        if (manualCode.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Manual one-time code is required") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.registerDevice(deviceId, displayName, manualCode)
                refresh()
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Registration failed") }
            }
        }
    }

    fun addGeofence(name: String, lat: String, lng: String, radiusMeters: String) {
        if (prefs.authToken.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Sign in first") }
            return
        }

        if (name.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Geofence name is required") }
            return
        }

        val latitude = lat.toDoubleOrNull()
        if (latitude == null || latitude < -90.0 || latitude > 90.0) {
            _uiState.update { it.copy(statusMessage = "Latitude must be a valid number between -90 and 90") }
            return
        }

        val longitude = lng.toDoubleOrNull()
        if (longitude == null || longitude < -180.0 || longitude > 180.0) {
            _uiState.update { it.copy(statusMessage = "Longitude must be a valid number between -180 and 180") }
            return
        }

        val radius = radiusMeters.toIntOrNull()
        if (radius == null || radius <= 0) {
            _uiState.update { it.copy(statusMessage = "Radius must be a positive whole number") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.createGeofence(name, latitude, longitude, radius)
                refresh()
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Geofence failed") }
            }
        }
    }

    fun deleteGeofence(geofenceId: String) {
        if (prefs.authToken.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Sign in first") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.deleteGeofence(geofenceId)
                refreshOnce()
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Delete geofence failed") }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        if (prefs.authToken.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Sign in first") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.removeDevice(deviceId)
                refreshOnce()
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Remove device failed") }
            }
        }
    }

    fun setDeviceMonitoring(deviceId: String, enabled: Boolean) {
        if (prefs.authToken.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Sign in first") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.setDeviceMonitoring(deviceId, enabled)
                refreshOnce()
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Device monitoring update failed") }
            }
        }
    }

    fun acknowledgeAlert(alertId: String) {
        if (prefs.authToken.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Sign in first") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.acknowledgeAlert(alertId)
                refreshOnce()
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "Acknowledge alert failed") }
            }
        }
    }

    fun startMonitoring() {
        val context = getApplication<Application>()
        if (!hasMonitoringPermissions(context)) {
            _uiState.update {
                it.copy(
                    monitoringEnabled = false,
                    statusMessage = "Grant Bluetooth and location permissions first",
                )
            }
            return
        }
        val intent = Intent(context, BleScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
        _uiState.update { it.copy(monitoringEnabled = true, statusMessage = "Background monitoring enabled") }
    }

    fun stopMonitoring() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, BleScanService::class.java))
        _uiState.update { it.copy(monitoringEnabled = false, statusMessage = "Background monitoring stopped") }
    }

    fun signOut() {
        prefs.authToken = ""
        prefs.ownerUserId = ""
        prefs.notifiedAlertIds = emptySet()
        prefs.notifiedOpenAlertKeys = emptySet()
        prefs.cachedDevices = emptyList()
        prefs.latestAdminRegistration = null
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        _uiState.update {
            it.copy(
                ownerUserId = "",
                user = null,
                authTokenPresent = false,
                adminMode = false,
                devices = emptyList(),
                alerts = emptyList(),
                geofences = emptyList(),
                latestAdminRegistration = null,
                statusMessage = "Signed out",
            )
        }
    }

    fun setStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun startAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshOnce()
                delay(1_000)
            }
        }
    }

    private suspend fun refreshOnce() {
        runCatching {
            if (prefs.authToken.isBlank()) {
                _uiState.update { it.copy(statusMessage = "Signed out") }
                return
            }
            val bootstrap = api.fetchBootstrap()
            notifyNewOpenAlerts(bootstrap.alerts)
            prefs.ownerUserId = bootstrap.user.id
            prefs.cachedDevices = bootstrap.devices
            _uiState.update {
                it.copy(
                    ownerUserId = bootstrap.user.id,
                    user = bootstrap.user,
                    devices = bootstrap.devices,
                    alerts = bootstrap.alerts,
                    geofences = bootstrap.geofences,
                    latestAdminRegistration = it.latestAdminRegistration,
                    latestRelaySnapshot = prefs.latestRelaySnapshot,
                    serverTime = bootstrap.serverTime,
                    lastBackendSyncAt = bootstrap.serverTime,
                    authTokenPresent = true,
                    statusMessage = "Synced with backend",
                )
            }
        }.onFailure { error ->
            if (error.isAuthFailure()) {
                prefs.authToken = ""
                prefs.ownerUserId = ""
                prefs.cachedDevices = emptyList()
                autoRefreshJob?.cancel()
                autoRefreshJob = null
                _uiState.update {
                    it.copy(
                        ownerUserId = "",
                        user = null,
                        authTokenPresent = false,
                        adminMode = false,
                        devices = emptyList(),
                        alerts = emptyList(),
                        geofences = emptyList(),
                        latestAdminRegistration = null,
                        latestRelaySnapshot = prefs.latestRelaySnapshot,
                        statusMessage = "Session expired. Please log in again.",
                    )
                }
                return@onFailure
            }
            _uiState.update {
                it.copy(
                    latestRelaySnapshot = prefs.latestRelaySnapshot,
                    statusMessage = error.message ?: "Refresh failed",
                )
            }
        }
    }

    private fun onAuthSuccess(
        ownerUserId: String,
        authToken: String,
        user: UserDto,
        statusMessage: String,
    ) {
        prefs.ownerUserId = ownerUserId
        prefs.authToken = authToken
        prefs.notifiedAlertIds = emptySet()
        prefs.notifiedOpenAlertKeys = emptySet()
        _uiState.update {
            it.copy(
                ownerUserId = ownerUserId,
                user = user,
                authTokenPresent = true,
                adminMode = false,
                latestAdminRegistration = null,
                statusMessage = statusMessage,
            )
        }
        if (_uiState.value.monitoringEnabled) {
            startMonitoring()
        }
        startAutoRefresh()
        refresh()
    }

    private fun notifyNewOpenAlerts(alerts: List<AlertDto>) {
        val notified = prefs.notifiedAlertIds.toMutableSet()
        var changed = false
        val context = getApplication<Application>()

        for (alert in alerts) {
            if (alert.status != "open" || notified.contains(alert.id)) {
                continue
            }
            NotificationFactory.notifyAlert(
                context = context,
                alertId = alert.id,
                title = alertTitle(alert.type),
                message = alert.message,
            )
            notified.add(alert.id)
            changed = true
        }

        if (changed) {
            prefs.notifiedAlertIds = notified.toList().takeLast(100).toSet()
        }
    }

    private fun hasMonitoringPermissions(context: Application): Boolean {
        val required = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        return required.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun Throwable.isAuthFailure(): Boolean {
        val message = message.orEmpty()
        return message.contains("HTTP 401") ||
            message.contains("invalid session", ignoreCase = true) ||
            message.contains("missing bearer token", ignoreCase = true) ||
            message.contains("session expired", ignoreCase = true)
    }
}
