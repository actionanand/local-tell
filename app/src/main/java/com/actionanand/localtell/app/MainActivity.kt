package com.actionanand.localtell.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionanand.localtell.app.data.InstalledPack
import com.actionanand.localtell.app.data.RemotePack
import com.actionanand.localtell.app.journey.JourneyForegroundService
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.model.SubscriptionCells
import com.actionanand.localtell.app.survey.TowerSurveyScreen
import com.actionanand.localtell.app.ui.theme.LocalTellTheme
import com.actionanand.localtell.app.ui.theme.ThemeMode
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var locationEnablement: LocationEnablement

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        locationEnablement = LocationEnablement(this)
        setContent {
            val preferences = remember { getSharedPreferences("localtell_preferences", Context.MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(ThemeMode.fromPreference(preferences.getString("theme_mode", null))) }
            LocalTellTheme(themeMode) {
                LocalTellApp(themeMode) { mode ->
                    themeMode = mode
                    preferences.edit().putString("theme_mode", mode.name).apply()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::locationEnablement.isInitialized) locationEnablement.onResume()
    }

    fun isLocationEnabled(): Boolean = locationEnablement.isEnabled()

    fun requestLocationEnable(onEnabled: () -> Unit) = locationEnablement.requestEnable(onEnabled)
}

private enum class Tab { HOME, PACKS, JOURNEY, SURVEY }

@Composable
private fun LocalTellApp(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == Tab.HOME, { tab = Tab.HOME }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(tab == Tab.PACKS, { tab = Tab.PACKS }, { Icon(Icons.Default.Download, null) }, label = { Text("Offline data") })
            NavigationBarItem(tab == Tab.JOURNEY, { tab = Tab.JOURNEY }, { Icon(Icons.Default.Route, null) }, label = { Text("Journey") })
            if (BuildConfig.ENABLE_TOWER_SURVEY) {
                NavigationBarItem(tab == Tab.SURVEY, { tab = Tab.SURVEY }, { Icon(Icons.Default.Route, null) }, label = { Text("Tower Survey") })
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(themeMode, onThemeModeChange)
                Tab.PACKS -> PacksScreen()
                Tab.JOURNEY -> JourneyScreen()
                Tab.SURVEY -> if (BuildConfig.ENABLE_TOWER_SURVEY) TowerSurveyScreen() else HomeScreen(themeMode, onThemeModeChange)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val status by vm.status.collectAsStateWithLifecycle()
    var permissionGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var phoneStateGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_PHONE_STATE)) }
    var locationEnabled by remember { mutableStateOf(activity?.isLocationEnabled() == true) }
    var selectedSubscriptionId by remember { mutableStateOf<Int?>(null) }
    val requestLocation: () -> Unit = {
        if (activity != null) {
            activity.requestLocationEnable {
                locationEnabled = activity.isLocationEnabled()
                if (locationEnabled) vm.refresh()
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (permissionGranted) {
            locationEnabled = activity?.isLocationEnabled() == true
            if (locationEnabled) vm.refresh() else requestLocation()
        }
    }
    val phoneStateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phoneStateGranted = granted
        vm.refresh()
    }
    androidx.compose.runtime.LaunchedEffect(permissionGranted, locationEnabled) {
        if (permissionGranted && locationEnabled && status is HomeStatus.Idle) vm.refresh()
    }

    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            BrandHeader(themeMode, onThemeModeChange)
        }
        if (!permissionGranted) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard("Permission needed", "LocalTell uses an on-device GNSS fix when needed to determine your locality. Coordinates are processed locally and are not uploaded.")
                Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text("Allow cell access") }
            }
        } else if (!locationEnabled) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard("Android Location setting is off", "Android requires the Location switch for cellular identity and an on-device GNSS fix when locality needs refreshing.")
                PermissionToggle("Enable Location", requestLocation)
            }
        }
        if (permissionGranted && !phoneStateGranted) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard("SIM details optional", "Cell ID lookup works without this permission. Enable SIM details to show SIM slots and carrier names.")
                PermissionToggle("Enable SIM details") { phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE) }
            }
        }
        item {
            when (val current = status) {
                HomeStatus.Idle -> Text("Ready")
                is HomeStatus.Loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(localityProgressText(current.state), style = MaterialTheme.typography.bodySmall)
                }
                is HomeStatus.Error -> InfoCard("Unable to resolve", current.message)
                is HomeStatus.Ready -> HomeResults(current, selectedSubscriptionId) { selectedSubscriptionId = it }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(enabled = permissionGranted, onClick = { if (locationEnabled) vm.refresh() else requestLocation() }) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.padding(3.dp)); Text("Refresh locality")
                }
            }
        }
        item { InfoCard("Offline by design", "LocalTell resolves an on-device GNSS fix using downloaded geographic data. Coordinates stay on this device; internet is used only for optional pack downloads and updates.") }
    }
}

