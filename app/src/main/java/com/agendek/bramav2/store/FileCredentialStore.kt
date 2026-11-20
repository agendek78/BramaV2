package com.agendek.bramav2.store

import com.agendek.bramav2.domain.CredentialLoad
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.CredentialStore
import com.agendek.bramav2.domain.StoredCredential
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Poświadczenie w pliku [FILE_NAME] w katalogu wyłączonym z backupu (spec 9.3): `"BRC1"`, stan (1 B),
 * `device_id` (16 B), `client_id` (16 B), długość (2 B) i `K_client` opakowany [KeyWrapper]. Zapis atomowy.
 * Uszkodzony plik daje [CredentialLoad.None] bez kasowania (do diagnozy); niedostępny klucz — [CredentialLoad.Locked].
 */
class FileCredentialStore(private val dir: File, private val wrapper: KeyWrapper) : CredentialStore {

    private val file get() = File(dir, FILE_NAME)

    override suspend fun load(): CredentialLoad {
        val record = read() ?: return CredentialLoad.None
        return try {
            wrapper.unwrap(record.wrappedKey).fill(0) // klucz musi dać się odczytać (9.3)
            CredentialLoad.Present(StoredCredential(record.state, record.deviceId, record.clientId))
        } catch (e: KeyUnavailableException) {
            CredentialLoad.Locked
        } catch (e: CorruptKeyException) {
            CredentialLoad.None // plik zostaje do diagnozy; nowa rejestracja go zastąpi
        }
    }

    override suspend fun clientKey(): ByteArray {
        val record = read() ?: throw IllegalStateException("Brak poświadczenia")
        return wrapper.unwrap(record.wrappedKey)
    }

    override suspend fun saveUnconfirmed(deviceId: ByteArray, clientId: ByteArray, clientKey: ByteArray) {
        write(Record(CredentialState.UNCONFIRMED, deviceId.copyOf(), clientId.copyOf(), wrapper.wrap(clientKey)))
    }

    override suspend fun markActive() {
        val record = read() ?: throw IllegalStateException("Brak poświadczenia")
        write(record.copy(state = CredentialState.ACTIVE))
    }

    override suspend fun delete() {
        if (file.exists() && !file.delete()) throw IllegalStateException("Nie udało się usunąć poświadczenia")
    }

    private data class Record(val state: CredentialState, val deviceId: ByteArray, val clientId: ByteArray, val wrappedKey: ByteArray)

    private fun read(): Record? {
        val bytes = file.takeIf { it.exists() }?.readBytes() ?: return null
        return try {
            val b = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4).also(b::get)
            if (!magic.contentEquals(MAGIC)) return null
            val state = when (b.get().toInt()) {
                1 -> CredentialState.UNCONFIRMED
                2 -> CredentialState.ACTIVE
                else -> return null
            }
            val deviceId = ByteArray(16).also(b::get)
            val clientId = ByteArray(16).also(b::get)
            val len = b.short.toInt() and 0xffff
            if (len != b.remaining()) return null
            Record(state, deviceId, clientId, ByteArray(len).also(b::get))
        } catch (e: java.nio.BufferUnderflowException) {
            null
        }
    }

    private fun write(r: Record) {
        val out = ByteBuffer.allocate(4 + 1 + 16 + 16 + 2 + r.wrappedKey.size)
            .put(MAGIC)
            .put(if (r.state == CredentialState.ACTIVE) 2 else 1)
            .put(r.deviceId)
            .put(r.clientId)
            .putShort(r.wrappedKey.size.toShort())
            .put(r.wrappedKey)
        atomicWrite(file, out.array())
    }

    companion object {
        const val FILE_NAME = "credential.bin"
        private val MAGIC = "BRC1".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Zapis przez plik tymczasowy i `renameTo` — nie zostawia połowicznego pliku. `fsync` przed zmianą nazwy: bez niego
 * po zaniku zasilania rename może przetrwać, a dane nie (pusty plik poświadczenia = ponowna rejestracja).
 */
internal fun atomicWrite(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(tmp).use {
        it.write(bytes)
        it.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        tmp.delete()
        throw IllegalStateException("Nie udało się zapisać ${target.name}")
    }
}
