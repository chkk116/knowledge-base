/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.captcha.SlideCaptcha
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import com.kb.kbplatform.shell.ui.common.kbItemEnter
import kotlinx.coroutines.launch

/**
 * 登录页 · UI 对标套壳 MobileLogin v3：
 * 品牌区（圆角徽标 + 字距拉开的「知识库」）→「欢迎回来」标题区 →
 * 胶囊输入框（52h，白色半透明 + 内描边 + 软影）→ 胶囊主按钮（发光投影）→
 * 底部滑出滑动验证码。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showCaptcha by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    fun doLogin(captchaToken: String) {
        scope.launch {
            loggingIn = true
            errorMsg = ""
            try {
                val s = KbApi.login(username.trim(), password, captchaToken)
                TokenStore.setUsername(s.username)
                // 身份缓存（角色/权限）：后台管理入口显隐用
                TokenStore.setInfo(
                    org.json.JSONObject()
                        .put("id", s.id)
                        .put("username", s.username)
                        .put("displayName", s.displayName)
                        .put("role", s.role)
                        .put("permissions", s.permissions)
                )
                TokenStore.set(s.token)   // AppRoot 观察到非空即进入主界面
            } catch (e: Exception) {
                errorMsg = e.message ?: "登录失败"
            } finally {
                loggingIn = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
    ) {
        // —— 品牌区 ——
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 64.dp)
                // 品牌区先落位（自上轻落），表单紧随其后自下而上，形成先后节奏
                .kbItemEnter(index = 0, fromBottom = -14),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(86.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color(0xFF4C8DFF), Color(0xFF1677FF))
                        ),
                        RoundedCornerShape(24.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("知", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "知 识 库",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
        }

        // —— 表单区 ——
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .padding(top = 34.dp)
                .kbItemEnter(index = 2, fromBottom = 20)
        ) {
            Text(
                "欢迎回来",
                fontSize = 25.sp, fontWeight = FontWeight.Bold,
                color = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFE5E6EB) else Color(0xFF1E2A3F),
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(6.dp))
            Text("登录你的账号，继续沉淀知识", fontSize = 13.sp, color = KbTextDim)
            Spacer(Modifier.height(22.dp))

            LoginPill(
                icon = Icons.Filled.Person,
                value = username,
                onValueChange = { username = it },
                hint = "用户名"
            )
            Spacer(Modifier.height(14.dp))
            LoginPill(
                icon = Icons.Filled.Lock,
                value = password,
                onValueChange = { password = it },
                hint = "密码",
                isPassword = true,
                showPassword = showPassword,
                onToggleShow = { showPassword = !showPassword }
            )

            if (errorMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "⚠️ $errorMsg",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }

            Spacer(Modifier.height(18.dp))
            // 胶囊主按钮（发光投影对标 .cta）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .shadow(
                        20.dp, RoundedCornerShape(999.dp),
                        ambientColor = Color(0x221C346C), spotColor = Color(0x661677FF)
                    )
                    .background(
                        if (username.isNotBlank() && password.isNotBlank() && !loggingIn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        RoundedCornerShape(999.dp)
                    )
                    .clickable(
                        enabled = username.isNotBlank() && password.isNotBlank() && !loggingIn
                    ) {
                        errorMsg = ""
                        showCaptcha = true
                    },
                contentAlignment = Alignment.Center
            ) {
                if (loggingIn) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(
                        "登  录",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 6.sp
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
        }
    }

    if (showCaptcha) {
        ModalBottomSheet(
            onDismissRequest = { showCaptcha = false },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "完成安全验证",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                SlideCaptcha(
                    onVerified = { token ->
                        showCaptcha = false
                        doLogin(token)
                    },
                    onDismiss = { showCaptcha = false }
                )
            }
        }
    }
}
@Composable
private fun LoginPill(
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleShow: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(18.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x141C346C), spotColor = Color(0x281C346C))
            .background(
                if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.surface
                else Color.White.copy(alpha = 0.85f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(11.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation()
            else VisualTransformation.None,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(hint, fontSize = 15.sp, color = KbTextDim)
                    }
                    inner()
                }
            }
        )
        if (isPassword && onToggleShow != null) {
            Text(
                if (showPassword) "隐藏" else "显示",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onToggleShow)
            )
        }
    }
}
