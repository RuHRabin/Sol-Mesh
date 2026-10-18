package com.example.p2p

import android.content.Context

/** Keeps the contact lists in SharedPreferences. */
class SharedPrefsContactStore(context: Context) : ContactStore {
    private val prefs = context.applicationContext.getSharedPreferences("sol_mesh_contacts", Context.MODE_PRIVATE)

    override fun load(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    override fun save(key: String, values: Set<String>) {
        prefs.edit().putStringSet(key, HashSet(values)).apply()
    }
}
