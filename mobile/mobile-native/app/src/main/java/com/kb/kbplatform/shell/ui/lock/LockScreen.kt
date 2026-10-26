/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.AppLock

/**
 * 应用锁解锁屏：输入 PIN 解锁；错误抖动提示并保留输入。
 * 密钥校验在 AppLock（salt+SHA-256），此处只做输入交互。
 */
@Composable
fun LockScreen() {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var attempts by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔒", fontSize = 40.sp)
        Spacer(Modifier.height(14.dp))
        Text("知识库已锁定", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("输入 PIN 码解锁", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(26.dp))

        // 圆点指示：已输入位数
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(6) { i ->
                val filled = i < pin.length
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { v ->
                pin = v.filter { it.isDigit() }.take(6)
                error = ""
            },
            placeholder = { Text("PIN 码") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            isError = error.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                if (AppLock.unlock(pin)) {
                    pin = ""
                } else {
                    attempts++
                    error = "PIN 码不正确（第 $attempts 次尝试）"
                    pin = ""
                }
            },
            enabled = pin.length >= 4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .height(46.dp)
        ) { Text("解 锁") }
    }
}
