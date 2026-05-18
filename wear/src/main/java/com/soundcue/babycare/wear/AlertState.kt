package com.soundcue.babycare.wear

import kotlinx.coroutines.flow.MutableStateFlow

data class WatchAlert(
    val title: String,
    val watchText: String,
    val subtype: String? = null,
    val reasoning: String? = null,
    val severity: String = "LOW",
    val timestamp: Long = System.currentTimeMillis()
)

object AlertStore {
    val latest = MutableStateFlow<WatchAlert?>(null)
}