@Composable
private fun HomeResults(status: HomeStatus.Ready, selectedSubscriptionId: Int?, onSelect: (Int?) -> Unit) {
    val context = LocalContext.current
    val selected = status.subscriptions.filter { selectedSubscriptionId == null || it.subscription?.subscriptionId == selectedSubscriptionId }
    val displayedCells = selected.flatMap(SubscriptionCells::cells)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = "Current locality",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text("Current locality", style = MaterialTheme.typography.labelLarge)
                }
                Text(status.locality?.localityName ?: localityEmptyTitle(status.localityState, displayedCells.any(RadioCell::registered)), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                status.locality?.let { match ->
                    listOfNotNull(match.subDistrict, match.district, match.state).distinct().takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", ")) }
                    Text("Offline pack ${match.packId} · ${match.sourceQuality.replace('-', ' ')}", style = MaterialTheme.typography.bodySmall)
                    if (status.localityState == LocalityState.USING_RECENT_OFFLINE_LOCALITY) Text("Recent offline locality", style = MaterialTheme.typography.bodySmall)
                }
                if (status.localityState == LocalityState.NO_GEOGRAPHIC_PACK) {
                    Text("Download a geographic locality pack to resolve your current locality offline.", style = MaterialTheme.typography.bodySmall)
                    status.legacyMatch?.let { Text("Legacy cell-pack estimate: ${it.areaName}", style = MaterialTheme.typography.bodySmall) }
                }
                localityStateMessage(status.localityState)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (status.subscriptions.any { it.subscription != null }) {
            Text("Cellular diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedSubscriptionId == null, { onSelect(null) }, { Text("All SIMs") })
                status.subscriptions.mapNotNull(SubscriptionCells::subscription).forEach { subscription ->
                    FilterChip(selectedSubscriptionId == subscription.subscriptionId, { onSelect(subscription.subscriptionId) }, { Text("SIM ${subscription.simSlotIndex + 1} · ${subscription.carrierName}") })
                }
            }
            Text("This selector only filters LocalTell diagnostics; it never changes Android's mobile-data SIM.", style = MaterialTheme.typography.bodySmall)
        }
        selected.forEach { group ->
            val heading = group.subscription?.let { "SIM ${it.simSlotIndex + 1} · ${it.carrierName}" } ?: "Serving cell"
            if (group.cells.isEmpty()) InfoCard(heading, "No cellular identity available")
            group.cells.forEach { cell -> CellCard(heading, cell) }
        }
        status.locality?.let { match ->
            OutlinedButton(onClick = {
                val text = "My locality is ${match.localityName}${match.district?.let { ", $it" } ?: ""}. (LocalTell offline locality)"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Share locality"))
            }) { Icon(Icons.Default.Share, null); Spacer(Modifier.padding(3.dp)); Text("Share") }
        }
    }
}

private fun localityProgressText(state: LocalityState): String = when (state) {
    LocalityState.READING_CELLULAR -> "Reading cellular diagnostics"
    LocalityState.ACQUIRING_GNSS -> "Acquiring a short on-device GNSS fix"
    LocalityState.RESOLVING_OFFLINE_LOCALITY -> "Resolving locality from offline geographic data"
    else -> "Working offline"
}

