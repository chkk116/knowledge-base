/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kb.kbplatform.shell.data.AppLock
import com.kb.kbplatform.shell.data.ShareInbox
import com.kb.kbplatform.shell.ui.AppRoot
import com.kb.kbplatform.shell.ui.lock.LockScreen
import com.kb.kbplatform.shell.ui.theme.KbTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppConfig.init(applicationContext)
        TokenStore.init(applicationContext)
        AppLock.init(applicationContext)
        enableEdgeToEdge()
        // 冷启动即带分享数据（微信分享拉起 App）
        ShareInbox.accept(applicationContext, intent)
        setContent {
            KbTheme {
                val locked by AppLock.locked.collectAsState()
                if (locked) LockScreen() else AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onAppForegrounded()
        com.kb.kbplatform.shell.data.AppForeground.active = true
        // 首次进前台申请一次通知权限（Android 13+）：原来只在「更新下载」时申请，
        // 用户不点更新就永远收不到消息通知
        com.kb.kbplatform.shell.data.MsgNotify.ensurePermission(this)
    }

    override fun onStop() {
        super.onStop()
        com.kb.kbplatform.shell.data.AppForeground.active = false
        AppLock.onAppBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 热启动（App 已在后台）：singleTask 模式走这里
        ShareInbox.accept(applicationContext, intent)
    }
}
