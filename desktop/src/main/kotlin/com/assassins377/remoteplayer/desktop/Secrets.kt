package com.assassins377.remoteplayer.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Password storage: Windows Credential Manager (DPAPI file fallback); on Linux Secret Service via `secret-tool`, else an AES-GCM file. */
interface Secrets {
    val backend: String
    fun get(id: String): String?
    fun put(id: String, value: String)
    fun remove(id: String)

    companion object {
        /** `REMOTEPLAYER_SECRETS=file` forces the file backend. */
        fun best(configDir: File, env: Map<String, String> = System.getenv()): Secrets = when {
            env["REMOTEPLAYER_SECRETS"] == "file" -> FileSecrets(File(configDir, "secrets.properties"), File(configDir, "secret.key"))
            Os.isWindows -> if (WinCredSecrets.available()) WinCredSecrets() else DpapiSecrets(File(configDir, "secrets.dpapi.properties"))
            SecretToolSecrets.available() -> SecretToolSecrets()
            else -> FileSecrets(File(configDir, "secrets.properties"), File(configDir, "secret.key"))
        }
    }
}

/** libsecret through the `secret-tool` CLI (package libsecret-tools); attributes: app=remoteplayer id=<id>. */
class SecretToolSecrets : Secrets {
    override val backend = "secret-service"
    private fun run(input: String?, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder("secret-tool", *args).redirectErrorStream(false).start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "" }
        return p.exitValue() to out
    }
    override fun get(id: String) = runCatching { run(null, "lookup", "app", "remoteplayer", "id", id).takeIf { it.first == 0 }?.second }.getOrNull()
    override fun put(id: String, value: String) {
        if (value.isEmpty()) { remove(id); return }
        val (code, _) = run(value, "store", "--label=Remote Video Player: $id", "app", "remoteplayer", "id", id)
        if (code != 0) error("secret-tool store failed")
    }
    override fun remove(id: String) { runCatching { run(null, "clear", "app", "remoteplayer", "id", id) } }

    companion object {
        /** secret-tool installed and a Secret Service reachable on the session bus (a lookup of a missing item exits 1 quietly). */
        fun available(): Boolean = runCatching {
            val p = ProcessBuilder("secret-tool", "lookup", "app", "remoteplayer", "id", "__probe__").start()
            p.outputStream.close()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() in 0..1 && err.isBlank()
        }.getOrDefault(false)
    }
}

/**
 * Fallback: AES-256-GCM values in secrets.properties, key in secret.key (both mode 600). Protects against casual
 * reading/backups of the config, not against someone with access to your user account.
 */
class FileSecrets(private val file: File, private val keyFile: File) : Secrets {
    override val backend = "encrypted-file"
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    private val key: SecretKeySpec by lazy {
        if (!keyFile.exists()) {
            keyFile.parentFile.mkdirs()
            keyFile.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) }); restrict(keyFile)
        }
        SecretKeySpec(keyFile.readBytes(), "AES")
    }
    private fun restrict(f: File) = runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }

    @Synchronized override fun get(id: String): String? = props.getProperty(id)?.let { runCatching { decrypt(it) }.getOrNull() }
    @Synchronized override fun put(id: String, value: String) { if (value.isEmpty()) props.remove(id) else props.setProperty(id, encrypt(value)); save() }
    @Synchronized override fun remove(id: String) { props.remove(id); save() }
    private fun save() { file.parentFile.mkdirs(); file.outputStream().use { props.store(it, "Remote Video Player secrets (AES-GCM)") }; restrict(file) }

    fun encrypt(s: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(s.toByteArray()))
    }
    fun decrypt(s: String): String {
        val b = Base64.getDecoder().decode(s)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
