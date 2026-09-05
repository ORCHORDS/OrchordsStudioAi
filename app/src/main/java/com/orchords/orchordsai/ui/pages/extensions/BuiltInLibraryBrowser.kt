package com.orchords.orchordsai.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orchords.orchordsai.R
import com.orchords.orchordsai.data.extensions.BuiltInLibrary
import com.orchords.orchordsai.data.extensions.LibraryContentKind
import com.orchords.orchordsai.data.extensions.filterLibraryPreview
import com.orchords.orchordsai.data.extensions.libraryPreviewItems

/** Read-only built-in definitions. Existing editors remain the owners of installed user copies. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuiltInLibraryBrowser(onDismiss: () -> Unit) {
    val catalogItems = remember { libraryPreviewItems(BuiltInLibrary.catalog) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<LibraryContentKind?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val visible = remember(catalogItems, query, kind) { filterLibraryPreview(catalogItems, query, kind) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.library_browser_title), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.library_browser_notice), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it.take(256); expandedId = null },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.library_browser_search)) },
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = kind == null, onClick = { kind = null; expandedId = null },
                                    label = { Text(stringResource(R.string.library_browser_all)) })
                                LibraryContentKind.entries.forEach { option ->
                                    val label = when (option) {
                                        LibraryContentKind.MODE -> R.string.library_browser_modes
                                        LibraryContentKind.LOREBOOK -> R.string.library_browser_lorebooks
                                        LibraryContentKind.SKILL -> R.string.library_browser_skills
                                    }
                                    FilterChip(selected = kind == option, onClick = { kind = option; expandedId = null },
                                        label = { Text(stringResource(label)) })
                                }
                            }
                            Text(stringResource(R.string.library_browser_count, visible.size), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (visible.isEmpty()) item { Text(stringResource(R.string.library_browser_empty)) }
                    items(visible, key = { it.id }) { item ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text(item.summary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { expandedId = if (expandedId == item.id) null else item.id }) {
                                    Text(stringResource(if (expandedId == item.id) R.string.library_browser_hide else R.string.library_browser_read))
                                }
                                if (expandedId == item.id) SelectionContainer {
                                    Text(item.body, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_browser_close)) }
            }
        }
    }
}
