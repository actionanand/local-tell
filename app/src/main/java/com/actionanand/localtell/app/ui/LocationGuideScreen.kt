package com.actionanand.localtell.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.actionanand.localtell.app.R

private data class LocationFaqItem(
    @StringRes val question: Int,
    @StringRes val answer: Int,
    @StringRes val keywords: Int,
)

private data class LocationFaqContent(
    val id: Int,
    val question: String,
    val answer: String,
    val keywords: String,
)

private val locationGuideFaqs = listOf(
    LocationFaqItem(R.string.location_guide_faq_1_question, R.string.location_guide_faq_1_answer, R.string.location_guide_faq_1_keywords),
    LocationFaqItem(R.string.location_guide_faq_2_question, R.string.location_guide_faq_2_answer, R.string.location_guide_faq_2_keywords),
    LocationFaqItem(R.string.location_guide_faq_3_question, R.string.location_guide_faq_3_answer, R.string.location_guide_faq_3_keywords),
    LocationFaqItem(R.string.location_guide_faq_4_question, R.string.location_guide_faq_4_answer, R.string.location_guide_faq_4_keywords),
    LocationFaqItem(R.string.location_guide_faq_5_question, R.string.location_guide_faq_5_answer, R.string.location_guide_faq_5_keywords),
    LocationFaqItem(R.string.location_guide_faq_6_question, R.string.location_guide_faq_6_answer, R.string.location_guide_faq_6_keywords),
    LocationFaqItem(R.string.location_guide_faq_7_question, R.string.location_guide_faq_7_answer, R.string.location_guide_faq_7_keywords),
    LocationFaqItem(R.string.location_guide_faq_8_question, R.string.location_guide_faq_8_answer, R.string.location_guide_faq_8_keywords),
    LocationFaqItem(R.string.location_guide_faq_9_question, R.string.location_guide_faq_9_answer, R.string.location_guide_faq_9_keywords),
)

@Composable
internal fun LocationGuideScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    val localizedFaqs = locationGuideFaqs.map { faq ->
        LocationFaqContent(
            id = faq.question,
            question = stringResource(faq.question),
            answer = stringResource(faq.answer),
            keywords = stringResource(faq.keywords),
        )
    }
    val visibleFaqs = localizedFaqs.filter { faq ->
        matchesLocationGuideQuery(query, faq.question, faq.answer, faq.keywords)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.location_guide_page_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.location_guide_page_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Text(stringResource(R.string.location_guide_intro), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.location_guide_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.offline_clear_search)) } }
                } else null,
            )
        }
        if (visibleFaqs.isEmpty()) {
            item { Text(stringResource(R.string.location_guide_no_results), style = MaterialTheme.typography.bodyMedium) }
        }
        items(visibleFaqs, key = { faq -> faq.id }) { faq ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(faq.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(faq.answer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.location_guide_privacy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.location_guide_privacy_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}