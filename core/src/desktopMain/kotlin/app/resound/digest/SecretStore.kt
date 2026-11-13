package app.resound.digest

import app.resound.data.KeyValueStore
import app.resound.platform.MasterKeyProvider

/** Desktop adapter over the shared encrypted platform store. */
class SecretStore(store: KeyValueStore, alias: String, keys: MasterKeyProvider) {
    private val delegate = app.resound.platform.SecretStore(store, alias, keys)

    fun read(name: String): String = delegate.read(name)
    fun write(name: String, value: String) = delegate.write(name, value)
    fun remove(name: String) = delegate.remove(name)
    fun has(name: String): Boolean = delegate.has(name)
}
