package com.wunderhand.app.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** The little the app remembers that is not a secret. */
interface Settings {
    /** The shop this person last chose, remembered across launches. */
    suspend fun chosenShopId(): String?
    suspend fun setChosenShopId(id: String?)

    /** Debug builds only: a dev server other than the one built in. */
    suspend fun serverOverride(): String?
    suspend fun setServerOverride(url: String?)
}

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "wunderhand")

class DataStoreSettings(private val context: Context) : Settings {
    private suspend fun read(key: Preferences.Key<String>) = context.store.data.first()[key]

    private suspend fun write(key: Preferences.Key<String>, value: String?) {
        context.store.edit { if (value == null) it.remove(key) else it[key] = value }
    }

    override suspend fun chosenShopId() = read(CHOSEN_SHOP)
    override suspend fun setChosenShopId(id: String?) = write(CHOSEN_SHOP, id)
    override suspend fun serverOverride() = read(SERVER)
    override suspend fun setServerOverride(url: String?) = write(SERVER, url)

    private companion object {
        val CHOSEN_SHOP = stringPreferencesKey("chosenShopId")
        val SERVER = stringPreferencesKey("serverOverride")
    }
}
