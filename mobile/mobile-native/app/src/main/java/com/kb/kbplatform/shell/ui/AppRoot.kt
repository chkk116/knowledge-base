/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.navigation.navArgument
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.SseClient
import com.kb.kbplatform.shell.ui.admin.AdminAiScreen
import com.kb.kbplatform.shell.ui.admin.AdminAnnounceScreen
import com.kb.kbplatform.shell.ui.admin.AdminHomeScreen
import com.kb.kbplatform.shell.ui.admin.AdminReviewScreen
import com.kb.kbplatform.shell.ui.admin.AdminUsersScreen
import com.kb.kbplatform.shell.ui.ai.AiChatScreen
import com.kb.kbplatform.shell.ui.capture.CaptureScreen
import com.kb.kbplatform.shell.ui.cards.CardsScreen
import com.kb.kbplatform.shell.ui.clip.ClipScreen
import com.kb.kbplatform.shell.ui.exam.ExamCenterScreen
import com.kb.kbplatform.shell.ui.exam.ExamTakeScreen
import com.kb.kbplatform.shell.ui.login.LoginScreen
import com.kb.kbplatform.shell.ui.main.MainScaffold
import com.kb.kbplatform.shell.ui.misc.AllDocsScreen
import com.kb.kbplatform.shell.ui.misc.ExamMineScreen
import com.kb.kbplatform.shell.ui.misc.FavoritesScreen
import com.kb.kbplatform.shell.ui.misc.HistoryScreen
import com.kb.kbplatform.shell.ui.misc.InboxScreen
import com.kb.kbplatform.shell.ui.misc.NotesScreen
import com.kb.kbplatform.shell.ui.misc.PointsScreen
import com.kb.kbplatform.shell.ui.misc.RankingScreen
import com.kb.kbplatform.shell.ui.misc.RecycleScreen
import com.kb.kbplatform.shell.ui.misc.SubscriptionsScreen
import com.kb.kbplatform.shell.ui.practice.ExamPracticeScreen
import com.kb.kbplatform.shell.ui.quick.QuickNoteScreen
import com.kb.kbplatform.shell.ui.reader.DocReaderScreen
import com.kb.kbplatform.shell.ui.search.SearchScreen
import com.kb.kbplatform.shell.ui.share.ShareImportScreen
import com.kb.kbplatform.shell.ui.theme.kbEnterBackward
import com.kb.kbplatform.shell.ui.theme.kbEnterForward
import com.kb.kbplatform.shell.ui.theme.kbExitBackward
import com.kb.kbplatform.shell.ui.theme.kbExitForward
import com.kb.kbplatform.shell.ui.web.WebScreen

/**
 * 根导航：原生五槽 TabBar 主框架（中央＋）+ 全部原生页面，UI 严格对标套壳 MobileXxx.vue。
 * 登录用套壳 v3 样式的原生页；能力层：应用锁/分享导入/更新/令牌保管。
 */
