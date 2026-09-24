package com.actionanand.localtell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionanand.localtell.app.data.RemotePack
import com.actionanand.localtell.app.journey.JourneyForegroundService
import com.actionanand.localtell.app.journey.JourneyPoint
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocalTellTheme { LocalTellApp() } }
    }
}

private enum class Tab { HOME, PACKS, JOURNEY }

@Composable
private fun LocalTellApp() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.HOME, { tab = Tab.HOME }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == Tab.PACKS, { tab = Tab.PACKS }, { Icon(Icons.Default.Download, null) }, label = { Text("Offline data") })
                NavigationBarItem(tab == Tab.JOURNEY, { tab = Tab.JOURNEY }, { Icon(Icons.Default.Route, null) }, label = { Text("Journey") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen()
                Tab.PACKS -> PacksScreen()
                Tab.JOURNEY -> JourneyScreen()
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        permissionGranted = fineGranted
        if (fineGranted) vm.refresh()
    }

    LaunchedEffect(permissionGranted) { if (permissionGranted) vm.refresh() }

    val locationEnabled = remember(status) {
        val manager = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("LocalTell", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Approximate locality from the cellular network and an offline database.")
        }
        if (!permissionGranted) {
            item {
                InfoCard(
                    "Permission needed",
                    "Android protects Cell IDs as location-sensitive data. LocalTell requests precise-location permission only to read cellular identities; it never requests GPS coordinates."
                )
                Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text("Allow cell access") }
            }
        } else if (!locationEnabled) {
            item {
                InfoCard("Android Location setting is off", "Many Android devices will not expose Cell IDs while the system Location switch is off. Turning it on does not make LocalTell request GPS coordinates.")
            }
        }
        item {
            when (val s = status) {
                HomeStatus.Idle -> Text("Ready")
                HomeStatus.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is HomeStatus.Error -> InfoCard("Unable to resolve", s.message)
                is HomeStatus.Ready -> {
                    val match = s.match
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Approximate area", style = MaterialTheme.typography.labelLarge)
                            Text(match?.areaName ?: if (s.cells.isEmpty()) "No serving cell" else "Unknown area", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            match?.district?.let { Text(listOfNotNull(it, match.state).joinToString(", ")) }
                            if (match != null) Text("Confidence ${match.confidence}% · Offline pack ${match.packId}")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    s.cells.firstOrNull()?.let { cell ->
                        InfoCard("Serving cell", "${cell.radio} · PLMN ${cell.plmn}\nTAC/LAC ${cell.areaCode ?: "—"} · Cell ${cell.cellId}\nSignal ${cell.dbm?.let { "$it dBm" } ?: "—"}")
                    }
                    if (match != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {
                                val text = "My approximate area is ${match.areaName}${match.district?.let { ", $it" } ?: ""}. (LocalTell cellular estimate)"
                                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                                context.startActivity(Intent.createChooser(send, "Share approximate area"))
                            }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                        }
                    }
                }
            }
        }
        item {
            Button(enabled = permissionGranted, onClick = vm::refresh) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Refresh cell")
            }
        }
        item {
            InfoCard("Offline by design", "After a state/India pack is downloaded, area lookup uses only the serving cellular identity and local SQLite data. Internet is used only for optional pack downloads/updates.")
        }
    }
}

@Composable
private fun PacksScreen(vm: PacksViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Offline data", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Download only the states you need. Entire-India can be published as another pack later.")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Refresh list") }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { error -> item { InfoCard("Data source", error) } }
        items(state.remote, key = RemotePack::id) { pack ->
            val installed = state.installed[pack.id]
            val progress = state.progress[pack.id]
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Version ${pack.version}" + (pack.compressedBytes?.let { " · ${formatBytes(it)} download" } ?: ""))
                    when {
                        progress != null -> {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("Downloading $progress%")
                        }
                        installed == null -> Button(onClick = { vm.download(pack) }) { Text("Download") }
                        installed.version < pack.version -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.download(pack) }) { Text("Update") }
                            OutlinedButton(onClick = { vm.remove(pack.id) }) { Text("Remove") }
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Installed", fontWeight = FontWeight.SemiBold)
                            OutlinedButton(onClick = { vm.remove(pack.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        if (!state.loading && state.remote.isEmpty() && state.error == null) item {
            InfoCard("No manifest loaded", "Create the LocalTell data release repository and publish manifest.json at the URL configured in app-config.json.")
        }
    }
}

@Composable
private fun JourneyScreen(vm: JourneyViewModel = viewModel()) {
    val context = LocalContext.current
    val points by vm.points.collectAsStateWithLifecycle()
    val hasFineLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    var hasNotifications by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val startJourney = {
        val intent = Intent(context, JourneyForegroundService::class.java).setAction(JourneyForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotifications = granted
        if (granted && hasFineLocation) startJourney()
    }

    LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Journey", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Records an entry only when the resolved locality changes.")
            if (!hasFineLocation) {
                Spacer(Modifier.height(8.dp))
                InfoCard("Cell permission required", "Allow cell access on the Home tab before starting Journey mode.")
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = hasFineLocation,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifications) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startJourney()
                        }
                    },
                ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Start") }
                OutlinedButton(onClick = {
                    context.startService(Intent(context, JourneyForegroundService::class.java).setAction(JourneyForegroundService.ACTION_STOP))
                    vm.refresh()
                }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
                TextButton(onClick = { vm.clear() }) { Text("Clear") }
            }
        }
        if (points.isEmpty()) item { InfoCard("No journey entries", "Start Journey after installing an offline data pack. Cell checks run about every 20 seconds while the foreground service is active.") }
        items(points, key = JourneyPoint::id) { p ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.areaName, fontWeight = FontWeight.Bold)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.timestamp)))
                    Text("${p.radio} · ${p.plmn} · confidence ${p.confidence}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f KB".format(bytes / 1024.0)
}

@Composable
private fun LocalTellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF7AD99B))
        else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF166534)),
        content = content,
    )
}
