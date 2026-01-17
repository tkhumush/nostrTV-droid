package com.nostrtv.android.data.nostr

import android.util.Log
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic utilities for Nostr protocol.
 * Implements NIP-04 encryption and secp256k1 operations.
 */
object NostrCrypto {
    private const val TAG = "NostrCrypto"
    private val secp256k1 = Secp256k1.get()
    private val secureRandom = SecureRandom()

    /**
     * Generate a new secp256k1 keypair.
     * Returns (privateKey, publicKey) as hex strings.
     */
    fun generateKeyPair(): KeyPair {
        val privateKeyBytes = ByteArray(32)
        secureRandom.nextBytes(privateKeyBytes)

        // Ensure valid private key
        while (!secp256k1.secKeyVerify(privateKeyBytes)) {
            secureRandom.nextBytes(privateKeyBytes)
        }

        val publicKeyBytes = secp256k1.pubKeyCompress(secp256k1.pubkeyCreate(privateKeyBytes))
        // Remove the 02/03 prefix for x-only pubkey (32 bytes)
        val xOnlyPubKey = publicKeyBytes.drop(1).toByteArray()

        return KeyPair(
            privateKey = privateKeyBytes.toHex(),
            publicKey = xOnlyPubKey.toHex()
        )
    }

    /**
     * Get public key from private key.
     */
    fun getPublicKey(privateKeyHex: String): String {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val publicKeyBytes = secp256k1.pubKeyCompress(secp256k1.pubkeyCreate(privateKeyBytes))
        // Remove the 02/03 prefix for x-only pubkey
        return publicKeyBytes.drop(1).toByteArray().toHex()
    }

    /**
     * NIP-04: Encrypt a message for a recipient.
     * Uses ECDH shared secret + AES-256-CBC.
     */
    fun encryptNip04(
        plaintext: String,
        senderPrivateKeyHex: String,
        recipientPubKeyHex: String
    ): String {
        try {
            val sharedSecret = computeSharedSecret(senderPrivateKeyHex, recipientPubKeyHex)

            // Generate random IV
            val iv = ByteArray(16)
            secureRandom.nextBytes(iv)

            // AES-256-CBC encryption
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(sharedSecret, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Format: base64(encrypted)?iv=base64(iv)
            val encryptedBase64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
            val ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)

            return "$encryptedBase64?iv=$ivBase64"
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            throw e
        }
    }

    /**
     * NIP-04: Decrypt a message from a sender.
     */
    fun decryptNip04(
        ciphertext: String,
        receiverPrivateKeyHex: String,
        senderPubKeyHex: String
    ): String {
        try {
            val sharedSecret = computeSharedSecret(receiverPrivateKeyHex, senderPubKeyHex)

            // Parse format: base64(encrypted)?iv=base64(iv)
            val parts = ciphertext.split("?iv=")
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid ciphertext format")
            }

            val encryptedBytes = android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT)
            val ivBytes = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)

            // AES-256-CBC decryption
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(sharedSecret, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivBytes))
            val decrypted = cipher.doFinal(encryptedBytes)

            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            throw e
        }
    }

    /**
     * Compute ECDH shared secret for NIP-04.
     */
    private fun computeSharedSecret(privateKeyHex: String, publicKeyHex: String): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()

        // Add 02 prefix to make it a compressed public key
        val publicKeyBytes = ("02" + publicKeyHex).hexToByteArray()

        // ECDH
        val sharedPoint = secp256k1.pubKeyTweakMul(publicKeyBytes, privateKeyBytes)

        // Take x-coordinate (first 32 bytes after prefix) and hash with SHA256
        val xCoord = sharedPoint.drop(1).take(32).toByteArray()
        return sha256(xCoord)
    }

    /**
     * Sign an event according to NIP-01.
     */
    fun signEvent(eventHash: ByteArray, privateKeyHex: String): String {
        val privateKeyBytes = privateKeyHex.hexToByteArray()

        // Schnorr signature
        val signature = secp256k1.signSchnorr(eventHash, privateKeyBytes, null)
        return signature.toHex()
    }

    /**
     * Compute event ID (hash) according to NIP-01.
     */
    fun computeEventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        val tagsJson = tags.joinToString(",") { tag ->
            "[" + tag.joinToString(",") { "\"$it\"" } + "]"
        }
        val serialized = "[0,\"$pubkey\",$createdAt,$kind,[$tagsJson],\"${escapeJson(content)}\"]"
        return sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * SHA-256 hash.
     */
    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    // Extension functions for hex conversion
    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

data class KeyPair(
    val privateKey: String,
    val publicKey: String
)
