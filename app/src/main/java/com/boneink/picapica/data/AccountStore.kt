package com.boneink.picapica.data

import android.content.Context
import com.boneink.picapica.network.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SourceAccount(val id: String, val source: SourceKind, val name: String = source.title)

/** Public account labels only. Credentials stay in each account's encrypted SessionStore. */
class AccountStore(context: Context, name: String) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val initial = runCatching {
        val a = JSONArray(prefs.getString("accounts", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val id = o.getString("id")
            if (!id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) null
            else SourceAccount(id, SourceKind.valueOf(o.getString("source")), o.getString("name"))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList()).let { rows ->
        val defaults = listOf(SourceAccount("pica", SourceKind.PICA), SourceAccount("xiangxiang", SourceKind.XIANGXIANG))
        defaults.filter { d -> rows.none { it.id == d.id } } + rows
    }
    private val rows = MutableStateFlow(initial)
    val accounts = rows.asStateFlow()
    private val selected = MutableStateFlow(initial.firstOrNull { it.id == prefs.getString("active", "pica") } ?: initial.first())
    val active = selected.asStateFlow()
    @Synchronized fun select(id: String) {
        val entry = rows.value.firstOrNull { it.id == id } ?: error("账号不存在")
        check(prefs.edit().putString("active", id).commit()) { "无法保存账号选择" }
        selected.value = entry
    }
    @Synchronized fun add(source: SourceKind): SourceAccount {
        val entry = SourceAccount(UUID.randomUUID().toString(), source)
        save(rows.value + entry)
        return entry
    }
    @Synchronized fun rename(id: String, label: String) {
        save(rows.value.map { if (it.id == id) it.copy(name = label.take(80).ifBlank { it.source.title }) else it })
        rows.value.firstOrNull { it.id == selected.value.id }?.let { selected.value = it }
    }
    private fun save(values: List<SourceAccount>) {
        val data = JSONArray(values.map { JSONObject().put("id", it.id).put("source", it.source.name).put("name", it.name) })
        check(prefs.edit().putString("accounts", data.toString()).commit()) { "无法保存账号" }
        rows.value = values
    }
}
