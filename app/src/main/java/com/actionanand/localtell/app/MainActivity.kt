package com.actionanand.localtell.app

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.actionanand.localtell.app.data.InstalledPack
import com.actionanand.localtell.app.data.IndiaRegion
import com.actionanand.localtell.app.data.PackCatalog
import com.actionanand.localtell.app.data.RegionPacks
import com.actionanand.localtell.app.data.RemotePack
import com.actionanand.localtell.app.data.formatPackBytes
import com.actionanand.localtell.app.easy.EasyLocation
import com.actionanand.localtell.app.easy.EasyError
import com.actionanand.localtell.app.easy.EasyViewModel
import com.actionanand.localtell.app.external.MapLinkBuilder
import com.actionanand.localtell.app.external.RideDestination
import com.actionanand.localtell.app.external.RideLinkBuilder
import com.actionanand.localtell.app.journey.JourneyForegroundService
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.journey.JourneyTrackingMode
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.model.SubscriptionCells
import com.actionanand.localtell.app.survey.TowerSurveyScreen
import com.actionanand.localtell.app.ui.LocationGuideScreen
import com.actionanand.localtell.app.ui.theme.LocalTellTheme
import com.actionanand.localtell.app.ui.theme.ThemeMode
import com.actionanand.localtell.app.ui.SignalQuality
import com.actionanand.localtell.app.ui.signalVisual
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var locationEnablement: LocationEnablement
    var locationWasEnabledByLocalTell by mutableStateOf(false)
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        locationEnablement = LocationEnablement(this)
        setContent {
            val preferences = remember { getSharedPreferences("localtell_preferences", Context.MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(ThemeMode.fromPreference(preferences.getString("theme_mode", null))) }
            LocalTellTheme(themeMode) {
                val defaultTab = if (preferences.getString("default_root_tab", "EASY") == "EASY") RootTab.EASY else RootTab.HOME
                LocalTellApp(defaultTab, themeMode) { mode ->
                    themeMode = mode
                    preferences.edit().putString("theme_mode", mode.name).apply()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::locationEnablement.isInitialized) locationEnablement.onResume()
        if (::locationEnablement.isInitialized && !locationEnablement.isEnabled()) {
            locationWasEnabledByLocalTell = false
        }
    }

    fun isLocationEnabled(): Boolean = locationEnablement.isEnabled()

    fun requestLocationEnable(onEnabled: () -> Unit) {
        val locationWasOff = !isLocationEnabled()
        locationEnablement.requestEnable {
            if (locationWasOff && isLocationEnabled() && isLocationTurnOffReminderEnabled()) {
                locationWasEnabledByLocalTell = true
            } else if (!isLocationTurnOffReminderEnabled()) {
                locationWasEnabledByLocalTell = false
            }
            onEnabled()
        }
    }

    fun takeLocationTurnOffReminder(): Boolean {
        if (!isLocationTurnOffReminderEnabled()) {
            locationWasEnabledByLocalTell = false
            return false
        }
        if (!isLocationEnabled()) {
            locationWasEnabledByLocalTell = false
            return false
        }
        if (!locationWasEnabledByLocalTell) return false
        locationWasEnabledByLocalTell = false
        return true
    }

    fun setLocationTurnOffReminderEnabled(enabled: Boolean) {
        getSharedPreferences("localtell_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("remind_location_turn_off", enabled)
            .apply()
        if (!enabled) locationWasEnabledByLocalTell = false
    }

    private fun isLocationTurnOffReminderEnabled(): Boolean =
        getSharedPreferences("localtell_preferences", Context.MODE_PRIVATE)
            .getBoolean("remind_location_turn_off", true)

    fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
}

private enum class RootTab { HOME, EASY, MORE }
private enum class MoreDestination { MENU, SETTINGS, OFFLINE_DATA, JOURNEY, TOWER_SURVEY, LOCATION_GUIDE }

@Composable
private fun LocalTellApp(defaultTab: RootTab, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    var tab by remember { mutableStateOf(defaultTab) }
    var moreDestination by remember { mutableStateOf(MoreDestination.MENU) }
    BackHandler(enabled = tab == RootTab.MORE && moreDestination != MoreDestination.MENU) { moreDestination = MoreDestination.MENU }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == RootTab.HOME, { tab = RootTab.HOME }, { Icon(Icons.Default.Home, null) }, label = { Text(stringResource(R.string.nav_home)) })
            NavigationBarItem(tab == RootTab.EASY, { tab = RootTab.EASY }, { Icon(Icons.Default.LocationOn, null) }, label = { Text(stringResource(R.string.nav_easy)) })
            NavigationBarItem(tab == RootTab.MORE, { tab = RootTab.MORE; moreDestination = MoreDestination.MENU }, { Icon(Icons.Default.MoreHoriz, null) }, label = { Text(stringResource(R.string.nav_more)) })
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                RootTab.HOME -> HomeScreen()
                RootTab.EASY -> EasyScreen()
                RootTab.MORE -> when (moreDestination) {
                    MoreDestination.MENU -> MoreScreen { moreDestination = it }
                    MoreDestination.LOCATION_GUIDE -> MoreChild(onBack = { moreDestination = MoreDestination.MENU }) { LocationGuideScreen() }
                    MoreDestination.SETTINGS -> MoreChild(onBack = { moreDestination = MoreDestination.MENU }) { SettingsScreen(themeMode, onThemeModeChange) }
                    MoreDestination.OFFLINE_DATA -> MoreChild(onBack = { moreDestination = MoreDestination.MENU }) { PacksScreen() }
                    MoreDestination.JOURNEY -> MoreChild(onBack = { moreDestination = MoreDestination.MENU }) { JourneyScreen() }
                    MoreDestination.TOWER_SURVEY -> if (BuildConfig.ENABLE_TOWER_SURVEY) MoreChild(onBack = { moreDestination = MoreDestination.MENU }) { TowerSurveyScreen() } else MoreScreen { moreDestination = it }
                }
            }
        }
    }
}

