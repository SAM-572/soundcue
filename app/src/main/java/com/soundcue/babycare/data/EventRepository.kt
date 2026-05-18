package com.soundcue.babycare.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class EventLogEntry(
    val event: String,           // baby_cry_general, baby_laugh, feeding, diaper, sleep_start ...
    val subtype: String? = null, // 배고픔, 불편, ...
    val confidence: Float = 1f,
    val source: String = "auto", // auto / manual / gesture
    val timestampMs: Long = System.currentTimeMillis()
) {
    val localDate: LocalDate get() =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val hour: Int get() =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).hour
}

data class DailySummary(
    val date: LocalDate,
    val cryCount: Int = 0,
    val laughCount: Int = 0,
    val coughCount: Int = 0,
    val burpCount: Int = 0,
    val fartCount: Int = 0,
    val firstWordCount: Int = 0,
    val feedingCount: Int = 0,
    val diaperCount: Int = 0,
    val sleepCount: Int = 0,
    val cryBySubtype: Map<String, Int> = emptyMap(),
    val peakCryHour: Int? = null,
    val entries: List<EventLogEntry> = emptyList()
)

class EventRepository(context: Context) {

    private val file = File(context.filesDir, "event_log.json")
    private val entries = mutableListOf<EventLogEntry>()

    private val _todaySummary = MutableStateFlow(DailySummary(LocalDate.now()))
    val todaySummary: StateFlow<DailySummary> = _todaySummary.asStateFlow()

    init { load() }

    @Synchronized
    fun log(entry: EventLogEntry) {
        entries.add(entry)
        if (entries.size > 5000) entries.removeAt(0) // cap
        save()
        recomputeToday()
    }

    fun logManual(event: String) {
        log(EventLogEntry(event = event, source = "manual"))
    }

    @Synchronized
    fun todayEntries(): List<EventLogEntry> {
        val today = LocalDate.now()
        return entries.filter { it.localDate == today }
    }

    @Synchronized
    fun entriesForDate(date: LocalDate): List<EventLogEntry> =
        entries.filter { it.localDate == date }

    @Synchronized
    fun weekEntries(): List<EventLogEntry> {
        val weekAgo = LocalDate.now().minusDays(7)
        return entries.filter { it.localDate >= weekAgo }
    }

    private fun recomputeToday() {
        val today = LocalDate.now()
        val todayList = entries.filter { it.localDate == today }

        val cries = todayList.filter { it.event.startsWith("baby_cry") }
        val crySubtypes = cries.groupBy { it.subtype ?: "기타" }
            .mapValues { it.value.size }

        val cryHours = cries.groupBy { it.hour }
        val peakHour = cryHours.maxByOrNull { it.value.size }?.key

        _todaySummary.value = DailySummary(
            date = today,
            cryCount = cries.size,
            laughCount = todayList.count { it.event.contains("laugh") },
            coughCount = todayList.count { it.event.contains("cough") },
            burpCount = todayList.count { it.event.contains("burp") },
            fartCount = todayList.count { it.event.contains("fart") },
            firstWordCount = todayList.count { it.event.contains("first_word") || it.event.contains("babble") },
            feedingCount = todayList.count { it.event == "feeding" },
            diaperCount = todayList.count { it.event == "diaper" },
            sleepCount = todayList.count { it.event.startsWith("sleep") },
            cryBySubtype = crySubtypes,
            peakCryHour = peakHour,
            entries = todayList
        )
    }

    @Synchronized
    private fun save() {
        runCatching {
            val arr = JSONArray()
            for (e in entries.takeLast(5000)) {
                arr.put(JSONObject().apply {
                    put("event", e.event)
                    put("subtype", e.subtype ?: "")
                    put("confidence", e.confidence.toDouble())
                    put("source", e.source)
                    put("ts", e.timestampMs)
                })
            }
            file.writeText(arr.toString())
        }.onFailure { Log.w("EventRepo", "save failed: ${it.message}") }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(EventLogEntry(
                    event = o.getString("event"),
                    subtype = o.optString("subtype").takeIf { it.isNotBlank() },
                    confidence = o.optDouble("confidence", 1.0).toFloat(),
                    source = o.optString("source", "auto"),
                    timestampMs = o.getLong("ts")
                ))
            }
            recomputeToday()
        }.onFailure { Log.w("EventRepo", "load failed: ${it.message}") }
    }
}
