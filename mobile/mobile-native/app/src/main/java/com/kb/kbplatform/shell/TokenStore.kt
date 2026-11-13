/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 登录令牌的唯一存放处（后端 HMAC 令牌，默认 24h）。
 *
 * - StateFlow 驱动 AppRoot 在 登录页 / 主界面 之间切换
 * - 任意请求收到 401 时清空 → 全局退回登录页（与 Web 端 request.js 行为对齐）
 * - 加密持久化：AES/GCM + AndroidKeyStore（密钥不出 Keystore，无新增依赖），
 *   存储值带 enc1: 前缀；旧版本的明文记录读取时直接沿用，下次写入自动升级为密文
 */
object TokenStore {
    private const val KEY_TOKEN = "kb_token"
    private const val KEY_USERNAME = "kb_username"

    private lateinit var prefs: SharedPreferences
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("kb_shell_session", Context.MODE_PRIVATE)
        _token.value = prefs.getString(KEY_TOKEN, null)?.let { decryptToken(it) }
        _username.value = prefs.getString(KEY_USERNAME, null).orEmpty()
    }

    /** 传 null 即登出 */
    fun set(value: String?) {
        if (value == null) {
            prefs.edit().remove(KEY_TOKEN).remove(KEY_USERNAME).apply()
            _username.value = ""
        } else {
            prefs.edit().putString(KEY_TOKEN, encryptToken(value)).apply()
        }
        _token.value = value
    }

    // ---------- Token 加密存取（AndroidKeyStore AES/GCM，IV 12 字节拼在密文前，Base64 后带 enc1: 前缀） ----------

    private const val KS_ALIAS = "kb_shell_token"
    private const val ENC_PREFIX = "enc1:"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encryptToken(plain: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        ENC_PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        plain // 加密环境异常时退回明文，宁可兼容不把用户锁在门外；下次写入会再试加密
    }

    private fun decryptToken(stored: String): String? = try {
        if (!stored.startsWith(ENC_PREFIX)) {
            stored // 旧版本明文记录：直接沿用，下次 set 覆盖为密文
        } else {
            val data = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null // 解密失败（如用户清除 Keystore）按未登录处理
    }

    /** 登录成功后记录用户名：离线缓存按账号隔离的键 */
    fun setUsername(v: String) {
        prefs.edit().putString(KEY_USERNAME, v).apply()
        _username.value = v
    }

    // ---------- 身份缓存（权限/角色/昵称）：登录响应与 /auth/me 写入，后台管理入口显隐用 ----------

    private val _info = MutableStateFlow<org.json.JSONObject?>(null)
    val info: StateFlow<org.json.JSONObject?> = _info

    fun setInfo(json: org.json.JSONObject?) {
        _info.value = json
    }

    /** 是否拥有某权限码（ADMIN 角色隐含全权，与后端 TokenClaims.hasPerm 语义对齐） */
    fun hasPerm(perm: String): Boolean {
        val i = _info.value ?: return false
        if (i.optString("role") == "ADMIN") return true
        val perms = i.optString("permissions")
        if (perms.split(',').any { it.trim().equals("*", ignoreCase = true) }) return true
        return perms.split(',').any { it.trim().equals(perm, ignoreCase = true) }
    }
}