@Composable
private fun MoreChild(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.padding(3.dp))
            Text(stringResource(R.string.more_back))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun MoreScreen(onOpen: (MoreDestination) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.more_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { MoreRow(Icons.Default.Route, stringResource(R.string.more_journey), stringResource(R.string.more_journey_description)) { onOpen(MoreDestination.JOURNEY) } }
        item { MoreRow(Icons.Default.Download, stringResource(R.string.more_offline), stringResource(R.string.more_offline_description)) { onOpen(MoreDestination.OFFLINE_DATA) } }
        if (BuildConfig.ENABLE_TOWER_SURVEY) item { MoreRow(Icons.Default.LocationOn, stringResource(R.string.more_survey), stringResource(R.string.more_survey_description)) { onOpen(MoreDestination.TOWER_SURVEY) } }
        item { MoreRow(Icons.Default.LocationOn, stringResource(R.string.location_guide_title), stringResource(R.string.location_guide_description)) { onOpen(MoreDestination.LOCATION_GUIDE) } }
        item { MoreRow(Icons.Default.SettingsIcon, stringResource(R.string.more_settings), stringResource(R.string.more_settings_description)) { onOpen(MoreDestination.SETTINGS) } }
    }
}

@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val preferences = remember { context.getSharedPreferences("localtell_preferences", Context.MODE_PRIVATE) }
    var defaultEasy by remember { mutableStateOf(preferences.getString("default_root_tab", "EASY") == "EASY") }
    var locationReminderEnabled by remember { mutableStateOf(preferences.getBoolean("remind_location_turn_off", true)) }
    var selectedLanguage by remember { mutableStateOf(AppLanguageManager.current(context)) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.more_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text(stringResource(R.string.more_settings_description), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsSectionHeader(Icons.Default.SettingsIcon, stringResource(R.string.settings_general)) }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_easy_default), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_easy_default_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = defaultEasy,
                        onCheckedChange = { enabled ->
                            defaultEasy = enabled
                            preferences.edit().putString("default_root_tab", if (enabled) "EASY" else "HOME").apply()
                        },
                    )
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_location_reminder), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_location_reminder_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = locationReminderEnabled,
                        onCheckedChange = { enabled ->
                            locationReminderEnabled = enabled
                            activity?.setLocationTurnOffReminderEnabled(enabled)
                                ?: preferences.edit().putBoolean("remind_location_turn_off", enabled).apply()
                        },
                    )
                }
            }
        }
        item { SettingsSectionHeader(Icons.Default.Palette, stringResource(R.string.settings_appearance)) }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = themeMode == ThemeMode.SYSTEM,
                            onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                            label = { Text(stringResource(R.string.settings_theme_automatic)) },
                            leadingIcon = { Icon(Icons.Default.BrightnessAuto, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        FilterChip(
                            selected = themeMode == ThemeMode.LIGHT,
                            onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                            label = { Text(stringResource(R.string.settings_theme_light)) },
                            leadingIcon = { Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        FilterChip(
                            selected = themeMode == ThemeMode.DARK,
                            onClick = { onThemeModeChange(ThemeMode.DARK) },
                            label = { Text(stringResource(R.string.settings_theme_dark)) },
                            leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
        item { SettingsSectionHeader(Icons.Default.Language, stringResource(R.string.settings_language)) }
        items(listOf(AppLanguage.SYSTEM, AppLanguage.TAMIL, AppLanguage.ENGLISH, AppLanguage.SANSKRIT)) { language ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = selectedLanguage == language,
                    role = Role.RadioButton,
                    onClick = {
                        if (language != selectedLanguage) {
                            selectedLanguage = language
                            AppLanguageManager.apply(context, language)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) activity?.recreate()
                        }
                    },
                ),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selectedLanguage == language, onClick = null)
                    Text(languageDisplayName(language), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun languageDisplayName(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system_default)
    AppLanguage.TAMIL -> stringResource(R.string.language_tamil)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.SANSKRIT -> stringResource(R.string.language_sanskrit)
}

@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun EasyScreen(vm: EasyViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val state by vm.state.collectAsStateWithLifecycle()
    var findInput by rememberSaveable { mutableStateOf("") }
    var ttsReady by remember { mutableStateOf(false) }
    var locationRequestAwaitingResult by remember { mutableStateOf(false) }
    var locationRequestObservedInFlight by remember { mutableStateOf(false) }
    var locationBeforeRequest by remember { mutableStateOf<EasyLocation?>(null) }
    var showLocationTurnOffReminder by remember { mutableStateOf(false) }
    val speaker = remember { TextToSpeech(context.applicationContext) { ttsReady = it == TextToSpeech.SUCCESS } }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    fun beginLocationRequest() {
        locationBeforeRequest = state.currentLocation
        locationRequestAwaitingResult = true
        locationRequestObservedInFlight = false
        vm.getMyLocation()
    }
    lateinit var requestLocation: () -> Unit
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestLocation()
        }
    }
    requestLocation = {
        when {
            !hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) -> permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            activity?.isLocationEnabled() == false -> activity.requestLocationEnable {
                if (activity.isLocationEnabled()) {
                    beginLocationRequest()
                }
            }
            else -> beginLocationRequest()
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        state.locating,
        state.currentLocation,
        state.pendingLocationChoice,
        state.error,
        locationRequestAwaitingResult,
        locationRequestObservedInFlight,
    ) {
        if (!locationRequestAwaitingResult) return@LaunchedEffect
        if (state.locating) {
            locationRequestObservedInFlight = true
            return@LaunchedEffect
        }
        if (state.pendingLocationChoice != null) return@LaunchedEffect
        if (state.currentLocation !== locationBeforeRequest) {
            locationRequestAwaitingResult = false
            locationRequestObservedInFlight = false
            locationBeforeRequest = null
            showLocationTurnOffReminder = activity?.takeLocationTurnOffReminder() == true
        } else if (!locationRequestObservedInFlight) {
            return@LaunchedEffect
        } else if (state.error != null) {
            locationRequestAwaitingResult = false
            locationRequestObservedInFlight = false
            locationBeforeRequest = null
        }
    }
    state.pendingLocationChoice?.let {
        ImproveLocationDialog(onUseAssisted = vm::useAssistedLocation, onStayOffline = vm::useApproximateLocation)
    }
    if (showLocationTurnOffReminder) {
        LocationTurnOffReminder(
            onKeepOn = { showLocationTurnOffReminder = false },
            onOpenSettings = {
                showLocationTurnOffReminder = false
                activity?.openLocationSettings()
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(stringResource(R.string.easy_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Text(stringResource(R.string.easy_share_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(enabled = !state.locating, onClick = requestLocation) { Text(if (state.locating) stringResource(R.string.easy_getting_location) else stringResource(R.string.easy_get_location)) }
            if (state.locating) {
                LocationWaitingAnimation(
                    message = stringResource(R.string.home_loading_finding_location),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
        state.currentLocation?.let { location -> item { EasyLocationCard(location, showAccuracy = true, onSpeak = {
            if (ttsReady) speaker.speak(location.encoded.numericCode.filter(Char::isDigit).map { it.toString() }.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "localtell-number") else Toast.makeText(context, R.string.easy_tts_unavailable, Toast.LENGTH_SHORT).show()
        }, onCopy = { copyText(context, location.encoded.numericCode) }, onShare = { shareEasyLocation(context, location) }) } }
        item {
            Text(stringResource(R.string.easy_find_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = findInput, onValueChange = { findInput = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.easy_find_placeholder)) }, leadingIcon = { Icon(Icons.Default.Search, stringResource(R.string.easy_search_icon)) }, trailingIcon = if (findInput.isNotEmpty()) ({ IconButton(onClick = { findInput = "" }) { Icon(Icons.Default.Close, stringResource(R.string.easy_clear_input)) } }) else null)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.find(findInput) }) { Text(stringResource(R.string.easy_find)) }
        }
        state.resolvedLocation?.let { location -> item { EasyLocationCard(location, showAccuracy = false, onSpeak = null, onCopy = { copyText(context, MapLinkBuilder.googleMaps(location.encoded.latitude, location.encoded.longitude).toString()) }, onShare = { shareEasyLocation(context, location) }) } }
        state.resolvedLocation?.let { location -> item { RideActions(context, location) } }
        state.error?.let { error -> item { InfoCard(stringResource(R.string.easy_title), easyErrorText(error)) } }
    }
}

@Composable
private fun EasyLocationCard(location: EasyLocation, showAccuracy: Boolean, onSpeak: (() -> Unit)?, onCopy: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (showAccuracy) stringResource(R.string.easy_current_location) else stringResource(R.string.easy_resolved_location), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.easy_latitude, coordinateDisplay(location.encoded.latitude)))
            Text(stringResource(R.string.easy_longitude, coordinateDisplay(location.encoded.longitude)))
            if (showAccuracy) location.accuracyMetres?.let { Text(stringResource(R.string.easy_accuracy, it.toInt())) }
            if (showAccuracy && location.locationQuality == com.actionanand.localtell.app.location.LocationQuality.APPROXIMATE) {
                Text(stringResource(R.string.location_approximate_locality), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showAccuracy) location.locationSource?.let { source ->
                Text(
                    stringResource(
                        R.string.location_source,
                        if (source == com.actionanand.localtell.app.location.LocationSource.GPS) stringResource(R.string.location_source_gps) else stringResource(R.string.location_source_assisted),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            location.locationNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.easy_number), style = MaterialTheme.typography.labelLarge)
            Text(location.encoded.numericCode, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.easy_short_code), style = MaterialTheme.typography.labelLarge)
            Text(location.encoded.shortCode, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            location.locality?.let { match ->
                val localityName = match.localityName.takeIf(String::isNotBlank)
                val hierarchy = listOfNotNull(match.subDistrict, match.district, match.state)
                    .filterNot { it == localityName }
                    .distinct()

                localityName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (hierarchy.isNotEmpty()) {
                    Text(hierarchy.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
            } ?: Text(stringResource(R.string.easy_offline_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onSpeak != null) OutlinedButton(onClick = onSpeak) { Text(stringResource(R.string.easy_read_aloud)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy) { Text(stringResource(R.string.easy_copy)) }
                Button(onClick = onShare) { Icon(Icons.Default.Share, null); Spacer(Modifier.padding(2.dp)); Text(stringResource(R.string.easy_share)) }
            }
            if (!showAccuracy) {
                OutlinedButton(onClick = { openMaps(context, location) }) { Text(stringResource(R.string.easy_open_maps)) }
                TextButton(onClick = { copyText(context, MapLinkBuilder.googleMaps(location.encoded.latitude, location.encoded.longitude).toString()) }) { Text(stringResource(R.string.easy_copy_maps)) }
            }
        }
    }
}

@Composable
private fun RideActions(context: Context, location: EasyLocation) {
    val locationLabel = stringResource(R.string.easy_location_label)
    val destination = RideDestination(location.encoded.latitude, location.encoded.longitude, locationLabel, location.locality?.localityName ?: MapLinkBuilder.coordinateText(location.encoded.latitude, location.encoded.longitude))
    var showUberChoice by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.easy_ride_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { showUberChoice = true }) { Text(stringResource(R.string.easy_uber)) }
        }
    }
    if (showUberChoice) {
        AlertDialog(
            onDismissRequest = { showUberChoice = false },
            title = { Text(stringResource(R.string.easy_uber_choice_title)) },
            text = { Text(stringResource(R.string.easy_uber_choice_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showUberChoice = false
                    launchUberAsDestination(context, destination)
                }) { Text(stringResource(R.string.easy_uber_destination)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUberChoice = false
                        launchUberAsPickup(context, destination)
                    }) { Text(stringResource(R.string.easy_uber_pickup)) }
                    TextButton(onClick = { showUberChoice = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

private fun coordinateDisplay(value: Double) = String.format(Locale.US, "%.6f", value)

@Composable
private fun easyErrorText(error: EasyError): String = when (error) {
    EasyError.TIMEOUT -> stringResource(R.string.easy_location_timeout)
    EasyError.LOCATION_DISABLED -> stringResource(R.string.easy_location_disabled)
    EasyError.PERMISSION_MISSING -> stringResource(R.string.easy_permission_needed)
    EasyError.LOCATION_UNAVAILABLE -> stringResource(R.string.easy_location_error)
    EasyError.NETWORK_UNAVAILABLE -> stringResource(R.string.location_connect_network)
    EasyError.INVALID_INPUT -> stringResource(R.string.easy_invalid)
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LocalTell", text))
    Toast.makeText(context, R.string.easy_copied, Toast.LENGTH_SHORT).show()
}

private fun shareEasyLocation(context: Context, location: EasyLocation) {
    val maps = MapLinkBuilder.googleMaps(location.encoded.latitude, location.encoded.longitude).toString()
    val text = context.getString(R.string.easy_share_message, coordinateDisplay(location.encoded.latitude), coordinateDisplay(location.encoded.longitude), location.encoded.numericCode, location.encoded.shortCode, maps)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, context.getString(R.string.share_location_title)))
}

private fun openMaps(context: Context, location: EasyLocation) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, MapLinkBuilder.googleMaps(location.encoded.latitude, location.encoded.longitude))) }.onFailure { Toast.makeText(context, R.string.easy_maps_unavailable, Toast.LENGTH_SHORT).show() }
}

private fun launchUberAsDestination(context: Context, destination: RideDestination) {
    launchUber(context, RideLinkBuilder.uberAsDestination(destination))
}

private fun launchUberAsPickup(context: Context, location: RideDestination) {
    launchUber(context, RideLinkBuilder.uberAsPickup(location))
}

private fun launchUber(context: Context, uri: android.net.Uri) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(RideLinkBuilder.UBER_PACKAGE)) }
        .onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.easy_ride_unavailable, context.getString(R.string.easy_uber)),
                Toast.LENGTH_SHORT,
            ).show()
        }
}