private fun localityEmptyTitle(state: LocalityState, hasServingCell: Boolean): String = when (state) {
    LocalityState.NO_GEOGRAPHIC_PACK -> "Geographic pack needed"
    LocalityState.GNSS_TIMEOUT -> "GNSS fix timed out"
    LocalityState.GPS_DISABLED -> "GPS is off"
    LocalityState.PERMISSION_MISSING -> "Permission needed"
    LocalityState.NO_LOCALITY_MATCH -> "No locality match"
    else -> if (hasServingCell) "Locality unavailable" else "No serving cell"
}

private fun localityStateMessage(state: LocalityState): String? = when (state) {
    LocalityState.GNSS_TIMEOUT -> "Try again outdoors or where the sky is more visible."
    LocalityState.GPS_DISABLED -> "Enable GPS to acquire a one-shot locality fix."
    LocalityState.PERMISSION_MISSING -> "Fine location permission is required for the on-device GNSS fix."
    LocalityState.NO_LOCALITY_MATCH -> "This geographic pack has no matching locality for the current coordinate."
    else -> null
}

@Composable
private fun BrandHeader(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val nextThemeMode = when (themeMode) {
        ThemeMode.SYSTEM -> ThemeMode.LIGHT
        ThemeMode.LIGHT -> ThemeMode.DARK
        ThemeMode.DARK -> ThemeMode.SYSTEM
    }
    val themeIcon = when (themeMode) {
        ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Default.LightMode
        ThemeMode.DARK -> Icons.Default.DarkMode
    }
    val themeDescription = when (themeMode) {
        ThemeMode.SYSTEM -> "Theme: Automatic"
        ThemeMode.LIGHT -> "Theme: Light"
        ThemeMode.DARK -> "Theme: Dark"
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.localtell_brand_icon),
            contentDescription = "LocalTell",
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Fit,
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("LocalTell", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Know where you are", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = { onThemeModeChange(nextThemeMode) }) {
            Icon(themeIcon, themeDescription, tint = MaterialTheme.colorScheme.primary)
        }
    }

    Spacer(Modifier.height(14.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(
                "See your locality offline using cellular changes and on-device GNSS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
            )
        }
    }
}

@Composable
private fun PermissionToggle(label: String, onEnable: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Switch(checked = false, onCheckedChange = { enabled -> if (enabled) onEnable() })
    }
}

@Composable
private fun CellCard(heading: String, cell: RadioCell) {
    val cellIdentityLabel = if (cell.radio == "NR") "NCI" else "Cell"
    val areaLabel = if (cell.radio == "NR") "TAC" else "TAC/LAC"
    val channelLabel = if (cell.radio == "NR") "NRARFCN" else "EARFCN"
    val radioMeasurements = buildList {
        cell.pci?.let { add("PCI $it") }
        cell.channelNumber?.let { add("$channelLabel $it") }
        if (cell.bands.isNotEmpty()) {
            add(cell.bands.joinToString(" · ") { band -> "Band ${if (cell.radio == "NR") "n$band" else band}" })
        }
    }
    val signalMeasurements = buildList {
        cell.rsrp?.let { add("${if (cell.radio == "NR") "SS-RSRP" else "RSRP"} $it dBm") }
        cell.rsrq?.let { add("${if (cell.radio == "NR") "SS-RSRQ" else "RSRQ"} $it dB") }
        cell.sinr?.let { add("${if (cell.radio == "NR") "SS-SINR" else "RSSNR"} $it dB") }
    }
    val details = buildList {
        add("${cell.radio} · ${if (cell.registered) "Registered" else "Available"}")
        add("MCC ${cell.mcc} · MNC ${cell.mnc} · PLMN ${cell.plmn}")
        add("$areaLabel ${cell.areaCode ?: "—"} · $cellIdentityLabel ${cell.cellId}")
        if (radioMeasurements.isNotEmpty()) add(radioMeasurements.joinToString(" · "))
        if (signalMeasurements.isNotEmpty()) add(signalMeasurements.joinToString(" · "))
        else cell.dbm?.let { add("Signal $it dBm") }
        cell.timingAdvance?.let { add("Timing advance $it") }
    }.joinToString("\n")
    InfoCard(heading, details)
}

