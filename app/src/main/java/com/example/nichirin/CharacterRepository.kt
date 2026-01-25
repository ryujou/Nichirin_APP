package com.example.nichirin

import android.content.Context
import org.json.JSONObject

data class CharacterColor(
    val name: String,
    val band: String,
    val hex: String,
    val image: String
)

object CharacterRepository {
    fun loadCharacters(context: Context): List<CharacterColor> {
        return try {
            context.assets.open("bangdream_avatars.json").bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val array = json.optJSONArray("items") ?: return emptyList()
                List(array.length()) { index ->
                    val item = array.optJSONObject(index)
                    val name = item?.optString("name", "")?.trim().orEmpty()
                    if (name.isBlank()) return@List null
                    CharacterColor(
                        name = name,
                        band = item?.optString("band", "")?.trim().orEmpty(),
                        hex = normalizeHex(item?.optString("color", "")) ?: "#FF66AA",
                        image = item?.optString("image", "")?.trim().orEmpty()
                    )
                }.filterNotNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun resolveImagePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://")) {
            return trimmed
        }
        val cleaned = trimmed.removePrefix("/").removePrefix("assets/")
        return "file:///android_asset/$cleaned"
    }

    private fun normalizeHex(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isBlank()) return null
        val value = if (raw.startsWith("#")) raw.substring(1) else raw
        if (!value.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return "#" + value.uppercase()
    }
}
