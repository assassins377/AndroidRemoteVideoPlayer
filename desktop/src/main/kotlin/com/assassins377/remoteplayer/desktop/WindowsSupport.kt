package com.assassins377.remoteplayer.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.util.Base64
import java.util.Properties

object Os {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}

/** Windows Credential Manager (generic credentials "RemoteVideoPlayer/<id>") through advapi32 CredReadW/CredWriteW. */
class WinCredSecrets : Secrets {
    override val backend = "windows-credential-manager"

    @Suppress("PropertyName", "unused")
    @Structure.FieldOrder("Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize", "CredentialBlob",
        "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName")
    open class CREDENTIAL(p: Pointer? = null) : Structure(p) {
        @JvmField var Flags = 0
        @JvmField var Type = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWritten = 0L
        @JvmField var CredentialBlobSize = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist = 0
        @JvmField var AttributeCount = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null
        init { if (p != null) read() }
    }

    interface Api : Library {
        fun CredReadW(target: WString, type: Int, flags: Int, out: PointerByReference): Boolean
        fun CredWriteW(cred: CREDENTIAL, flags: Int): Boolean
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
        fun CredFree(p: Pointer)
    }

    private val api: Api by lazy { Native.load("Advapi32", Api::class.java, W32APIOptions.UNICODE_OPTIONS) }
    private fun target(id: String) = WString("RemoteVideoPlayer/$id")

    override fun get(id: String): String? {
        val ref = PointerByReference()
        if (!api.CredReadW(target(id), CRED_TYPE_GENERIC, 0, ref)) return null
        return try {
            val c = CREDENTIAL(ref.value)
            c.CredentialBlob?.getByteArray(0, c.CredentialBlobSize)?.toString(Charsets.UTF_8)
        } finally { api.CredFree(ref.value) }
    }

    override fun put(id: String, value: String) {
        if (value.isEmpty()) { remove(id); return }
        val bytes = value.toByteArray(Charsets.UTF_8)
        val mem = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val c = CREDENTIAL().apply {
            Type = CRED_TYPE_GENERIC; TargetName = target(id); UserName = WString("remoteplayer")
            CredentialBlobSize = bytes.size; CredentialBlob = mem; Persist = CRED_PERSIST_LOCAL_MACHINE
        }
        check(api.CredWriteW(c, 0)) { "CredWrite failed: ${Native.getLastError()}" }
    }

    override fun remove(id: String) { runCatching { api.CredDeleteW(target(id), CRED_TYPE_GENERIC, 0) } }

    companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        fun available(): Boolean = Os.isWindows && runCatching { WinCredSecrets().get("__probe__"); true }.getOrDefault(false)
    }
}

/** Fallback on Windows: values encrypted with DPAPI (current user) in a properties file. */
class DpapiSecrets(private val file: File) : Secrets {
    override val backend = "dpapi-file"
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    @Synchronized override fun get(id: String) = props.getProperty(id)?.let {
        runCatching { Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(it)).toString(Charsets.UTF_8) }.getOrNull()
    }
    @Synchronized override fun put(id: String, value: String) {
        if (value.isEmpty()) props.remove(id) else props.setProperty(id, Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))))
        save()
    }
    @Synchronized override fun remove(id: String) { props.remove(id); save() }
    private fun save() { file.parentFile.mkdirs(); file.outputStream().use { props.store(it, "Remote Video Player secrets (DPAPI)") } }
}

/** Finds an installed 64-bit VLC on Windows (registry, then default folders). */
object WinVlc {
    fun candidates(env: Map<String, String> = System.getenv()): List<File> {
        val reg = listOf("SOFTWARE\\VideoLAN\\VLC", "SOFTWARE\\WOW6432Node\\VideoLAN\\VLC").mapNotNull { key ->
            runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "InstallDir") }.getOrNull()
        }
        val defaults = listOfNotNull(env["ProgramFiles"], env["ProgramW6432"], "C:\\Program Files").map { "$it\\VideoLAN\\VLC" }
        return (reg + defaults).map(::File).distinct()
    }

    /** Directory containing libvlc.dll, or null. */
    fun find(env: Map<String, String> = System.getenv()): File? = candidates(env).firstOrNull { File(it, "libvlc.dll").isFile }

    /** Lets JNA load libvlc from that folder (vlcj's own discovery covers the same places; this is a fallback). */
    fun register(dir: File) {
        com.sun.jna.NativeLibrary.addSearchPath("libvlc", dir.absolutePath)
        com.sun.jna.NativeLibrary.addSearchPath("libvlccore", dir.absolutePath)
    }
}
