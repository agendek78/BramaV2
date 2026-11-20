package com.agendek.bramav2.store

import com.agendek.bramav2.domain.CommandOutcome
import com.agendek.bramav2.domain.GateCommand
import com.agendek.bramav2.domain.HistoryEntry
import com.agendek.bramav2.domain.HistoryStore
import com.agendek.bramav2.protocol.AckResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Historia komend (spec 4.1) w pliku JSON-lines: `{"t":…,"c":"OPEN"|"CLOSE","a":true|false,"o":"…","r":"…"|null}`.
 * Bez sekretów i surowych pakietów; uszkodzone linie są pomijane.
 */
class FileHistoryStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HistoryStore {

    private val cache = MutableStateFlow<List<HistoryEntry>?>(null)

    /** Pierwszy odczyt pliku pod blokadą i poza wątkiem kolektora (ekran historii zbiera na wątku głównym). */
    override val entries: Flow<List<HistoryEntry>> = cache.onStart { ensureLoaded() }.filterNotNull()

    private val lock = Mutex()

    override suspend fun append(entry: HistoryEntry) = locked {
        file.appendText(encode(entry) + "\n")
        cache.value = listOf(entry) + (cache.value ?: readNewestFirst().drop(1))
    }

    override suspend fun trim(keepLast: Int) = locked {
        val newestFirst = readNewestFirst()
        if (newestFirst.size > keepLast) {
            val kept = newestFirst.take(keepLast)
            atomicWrite(file, kept.reversed().joinToString("") { encode(it) + "\n" }.toByteArray(Charsets.UTF_8))
            cache.value = kept
        }
    }

    private suspend fun ensureLoaded() = locked {
        if (cache.value == null) cache.value = readNewestFirst()
    }

    private suspend fun locked(block: () -> Unit) = lock.withLock { withContext(io) { block() } }

    private fun readNewestFirst(): List<HistoryEntry> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull(::decode).reversed()

    private fun encode(e: HistoryEntry): String {
        val (c, a) = when (val cmd = e.command) {
            is GateCommand.Open -> "OPEN" to cmd.autoCloseOnDisconnect.toString()
            GateCommand.Close -> "CLOSE" to "null"
        }
        val r = e.ackResult?.let { "\"${it.name}\"" } ?: "null"
        return "{\"t\":${e.timeMillis},\"c\":\"$c\",\"a\":$a,\"o\":\"${e.outcome.name}\",\"r\":$r}"
    }

    private fun decode(line: String): HistoryEntry? = runCatching {
        val m = LINE.matchEntire(line.trim()) ?: return null
        val (t, c, a, o, r) = m.destructured
        val command = when (c) {
            "OPEN" -> GateCommand.Open(a.toBooleanStrict())
            "CLOSE" -> GateCommand.Close
            else -> return null
        }
        HistoryEntry(t.toLong(), command, CommandOutcome.valueOf(o), if (r == "null") null else AckResult.valueOf(r.trim('"')))
    }.getOrNull()

    private companion object {
        val LINE = Regex("""\{"t":(\d+),"c":"(OPEN|CLOSE)","a":(true|false|null),"o":"([A-Z_]+)","r":("[A-Z_]+"|null)\}""")
    }
}
