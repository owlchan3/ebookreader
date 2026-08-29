package com.ebookreader.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ebookreader.domain.model.Book

@Composable
fun AddRelatedBookDialog(
    allBooks: List<Book>,
    currentBookId: Long,
    relatedBookIds: List<Long>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }

    val excludeIds = remember(relatedBookIds) { (relatedBookIds + currentBookId).toSet() }

    val filteredBooks = remember(allBooks, searchQuery, excludeIds) {
        val q = searchQuery.trim().lowercase()
        allBooks.filter { book ->
            book.id !in excludeIds &&
                (q.isEmpty() || book.title.lowercase().contains(q) || book.author.lowercase().contains(q))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加相关书籍") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索书名或作者") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (filteredBooks.isEmpty()) {
                    Text(
                        "没有找到可添加的书籍",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredBooks, key = { it.id }) { book ->
                            val isSelected = book.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isSelected) selectedIds.remove(book.id)
                                        else selectedIds.add(book.id)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.size(width = 36.dp, height = 52.dp).clip(RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (book.coverPath != null)
                                        AsyncImage(model = book.coverPath, contentDescription = null)
                                    else
                                        Icon(
                                            Icons.AutoMirrored.Filled.MenuBook, null,
                                            Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        book.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (book.author.isNotEmpty())
                                        Text(
                                            book.author,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        if (isSelected) selectedIds.remove(book.id)
                                        else selectedIds.add(book.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(selectedIds.toList())
                    selectedIds.clear()
                },
                enabled = selectedIds.isNotEmpty(),
            ) {
                Text(if (selectedIds.isNotEmpty()) "添加 (${selectedIds.size})" else "添加")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                selectedIds.clear()
                onDismiss()
            }) { Text("取消") }
        },
    )
}
