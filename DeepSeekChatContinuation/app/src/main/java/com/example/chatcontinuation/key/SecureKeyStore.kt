package com.example.chatcontinuation.key

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 本机加密存储（设计稿 6.2 / 七）。
 *
 * - 明文 Key 只存在于调用方内存；落盘的是 Android Keystore 中 AES-256-GCM
 *   主密钥加密后的密文，IV 与密文分字段保存。
 * - 主密钥生成于 AndroidKeyStore，不可导出；卸载或清除应用数据时随之销毁。
 * - 备份规则（backup_rules / data_extraction_rules）排除了本存储文件，
 *   因此聊天备份里永远不会有 API Key。
 * - 日志不得输出 Key 内容；错误日志只记异常类名（见 util/LogRedactor）。
 */
class SecureKeyStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    fun hasKey(): Boolean =
        synchronized(lock) { prefs.contains(PREF_CIPHERTEXT) && prefs.contains(PREF_IV) }

    /** 加密保存。调用前应已通过 [KeyVerifier] 验证。 */
    fun saveKey(plainKey: String) {
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
            val cipherText = cipher.doFinal(plainKey.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(PREF_IV, Base64.getEncoder().encodeToString(cipher.iv))
                .putString(PREF_CIPHERTEXT, Base64.getEncoder().encodeToString(cipherText))
                .apply()
        }
    }

    /** 读取明文 Key；仅在受信任页面按需请求时使用，解密失败返回 null。 */
    fun getKey(): String? {
        synchronized(lock) {
            val ivBase64 = prefs.getString(PREF_IV, null) ?: return null
            val cipherTextBase64 = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateMasterKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(ivBase64))
                )
                String(cipher.doFinal(Base64.getDecoder().decode(cipherTextBase64)), Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "key decrypt failed: ${t.javaClass.simpleName}")
                null
            }
        }
    }

    /** 删除本机保存的 Key（Keystore 主密钥保留，可继续用于下次保存）。 */
    fun deleteKey() {
        synchronized(lock) {
            prefs.edit().remove(PREF_IV).remove(PREF_CIPHERTEXT).apply()
        }
    }

    /** 仅用于界面展示的掩码，如 sk-…a1b2；永远不返回完整 Key。 */
    fun maskedForDisplay(): String? {
        val key = getKey() ?: return null
        return if (key.length >= 4) "sk-…${key.takeLast(4)}" else "sk-…****"
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val PROVIDER = "AndroidKeyStore"
        private const val MASTER_ALIAS = "assistant_master_key"
        private const val PREFS_FILE = "secure_key_store"
        private const val PREF_IV = "api_key_iv"
        private const val PREF_CIPHERTEXT = "api_key_ciphertext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
