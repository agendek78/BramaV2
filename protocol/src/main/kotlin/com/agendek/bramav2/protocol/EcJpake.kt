package com.agendek.bramav2.protocol

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom

/** Rola EC-JPAKE; identyfikator ASCII wchodzi do hasha ZKP (jak `ID_MINE`/`ID_PEER` w mbedTLS). */
enum class JpakeRole(val id: String) { CLIENT("client"), SERVER("server") }

/** Źródło skalarów z przedziału [1, n-1] (klucze i nonce dowodów ZKP). */
fun interface ScalarSource {
    fun next(): BigInteger
}

class SecureRandomScalars(private val random: SecureRandom = SecureRandom()) : ScalarSource {
    override fun next(): BigInteger {
        while (true) {
            val k = BigInteger(EcJpake.ORDER.bitLength(), random)
            if (k.signum() > 0 && k < EcJpake.ORDER) return k
        }
    }
}

/**
 * EC-JPAKE na P-256 z SHA-256 w formacie `mbedtls_ecjpake` (mbedTLS 3.6, spec 11.4): punkty jako TLS `ECPoint`
 * (1 B długości + punkt nieskompresowany), dowód ZKP jako `ECPoint V || 1 B długości || r` (r minimalnej długości),
 * runda 2 serwera poprzedzona `ECParameters` (`03 00 17`). Wspólny sekret jak `mbedtls_ecjpake_derive_secret`:
 * SHA-256 współrzędnej x punktu K.
 *
 * Kolejność pobierania skalarów: [writeRound1] — x1, v1, x2, v2; [writeRound2] — v. Nie jest bezpieczna wątkowo.
 * Błędne dane od strony przeciwnej → [ProtocolException]; złe użycie (kolejność wywołań) → [IllegalStateException].
 */