@Composable
private fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val status by vm.status.collectAsStateWithLifecycle()
    var permissionGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var phoneStateGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_PHONE_STATE)) }
    var locationEnabled by remember { mutableStateOf(activity?.isLocationEnabled() == true) }
    var selectedSubscriptionId by remember { mutableStateOf<Int?>(null) }
    var homeRequestAwaitingCompletion by remember { mutableStateOf(false) }
    var homeRequestObservedActiveState by remember { mutableStateOf(false) }
    var homeStatusBeforeRequest by remember { mutableStateOf<HomeStatus?>(null) }
    var showLocationTurnOffReminder by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun syncLocationEnabled(): Boolean {
        val enabledNow = activity?.isLocationEnabled() == true
        locationEnabled = enabledNow
        return enabledNow
    }
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncLocationEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun beginHomeRefreshAfterLocationEnablement() {
        homeStatusBeforeRequest = status
        homeRequestAwaitingCompletion = true
        homeRequestObservedActiveState = false
        vm.refresh()
    }
    val requestLocation: () -> Unit = {
        if (activity != null) {
            activity.requestLocationEnable {
                if (syncLocationEnabled()) beginHomeRefreshAfterLocationEnablement()
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (permissionGranted) {
            if (syncLocationEnabled()) vm.refresh() else requestLocation()
        }
    }
    val phoneStateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phoneStateGranted = granted
        vm.refresh()
    }
    val refreshLocality: () -> Unit = {
        if (syncLocationEnabled()) vm.refresh() else requestLocation()
    }
    androidx.compose.runtime.LaunchedEffect(permissionGranted, locationEnabled) {
        if (permissionGranted && locationEnabled && status is HomeStatus.Idle) vm.refresh()
    }
    androidx.compose.runtime.LaunchedEffect((status as? HomeStatus.Ready)?.localityState) {
        if ((status as? HomeStatus.Ready)?.localityState == LocalityState.GPS_DISABLED) {
            locationEnabled = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        status,
        homeRequestAwaitingCompletion,
        homeRequestObservedActiveState,
    ) {
        if (!homeRequestAwaitingCompletion) return@LaunchedEffect
        when (status) {
            is HomeStatus.Loading,
            is HomeStatus.AwaitingLocationChoice -> homeRequestObservedActiveState = true

            is HomeStatus.Ready,
            is HomeStatus.Error -> if (homeRequestObservedActiveState || status !== homeStatusBeforeRequest) {
                homeRequestAwaitingCompletion = false
                homeRequestObservedActiveState = false
                homeStatusBeforeRequest = null
                showLocationTurnOffReminder = activity?.takeLocationTurnOffReminder() == true
            }

            HomeStatus.Idle -> Unit
        }
    }
    (status as? HomeStatus.AwaitingLocationChoice)?.let {
        ImproveLocationDialog(onUseAssisted = vm::useAssistedLocation, onStayOffline = vm::useApproximateLocation)
    }
    if (showLocationTurnOffReminder) {
        LocationTurnOffReminder(
            onKeepOn = { showLocationTurnOffReminder = false },
            onOpenSettings = {
                showLocationTurnOffReminder = false
                activity?.openLocationSettings()
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            BrandHeader()
        }
        if (!permissionGranted) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard(stringResource(R.string.home_location_permission_title), stringResource(R.string.home_location_permission_message))
                Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text(stringResource(R.string.home_allow_location)) }
            }
        } else if (!locationEnabled) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard(stringResource(R.string.home_location_off_title), stringResource(R.string.home_location_off_message))
                PermissionToggle(stringResource(R.string.home_turn_on_location), requestLocation)
            }
        }
        if (permissionGranted && !phoneStateGranted) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard(stringResource(R.string.home_sim_details_optional), stringResource(R.string.home_sim_details_message))
                PermissionToggle(stringResource(R.string.home_allow_sim_details)) { phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE) }
            }
        }
        item {
            when (val current = status) {
                HomeStatus.Idle -> Unit
                is HomeStatus.Loading -> LocationLoadingState(current.state)
                is HomeStatus.AwaitingLocationChoice -> Unit
                is HomeStatus.Error -> InfoCard(stringResource(R.string.home_unable_to_resolve), current.message)
                is HomeStatus.Ready -> HomeResults(
                    status = current,
                    selectedSubscriptionId = selectedSubscriptionId,
                    onSelect = { selectedSubscriptionId = it },
                    onRefresh = refreshLocality,
                )
            }
        }
        if (status !is HomeStatus.Ready && status !is HomeStatus.Loading && status !is HomeStatus.AwaitingLocationChoice) item {
            RefreshLocalityButton(enabled = permissionGranted, onRefresh = refreshLocality)
        }
    }
}

