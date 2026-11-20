package com.agendek.bramav2.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Opakowanie sekretu kluczem, który nie opuszcza magazynu kluczy (spec 9.3). */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    /**
     * @throws KeyUnavailableException klucz niedostępny (np. przed pierwszym odblokowaniem po restarcie);
     * @throws CorruptKeyException dane lub klucz nie do odzyskania (zły tag, brak aliasu, zła długość).
     */
    fun unwrap(wrapped: ByteArray): ByteArray
}

class KeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CorruptKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-256-GCM kluczem z Android Keystore (alias [ALIAS]): nieeksportowalny, bez biometrii — praca przy
 * zablokowanym ekranie i w Android Auto (spec 9.3). Wynik: IV (12 B) || szyfrogram z tagiem.
 */
class KeystoreCipher : KeyWrapper {

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Nowe poświadczenie (rejestracja) może użyć nowego klucza; stare i tak nie da się już odczytać.
            deleteKey()
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size <= IV_SIZE) throw CorruptKeyException("Za krótki szyfrogram")
        // Bez generowania nowego klucza: brak aliasu oznacza utracony klucz, nie blokadę.
        val key = existingKey() ?: throw CorruptKeyException("Brak klucza Keystore")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped, 0, IV_SIZE))
            cipher.doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw CorruptKeyException("Uszkodzone poświadczenie", e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Podklasa GeneralSecurityException: bez tej gałęzi trwała utrata klucza wyglądałaby jak blokada do odblokowania.
            throw CorruptKeyException("Klucz Keystore trwale unieważniony", e)
        } catch (e: java.security.GeneralSecurityException) {
            throw KeyUnavailableException("Klucz Keystore niedostępny", e)
        }
    }

    private fun existingKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: java.security.GeneralSecurityException) {
        throw KeyUnavailableException("Keystore niedostępny", e)
    } catch (e: java.io.IOException) {
        throw KeyUnavailableException("Keystore niedostępny", e)
    }

    private fun deleteKey() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "brama_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