@Composable
fun AppRoot() {
    val token by TokenStore.token.collectAsState()
    val nav = rememberNavController()
    val appCtx = androidx.compose.ui.platform.LocalContext.current

    /**
     * 安全导航：路由拼错/页面被删时，Navigation 会抛
     * `IllegalArgumentException: Navigation destination that matches route X cannot be found`，
     * 且是在点击事件里抛的 → 直接闪退。这里兜住，只打日志。
     * （Tab 页不是路由，跳 Tab 要用 switchTab，见 MainScaffold。）
     *
     * 2026-10-27：全工程只剩这一处 `nav.navigate`，其余（含带参数的 web/exam/doc-edit）
     * 统一走 safeNav —— 之前它们是裸调，任何一个路由名写错都是秒退，而线上只能看到
     * 混淆后的堆栈，极难定位。
     */
    val safeNav: (String) -> Unit = { route ->
        try {
            nav.navigate(route)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("KbNav", "未知路由，已忽略: $route", e)
        }
    }

    // 站内通知落地成系统通知：仅当 App 不在前台（在前台顶部红点已经够了）
    LaunchedEffect(Unit) {
        com.kb.kbplatform.shell.data.SseClient.events.collect { obj ->
            if (!com.kb.kbplatform.shell.data.AppForeground.active) {
                val body = obj.optString("summary")
                    .ifBlank { obj.optString("content") }
                    .ifBlank { "你有一条新消息" }
                val who = obj.optString("actor")
                com.kb.kbplatform.shell.data.MsgNotify.show(
                    appCtx,
                    if (who.isBlank()) "知识库" else who,
                    body
                )
            }
        }
    }

    LaunchedEffect(token) {
        if (token != null) {
            SseClient.start()
            // 冷启动时先把身份拉一次：TokenStore.info 原本只在「我的」页加载后才填，
            // 导致首页头像/权限判断在第一次进「我的」之前都是空的。
            runCatching { TokenStore.setInfo(com.kb.kbplatform.shell.data.KbApi.me()) }
            // 冷启动就检查更新：原来只有进「我的」页才会 check，用户不点进去永远收不到升级提示
            runCatching { com.kb.kbplatform.shell.data.AppUpdater.check(appCtx) }
        } else {
            SseClient.stop()
            TokenStore.setInfo(null)
        }
    }

    if (token == null) {
        LoginScreen()
        return
    }

    // 页面转场（2026-10-26）：原来没配，Navigation 默认只有淡入淡出，层级感很弱。
    // 位移量对齐套壳 `.fade-page`（进 16px / 出 12px 那一套），这里取 20/14dp。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val slideInPx = with(density) { com.kb.kbplatform.shell.ui.theme.SLIDE_IN_DP.dp.roundToPx() }
    val slideOutPx = with(density) { com.kb.kbplatform.shell.ui.theme.SLIDE_OUT_DP.dp.roundToPx() }
    NavHost(
        navController = nav,
        startDestination = "main",
        enterTransition = { kbEnterForward(slideInPx) },
        exitTransition = { kbExitForward(slideOutPx) },
        popEnterTransition = { kbEnterBackward(slideOutPx) },
        popExitTransition = { kbExitBackward(slideInPx) }
    ) {
        // 主体验：原生五槽 TabBar（中央＋）+ 原生页面，全部对标套壳 MobileXxx.vue
        composable("main") {
            MainScaffold(
                openWeb = { path, title ->
                    safeNav("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                },
                openRoute = { route -> safeNav(route) }
            )
        }
        composable("capture") { CaptureScreen(onDone = { nav.popBackStack() }, openRoute = { r -> safeNav(r) }) }
        composable("quick") { QuickNoteScreen(onDone = { nav.popBackStack() }) }
        composable("scan") { com.kb.kbplatform.shell.ui.scan.ScanEditorScreen(onDone = { nav.popBackStack() }) }
        composable("drafts") { com.kb.kbplatform.shell.ui.draft.DraftsScreen(onBack = { nav.popBackStack() }) }
        composable(
            route = "embed/{kind}",
            arguments = listOf(navArgument("kind") { type = NavType.StringType })
        ) { entry ->
            com.kb.kbplatform.shell.ui.embed.EmbedScreen(
                kind = entry.arguments?.getString("kind") ?: "adminstats",
                onBack = { nav.popBackStack() }
            )
        }
        composable("clip") { ClipScreen(onDone = { nav.popBackStack() }) }
        composable("inbox") { InboxScreen(onBack = { nav.popBackStack() }, openWeb = { p, t ->
            safeNav("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }, openRoute = { r -> safeNav(r) }) }
        composable("favorites") { FavoritesScreen(onBack = { nav.popBackStack() }, openWeb = { p, t ->
            safeNav("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }, openRoute = { r -> safeNav(r) }) }
        composable("history") { HistoryScreen(onBack = { nav.popBackStack() }, openWeb = { p, t ->
            safeNav("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }, openRoute = { r -> safeNav(r) }) }
        composable("all-docs") { AllDocsScreen(onBack = { nav.popBackStack() }, openWeb = { p, t ->
            safeNav("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }, openRoute = { r -> safeNav(r) }) }
        composable("exam-mine") { ExamMineScreen(onBack = { nav.popBackStack() }) }
        composable("points") { PointsScreen(onBack = { nav.popBackStack() }) }
        composable("ranking") { RankingScreen(onBack = { nav.popBackStack() }) }
        composable("subscriptions") { SubscriptionsScreen(onBack = { nav.popBackStack() }) }
        composable("recycle") { RecycleScreen(onBack = { nav.popBackStack() }) }
        composable("notes") { NotesScreen(onBack = { nav.popBackStack() }) }
            composable("search") {
                SearchScreen(openWeb = { path, title ->
                    safeNav("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                }, openRoute = { r -> safeNav(r) })
            }
            composable("ai") {
                AiChatScreen(onBack = { nav.popBackStack() })
            }
            composable("cards") { CardsScreen() }
            composable("practice") { ExamPracticeScreen(onBack = { nav.popBackStack() }) }
            composable("share-import") { ShareImportScreen(onDone = { nav.popBackStack() }) }
            // 首页金刚区「扫一扫」：以 WebView 打开移动资产扫码页，自动起摄像头并复用登录态
            composable("asset-scan") {
                WebScreen(path = "/m/asset?scan=1", title = "扫一扫", onBack = { nav.popBackStack() })
            }
            composable("admin") {
                AdminHomeScreen(
                    onOpen = { r -> safeNav(r) },
                    onOpenWeb = { path, title ->
                        safeNav("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                    },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("admin/users") { AdminUsersScreen(onBack = { nav.popBackStack() }) }
            composable("admin/ai") { AdminAiScreen(onBack = { nav.popBackStack() }) }
            composable("admin/review") { AdminReviewScreen(onBack = { nav.popBackStack() }) }
            composable("admin/announce") { AdminAnnounceScreen(onBack = { nav.popBackStack() }) }
            // 站点设置（原生）：原来「站点维护」是把桌面后台表单塞进 WebView，手机上要左右拖
            composable("site-settings") {
                com.kb.kbplatform.shell.ui.admin.SiteSettingsScreen(
                    onBack = { nav.popBackStack() },
                    openWeb = { path, title ->
                        safeNav("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                    }
                )
            }
            composable("exams") {
                ExamCenterScreen(
                    openExam = { id, title ->
                        safeNav("exam/${id}?title=${Uri.encode(title)}")
                    },
                    openWeb = { path, title ->
                        safeNav("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                    },
                    openRoute = { route -> safeNav(route) }
                )
            }
            composable(
                route = "exam/{id}?title={title}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "考试" }
                )
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                val t = entry.arguments?.getString("title").orEmpty().ifBlank { "考试" }
                ExamTakeScreen(paperId = id, titleHint = t, onExit = { nav.popBackStack() })
            }
            composable(
                route = "reader/{shareId}/{docId}?title={title}",
                arguments = listOf(
                    navArgument("shareId") { type = NavType.LongType },
                    navArgument("docId") { type = NavType.LongType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "文档" }
                )
            ) { entry ->
                val sid = entry.arguments?.getLong("shareId") ?: return@composable
                val did = entry.arguments?.getLong("docId") ?: return@composable
                val t = entry.arguments?.getString("title").orEmpty().ifBlank { "文档" }
                DocReaderScreen(
                    shareId = sid,
                    docId = did,
                    titleHint = t,
                    onBack = { nav.popBackStack() },
                    onEdit = {
                        safeNav("doc-edit/$sid/$did?title=${Uri.encode(t)}")
                    }
                )
            }
            composable(
                route = "doc-edit/{shareId}/{docId}?title={title}",
                arguments = listOf(
                    navArgument("shareId") { type = NavType.LongType },
                    navArgument("docId") { type = NavType.LongType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "编辑" }
                )
            ) { entry ->
                val sid = entry.arguments?.getLong("shareId") ?: return@composable
                val did = entry.arguments?.getLong("docId") ?: return@composable
                val t = entry.arguments?.getString("title").orEmpty().ifBlank { "编辑" }
                com.kb.kbplatform.shell.ui.edit.DocEditScreen(
                    shareId = sid, docId = did, titleHint = t.removePrefix("编辑 · "),
                    onBack = { nav.popBackStack() }
                )
            }
            composable("courses") { com.kb.kbplatform.shell.ui.misc.CoursesScreen(
                onBack = { nav.popBackStack() },
                openCourse = { id, title -> safeNav("course/$id?title=${Uri.encode(title)}") }
            ) }
            composable(
                route = "course/{id}?title={title}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "课程" }
                )
            ) { entry ->
                com.kb.kbplatform.shell.ui.misc.CourseDetailScreen(
                    courseId = entry.arguments?.getLong("id") ?: 0L,
                    titleHint = entry.arguments?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
            composable("wrong-book") { com.kb.kbplatform.shell.ui.misc.WrongBookScreen(onBack = { nav.popBackStack() }) }
            composable("badges") { com.kb.kbplatform.shell.ui.misc.BadgesScreen(onBack = { nav.popBackStack() }) }
            composable("files") { com.kb.kbplatform.shell.ui.misc.FilesScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = "versions/{shareId}/{docId}",
                arguments = listOf(
                    navArgument("shareId") { type = NavType.LongType },
                    navArgument("docId") { type = NavType.LongType }
                )
            ) { entry ->
                com.kb.kbplatform.shell.ui.misc.VersionsScreen(
                    shareId = entry.arguments?.getLong("shareId") ?: 0L,
                    docId = entry.arguments?.getLong("docId") ?: 0L,
                    onBack = { nav.popBackStack() }
                )
            }
            composable("admin-roles") { com.kb.kbplatform.shell.ui.misc.AdminRolesScreen(onBack = { nav.popBackStack() }) }
            composable("admin-docs") { com.kb.kbplatform.shell.ui.misc.AdminDocsScreen(onBack = { nav.popBackStack() }) }
            composable("discover") { com.kb.kbplatform.shell.ui.misc.DiscoverScreen(
                onBack = { nav.popBackStack() },
                openPost = { id, title -> safeNav("post/$id?title=${Uri.encode(title)}") }
            ) }
            composable("stats") { com.kb.kbplatform.shell.ui.misc.StatsScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = "user/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                com.kb.kbplatform.shell.ui.misc.UserProfileScreen(
                    userId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                    openPost = { id, title -> safeNav("post/$id?title=${Uri.encode(title)}") }
                )
            }
            composable(
                route = "post/{id}?title={title}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "文章" }
                )
            ) { entry ->
                com.kb.kbplatform.shell.ui.misc.PostViewScreen(
                    postId = entry.arguments?.getLong("id") ?: 0L,
                    titleHint = entry.arguments?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
            composable("about") { com.kb.kbplatform.shell.ui.misc.AboutScreen(
                // 读构建产物里的版本号：原来写死 1.4.0-native，与 build.gradle 的 0.4.0 长期不一致
                versionName = com.kb.kbplatform.shell.BuildConfig.VERSION_NAME,
                onOpenChangelog = { safeNav("changelog") },
                onBack = { nav.popBackStack() }
            ) }
            composable("changelog") { com.kb.kbplatform.shell.ui.misc.StaticTextScreen("changelog", "更新日志", onBack = { nav.popBackStack() }) }
            composable("privacy") { com.kb.kbplatform.shell.ui.misc.StaticTextScreen("privacy", "隐私政策", onBack = { nav.popBackStack() }) }
            composable("disclaimer") { com.kb.kbplatform.shell.ui.misc.StaticTextScreen("disclaimer", "免责声明", onBack = { nav.popBackStack() }) }
            composable("appearance") { com.kb.kbplatform.shell.ui.misc.AppearanceScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = "web?path={path}&title={title}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; defaultValue = "/m/" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "页面" }
                )
            ) { entry ->
                WebScreen(
                    path = entry.arguments?.getString("path") ?: "/m/",
                    title = entry.arguments?.getString("title").orEmpty().ifBlank { "页面" },
                    onBack = { nav.popBackStack() }
                )
            }
        }
    

    // 全局更新弹窗：不再要求用户先进「我的」才看得到新版本
    GlobalUpdatePrompt()
}

/**
 * 全站更新提示（2026-10-26）。
 *
 * 之前更新只体现在「我的 → 检查更新」那一行文字里，用户不主动进去就永远不知道有新版本。
 * 现在冷启动检查到新版本就直接弹窗：说明 → 下载（带进度）→ 安装。
 * 强制更新（force=true）时不给「稍后」。
 */
@Composable
private fun GlobalUpdatePrompt() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st by com.kb.kbplatform.shell.data.AppUpdater.state.collectAsState()

    // 进入下载态时提示一次：告诉用户已经转到后台，可以继续用，不用守着
    val downloading = st is com.kb.kbplatform.shell.data.AppUpdater.State.Downloading
    androidx.compose.runtime.LaunchedEffect(downloading) {
        if (downloading) {
            android.widget.Toast.makeText(
                ctx, "已在后台下载，可以继续使用，完成后会提示安装", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    when (val s = st) {
        is com.kb.kbplatform.shell.data.AppUpdater.State.Available -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!s.force) com.kb.kbplatform.shell.data.AppUpdater.reset() },
            title = { androidx.compose.material3.Text("发现新版本 v" + s.versionName) },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (s.notes.isNotBlank()) {
                        androidx.compose.material3.Text(s.notes, fontSize = 13.sp)
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    }
                    androidx.compose.material3.Text(
                        "点「后台更新」后可以继续使用 App，下载完成会提示安装（首次需要允许「安装未知应用」）。",
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 通知被关掉时明确说一声，否则用户切走后完全看不到下载进度
                    if (!com.kb.kbplatform.shell.data.NotifyPerm.granted(ctx)) {
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.Text(
                            "通知未开启：下载进度不会显示在通知栏，可在「我的 → 通知提醒」里开启。",
                            fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    com.kb.kbplatform.shell.data.UpdateNotify.ensurePermission(ctx)
                    scope.launch { com.kb.kbplatform.shell.data.AppUpdater.download(ctx) }
                }) { androidx.compose.material3.Text("后台更新") }
            },
            dismissButton = if (s.force) null else {
                {
                    androidx.compose.material3.TextButton(onClick = {
                        com.kb.kbplatform.shell.data.AppUpdater.reset()
                    }) { androidx.compose.material3.Text("稍后") }
                }
            }
        )

        // 下载中**不弹窗**：点「立即更新」后自动转入后台下载，用户可以继续用 App。
        // 原来这里是模态且 onDismissRequest 为空、没有按钮 —— 22MB 弱网要几分钟，用户只能干等。
        // 进度改在「我的 → 检查更新」那一行显示，下载完再弹安装提示。
        is com.kb.kbplatform.shell.data.AppUpdater.State.Downloading -> Unit

        is com.kb.kbplatform.shell.data.AppUpdater.State.Ready -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.kb.kbplatform.shell.data.AppUpdater.reset() },
            title = { androidx.compose.material3.Text("更新包已就绪") },
            text = { androidx.compose.material3.Text("点击「安装」拉起系统安装器。") },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    when (com.kb.kbplatform.shell.data.AppUpdater.install(ctx)) {
                        com.kb.kbplatform.shell.data.AppUpdater.InstallResult.STARTED ->
                            com.kb.kbplatform.shell.data.AppUpdater.reset()
                        com.kb.kbplatform.shell.data.AppUpdater.InstallResult.NEED_PERMISSION ->
                            android.widget.Toast.makeText(
                                ctx, "请允许「安装未知应用」后再点安装", android.widget.Toast.LENGTH_LONG
                            ).show()
                        com.kb.kbplatform.shell.data.AppUpdater.InstallResult.FAILED ->
                            android.widget.Toast.makeText(
                                ctx, "安装失败，请到「我的 → 检查更新」重试", android.widget.Toast.LENGTH_LONG
                            ).show()
                    }
                }) { androidx.compose.material3.Text("安装") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.kb.kbplatform.shell.data.AppUpdater.reset()
                }) { androidx.compose.material3.Text("稍后") }
            }
        )

        // 下载失败：留在 App 内直接给出原因与重试入口（原来只有通知/「我的」页那一行，
        // 用户看到 Software caused connection abort 这种英文原文完全无从下手）
        is com.kb.kbplatform.shell.data.AppUpdater.State.Error -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.kb.kbplatform.shell.data.AppUpdater.reset() },
            title = { androidx.compose.material3.Text("更新下载失败") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(s.message, fontSize = 13.sp)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Text(
                        "已下载的部分会保留，点「重试」从断点继续；建议先切换网络（Wi-Fi / 移动数据）再试。",
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    scope.launch { com.kb.kbplatform.shell.data.AppUpdater.download(ctx) }
                }) { androidx.compose.material3.Text("重试") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.kb.kbplatform.shell.data.AppUpdater.reset()
                }) { androidx.compose.material3.Text("稍后") }
            }
        )

        else -> Unit
    }
}
