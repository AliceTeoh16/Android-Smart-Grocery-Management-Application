package com.xmum.smartgrocery.data.session

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    const val ITERATIONS = 600_000
    fun salt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
    fun hash(password: String, salt: String, iterations: Int = ITERATIONS): String {
        require(iterations >= ITERATIONS)
        val spec = PBEKeySpec(password.toCharArray(), salt.hexBytes(), iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded.toHex() }
        finally { spec.clearPassword() }
    }
    fun verify(password: String, salt: String, iterations: Int, expected: String): Boolean =
        expected.isNotEmpty() && MessageDigest.isEqual(hash(password, salt, iterations).hexBytes(), expected.hexBytes())
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
