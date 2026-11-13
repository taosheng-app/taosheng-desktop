package app.resound.platform

import app.resound.data.*

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 主密钥来源：优先系统钥匙串，失败回退到仅当前用户可读的文件。对应安卓 Keystore 里的 AES 密钥。 */
interface MasterKeyProvider {
    /** 取出 [alias] 对应的 32 字节密钥，没有则生成并保存。 */
    fun getOrCreate(alias: String): ByteArray
}

/**
 * 用系统钥匙串保存主密钥（Windows 凭据管理器 / macOS 钥匙串 / Linux Secret Service）。
 * 钥匙串不可用（无 Secret Service、无桌面会话等）时回退到 [fallbackDir] 下的密钥文件，并只记录一次。
 */
class KeyringMasterKeyProvider(
    private val fallbackDir: File,
    private val service: String = "app.resound.taosheng",
    private val onFallback: (Throwable?) -> Unit = {},
    /** 测试注入：返回 null 表示钥匙串不可用。 */
    private val keyring: KeyringAccess? = defaultKeyring(),
) : MasterKeyProvider {

    interface KeyringAccess {
        fun get(account: String): String?
        fun set(account: String, value: String)
    }

    private var reported = false

    override fun getOrCreate(alias: String): ByteArray = synchronized(this) {
        keyring?.let { k ->
            try {
                k.get(alias)?.let { return Base64.getDecoder().decode(it) }
                val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
                k.set(alias, Base64.getEncoder().encodeToString(fresh))
                return fresh
            } catch (e: Throwable) {
                report(e)
            }
        } ?: report(null)
        FileMasterKeyProvider(fallbackDir).getOrCreate(alias)
    }

    private fun report(e: Throwable?) {
        if (!reported) { reported = true; onFallback(e) }
    }

    companion object {
        /** java-keyring 在无后端时会抛异常；包成可空访问器，构造失败即视为不可用。 */
        fun defaultKeyring(): KeyringAccess? = try {
            val k = com.github.javakeyring.Keyring.create()
            object : KeyringAccess {
                override fun get(account: String): String? = try {
                    k.getPassword("app.resound.taosheng", account)
                } catch (e: com.github.javakeyring.PasswordAccessException) { null }
                override fun set(account: String, value: String) = k.setPassword("app.resound.taosheng", account, value)
            }
        } catch (e: Throwable) { null }
    }
}

/** 文件密钥：`<dir>/<alias>.key`，Base64；尽力限制为仅当前用户可读写（POSIX 权限 600，Windows 用 ACL 属性）。 */
class FileMasterKeyProvider(private val dir: File) : MasterKeyProvider {
    override fun getOrCreate(alias: String): ByteArray = synchronized(this) {
        require(alias.matches(Regex("[A-Za-z0-9._-]+"))) { "非法的密钥别名" }
        dir.mkdirs()
        val file = File(dir, "$alias.key")
        if (file.isFile) return Base64.getDecoder().decode(file.readText().trim())
        val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
        file.writeText(Base64.getEncoder().encodeToString(fresh))
        restrict(file)
        fresh
    }

    private fun restrict(file: File) {
        runCatching {
            val path = file.toPath()
            val posix = java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
            if (posix != null) {
                posix.setPermissions(setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ))
            } else {
                val acl = java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView::class.java)
                val owner = java.nio.file.Files.getOwner(path)
                val entry = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(java.nio.file.attribute.AclEntryPermission.values().toSet()).build()
                acl?.acl = listOf(entry)
            }
        }
    }
}

/**
 * 密钥存储，签名与安卓 `digest/SecretStore` 一致：`read/write/remove/has`。
 * 格式：Base64( 12 字节 IV + AES-256-GCM 密文 )，存入 [store]；任何日志、异常都不含明文或密钥。
 */
class SecretStore(
    private val store: KeyValueStore,
    private val alias: String,
    private val keys: MasterKeyProvider,
) {
    private val random = SecureRandom()

    private fun secret() = SecretKeySpec(keys.getOrCreate(alias), "AES")

    @Synchronized
    fun read(name: String): String {
        val encoded = store.getString(name, null) ?: return ""
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > 12) { "密钥数据损坏" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }

    @Synchronized
    fun write(name: String, value: String) {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        val encrypted = iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(store.edit().putString(name, Base64.getEncoder().encodeToString(encrypted)).commit()) { "密钥保存失败，请重试" }
    }

    @Synchronized
    fun remove(name: String) {
        check(store.edit().remove(name).commit()) { "密钥清除失败，请重试" }
    }

    fun has(name: String) = store.contains(name)
}