@Composable
private fun HomeResults(
    status: HomeStatus.Ready,
    selectedSubscriptionId: Int?,
    onSelect: (Int?) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val selected = status.subscriptions.filter { selectedSubscriptionId == null || it.subscription?.subscriptionId == selectedSubscriptionId }
    val displayedCells = selected.flatMap(SubscriptionCells::cells)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = stringResource(R.string.home_current_locality),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(stringResource(R.string.home_current_locality), style = MaterialTheme.typography.labelLarge)
                }
                Text(status.locality?.localityName ?: localityEmptyTitle(status.localityState, displayedCells.any(RadioCell::registered)), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                if (status.locationQuality == com.actionanand.localtell.app.location.LocationQuality.APPROXIMATE) {
                    Text(stringResource(R.string.location_approximate_locality), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                status.locality?.let { match ->
                    listOfNotNull(match.subDistrict, match.district, match.state).distinct().takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", ")) }
                    Text(stringResource(R.string.home_offline_pack, match.packId, match.sourceQuality.replace('-', ' ')), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                status.accuracyMetres?.let { accuracy ->
                    Text(
                        stringResource(R.string.home_accuracy, accuracy.roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                status.locationSource?.let { source ->
                    Text(
                        stringResource(
                            R.string.location_source,
                            if (source == com.actionanand.localtell.app.location.LocationSource.GPS) stringResource(R.string.location_source_gps) else stringResource(R.string.location_source_assisted),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.localityState == LocalityState.USING_RECENT_OFFLINE_LOCALITY) {
                    Text(stringResource(R.string.home_recent_offline_locality), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (status.localityState == LocalityState.NO_GEOGRAPHIC_PACK) {
                    status.legacyMatch?.let { Text(stringResource(R.string.home_legacy_cell_estimate, it.areaName), style = MaterialTheme.typography.bodySmall) }
                }
                (status.locationNotice ?: localityStateMessage(status.localityState))?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                status.locality?.let { match ->
                    val locality = match.localityName + (match.district?.let { ", $it" } ?: "")
                    val shareText = stringResource(R.string.home_share_message, locality)
                    val shareTitle = stringResource(R.string.home_share_locality)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }, shareTitle))
                    }) { Icon(Icons.Default.Share, null); Spacer(Modifier.padding(3.dp)); Text(stringResource(R.string.home_share_button)) }
                }
            }
        }
        RefreshLocalityButton(enabled = true, onRefresh = onRefresh)
        if (status.subscriptions.any { it.subscription != null }) {
            Text(stringResource(R.string.home_cellular_diagnostics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedSubscriptionId == null, { onSelect(null) }, { Text(stringResource(R.string.home_all_sims)) })
                status.subscriptions.mapNotNull(SubscriptionCells::subscription).forEach { subscription ->
                    FilterChip(selectedSubscriptionId == subscription.subscriptionId, { onSelect(subscription.subscriptionId) }, { Text(stringResource(R.string.home_sim_label, subscription.simSlotIndex + 1)) })
                }
            }
        }
        selected.forEach { group ->
            val subscription = group.subscription
            val heading = if (subscription != null) {
                "${stringResource(R.string.home_sim_label, subscription.simSlotIndex + 1)} · ${subscription.carrierName}"
            } else {
                stringResource(R.string.home_serving_cell)
            }
            if (group.cells.isEmpty()) InfoCard(heading, stringResource(R.string.home_no_cell_identity))
            group.cells.forEach { cell -> CellCard(heading, cell) }
        }
    }
}

