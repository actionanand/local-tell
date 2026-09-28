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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
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
import com.actionanand.localtell.app.data.IndiaRegion
import com.actionanand.localtell.app.data.PackCatalog
import com.actionanand.localtell.app.data.RegionPacks
import com.actionanand.localtell.app.data.RemotePack
import com.actionanand.localtell.app.data.formatPackBytes
import com.actionanand.localtell.app.journey.JourneyForegroundService
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.journey.JourneyTrackingMode
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
    var pendingRemoval by remember { mutableStateOf<PackRemoval?>(null) }
    var expandedRegions by remember { mutableStateOf(emptySet<IndiaRegion>()) }
    val remoteIds = state.remote.mapTo(mutableSetOf()) { it.id }
    val installedOnly = state.installed.values.filter { it.id !in remoteIds }.sortedBy { it.name }
    val regions = PackCatalog.regions(state.remote)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Offline data", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Download offline locality data for all India, a region, or individual State/UT.")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = state.batch == null, onClick = vm::refresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.padding(3.dp)); Text("Refresh list") }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.remote.isNotEmpty()) {
            item { IndiaDownloadCard(regions, state, vm) }
        }
        regions.forEach { regionPacks ->
            val expanded = regionPacks.region in expandedRegions
            item(key = "region-${regionPacks.region.manifestKey}") {
                RegionDownloadCard(
                    regionPacks = regionPacks,
                    state = state,
                    expanded = expanded,
                    onToggle = {
                        expandedRegions = if (expanded) expandedRegions - regionPacks.region else expandedRegions + regionPacks.region
                    },
                    onDownload = { vm.downloadRegion(regionPacks.packs, regionPacks.region.displayName) },
                )
            }
            if (expanded) {
                items(regionPacks.packs, key = RemotePack::id) { pack ->
                    StatePackRow(
                        pack = pack,
                        installedVersion = state.installed[pack.id]?.version,
                        progress = state.progress[pack.id],
                        batchActive = state.batch != null,
                        onDownload = { vm.download(pack) },
                        onRemoveRequested = { pendingRemoval = PackRemoval(pack.id, pack.name) },
                    )
                }
            }
        }
        items(installedOnly, key = { it.id }) { pack ->
            InstalledOnlyPackRow(pack, state.batch != null) { pendingRemoval = PackRemoval(pack.id, pack.name) }
        }
        state.error?.let { error -> item { InfoCard("Offline data", error) } }
        if (!state.loading && state.remote.isEmpty() && state.installed.isEmpty() && state.error == null) {
            item { InfoCard("No offline data packs", "No offline data packs are currently available. Try refreshing the list later.") }
        }
    }
    pendingRemoval?.let { pack ->
        ConfirmationDialog(
            title = "Remove offline data?",
            message = "Remove the downloaded offline data for ${pack.name}? You will need to download it again to use locality lookup offline.",
            confirmLabel = "Remove",
            onDismiss = { pendingRemoval = null },
            onConfirm = { vm.remove(pack.id); pendingRemoval = null },
        )
    }
}

@Composable
private fun IndiaDownloadCard(regions: List<RegionPacks>, state: PackUiState, vm: PacksViewModel) {
    val packs = regions.flatMap(RegionPacks::packs)
    val required = PackCatalog.requiredPacks(packs, state.installed)
    val totals = PackCatalog.totals(packs)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("India", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${packs.size} State/UT packs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(packSizeSummary(totals), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BatchStatus(state.batch, "India")
            if (required.isEmpty()) {
                Text("All available packs are installed", fontWeight = FontWeight.SemiBold)
            } else {
                Button(enabled = state.batch == null, onClick = vm::downloadAll) { Text(batchActionLabel(packs, state.installed, "Download all")) }
            }
        }
    }
}

@Composable
private fun RegionDownloadCard(regionPacks: RegionPacks, state: PackUiState, expanded: Boolean, onToggle: () -> Unit, onDownload: () -> Unit) {
    val required = PackCatalog.requiredPacks(regionPacks.packs, state.installed)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(regionPacks.region.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${regionPacks.packs.size} State/UT packs · ${packSizeSummary(regionPacks.totals)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse ${regionPacks.region.displayName}" else "Expand ${regionPacks.region.displayName}")
                }
            }
            BatchStatus(state.batch, regionPacks.region.displayName)
            if (required.isEmpty()) {
                Text("Installed", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            } else {
                OutlinedButton(enabled = state.batch == null, onClick = onDownload) { Text(batchActionLabel(regionPacks.packs, state.installed, "Download region")) }
            }
        }
    }
}

