/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kb.kbplatform.shell.data.ApiError
import com.kb.kbplatform.shell.data.AppLock
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.theme.KbTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 系统级「随手问」入口。
 *
 * 注册 ACTION_PROCESS_TEXT（text/plain）：任意 App 里长按选中文字 → 系统选择菜单出现「问知识库」
 * → 拉起本页，直接把选中文字交给 AI 解释 / 总结 / 翻译，或继续追问，结果可一键复制。
 *
 * 刻意做成独立轻量 Activity：不初始化 WebView、不加载完整 AppRoot，冷启动更快、更聚焦。
 *
 * 模型选择与对话页同源：GET /api/ai/models 返回「系统模型 + 当前用户私有模型（scope=user）」，
 * 提问时系统模型走 modelId、私有模型走 userModelId，后端候选链逻辑完全一致。
 */
class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppConfig.init(applicationContext)
        TokenStore.init(applicationContext)
        AppLock.init(applicationContext)
        // 应用锁开启时，禁止借系统「随手问」入口绕过 PIN：交给 MainActivity 的 LockScreen
        if (AppLock.locked.value) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        enableEdgeToEdge()
        // ACTION_PROCESS_TEXT 携带的选中文字（Android 6.0+）
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        setContent {
            KbTheme {
                ProcessTextScreen(initialText = selected, onClose = { finish() })
            }
        }
    }
}

/** 快捷动作：标签 → 提示词模板 */
private val ACTIONS: List<Pair<String, (String) -> String>> = listOf(
    "解释" to { t -> "请用通俗易懂的语言解释下面这段内容，必要时举例说明：\n\n$t" },
    "总结" to { t -> "请用 3-5 个要点总结下面这段内容，只输出要点：\n\n$t" },
    "翻译" to { t -> "请把下面这段内容翻译成中文（若已是中文则翻译成英文），只输出译文：\n\n$t" },
)

/** 模型下拉项（id + 展示名 + 来源，scope: system|user） */
private data class ModelOption(val id: Long, val name: String, val scope: String)

/** 一轮问答：display 给用户看的问句（非完整 prompt），失败时只标错误不丢上下文 */
private data class Round(
    val display: String,
    val answer: String,
    val modelName: String,
    val failed: Boolean = false,
    val errorMsg: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessTextScreen(initialText: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var text by rememberSaveable { mutableStateOf(initialText) }
    var question by rememberSaveable { mutableStateOf("") }
    var conversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val loggedIn = TokenStore.token.collectAsState().value != null
    val rounds = remember { mutableStateListOf<Round>() }

    // ---------- 模型选择 ----------
    var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modelsLoaded by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<ModelOption?>(null) } // null = 后端默认模型
    var modelMenuOpen by remember { mutableStateOf(false) }

    // 登录后拉一次可选模型列表；失败静默降级为「默认模型」，不阻塞提问
    LaunchedEffect(loggedIn) {
        if (!loggedIn || modelsLoaded) return@LaunchedEffect
        modelsLoaded = true
        try {
            val arr = KbApi.aiModels()
            models = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    ModelOption(
                        id = o.optLong("id"),
                        name = o.optString("name").ifBlank { o.optString("model") },
                        scope = o.optString("scope", "system")
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            models = emptyList()
        }
    }

    // 新回答追加后滚到底部（先等一帧让新内容完成布局，maxValue 才是准的）
    LaunchedEffect(rounds.size, rounds.lastOrNull()?.answer) {
        if (rounds.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }

    fun ask(prompt: String, display: String) {
        if (!loggedIn) {
            error = "请先登录知识库 App 后再使用"
            return
        }
        if (text.isBlank() && question.isBlank() && prompt.isBlank()) {
            error = "请先输入内容"
            return
        }
        loading = true
        error = ""
        rounds.add(Round(display = display, answer = "", modelName = ""))
        val idx = rounds.lastIndex
        val sysId = selectedModel?.takeIf { it.scope != "user" }?.id
        val userId = selectedModel?.takeIf { it.scope == "user" }?.id
        scope.launch {
            try {
                val res = KbApi.aiAsk(prompt, conversationId, modelId = sysId, userModelId = userId, mode = "kb")
                // 记住会话，追问才能承接上一轮回答
                conversationId = res.optLong("conversationId").takeIf { it > 0L }
                // optString 对 JSON null 会返回字面量 "null"，这里显式判空
                val raw = res.opt("answer")
                val answer = (raw?.takeIf { it != JSONObject.NULL }?.toString() ?: "")
                    .ifBlank { "（暂无返回内容）" }
                rounds[idx] = rounds[idx].copy(
                    answer = answer,
                    modelName = res.optString("modelName")
                )
                question = ""
            } catch (e: CancellationException) {
                rounds.removeAt(idx)
                throw e
            } catch (e: Exception) {
                // 401 时 ApiClient 已清令牌，loggedIn 翻转后登录引导自然出现
                val msg = (e as? ApiError)?.message ?: e.message ?: "请求失败，请检查网络"
                rounds[idx] = rounds[idx].copy(failed = true, errorMsg = msg)
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问知识库", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 选中的原文（可直接改）
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("选中的内容") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )

            // 快捷动作
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ACTIONS.forEach { (label, promptFn) ->
                    AssistChip(
                        onClick = { ask(promptFn(text), label) },
                        label = { Text(label) },
                        enabled = !loading && loggedIn && text.isNotBlank(),
                    )
                }
            }

            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("想进一步问什么（可选）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )

            // 模型选择：与 AI 对话页同源（系统模型 + 我的私有模型）
            Box {
                AssistChip(
                    onClick = { modelMenuOpen = true },
                    label = {
                        Text(
                            "模型：" + (selectedModel?.name ?: "默认模型") +
                                (selectedModel?.let { if (it.scope == "user") "（我的）" else "" } ?: "")
                        )
                    },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    enabled = !loading && loggedIn,
                )
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("默认模型") },
                        onClick = { selectedModel = null; modelMenuOpen = false },
                    )
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.name}（${if (m.scope == "user") "我的" else "系统"}）") },
                            onClick = { selectedModel = m; modelMenuOpen = false },
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val p = when {
                        question.isNotBlank() && text.isNotBlank() -> "$question\n\n背景内容：\n\n$text"
                        question.isNotBlank() -> question
                        else -> "请解释下面内容：\n\n$text"
                    }
                    ask(p, question.ifBlank { "解释选中内容" })
                },
                enabled = !loading && loggedIn && (text.isNotBlank() || question.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "思考中…" else "问 AI")
            }

            if (!loggedIn) {
                Text("请先登录知识库 App 后再使用", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onClose()
                }) { Text("打开知识库登录") }
            }

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("AI 正在思考…", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            // 问答历史（最近在底部，承接 conversationId 的多轮上下文）
            rounds.forEachIndexed { i, r ->
                Card(
                    colors = if (r.failed) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            r.display,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (r.failed) {
                            Text(r.errorMsg, color = MaterialTheme.colorScheme.onErrorContainer)
                        } else {
                            Text(r.answer, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    copyToClipboard(ctx, r.answer)
                                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("复制")
                                }
                                if (r.modelName.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            r.modelName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, s: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("kb-ai", s))
}
