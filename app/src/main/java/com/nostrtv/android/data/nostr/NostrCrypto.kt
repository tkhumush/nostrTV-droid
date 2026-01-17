package com.nostrtv.android.data.nostr

import android.util.Log
import fr.acinq.secp256k1.Secp256k1
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
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

    // ==================== NIP-44 Implementation ====================

    private const val NIP44_SALT = "nip44-v2"

    /**
     * NIP-44: Encrypt a message for a recipient.
     * Uses ECDH + HKDF + ChaCha20-Poly1305.
     */
    fun encryptNip44(
        plaintext: String,
        senderPrivateKeyHex: String,
        recipientPubKeyHex: String
    ): String {
        try {
            val conversationKey = computeNip44ConversationKey(senderPrivateKeyHex, recipientPubKeyHex)

            // Generate random 32-byte nonce
            val nonce = ByteArray(32)
            secureRandom.nextBytes(nonce)

            // Derive message keys using HKDF
            val (chachaKey, chachaNonce, hmacKey) = deriveMessageKeys(conversationKey, nonce)

            // Pad the plaintext
            val padded = padPlaintext(plaintext.toByteArray(Charsets.UTF_8))

            // Encrypt with ChaCha20-Poly1305
            val ciphertext = chacha20Poly1305Encrypt(padded, chachaKey, chachaNonce)

            // Construct payload: version (1) + nonce (32) + ciphertext (variable)
            val payload = ByteArray(1 + 32 + ciphertext.size)
            payload[0] = 2 // NIP-44 version 2
            System.arraycopy(nonce, 0, payload, 1, 32)
            System.arraycopy(ciphertext, 0, payload, 33, ciphertext.size)

            return android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "NIP-44 encryption failed", e)
            throw e
        }
    }

    /**
     * NIP-44: Decrypt a message from a sender.
     */
    fun decryptNip44(
        ciphertext: String,
        receiverPrivateKeyHex: String,
        senderPubKeyHex: String
    ): String {
        try {
            val payload = android.util.Base64.decode(ciphertext, android.util.Base64.DEFAULT)

            if (payload.size < 35) { // 1 + 32 + 2 minimum (version + nonce + min ciphertext)
                throw IllegalArgumentException("NIP-44 payload too short: ${payload.size}")
            }

            val version = payload[0].toInt()
            if (version != 2) {
                throw IllegalArgumentException("Unsupported NIP-44 version: $version")
            }

            val nonce = payload.sliceArray(1..32)
            val encryptedData = payload.sliceArray(33 until payload.size)

            Log.d(TAG, "NIP-44 decrypt: payload=${payload.size}, nonce=${nonce.size}, encrypted=${encryptedData.size}")

            val conversationKey = computeNip44ConversationKey(receiverPrivateKeyHex, senderPubKeyHex)

            // Derive message keys using HKDF
            val (chachaKey, chachaNonce, _) = deriveMessageKeys(conversationKey, nonce)

            // Decrypt with ChaCha20-Poly1305
            val padded = chacha20Poly1305Decrypt(encryptedData, chachaKey, chachaNonce)

            // Unpad the plaintext
            val plaintext = unpadPlaintext(padded)

            return String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "NIP-44 decryption failed", e)
            throw e
        }
    }

    /**
     * Compute NIP-44 conversation key using ECDH + HKDF.
     */
    private fun computeNip44ConversationKey(privateKeyHex: String, publicKeyHex: String): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()

        // Add 02 prefix to make it a compressed public key
        val publicKeyBytes = ("02" + publicKeyHex).hexToByteArray()

        // ECDH - get shared point
        val sharedPoint = secp256k1.pubKeyTweakMul(publicKeyBytes, privateKeyBytes)

        // Take x-coordinate only (first 32 bytes after the prefix byte)
        val sharedX = sharedPoint.sliceArray(1..32)

        // HKDF extract and expand to get conversation key
        return hkdfExtract(NIP44_SALT.toByteArray(), sharedX)
    }

    /**
     * Derive message keys from conversation key and nonce.
     */
    private fun deriveMessageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val expanded = hkdfExpand(conversationKey, nonce, 76)

        val chachaKey = expanded.sliceArray(0..31)      // 32 bytes
        val chachaNonce = expanded.sliceArray(32..43)   // 12 bytes
        val hmacKey = expanded.sliceArray(44..75)       // 32 bytes

        return Triple(chachaKey, chachaNonce, hmacKey)
    }

    /**
     * HKDF-Extract using HMAC-SHA256.
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand using HMAC-SHA256.
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1

        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()

            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }

        return result
    }

    /**
     * ChaCha20-Poly1305 encryption.
     */
    private fun chacha20Poly1305Encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val paramSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec)
            return cipher.doFinal(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20-Poly1305 encrypt failed, trying fallback", e)
            // Fallback for older Android versions - use AES-GCM as approximation
            // This won't be compatible but will at least not crash
            throw e
        }
    }

    /**
     * ChaCha20-Poly1305 decryption.
     */
    private fun chacha20Poly1305Decrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val paramSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20-Poly1305 decrypt failed", e)
            throw e
        }
    }

    /**
     * Pad plaintext according to NIP-44 spec.
     */
    private fun padPlaintext(plaintext: ByteArray): ByteArray {
        val unpaddedLen = plaintext.size
        if (unpaddedLen < 1 || unpaddedLen > 65535) {
            throw IllegalArgumentException("Plaintext too long or empty")
        }

        // Calculate padded length (next power of 2, minimum 32)
        val paddedLen = calcPaddedLen(unpaddedLen)

        // Create padded buffer: 2 bytes length prefix + plaintext + padding
        val padded = ByteArray(paddedLen)

        // Big-endian length prefix
        padded[0] = ((unpaddedLen shr 8) and 0xFF).toByte()
        padded[1] = (unpaddedLen and 0xFF).toByte()

        System.arraycopy(plaintext, 0, padded, 2, unpaddedLen)
        // Rest is already zeros (padding)

        return padded
    }

    /**
     * Unpad plaintext according to NIP-44 spec.
     */
    private fun unpadPlaintext(padded: ByteArray): ByteArray {
        if (padded.size < 2) {
            throw IllegalArgumentException("Padded data too short")
        }

        // Read big-endian length prefix
        val unpaddedLen = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)

        if (unpaddedLen < 1 || unpaddedLen > padded.size - 2) {
            throw IllegalArgumentException("Invalid padding length: $unpaddedLen, padded size: ${padded.size}")
        }

        return padded.sliceArray(2 until (2 + unpaddedLen))
    }

    /**
     * Calculate padded length according to NIP-44 spec.
     */
    private fun calcPaddedLen(unpaddedLen: Int): Int {
        val minPadded = unpaddedLen + 2 // 2 bytes for length prefix
        if (minPadded <= 32) return 32
        if (minPadded <= 64) return 64
        if (minPadded <= 128) return 128
        if (minPadded <= 256) return 256
        if (minPadded <= 512) return 512
        if (minPadded <= 1024) return 1024
        if (minPadded <= 2048) return 2048
        if (minPadded <= 4096) return 4096
        if (minPadded <= 8192) return 8192
        if (minPadded <= 16384) return 16384
        if (minPadded <= 32768) return 32768
        return 65536
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
