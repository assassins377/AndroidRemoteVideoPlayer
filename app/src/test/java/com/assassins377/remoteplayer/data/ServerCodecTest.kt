package com.assassins377.remoteplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCodecTest {
    /** Fake "encryption" so we can verify the password never hits JSON in plain text. */
    private val fake = object : SecretStore {
        override fun encrypt(plain: String) = plain.reversed() + "#enc"
        override fun decrypt(cipherText: String) = cipherText.removeSuffix("#enc").reversed()
    }

    private val server = ServerConfig("1", "NAS", ServerType.SMB, "192.168.1.5", "video", "user", "s3cretPass", "WG")

    @Test fun roundTripRestoresPassword() {
        val codec = ServerCodec(fake)
        assertEquals(listOf(server), codec.decode(codec.encode(listOf(server))))
    }

    @Test fun plainPasswordNotInStoredJson() {
        val raw = ServerCodec(fake).encode(listOf(server))
        assertFalse(raw.contains("s3cretPass"))
        assertTrue(raw.contains("192.168.1.5"))
    }

    @Test fun corruptOrEmptyInputGivesEmptyList() {
        val codec = ServerCodec(fake)
        assertEquals(emptyList<ServerConfig>(), codec.decode(null))
        assertEquals(emptyList<ServerConfig>(), codec.decode("{not json"))
    }

    @Test fun undecryptableSecretYieldsBlankPassword() {
        val broken = object : SecretStore {
            override fun encrypt(plain: String) = "x"
            override fun decrypt(cipherText: String): String = throw IllegalStateException("key lost")
        }
        val codec = ServerCodec(broken)
        assertEquals("", codec.decode(codec.encode(listOf(server))).single().password)
    }
}
