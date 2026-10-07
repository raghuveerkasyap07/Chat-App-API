package com.demo.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

class TestContext(private val memoryPrefs: InMemorySharedPreferences = InMemorySharedPreferences()) : ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        return memoryPrefs
    }

    override fun getApplicationContext(): Context {
        return this
    }
}

class InMemorySharedPreferences : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class EditorImpl : SharedPreferences.Editor {
        private val temp = HashMap<String, Any?>()
        private val removeKeys = HashSet<String>()

        override fun putString(key: String?, value: String?) = apply { if (key != null) temp[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) temp[key] = values }
        override fun putInt(key: String?, value: Int) = apply { if (key != null) temp[key] = value }
        override fun putLong(key: String?, value: Long) = apply { if (key != null) temp[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { if (key != null) temp[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) temp[key] = value }
        override fun remove(key: String?) = apply { if (key != null) removeKeys.add(key) }
        override fun clear() = apply { map.clear() }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            removeKeys.forEach { map.remove(it) }
            temp.forEach { (k, v) -> if (v != null) map[k] = v else map.remove(k) }
        }
    }
}