@Composable
private fun ImproveLocationDialog(onUseAssisted: () -> Unit, onStayOffline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStayOffline,
        title = { Text(stringResource(R.string.location_improve_title)) },
        text = { Text(stringResource(R.string.location_improve_message)) },
        confirmButton = { TextButton(onClick = onUseAssisted) { Text(stringResource(R.string.location_use_assisted)) } },
        dismissButton = { TextButton(onClick = onStayOffline) { Text(stringResource(R.string.location_stay_offline)) } },
    )
}

@Composable
private fun LocationTurnOffReminder(onKeepOn: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepOn,
        title = { Text(stringResource(R.string.location_turn_off_title)) },
        text = { Text(stringResource(R.string.location_turn_off_message)) },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.location_settings)) } },
        dismissButton = { TextButton(onClick = onKeepOn) { Text(stringResource(R.string.location_keep_on)) } },
    )
}

@Composable
private fun RefreshLocalityButton(enabled: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Button(enabled = enabled, onClick = onRefresh) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.padding(3.dp))
            Text(stringResource(R.string.home_refresh_locality))
        }
    }
}

@Composable
private fun LocationLoadingState(state: LocalityState) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        LocationWaitingAnimation(
            message = localityProgressText(state),
            modifier = Modifier.padding(22.dp),
        )
    }
}

@Composable
private fun LocationWaitingAnimation(message: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "location_loading")
    val lineFraction by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "location_loading_line",
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Box(Modifier.fillMaxWidth().height(6.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(lineFraction).height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(2.dp),
                content = {},
            )
        }
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun localityProgressText(state: LocalityState): String = when (state) {
    LocalityState.READING_CELLULAR -> stringResource(R.string.home_loading_checking_location)
    LocalityState.ACQUIRING_GNSS -> stringResource(R.string.home_loading_finding_location)
    LocalityState.RESOLVING_OFFLINE_LOCALITY -> stringResource(R.string.home_loading_finding_locality)
    else -> stringResource(R.string.home_loading_wait)
}

@Composable
private fun localityEmptyTitle(state: LocalityState, hasServingCell: Boolean): String = when (state) {
    LocalityState.NO_GEOGRAPHIC_PACK -> stringResource(R.string.home_offline_data_needed_title)
    LocalityState.GNSS_TIMEOUT -> stringResource(R.string.home_location_unavailable_title)
    LocalityState.GPS_DISABLED -> stringResource(R.string.home_location_off_title)
    LocalityState.PERMISSION_MISSING -> stringResource(R.string.home_location_permission_title)
    LocalityState.NO_LOCALITY_MATCH -> stringResource(R.string.home_locality_unavailable_title)
    else -> if (hasServingCell) stringResource(R.string.home_locality_unavailable_title) else stringResource(R.string.home_no_serving_cell)
}

@Composable
private fun localityStateMessage(state: LocalityState): String? = when (state) {
    LocalityState.NO_GEOGRAPHIC_PACK -> stringResource(R.string.home_offline_data_needed_message)
    LocalityState.GNSS_TIMEOUT -> stringResource(R.string.home_location_unavailable_message)
    LocalityState.GPS_DISABLED -> stringResource(R.string.home_location_off_message)
    LocalityState.PERMISSION_MISSING -> stringResource(R.string.home_location_permission_message)
    LocalityState.NO_LOCALITY_MATCH -> stringResource(R.string.home_locality_unavailable_message)
    else -> null
}

