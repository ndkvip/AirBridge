package com.airbridge.drop.protocol.http

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Generates an in-memory self-signed X.509v3 certificate using pure ASN.1 DER encoding
 * (zero external crypto dependencies) and creates a TLS 1.2/1.3 SSLContext for AirDrop HTTPS.
 */
object AirDropTlsContext {

    data class PersistentTlsIdentity(
        val sslContext: SSLContext,
        val certSha256Hex: String
    )

    /**
     * Loads or generates a persistent self-signed X.509v3 certificate stored in app private filesDir
     * so macOS clients can pin its SHA-256 fingerprint (TOFU) across restarts on public Wi-Fi.
     */
    fun createPersistentSslContext(context: Context, commonName: String = "AirBridge"): PersistentTlsIdentity {
        val password = "airbridge_tls_store".toCharArray()
        val ksFile = File(context.filesDir, "airbridge_tls.p12")
        val keyStore = KeyStore.getInstance("PKCS12")
        var cert: X509Certificate? = null

        if (ksFile.exists()) {
            runCatching {
                FileInputStream(ksFile).use { fis -> keyStore.load(fis, password) }
                cert = keyStore.getCertificate("airbridge") as? X509Certificate
            }
        }

        if (cert == null) {
            val keyPair = generateRsaKeyPair()
            val generatedCert = generateSelfSignedCert(keyPair, commonName)
            keyStore.load(null, null)
            keyStore.setKeyEntry("airbridge", keyPair.private, password, arrayOf(generatedCert))
            runCatching {
                FileOutputStream(ksFile).use { fos -> keyStore.store(fos, password) }
            }
            cert = generatedCert
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, trustAll, SecureRandom())
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(cert!!.encoded)
            .joinToString("") { "%02x".format(it) }
        return PersistentTlsIdentity(sslContext = sslContext, certSha256Hex = sha256)
    }

    fun createSslContext(commonName: String = "AirBridge"): SSLContext {
        val keyPair = generateRsaKeyPair()
        val cert = generateSelfSignedCert(keyPair, commonName)

        val password = "airbridge".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("airdrop", keyPair.private, password, arrayOf(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }

        // AirDrop uses ad-hoc peer-to-peer TLS with Apple/self-signed device certificates
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, trustAll, SecureRandom())
        }
    }

    private fun generateRsaKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    private fun generateSelfSignedCert(keyPair: KeyPair, commonName: String): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60L * 60L * 1000L)
        val notAfter = Date(now + 365L * 24L * 60L * 60L * 1000L)
        val serial = BigInteger(63, SecureRandom()).abs().add(BigInteger.ONE)

        val sha256WithRsaOid = derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11))
        val sigAlgId = derSequence(sha256WithRsaOid, derNull())

        val cnOid = derOid(intArrayOf(2, 5, 4, 3))
        val name = derSequence(
            derSet(derSequence(cnOid, derUtf8String(commonName)))
        )

        val validity = derSequence(
            derUtcTime(notBefore),
            derUtcTime(notAfter)
        )

        val version = derExplicit(0, derInteger(BigInteger.valueOf(2))) // v3
        val subjectPublicKeyInfo = keyPair.public.encoded // Already DER-encoded SubjectPublicKeyInfo

        val tbsCert = derSequence(
            version,
            derInteger(serial),
            sigAlgId,
            name,
            validity,
            name,
            subjectPublicKeyInfo
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCert)
        val signatureBytes = signer.sign()

        val fullCertDer = derSequence(
            tbsCert,
            sigAlgId,
            derBitString(signatureBytes)
        )

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(fullCertDer)) as X509Certificate
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray = derTag(0x30, concat(*elements))
    private fun derSet(vararg elements: ByteArray): ByteArray = derTag(0x31, concat(*elements))
    private fun derExplicit(tagNum: Int, content: ByteArray): ByteArray = derTag(0xA0 or tagNum, content)
    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())
    private fun derUtf8String(value: String): ByteArray =
        derTag(0x0C, value.toByteArray(StandardCharsets.UTF_8))

    private fun derBitString(bytes: ByteArray): ByteArray {
        val content = ByteArray(bytes.size + 1)
        content[0] = 0x00 // 0 unused bits
        System.arraycopy(bytes, 0, content, 1, bytes.size)
        return derTag(0x03, content)
    }

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(date).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun derOid(arcs: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val stack = IntArray(5)
            var count = 0
            stack[count++] = v and 0x7F
            v = v ushr 7
            while (v > 0) {
                stack[count++] = (v and 0x7F) or 0x80
                v = v ushr 7
            }
            for (j in (count - 1) downTo 0) out.write(stack[j])
        }
        return derTag(0x06, out.toByteArray())
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 128 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            len <= 0xFFFF -> {
                out.write(0x82)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (a in arrays) out.write(a)
        return out.toByteArray()
    }
}
