package com.kreativesolutions.tapback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class HowToPage(val title: Int, val body: Int)

private val HOW_TO_PAGES = listOf(
    HowToPage(R.string.howto_1_title, R.string.howto_1_body),
    HowToPage(R.string.howto_2_title, R.string.howto_2_body),
    HowToPage(R.string.howto_3_title, R.string.howto_3_body),
    HowToPage(R.string.howto_4_title, R.string.howto_4_body),
    HowToPage(R.string.howto_5_title, R.string.howto_5_body),
    HowToPage(R.string.howto_6_title, R.string.howto_6_body)
)

@Composable
fun HowToWalkthrough(
    onFinished: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    val last = page == HOW_TO_PAGES.lastIndex
    val current = HOW_TO_PAGES[page]
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.howto_step, page + 1, HOW_TO_PAGES.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!last) {
                    TextButton(onClick = onFinished) {
                        Text(stringResource(R.string.howto_skip))
                    }
                }
            }
            Text(
                text = stringResource(current.title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(current.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (page > 0) Arrangement.SpaceBetween else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page > 0) {
                    TextButton(onClick = { page -= 1 }) {
                        Text(stringResource(R.string.howto_back))
                    }
                }
                Button(onClick = {
                    if (last) onFinished() else page += 1
                }) {
                    Text(
                        if (last) stringResource(R.string.howto_done)
                        else stringResource(R.string.howto_next)
                    )
                }
            }
        }
    }
}
