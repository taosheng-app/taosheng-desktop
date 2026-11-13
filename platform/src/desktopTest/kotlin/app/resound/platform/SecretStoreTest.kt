package app.resound.platform
import app.resound.data.AppDirs
import app.resound.data.KeyValueStore
import app.resound.data.FileKeyValueStore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretStoreTest {
    private fun dir() = kotlin.io.path.createTempDirectory("secret").toFile()
    private fun store(d: File = dir(), kv: KeyValueStore = FileKeyValueStore(File(d, "s.json"))) =
        SecretStore(kv, "doubao", FileMasterKeyProvider(File(d, "keys"))) to kv

    @Test
    fun round_trip_and_ciphertext_hides_plaintext() {
        val (secrets, kv) = store()
        secrets.write("key", "sk-很秘密-12345")
        assertEquals("sk-很秘密-12345", secrets.read("key"))
        assertTrue(secrets.has("key"))
        val raw = kv.getString("key", null)!!
        assertFalse(raw.contains("sk-") || raw.contains("12345"), "密文里不应出现明文")
        secrets.remove("key")
        assertEquals("", secrets.read("key"))
        assertFalse(secrets.has("key"))
    }

    @Test
    fun same_value_encrypts_differently_each_time_and_tampering_is_rejected() {
        val d = dir()
        val (secrets, kv) = store(d)
        secrets.write("a", "same"); val first = kv.getString("a", null)
        secrets.write("a", "same"); val second = kv.getString("a", null)
        assertTrue(first != second, "IV 应每次随机")
        // 篡改密文：GCM 校验必须失败，而不是返回乱码
        val bytes = java.util.Base64.getDecoder().decode(second)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        kv.edit().putString("a", java.util.Base64.getEncoder().encodeToString(bytes)).commit()
        assertFailsWith<Exception> { secrets.read("a") }
        // 换了主密钥（模拟钥匙串被清）同样读不出，不会静默返回空
        val (other, _) = SecretStore(kv, "doubao", FileMasterKeyProvider(File(d, "keys2"))) to kv
        secrets.write("b", "x")
        assertFailsWith<Exception> { other.read("b") }
    }

    @Test
    fun keyring_failure_falls_back_to_file_once() {
        val d = dir()
        var reports = 0
        val broken = object : KeyringMasterKeyProvider.KeyringAccess {
            override fun get(account: String): String? = error("没有 Secret Service")
            override fun set(account: String, value: String) = error("没有 Secret Service")
        }
        val provider = KeyringMasterKeyProvider(File(d, "fallback"), keyring = broken, onFallback = { reports++ })
        val k1 = provider.getOrCreate("x"); val k2 = provider.getOrCreate("x")
        assertEquals(32, k1.size)
        assertTrue(k1.contentEquals(k2), "回退文件密钥应稳定")
        assertEquals(1, reports, "回退只报告一次")
    }
}