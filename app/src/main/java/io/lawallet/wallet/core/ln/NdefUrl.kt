package io.lawallet.wallet.core.ln

/** Raw NDEF record, so parsing is JVM-testable without android.nfc. */
class NdefRecordData(val tnf: Short, val type: ByteArray, val payload: ByteArray)

/** Port of apps/web/lib/client/ndef-url.ts — extracts a Bolt card / LNURL link from an NDEF message. */
object NdefUrl {
    private const val TNF_WELL_KNOWN: Short = 1
    private const val TNF_ABSOLUTE_URI: Short = 3

    private val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:", "ftp://anonymous:anonymous@",
        "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:",
        "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
        "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )
    private val PAYMENT = Regex("^(https?://|lnurlw://|lnurlp://|lnurl1)", RegexOption.IGNORE_CASE)

    private fun clean(s: String) = s.replace("\u0000", "").trim()
    private fun isPaymentUrl(s: String) = PAYMENT.containsMatchIn(s)

    private fun decodeUri(bytes: ByteArray): String? {
        val asText = clean(String(bytes))
        if (isPaymentUrl(asText)) return asText
        if (bytes.size < 2) return null
        val code = bytes[0].toInt() and 0xff
        if (code >= URI_PREFIXES.size) return null
        val rest = clean(String(bytes, 1, bytes.size - 1))
        if (rest.isEmpty() || rest.any { it < ' ' }) return null
        return (URI_PREFIXES[code] + rest).takeIf(::isPaymentUrl)
    }

    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.size < 3) return null
        val status = bytes[0].toInt()
        val langLen = status and 0x3f
        if (langLen == 0 || bytes.size <= 1 + langLen) return null
        val lang = String(bytes, 1, langLen)
        if (!Regex("^[A-Za-z-]{2,16}$").matches(lang)) return null
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        return clean(String(bytes, 1 + langLen, bytes.size - 1 - langLen, charset)).takeIf(::isPaymentUrl)
    }

    fun recordToUrl(r: NdefRecordData): String? {
        if (r.payload.isEmpty()) return null
        val type = String(r.type)
        return when {
            r.tnf == TNF_WELL_KNOWN && type == "U" -> decodeUri(r.payload)
            r.tnf == TNF_ABSOLUTE_URI -> clean(String(r.type)).takeIf(::isPaymentUrl) ?: decodeUri(r.payload)
            r.tnf == TNF_WELL_KNOWN && type == "T" -> decodeText(r.payload) ?: clean(String(r.payload)).takeIf(::isPaymentUrl)
            else -> clean(String(r.payload)).takeIf(::isPaymentUrl) ?: decodeUri(r.payload) ?: decodeText(r.payload)
        }
    }

    /** LNURL-withdraw links win over a generic https URL (an AAR must not shadow the card link). */
    fun firstBoltcardUrl(records: List<NdefRecordData>): String? {
        val urls = records.mapNotNull(::recordToUrl)
        return urls.firstOrNull { Regex("^(lnurlw://|lnurl1)", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: urls.firstOrNull()
    }
}