@Composable
private fun BrandHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.localtell_brand_icon),
            contentDescription = "LocalTell",
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Fit,
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("LocalTell", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
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
    val fallbackSignal = cell.dbm?.let { stringResource(R.string.home_signal, it) }
    val timingAdvance = cell.timingAdvance?.let { stringResource(R.string.home_timing_advance, it) }
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
        add("MCC ${cell.mcc} · MNC ${cell.mnc} · PLMN ${cell.plmn}")
        add("$areaLabel ${cell.areaCode ?: "—"} · $cellIdentityLabel ${cell.cellId}")
        if (radioMeasurements.isNotEmpty()) add(radioMeasurements.joinToString(" · "))
        if (signalMeasurements.isNotEmpty()) add(signalMeasurements.joinToString(" · "))
        fallbackSignal?.let { add(it) }
        timingAdvance?.let { add(it) }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${cell.radio} · ${if (cell.registered) stringResource(R.string.home_registered) else stringResource(R.string.home_available)}")
            SignalStrengthIndicator(cell)
            details.forEach { Text(it) }
        }
    }
}

@Composable
private fun SignalStrengthIndicator(cell: RadioCell) {
    val signal = signalVisual(cell) ?: return
    val color = when (signal.quality) {
        SignalQuality.EXCELLENT -> Color(0xFF1B5E20)
        SignalQuality.GOOD -> Color(0xFF2E7D32)
        SignalQuality.FAIR -> Color(0xFFF9A825)
        SignalQuality.WEAK -> Color(0xFFEF6C00)
        SignalQuality.VERY_WEAK -> Color(0xFFC62828)
    }
    val qualityText = when (signal.quality) {
        SignalQuality.EXCELLENT -> stringResource(R.string.signal_excellent)
        SignalQuality.GOOD -> stringResource(R.string.signal_good)
        SignalQuality.FAIR -> stringResource(R.string.signal_fair)
        SignalQuality.WEAK -> stringResource(R.string.signal_weak)
        SignalQuality.VERY_WEAK -> stringResource(R.string.signal_very_weak)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.signal_strength), style = MaterialTheme.typography.labelLarge)
        Text(
            text = "$qualityText · ${signal.dbm} dBm",
            style = MaterialTheme.typography.bodySmall,
        )
        Box(Modifier.fillMaxWidth().height(6.dp)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(3.dp),
                content = {},
            )
            Surface(
                modifier = Modifier.fillMaxWidth(signal.progress).height(6.dp),
                color = color,
                shape = RoundedCornerShape(3.dp),
                content = {},
            )
        }
    }
}

