package com.actionanand.localtell.app.geography

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import com.actionanand.localtell.app.R
import java.util.Locale

/** Display translations only: pack names, IDs and canonical region data are never rewritten. */
@Composable
private fun regionName(region: DetectedRegion): Pair<String, String> {
    val locale = LocalLocale.current.platformLocale
    val country = remember(region.countryCode, region.countryName, locale) {
        Locale.Builder().setRegion(region.countryCode).build().getDisplayCountry(locale)
            .takeUnless { it.isBlank() || it == region.countryCode } ?: region.countryName
    }
    val label = when (region.adminCode) {
        "IN-KA" -> R.string.geo_name_ka; "IN-TN" -> R.string.geo_name_tn
        "IN-KL" -> R.string.geo_name_kl; "IN-MH" -> R.string.geo_name_mh
        "IN-DL" -> R.string.geo_name_dl; "IN-PY" -> R.string.geo_name_py
        "IN-AN" -> R.string.geo_name_an; "IN-LD" -> R.string.geo_name_ld
        else -> null
    }
    return (label?.let { stringResource(it) } ?: region.adminName ?: country) to country
}

@Composable
fun offlineGuidanceTitle(guidance: OfflineDataGuidance): String = when (guidance.availability) {
    DataAvailability.ASSET_UNAVAILABLE -> stringResource(R.string.geo_asset_unavailable_title)
    DataAvailability.UNKNOWN_REGION -> stringResource(R.string.geo_unknown_title)
    DataAvailability.UNSUPPORTED -> guidance.region?.let { regionName(it).second } ?: stringResource(R.string.geo_unknown_title)
    DataAvailability.INSTALLED_NO_MATCH -> stringResource(R.string.geo_installed_nomatch_title)
    DataAvailability.INSTALLED_ERROR -> stringResource(R.string.geo_installed_error_title)
    else -> stringResource(R.string.home_offline_data_needed_title)
}

@Composable
fun OfflineGuidanceContent(guidance: OfflineDataGuidance, onOpen: (OfflineDataTarget?) -> Unit) {
    val names = guidance.region?.let { regionName(it) }
    val name = names?.first.orEmpty()
    if (guidance.region != null && guidance.availability != DataAvailability.UNSUPPORTED) {
        Text(if (guidance.region.adminCode != null) "$name, ${names?.second.orEmpty()}" else name,
            style = MaterialTheme.typography.titleMedium)
    }
    val message = when (guidance.availability) {
        DataAvailability.ASSET_UNAVAILABLE -> stringResource(R.string.geo_asset_unavailable_message)
        DataAvailability.UNKNOWN_REGION -> stringResource(R.string.geo_unknown_message)
        DataAvailability.UNSUPPORTED -> stringResource(R.string.geo_unsupported, names?.second.orEmpty())
        DataAvailability.CATALOG_UNKNOWN -> stringResource(R.string.geo_catalog_unknown, name)
        DataAvailability.UNPUBLISHED -> stringResource(R.string.geo_unpublished, name)
        DataAvailability.INSTALLED_NO_MATCH -> stringResource(R.string.geo_installed_nomatch, name)
        DataAvailability.INSTALLED_ERROR -> stringResource(R.string.geo_installed_error, name)
        DataAvailability.DOWNLOADABLE -> stringResource(if (guidance.partialCountryData) R.string.geo_partial_data else R.string.geo_missing_data, name)
    }
    Text(message, style = MaterialTheme.typography.bodyMedium)
    if (guidance.availability != DataAvailability.UNSUPPORTED) {
        Button(onClick = { onOpen(guidance.target) }) {
            Text(if (guidance.availability == DataAvailability.DOWNLOADABLE) stringResource(R.string.geo_download_data, name)
            else stringResource(R.string.geo_browse_data))
        }
    }
    Text(stringResource(R.string.geo_attribution), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
