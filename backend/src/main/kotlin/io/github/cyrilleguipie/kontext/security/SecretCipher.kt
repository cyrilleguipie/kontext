package io.github.cyrilleguipie.kontext.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedSecret(val ciphertext: ByteArray, val nonce: ByteArray)

class SecretCipher(base64Key: String) {
    private val key = SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES")
    private val random = SecureRandom()

    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedSecret {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return EncryptedSecret(cipher.doFinal(plaintext), nonce)
    }

    fun decrypt(
        encrypted: EncryptedSecret,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypted.nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(encrypted.ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