@Composable
private fun PacksScreen(vm: PacksViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingRemoval by remember { mutableStateOf<PackRemoval?>(null) }
    var pendingRegionRemoval by remember { mutableStateOf<RegionRemoval?>(null) }
    var expandedRegions by remember { mutableStateOf(emptySet<IndiaRegion>()) }
    var expandedRegionToReveal by remember { mutableStateOf<IndiaRegion?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val packListState = rememberLazyListState()
    val firstRowRevealDistance = with(LocalDensity.current) { 132.dp.toPx() }
    val remoteIds = state.remote.mapTo(mutableSetOf()) { it.id }
    val installedOnly = state.installed.values.filter { it.id !in remoteIds }.sortedBy { it.name }
    val regions = PackCatalog.regions(state.remote)
    val searchEnabled = !state.loading && state.remote.isNotEmpty()
    val searchActive = searchQuery.isNotBlank()
    val displayedRegions = if (state.loading) emptyList() else PackCatalog.filterRegions(regions, searchQuery)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    androidx.compose.runtime.LaunchedEffect(expandedRegionToReveal) {
        expandedRegionToReveal?.let { region ->
            val regionHeader = packListState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.key == "region-${region.manifestKey}"
            }
            if (regionHeader != null) {
                val scrollDistance = (
                    regionHeader.offset + regionHeader.size + firstRowRevealDistance -
                        packListState.layoutInfo.viewportEndOffset
                    ).coerceAtLeast(0f)
                if (scrollDistance > 0f) {
                    packListState.animateScrollBy(scrollDistance)
                }
            }
            expandedRegionToReveal = null
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = packListState,
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.offline_description))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = state.batch == null, onClick = vm::refresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.padding(3.dp)); Text(stringResource(R.string.offline_refresh)) }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                enabled = searchEnabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(if (state.loading) stringResource(R.string.offline_loading) else stringResource(R.string.offline_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.offline_search_description)) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, stringResource(R.string.offline_clear_search)) } }
                } else {
                    null
                },
            )
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!state.loading && state.remote.isNotEmpty() && !searchActive) {
            item { IndiaDownloadCard(regions, state, vm) }
        }
        displayedRegions.forEach { displayedRegion ->
            val fullRegion = regions.first { it.region == displayedRegion.region }
            val expanded = searchActive || displayedRegion.region in expandedRegions
            val installedRegionPacks = PackCatalog.installedPacks(fullRegion.packs, state.installed)
            val regionBusy = state.batch != null || fullRegion.packs.any { state.activeDownloads.contains(it.id) }
            item(key = "region-${displayedRegion.region.manifestKey}") {
                RegionDownloadCard(
                    regionPacks = fullRegion,
                    state = state,
                    expanded = expanded,
                    expandable = !searchActive,
                    onToggle = {
                        val wasExpanded = displayedRegion.region in expandedRegions
                        expandedRegions = if (wasExpanded) expandedRegions - displayedRegion.region else expandedRegions + displayedRegion.region
                        if (!wasExpanded) {
                            expandedRegionToReveal = displayedRegion.region
                        }
                    },
                    onDownload = { vm.downloadRegion(fullRegion.packs, fullRegion.region.manifestKey) },
                    batchStartEnabled = state.batch == null && state.activeDownloads.isEmpty(),
                    installedPackCount = installedRegionPacks.size,
                    removeEnabled = !regionBusy,
                    onRemoveRegion = installedRegionPacks.takeIf { it.isNotEmpty() }?.let { installedPacks ->
                        { pendingRegionRemoval = RegionRemoval(fullRegion.region.displayName, installedPacks) }
                    },
                    onCancelBatch = vm::cancelBatchDownload,
                )
            }
            if (expanded) {
                item(key = "region-label-${displayedRegion.region.manifestKey}") {
                    Text(
                        text = stringResource(R.string.offline_states_in_region, fullRegion.region.displayName),
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(displayedRegion.packs, key = RemotePack::id) { pack ->
                    StatePackRow(
                        pack = pack,
                        installedVersion = state.installed[pack.id]?.version,
                        progress = state.progress[pack.id],
                        busy = state.activeDownloads.contains(pack.id),
                        batchActive = state.batch != null,
                        onDownload = { vm.download(pack) },
                        onCancel = { vm.cancelDownload(pack.id) },
                        onRemoveRequested = { pendingRemoval = PackRemoval(pack.id, pack.name) },
                    )
                }
            }
        }
        if (!state.loading && !searchActive) {
            items(installedOnly, key = { it.id }) { pack ->
                InstalledOnlyPackRow(pack, state.batch != null) { pendingRemoval = PackRemoval(pack.id, pack.name) }
            }
        }
        if (!state.loading && searchEnabled && searchActive && displayedRegions.isEmpty()) {
            item { InfoCard(stringResource(R.string.offline_no_resources_title), stringResource(R.string.offline_no_resources_message, searchQuery.trim())) }
        }
        state.error?.let { error -> item { InfoCard(stringResource(R.string.offline_title), error) } }
        if (!state.loading && state.remote.isEmpty() && state.installed.isEmpty() && state.error == null) {
            item { InfoCard(stringResource(R.string.offline_no_packs_title), stringResource(R.string.offline_no_packs_message)) }
        }
    }
    pendingRemoval?.let { pack ->
        ConfirmationDialog(
            title = stringResource(R.string.offline_remove_pack_title),
            message = stringResource(R.string.offline_remove_pack_message, pack.name),
            confirmLabel = stringResource(R.string.offline_remove),
            onDismiss = { pendingRemoval = null },
            onConfirm = { vm.remove(pack.id); pendingRemoval = null },
        )
    }
    pendingRegionRemoval?.let { region ->
        ConfirmationDialog(
            title = stringResource(R.string.offline_remove_region_title, region.name),
            message = stringResource(R.string.offline_remove_region_message, region.packs.size, region.name),
            confirmLabel = stringResource(R.string.offline_remove),
            onDismiss = { pendingRegionRemoval = null },
            onConfirm = { vm.removeRegion(region.packs); pendingRegionRemoval = null },
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
            Text(stringResource(R.string.offline_pack_count, packs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(packSizeSummary(totals), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BatchStatus(state.batch, "india", vm::cancelBatchDownload)
            if (required.isEmpty()) {
                Text(stringResource(R.string.offline_all_installed), fontWeight = FontWeight.SemiBold)
            } else {
                Button(enabled = state.batch == null && state.activeDownloads.isEmpty(), onClick = vm::downloadAll) { Text(batchActionLabel(packs, state.installed, BatchAction.DOWNLOAD_ALL).label()) }
            }
        }
    }
}

@Composable
private fun RegionDownloadCard(
    regionPacks: RegionPacks,
    state: PackUiState,
    expanded: Boolean,
    expandable: Boolean,
    onToggle: () -> Unit,
    onDownload: () -> Unit,
    batchStartEnabled: Boolean,
    installedPackCount: Int,
    removeEnabled: Boolean,
    onRemoveRegion: (() -> Unit)?,
    onCancelBatch: () -> Unit,
) {
    val required = PackCatalog.requiredPacks(regionPacks.packs, state.installed)
    val regionName = regionPacks.region.displayName
    val toggleLabel = if (expanded) stringResource(R.string.offline_collapse_region, regionName) else stringResource(R.string.offline_expand_region, regionName)
    val containerColor = if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val contentColor = if (expanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val supportingColor = if (expanded) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = if (expandable) {
                    Modifier.fillMaxWidth().clickable(onClickLabel = toggleLabel, onClick = onToggle)
                } else {
                    Modifier.fillMaxWidth()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(regionName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${stringResource(R.string.offline_pack_count, regionPacks.packs.size)} · ${packSizeSummary(regionPacks.totals)}", style = MaterialTheme.typography.bodySmall, color = supportingColor)
                }
                if (expandable) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
            }
            BatchStatus(state.batch, regionPacks.region.manifestKey, onCancelBatch)
            if (required.isEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.offline_installed), modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    onRemoveRegion?.let { OutlinedButton(enabled = removeEnabled, onClick = it) { Text(stringResource(R.string.offline_remove_region)) } }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(enabled = batchStartEnabled, onClick = onDownload) { Text(batchActionLabel(regionPacks.packs, state.installed, BatchAction.DOWNLOAD_REGION).label()) }
                    if (installedPackCount > 0) {
                        onRemoveRegion?.let { OutlinedButton(enabled = removeEnabled, onClick = it) { Text(stringResource(R.string.offline_remove_region)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchStatus(batch: BatchDownloadProgress?, scopeKey: String, onCancel: () -> Unit) {
    if (batch?.scopeKey == scopeKey) {
        LinearProgressIndicator(progress = { batch.completed.toFloat() / batch.total }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.offline_downloading_batch, batch.completed + 1, batch.total, batch.currentPackName), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.offline_cancel_download)) }
    }
}

@Composable
private fun StatePackRow(pack: RemotePack, installedVersion: Long?, progress: Int?, busy: Boolean, batchActive: Boolean, onDownload: () -> Unit, onCancel: () -> Unit, onRemoveRequested: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${pack.name} · ${formatPackBytes(pack.compressedBytes) ?: stringResource(R.string.offline_size_unavailable)} (V${pack.version})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                PackAction(pack.version, installedVersion, progress, busy, batchActive, onDownload, onCancel, onRemoveRequested)
                Text(formatPackBytes(pack.uncompressedBytes)?.let { stringResource(R.string.offline_on_device, it) } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PackAction(remoteVersion: Long, installedVersion: Long?, progress: Int?, busy: Boolean, batchActive: Boolean, onDownload: () -> Unit, onCancel: () -> Unit, onRemoveRequested: () -> Unit) {
    when {
        progress != null -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(0.45f))
                    Text(stringResource(R.string.offline_downloading_percent, progress), style = MaterialTheme.typography.bodySmall)
                }
                if (!batchActive) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
        busy && !batchActive -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offline_starting), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        installedVersion == null -> Button(enabled = !batchActive && !busy, onClick = onDownload) { Text(stringResource(R.string.offline_download)) }
        installedVersion < remoteVersion -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !batchActive && !busy, onClick = onDownload) { Text(stringResource(R.string.offline_update)) }
            OutlinedButton(enabled = !batchActive && !busy, onClick = onRemoveRequested) { Text(stringResource(R.string.offline_remove)) }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offline_installed), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            OutlinedButton(enabled = !batchActive && !busy, onClick = onRemoveRequested) { Text(stringResource(R.string.offline_remove)) }
        }
    }
}

@Composable
private fun InstalledOnlyPackRow(pack: InstalledPack, batchActive: Boolean, onRemoveRequested: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.offline_installed_version, pack.version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(enabled = !batchActive, onClick = onRemoveRequested) { Text(stringResource(R.string.offline_remove)) }
        }
    }
}

