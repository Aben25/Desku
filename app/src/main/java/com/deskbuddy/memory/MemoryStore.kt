package com.deskbuddy.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Memory(val id: String, val text: String, val createdAt: Long)

/**
 * The only thing Desk Buddy keeps between conversations: details the user explicitly asked it
 * to remember. One small JSON file in the app's private storage. Conversations themselves are
 * never written to disk.
 */
class MemoryStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val _memories = MutableStateFlow(load())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    fun all(): List<Memory> = _memories.value

    fun add(text: String): Memory = synchronized(lock) {
        val clean = text.trim().take(MAX_LENGTH)
        require(clean.isNotEmpty()) { "empty memory" }
        val next = (_memories.value.mapNotNull { it.id.removePrefix("m").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val memory = Memory("m$next", clean, clock())
        save(_memories.value + memory)
        memory
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val remaining = _memories.value.filterNot { it.id == id }
        if (remaining.size == _memories.value.size) return false
        save(remaining)
        true
    }

    fun clear() = synchronized(lock) { save(emptyList()) }

    private fun save(list: List<Memory>) {
        val json = JSONArray()
        list.forEach { json.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt)) }
        // Write-then-rename so a power cut mid-write can't leave a half file behind.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
        _memories.value = list
    }

    private fun load(): List<Memory> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                Memory(o.getString("id"), o.getString("text"), o.optLong("createdAt"))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val MAX_LENGTH = 500
    }
}