class EcJpake(
    private val role: JpakeRole,
    password: ByteArray,
    private val scalars: ScalarSource = SecureRandomScalars(),
) {
    private val password = password.copyOf()
    private val peerId = if (role == JpakeRole.CLIENT) JpakeRole.SERVER.id else JpakeRole.CLIENT.id

    private var xm1: BigInteger? = null
    private var xm2: BigInteger? = null
    private var pubM1: ECPoint? = null
    private var pubM2: ECPoint? = null
    private var pubP1: ECPoint? = null
    private var pubP2: ECPoint? = null
    private var pubP: ECPoint? = null

    var isDestroyed = false
        private set

    fun writeRound1(): ByteArray {
        live()
        check(xm1 == null) { "Runda 1 już wysłana" }
        val out = ByteArrayOutputStream()
        val (x1, p1) = writeKeyKp(G, out)
        val (x2, p2) = writeKeyKp(G, out)
        xm1 = x1; pubM1 = p1; xm2 = x2; pubM2 = p2
        return out.toByteArray()
    }

    fun readRound1(peer: ByteArray) {
        live()
        check(pubP1 == null) { "Runda 1 już odebrana" }
        val r = Reader(peer)
        val p1 = readKeyKp(r, G)
        val p2 = readKeyKp(r, G)
        r.end()
        pubP1 = p1; pubP2 = p2
    }

    fun writeRound2(): ByteArray {
        live()
        val gen = round1Done { pubP1!!.add(pubP2!!).add(pubM1!!) }
        val xm = xm2!!.multiply(secret()).mod(ORDER)
        val pub = gen.multiply(xm).normalize()
        val out = ByteArrayOutputStream()
        if (role == JpakeRole.SERVER) out.write(EC_PARAMETERS)
        out.write(tlsPoint(pub))
        writeZkp(gen, xm, pub, out)
        return out.toByteArray()
    }

    fun readRound2(peer: ByteArray) {
        live()
        val gen = round1Done { pubM1!!.add(pubM2!!).add(pubP1!!) }
        check(pubP == null) { "Runda 2 już odebrana" }
        val r = Reader(peer)
        if (role == JpakeRole.CLIENT && !r.take(EC_PARAMETERS.size).contentEquals(EC_PARAMETERS)) {
            throw ProtocolException("Nieobsługiwane ECParameters EC-JPAKE")
        }
        val p = readKeyKp(r, gen)
        r.end()
        pubP = p
    }

    /** `S` (32 B): SHA-256 współrzędnej x punktu K = xm2 · (Xp − Xp2 · xm2 · s). */
    fun deriveSecret(): ByteArray {
        live()
        val xp = checkNotNull(pubP) { "Brak rundy 2 strony przeciwnej" }
        val x2 = xm2!!
        val k = xp.subtract(pubP2!!.multiply(x2.multiply(secret()).mod(ORDER))).multiply(x2).normalize()
        if (k.isInfinity) throw ProtocolException("Zdegenerowany sekret EC-JPAKE")
        return Crypto.sha256(unsigned(k.affineXCoord.toBigInteger(), FIELD_BYTES))
    }

    /** Zeruje hasło i zapomina skalary; dalsze wywołania rzucają [IllegalStateException]. */
    fun destroy() {
        password.fill(0)
        xm1 = null; xm2 = null
        isDestroyed = true
    }

    private fun live() = check(!isDestroyed) { "EC-JPAKE zniszczony" }

    private fun <T> round1Done(block: () -> T): T {
        check(xm1 != null && pubP1 != null) { "Runda 1 niekompletna" }
        return block()
    }

    private fun secret(): BigInteger = BigInteger(1, password).mod(ORDER)

    private fun writeKeyKp(gen: ECPoint, out: ByteArrayOutputStream): Pair<BigInteger, ECPoint> {
        val x = nextScalar()
        val pub = gen.multiply(x).normalize()
        out.write(tlsPoint(pub))
        writeZkp(gen, x, pub, out)
        return x to pub
    }

    private fun writeZkp(gen: ECPoint, x: BigInteger, pub: ECPoint, out: ByteArrayOutputStream) {
        val v = nextScalar()
        val vPub = gen.multiply(v).normalize()
        val h = hash(gen, vPub, pub, role.id)
        val r = v.subtract(x.multiply(h)).mod(ORDER)
        out.write(tlsPoint(vPub))
        val rBytes = minimal(r)
        out.write(rBytes.size)
        out.write(rBytes)
    }

    private fun readKeyKp(r: Reader, gen: ECPoint): ECPoint {
        val pub = r.point()
        if (pub.isInfinity) throw ProtocolException("Klucz EC-JPAKE w nieskończoności")
        val v = r.point()
        val rLen = r.byte()
        if (rLen == 0) throw ProtocolException("Pusty r ZKP")
        val rv = BigInteger(1, r.take(rLen))
        if (rv.signum() == 0 || rv >= ORDER) throw ProtocolException("r ZKP poza zakresem")
        val h = hash(gen, v, pub, peerId)
        val check = pub.multiply(h).add(gen.multiply(rv)).normalize()
        if (check != v.normalize()) throw ProtocolException("Niepoprawny dowód ZKP EC-JPAKE")
        return pub
    }

    private fun nextScalar(): BigInteger = scalars.next().also {
        require(it.signum() > 0 && it < ORDER) { "Skalar poza [1, n-1]" }
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun byte(): Int {
            if (pos >= bytes.size) throw ProtocolException("Obcięta runda EC-JPAKE")
            return bytes[pos++].toInt() and 0xff
        }

        fun take(n: Int): ByteArray {
            if (bytes.size - pos < n) throw ProtocolException("Obcięta runda EC-JPAKE")
            return bytes.copyOfRange(pos, pos + n).also { pos += n }
        }

        /** TLS `ECPoint`: 1 B długości + punkt (nieskompresowany 65 B albo `00` — nieskończoność). */
        fun point(): ECPoint {
            val len = byte()
            val enc = take(len)
            if (len != 1 && len != POINT_BYTES) throw ProtocolException("Zła długość ECPoint")
            return try {
                CURVE.decodePoint(enc)
            } catch (e: IllegalArgumentException) {
                throw ProtocolException("Punkt EC-JPAKE spoza krzywej")
            }
        }

        fun end() {
            if (pos != bytes.size) throw ProtocolException("Nadmiarowe bajty rundy EC-JPAKE")
        }
    }

    companion object {
        private val PARAMS = CustomNamedCurves.getByName("secp256r1")
        private val CURVE = PARAMS.curve
        private val G: ECPoint = PARAMS.g
        val ORDER: BigInteger = PARAMS.n
        private const val FIELD_BYTES = 32
        private const val POINT_BYTES = 1 + 2 * FIELD_BYTES

        /** `ECParameters`: named_curve (3), secp256r1 (TLS 23). */
        private val EC_PARAMETERS = byteArrayOf(0x03, 0x00, 0x17)

        private fun tlsPoint(p: ECPoint): ByteArray = p.getEncoded(false).let { byteArrayOf(it.size.toByte()) + it }

        /** Hash ZKP jak `ecjpake_hash`: len32(G)||G || len32(V)||V || len32(X)||X || len32(id)||id, mod n. */
        private fun hash(gen: ECPoint, v: ECPoint, x: ECPoint, id: String): BigInteger {
            val buf = ByteArrayOutputStream()
            for (p in listOf(gen, v, x)) {
                val enc = p.normalize().getEncoded(false)
                buf.write(be32(enc.size))
                buf.write(enc)
            }
            val idBytes = id.toByteArray(Charsets.US_ASCII)
            buf.write(be32(idBytes.size))
            buf.write(idBytes)
            return BigInteger(1, Crypto.sha256(buf.toByteArray())).mod(ORDER)
        }

        private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        /** Bajty big-endian bez wiodących zer (jak `mbedtls_mpi_size` + `write_binary`). */
        private fun minimal(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            val start = b.indexOfFirst { it != 0.toByte() }.let { if (it < 0) b.size else it }
            return b.copyOfRange(start, b.size)
        }

        private fun unsigned(v: BigInteger, len: Int): ByteArray {
            val m = minimal(v)
            return ByteArray(len - m.size) + m
        }
    }
}
