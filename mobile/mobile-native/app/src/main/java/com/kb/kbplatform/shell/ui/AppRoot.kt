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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

    LaunchedEffect(token) {
        if (token != null) SseClient.start() else SseClient.stop()
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
                    nav.navigate("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                },
                openRoute = { route -> nav.navigate(route) }
            )
        }
        composable("capture") { CaptureScreen(onDone = { nav.popBackStack() }, openRoute = { r -> nav.navigate(r) }) }
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
            nav.navigate("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }) }
        composable("favorites") { FavoritesScreen(onBack = { nav.popBackStack() }, openWeb = { p, t ->
            nav.navigate("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }) }
        composable("history") { HistoryScreen(onBack = { nav.popBackStack() }, openWeb = { p, t ->
            nav.navigate("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }) }
        composable("all-docs") { AllDocsScreen(onBack = { nav.popBackStack() }, openWeb = { p, t ->
            nav.navigate("web?path=${Uri.encode(p)}&title=${Uri.encode(t)}")
        }) }
        composable("exam-mine") { ExamMineScreen(onBack = { nav.popBackStack() }) }
        composable("points") { PointsScreen(onBack = { nav.popBackStack() }) }
        composable("ranking") { RankingScreen(onBack = { nav.popBackStack() }) }
        composable("subscriptions") { SubscriptionsScreen(onBack = { nav.popBackStack() }) }
        composable("recycle") { RecycleScreen(onBack = { nav.popBackStack() }) }
        composable("notes") { NotesScreen(onBack = { nav.popBackStack() }) }
            composable("search") {
                SearchScreen(openWeb = { path, title ->
                    nav.navigate("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                })
            }
            composable("ai") {
                AiChatScreen(onBack = { nav.popBackStack() })
            }
            composable("cards") { CardsScreen() }
            composable("practice") { ExamPracticeScreen() }
            composable("share-import") { ShareImportScreen(onDone = { nav.popBackStack() }) }
            composable("admin") {
                AdminHomeScreen(
                    onOpen = { r -> nav.navigate(r) },
                    onOpenWeb = { path, title ->
                        nav.navigate("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                    },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("admin/users") { AdminUsersScreen(onBack = { nav.popBackStack() }) }
            composable("admin/ai") { AdminAiScreen(onBack = { nav.popBackStack() }) }
            composable("admin/review") { AdminReviewScreen(onBack = { nav.popBackStack() }) }
            composable("admin/announce") { AdminAnnounceScreen(onBack = { nav.popBackStack() }) }
            composable("exams") {
                ExamCenterScreen(
                    openExam = { id, title ->
                        nav.navigate("exam/${id}?title=${Uri.encode(title)}")
                    },
                    openWeb = { path, title ->
                        nav.navigate("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                    },
                    openRoute = { route -> nav.navigate(route) }
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
                        nav.navigate("doc-edit/$sid/$did?title=${Uri.encode(t)}")
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
                openCourse = { id, title -> nav.navigate("course/$id?title=${Uri.encode(title)}") }
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
                openPost = { id, title -> nav.navigate("post/$id?title=${Uri.encode(title)}") }
            ) }
            composable("stats") { com.kb.kbplatform.shell.ui.misc.StatsScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = "user/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                com.kb.kbplatform.shell.ui.misc.UserProfileScreen(
                    userId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                    openPost = { id, title -> nav.navigate("post/$id?title=${Uri.encode(title)}") }
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
                onOpenChangelog = { nav.navigate("changelog") },
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
    }