@Composable
private fun PacksScreen(vm: PacksViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val remoteIds = state.remote.mapTo(mutableSetOf()) { it.id }
    val installedOnly = state.installed.values.filter { it.id !in remoteIds }.sortedBy { it.name }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Offline data", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Download only the states you need. Entire-India can be published as another pack later.")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.padding(3.dp)); Text("Refresh list") }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(state.remote, key = RemotePack::id) { pack -> PackItem(pack, state.installed[pack.id]?.version, state.progress[pack.id], vm) }
        items(installedOnly, key = { it.id }) { pack -> InstalledPackItem(pack, vm) }
        state.error?.let { error -> item { InfoCard("Offline data", error) } }
        if (!state.loading && state.remote.isEmpty() && state.installed.isEmpty() && state.error == null) {
            item { InfoCard("No offline data packs", "No offline data packs are currently available. Try refreshing the list later.") }
        }
    }
}

@Composable
private fun PackItem(pack: RemotePack, installedVersion: Long?, progress: Int?, vm: PacksViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(pack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Version ${pack.version}" + (pack.compressedBytes?.let { " · ${formatBytes(it)} download" } ?: ""))
            when {
                progress != null -> { LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("Downloading $progress%") }
                installedVersion == null -> Button(onClick = { vm.download(pack) }) { Text("Download") }
                installedVersion < pack.version -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.download(pack) }) { Text("Update") }; OutlinedButton(onClick = { vm.remove(pack.id) }) { Text("Remove") } }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Installed", fontWeight = FontWeight.SemiBold); OutlinedButton(onClick = { vm.remove(pack.id) }) { Text("Remove") } }
            }
        }
    }
}

@Composable
private fun InstalledPackItem(pack: InstalledPack, vm: PacksViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(pack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Version ${pack.version} · Installed offline")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Installed", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { vm.remove(pack.id) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun JourneyScreen(vm: JourneyViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val points by vm.points.collectAsStateWithLifecycle()
    val hasFineLocation = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    var locationEnabled by remember { mutableStateOf(activity?.isLocationEnabled() == true) }
    var hasNotifications by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) }
    val startService = { ContextCompat.startForegroundService(context, Intent(context, JourneyForegroundService::class.java).setAction(JourneyForegroundService.ACTION_START)) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startService() }
    val beginJourney = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifications) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startService()
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Journey", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Records an entry only when the resolved locality changes.")
            if (!hasFineLocation) { Spacer(Modifier.height(8.dp)); InfoCard("Cell permission required", "Allow cell access on the Home tab before starting Journey mode.") }
            else if (!locationEnabled) { Spacer(Modifier.height(8.dp)); InfoCard("Location setting required", "Android requires the Location switch for cellular identity and on-device GNSS locality fixes.") }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = hasFineLocation, onClick = {
                    if (locationEnabled) beginJourney() else activity?.requestLocationEnable { locationEnabled = activity.isLocationEnabled(); if (locationEnabled) beginJourney() }
                }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.padding(2.dp)); Text("Start") }
                OutlinedButton(onClick = { context.startService(Intent(context, JourneyForegroundService::class.java).setAction(JourneyForegroundService.ACTION_STOP)); vm.refresh() }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.padding(2.dp)); Text("Stop") }
                TextButton(onClick = vm::clear) { Text("Clear") }
            }
        }
        if (points.isEmpty()) item { InfoCard("No journey entries", "Start Journey after installing an offline data pack. Cell checks run about every 20 seconds while the foreground service is active.") }
        items(points, key = JourneyPoint::id) { point -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(point.areaName, fontWeight = FontWeight.Bold); Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(point.timestamp))); Text("${point.radio} · ${point.plmn} · confidence ${point.confidence}%", style = MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable
private fun InfoCard(title: String, body: String) { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(body) } } }

private fun hasPermission(context: android.content.Context, permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
private fun formatBytes(bytes: Long): String = when { bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)); bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024)); else -> "%.1f KB".format(bytes / 1024.0) }