@Composable
private fun packSizeSummary(totals: com.actionanand.localtell.app.data.PackTotals): String = listOfNotNull(
    formatPackBytes(totals.compressedBytes)?.let { stringResource(R.string.offline_download_size, it) },
    formatPackBytes(totals.uncompressedBytes)?.let { stringResource(R.string.offline_on_device, it) },
).joinToString(" · ")

private enum class BatchAction { DOWNLOAD_ALL, DOWNLOAD_REGION, UPDATE_ALL, DOWNLOAD_REMAINING }

private fun batchActionLabel(packs: Collection<RemotePack>, installed: Map<String, InstalledPack>, default: BatchAction): BatchAction {
    val required = PackCatalog.requiredPacks(packs, installed)
    return when {
        required.size == packs.size -> default
        required.all { installed[it.id] != null } -> BatchAction.UPDATE_ALL
        else -> BatchAction.DOWNLOAD_REMAINING
    }
}

@Composable
private fun BatchAction.label(): String = stringResource(
    when (this) {
        BatchAction.DOWNLOAD_ALL -> R.string.offline_download_all
        BatchAction.DOWNLOAD_REGION -> R.string.offline_download_region
        BatchAction.UPDATE_ALL -> R.string.offline_update_all
        BatchAction.DOWNLOAD_REMAINING -> R.string.offline_download_remaining
    },
)

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
    var journeyStopAwaitingReminder by remember { mutableStateOf(false) }
    var showLocationTurnOffReminder by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun syncLocationEnabled(): Boolean {
        val enabledNow = activity?.isLocationEnabled() == true
        locationEnabled = enabledNow
        return enabledNow
    }
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncLocationEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
    androidx.compose.runtime.LaunchedEffect(tracking.mode) {
        if (tracking.mode == JourneyTrackingMode.GPS_DISABLED) locationEnabled = false
    }
    androidx.compose.runtime.LaunchedEffect(tracking.mode, journeyStopAwaitingReminder) {
        if (journeyStopAwaitingReminder && tracking.mode == JourneyTrackingMode.STOPPED) {
            journeyStopAwaitingReminder = false
            showLocationTurnOffReminder = activity?.takeLocationTurnOffReminder() == true
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.journey_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.journey_description))
            if (!hasFineLocation) { Spacer(Modifier.height(8.dp)); InfoCard(stringResource(R.string.journey_permission_title), stringResource(R.string.journey_permission_message)) }
            else if (!locationEnabled) { Spacer(Modifier.height(8.dp)); InfoCard(stringResource(R.string.journey_location_settings_title), stringResource(R.string.journey_location_setting_message)) }
            if (tracking.mode != JourneyTrackingMode.STOPPED) {
                Spacer(Modifier.height(8.dp))
                TrackingStatusCard(tracking.mode, tracking.localityName, tracking.lastCheckedAt, tracking.detail)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = hasFineLocation && !trackingIsRunning, onClick = {
                    if (syncLocationEnabled()) {
                        beginJourney()
                    } else {
                        activity?.requestLocationEnable { if (syncLocationEnabled()) beginJourney() }
                    }
                }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.padding(2.dp)); Text(stringResource(R.string.journey_start)) }
                OutlinedButton(enabled = trackingIsRunning, onClick = {
                    journeyStopAwaitingReminder = true
                    vm.markStopped()
                    context.startService(Intent(context, JourneyForegroundService::class.java).setAction(JourneyForegroundService.ACTION_STOP))
                }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.padding(2.dp)); Text(stringResource(R.string.journey_stop)) }
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.journey_clear)) }
            }
        }
        if (points.isEmpty()) item { InfoCard(stringResource(R.string.journey_no_entries), stringResource(R.string.journey_no_entries_message)) }
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
        title = stringResource(R.string.journey_clear_title),
        message = stringResource(R.string.journey_clear_message),
        confirmLabel = stringResource(R.string.journey_clear),
        onDismiss = { confirmClear = false },
        onConfirm = { vm.clear(); confirmClear = false },
    )
    if (showLocationTurnOffReminder) {
        LocationTurnOffReminder(
            onKeepOn = { showLocationTurnOffReminder = false },
            onOpenSettings = {
                showLocationTurnOffReminder = false
                activity?.openLocationSettings()
            },
        )
    }
}

@Composable
private fun TrackingStatusCard(mode: JourneyTrackingMode, localityName: String?, lastCheckedAt: Long?, detail: String?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (mode) {
                    JourneyTrackingMode.STARTING -> stringResource(R.string.journey_starting_status)
                    JourneyTrackingMode.ACQUIRING_LOCALITY -> stringResource(R.string.journey_acquiring_locality)
                    JourneyTrackingMode.ACTIVE -> stringResource(R.string.journey_active_status)
                    JourneyTrackingMode.WAITING_FOR_LOCALITY -> stringResource(R.string.journey_waiting_locality)
                    JourneyTrackingMode.GPS_DISABLED -> stringResource(R.string.journey_disabled_status)
                    JourneyTrackingMode.PERMISSION_REQUIRED -> stringResource(R.string.journey_permission_status)
                    JourneyTrackingMode.STOPPED -> stringResource(R.string.journey_stopped_status)
                },
                fontWeight = FontWeight.Bold,
            )
            if (mode in setOf(
                    JourneyTrackingMode.STARTING,
                    JourneyTrackingMode.ACQUIRING_LOCALITY,
                    JourneyTrackingMode.WAITING_FOR_LOCALITY,
                )
            ) {
                LocationWaitingAnimation(
                    message = stringResource(R.string.home_loading_finding_location),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            localityName?.let { Text(stringResource(R.string.journey_current_locality, it)) }
            lastCheckedAt?.let { Text(stringResource(R.string.journey_last_checked, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))), style = MaterialTheme.typography.bodySmall) }
            detail?.takeUnless { mode == JourneyTrackingMode.ACQUIRING_LOCALITY }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
private data class RegionRemoval(val name: String, val packs: List<RemotePack>)

@Composable
private fun ConfirmationDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
    )
}

@Composable
private fun InfoCard(title: String, body: String) { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(body) } } }

private fun hasPermission(context: android.content.Context, permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