@Composable
private fun BatchStatus(batch: BatchDownloadProgress?, label: String) {
    if (batch?.label == label) {
        LinearProgressIndicator(progress = { batch.completed.toFloat() / batch.total }, modifier = Modifier.fillMaxWidth())
        Text("Downloading ${batch.completed + 1} of ${batch.total}: ${batch.currentPackName}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatePackRow(pack: RemotePack, installedVersion: Long?, progress: Int?, batchActive: Boolean, onDownload: () -> Unit, onRemoveRequested: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${pack.name} · ${formatPackBytes(pack.compressedBytes) ?: "Size unavailable"} (V${pack.version})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                PackAction(pack.version, installedVersion, progress, batchActive, onDownload, onRemoveRequested)
                Text(formatPackBytes(pack.uncompressedBytes)?.let { "$it on device" } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PackAction(remoteVersion: Long, installedVersion: Long?, progress: Int?, batchActive: Boolean, onDownload: () -> Unit, onRemoveRequested: () -> Unit) {
    when {
        progress != null -> {
            Column {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(0.45f))
                Text("Downloading $progress%", style = MaterialTheme.typography.bodySmall)
            }
        }
        installedVersion == null -> Button(enabled = !batchActive, onClick = onDownload) { Text("Download") }
        installedVersion < remoteVersion -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !batchActive, onClick = onDownload) { Text("Update") }
            OutlinedButton(enabled = !batchActive, onClick = onRemoveRequested) { Text("Remove") }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Installed", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            OutlinedButton(enabled = !batchActive, onClick = onRemoveRequested) { Text("Remove") }
        }
    }
}

@Composable
private fun InstalledOnlyPackRow(pack: InstalledPack, batchActive: Boolean, onRemoveRequested: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("V${pack.version} · Installed offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(enabled = !batchActive, onClick = onRemoveRequested) { Text("Remove") }
        }
    }
}

private fun packSizeSummary(totals: com.actionanand.localtell.app.data.PackTotals): String = listOfNotNull(
    formatPackBytes(totals.compressedBytes)?.let { "$it download" },
    formatPackBytes(totals.uncompressedBytes)?.let { "$it on device" },
).joinToString(" · ")

private fun batchActionLabel(packs: Collection<RemotePack>, installed: Map<String, InstalledPack>, default: String): String {
    val required = PackCatalog.requiredPacks(packs, installed)
    return when {
        required.size == packs.size -> default
        required.all { installed[it.id] != null } -> "Update all"
        else -> "Download remaining"
    }
}

@Composable
private fun JourneyScreen(vm: JourneyViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val points by vm.points.collectAsStateWithLifecycle()
    val tracking by vm.tracking.collectAsStateWithLifecycle()
    val hasFineLocation = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    var locationEnabled by remember { mutableStateOf(activity?.isLocationEnabled() == true) }
    var hasNotifications by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) }
    var confirmClear by remember { mutableStateOf(false) }
    val trackingIsRunning = tracking.mode in setOf(
        JourneyTrackingMode.STARTING,
        JourneyTrackingMode.ACQUIRING_LOCALITY,
        JourneyTrackingMode.ACTIVE,
        JourneyTrackingMode.WAITING_FOR_LOCALITY,
        JourneyTrackingMode.GPS_DISABLED,
    )
    val startService = {
        vm.markStarting()
        ContextCompat.startForegroundService(context, Intent(context, JourneyForegroundService::class.java).setAction(JourneyForegroundService.ACTION_START))
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotifications = granted
        if (granted) startService()
    }
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
            if (tracking.mode != JourneyTrackingMode.STOPPED) {
                Spacer(Modifier.height(8.dp))
                TrackingStatusCard(tracking.mode, tracking.localityName, tracking.lastCheckedAt, tracking.detail)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = hasFineLocation && !trackingIsRunning, onClick = {
                    if (locationEnabled) beginJourney() else activity?.requestLocationEnable { locationEnabled = activity.isLocationEnabled(); if (locationEnabled) beginJourney() }
                }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.padding(2.dp)); Text("Start") }
                OutlinedButton(enabled = trackingIsRunning, onClick = {
                    vm.markStopped()
                    context.startService(Intent(context, JourneyForegroundService::class.java).setAction(JourneyForegroundService.ACTION_STOP))
                }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.padding(2.dp)); Text("Stop") }
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (points.isEmpty()) item { InfoCard("No journey entries", "Start Journey after installing an offline data pack. Locality checks run about every 20 seconds while tracking is active.") }
        items(points, key = JourneyPoint::id) { point ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(point.areaName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    listOfNotNull(point.district, point.state).distinct().takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), style = MaterialTheme.typography.bodyMedium) }
                    Text(formatJourneyTimestamp(point.timestamp), style = MaterialTheme.typography.bodySmall)
                    Text("${point.radio} · ${point.plmn}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmClear) ConfirmationDialog(
        title = "Clear journey history?",
        message = "This will permanently remove all saved journey entries.",
        confirmLabel = "Clear",
        onDismiss = { confirmClear = false },
        onConfirm = { vm.clear(); confirmClear = false },
    )
}

@Composable
private fun TrackingStatusCard(mode: JourneyTrackingMode, localityName: String?, lastCheckedAt: Long?, detail: String?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (mode) {
                    JourneyTrackingMode.STARTING -> "Starting…"
                    JourneyTrackingMode.ACQUIRING_LOCALITY -> "Acquiring locality…"
                    JourneyTrackingMode.ACTIVE -> "Tracking active"
                    JourneyTrackingMode.WAITING_FOR_LOCALITY -> "Waiting for locality…"
                    JourneyTrackingMode.GPS_DISABLED -> "Location disabled"
                    JourneyTrackingMode.PERMISSION_REQUIRED -> "Permission required"
                    JourneyTrackingMode.STOPPED -> "Journey stopped"
                },
                fontWeight = FontWeight.Bold,
            )
            localityName?.let { Text("Current locality: $it") }
            lastCheckedAt?.let { Text("Last checked: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
            detail?.takeUnless { it == "Acquiring locality…" }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatJourneyTimestamp(timestamp: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}

private data class PackRemoval(val id: String, val name: String)

@Composable
private fun ConfirmationDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
    )
}

@Composable
private fun InfoCard(title: String, body: String) { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(body) } } }

private fun hasPermission(context: android.content.Context, permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
