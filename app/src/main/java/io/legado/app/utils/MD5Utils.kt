package io.legado.app.utils

import java.io.InputStream
import java.security.MessageDigest

/**
 * 将字符串转化为MD5（使用标准 JDK MessageDigest，避免第三方安全提供者如 BouncyCastle 在 Android 上未注册 MD5 时的异常）
 */
@Suppress("unused")
object MD5Utils {

    fun md5Encode(str: String?): String {
        if (str == null) return ""
        return md5Encode(str.toByteArray(Charsets.UTF_8))
    }

    fun md5Encode(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return bytesToHex(digest)
    }

    fun md5Encode(inputStream: InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
        val digest = md.digest()
        return bytesToHex(digest)
    }

    fun md5Encode16(str: String): String {
        var reStr = md5Encode(str)
        if (reStr.length >= 24) {
            reStr = reStr.substring(8, 24)
        }
        return reStr
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val hex = Integer.toHexString((b.toInt() and 0xFF))
            if (hex.length == 1) {
                sb.append('0')
            }
            sb.append(hex)
        }
        return sb.toString()
    }
}
