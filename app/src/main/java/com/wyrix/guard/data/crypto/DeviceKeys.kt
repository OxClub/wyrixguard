package com.wyrix.guard.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Hardware-backed (where available) keys. Private key material never leaves the Android Keystore. */
@Singleton
class DeviceKeys @Inject constructor() {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun ensureSigningKey() {
        if (ks.containsAlias(SIGN_ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    /** Base64 X.509 SubjectPublicKeyInfo. The server derives the device's cryptographic ID from its SHA-256. */
    fun publicKeyB64(): String {
        ensureSigningKey()
        return Base64.encodeToString(ks.getCertificate(SIGN_ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }

    /** ECDSA/SHA-256, DER-encoded, base64. */
    fun sign(message: String): String {
        ensureSigningKey()
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(ks.getKey(SIGN_ALIAS, null) as PrivateKey)
        s.update(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    fun deleteSigningKey() { if (ks.containsAlias(SIGN_ALIAS)) ks.deleteEntry(SIGN_ALIAS) }

    private fun aesKey(): SecretKey {
        (ks.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** AES-256-GCM; output = base64(iv || ciphertext). */
    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aesKey())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(b64: String): String? = try {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (_: Exception) { null }

    private companion object {
        const val SIGN_ALIAS = "wyrix_device_sign"
        const val AES_ALIAS = "wyrix_store_aes"
    }
}
