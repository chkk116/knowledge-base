package com.kb.kbplatform.bootstrap;

import com.kb.kbplatform.domain.Comment;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.FeatureFlag;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.CommentRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.service.FeatureFlagService;
import com.kb.kbplatform.security.PasswordUtil;
import com.kb.kbplatform.service.DocumentService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动种子数据：库空时写入测试账号、示例文档与示例评论，便于登录、搜索、评论开箱即用。
 * 受 application.yml 的 kb.seed.enabled 控制（默认 true）。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepo;
    private final DocumentService docService;
    private final CommentRepository commentRepo;
    private final FeatureFlagService flagSvc;
    private final boolean seedEnabled;

    public DataInitializer(UserRepository userRepo,
                           DocumentService docService,
                           CommentRepository commentRepo,
                           FeatureFlagService flagSvc,
                           @org.springframework.beans.factory.annotation.Value("${kb.seed.enabled:true}") boolean seedEnabled) {
        this.userRepo = userRepo;
        this.docService = docService;
        this.commentRepo = commentRepo;
        this.flagSvc = flagSvc;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) return;
        ensureSeededUsers();   // 幂等：库空则建，已存在则自愈（演示账号被禁用时自动恢复启用）
        if (docService.listAll().isEmpty()) seedDocs();
        seedRichDocs();        // 图文+表情包示例帖（按分类幂等，不重复写）
        seedOpsDoc();          // 知识库操作流程说明帖（按分类幂等，不重复写）
        if (commentRepo.count() == 0) seedComments();
        healSeedStatus();      // 自愈：示例帖（历史遗留内容）确保为 PUBLISHED，避免被测试/异常操作改成非发布态后从首页消失
        seedFeatureFlags();    // 核心维护：功能开关默认全部开启（幂等，不覆盖管理员手动关闭）
    }

    /**
     * 核心维护 · 功能开关默认值：首次启动写入全部功能（enabled=true）。
     * 委托给 FeatureFlagService.ensureDefaults（自带 @Transactional，跨 bean 调用确保事务生效，
     * 避免 ApplicationRunner 自调用导致的事务代理失效）。
     */
    private void seedFeatureFlags() {
        flagSvc.ensureDefaults(DEFAULT_FEATURES);
    }

    private static final java.util.List<FeatureFlag> DEFAULT_FEATURES = java.util.List.of(
            new FeatureFlag("ai_chat", "AI 问答", "基于知识库的智能问答；未录入训练数据时可暂时关闭", "ai", null),
            new FeatureFlag("courses", "培训课程", "学员侧培训课程浏览与学习", "learning", "/admin/courses"),
            new FeatureFlag("exams", "在线考试", "学员侧考试测评与成绩", "learning", "/admin/exams"),
            new FeatureFlag("quiz", "答题闯关", "轻量答题玩法", "learning", "/admin/exams"),
            new FeatureFlag("doc_shares", "共享文档", "团队协作空间", "collaboration", "/admin/doc-shares"),
            new FeatureFlag("docs", "知识文档", "文章浏览 / 检索 / 发布", "core", "/admin/docs"),
            new FeatureFlag("points", "积分体系", "积分获取 / 排行榜 / 明细", "core", "/admin/stats"),
            new FeatureFlag("ranking", "积分排行榜", "全站积分排名展示", "core", "/admin/stats"),
            new FeatureFlag("search", "全局搜索", "知识检索入口", "core", "/admin/docs"),
            new FeatureFlag("profile", "个人中心", "个人资料 / 收藏 / 历史", "core", "/admin/users")
    );

    private void ensureSeededUsers() {
        ensureUser("admin", "Admin@123", "ADMIN", "系统管理员");
        ensureUser("zhangsan", "Zhang@123", "USER", "张三");
        ensureUser("lisi", "Li@123", "USER", "李四");
    }

    /**
     * 幂等写入种子账号：不存在则创建；已存在则自愈——
     * 历史表中 enabled 因 ddl 加列隐式默认 false，这里保证种子演示账号始终可用。
     */
    private void ensureUser(String username, String pwd, String role, String displayName) {
        User existing = userRepo.findByUsername(username).orElse(null);
        if (existing == null) {
            User u = new User();
            u.setUsername(username);
            u.setPassword(PasswordUtil.encode(pwd));
            u.setRole(role);
            u.setDisplayName(displayName);
            u.setEnabled(true);
            userRepo.save(u);
        } else if (!existing.isEnabled()) {
            existing.setEnabled(true);
            userRepo.save(existing);
        }
    }

    private void seedDocs() {
        docService.create(mk("SpringBoot 入门指南", "教程", "java,入门",
                "Spring Boot 让你快速搭建独立运行的 Spring 应用。只需引入 starter 依赖，即可获得嵌入式 Tomcat、自动配置与起步依赖，几条注解就能跑起一个 REST 服务。"));
        docService.create(mk("MySQL 索引优化实战", "数据库", "mysql,索引,性能",
                "索引能显著提升查询速度。常见类型有 B+Tree 与哈希索引；为高频查询列建立联合索引，并注意最左前缀原则，避免出现隐式类型转换导致索引失效。"));
        docService.create(mk("Vue3 组合式 API 速记", "前端", "vue,前端",
                "Vue 3 推荐用 <script setup> 与组合式 API。ref 用于基本类型，reactive 用于对象；computed 派生状态，watch 监听变化，生命周期用 onMounted 等。"));
        docService.create(mk("RESTful 接口设计要点", "架构", "rest,api,设计",
                "REST 用名词复数表示资源，用 HTTP 方法表达动作：GET 查询、POST 新增、PUT 修改、DELETE 删除；用状态码表达结果，避免把动作写进 URL。"));
        docService.create(mk("知识库检索方案对比", "检索", "搜索,知识库",
                "关键词检索适合精确匹配，向量检索擅长语义理解。生产环境常用混合检索：向量语义 + BM25 关键词 + RRF 融合 + 重排，兼顾召回与精度。"));
    }

    private Document mk(String title, String category, String tags, String content) {
        Document d = new Document();
        d.setTitle(title);
        d.setCategory(category);
        d.setTags(tags);
        d.setContent(content);
        d.setOwnerId(1L);
        d.setStatus("PUBLISHED");
        return d;
    }

    /**
     * 图文 + 表情包示例帖：仅在尚无「图文示例」分类时写入，便于开箱看到带图片和表情的帖子。
     * 图片用内联 SVG（data URI），不依赖外网即可渲染。
     */
    private void seedRichDocs() {
        boolean exists = docService.listAll().stream()
                .anyMatch(d -> "图文示例".equals(d.getCategory()));
        if (exists) return;

        String cover1 = svgCover("#4f46e5", "📚", "知识库使用三步");
        docService.create(mkRich(
                "知识库使用图解 🚀",
                "图文示例",
                "教程,图文,入门",
                "<p>欢迎使用知识库！三步即可上手：</p>"
                        + "<p>① 在首页搜索框输入关键词，秒级检索文档 🔍</p>"
                        + "<p>② 登录后点击「写文章」发布你的笔记 ✍️</p>"
                        + "<p>③ 在文档详情页参与评论、收藏与分享 💬</p>"
                        + "<p style='text-align:center'>" + cover1 + "</p>"
                        + "<p>小提示：写文章时可插入<strong>图片</strong>和<strong>表情包</strong>，还能用🎤语音输入，写作更丝滑～ 😎</p>",
                cover1));

        String cover2 = svgCover("#0ea5e9", "😊", "表情包仓库");
        docService.create(mkRich(
                "表情包与写作小技巧 🎉",
                "图文示例",
                "表情,技巧,写作",
                "<p>一篇好笔记也可以很有趣！适当使用表情包能让内容更生动：</p>"
                        + "<p>👍 赞同 · 🔥 热门 · ❤️ 喜欢 · 🎯 重点 · ⚠️ 注意 · 💡 灵感</p>"
                        + "<p>排版建议：每段一个主题，关键句加粗，长文配图。下面是常用表情示意：</p>"
                        + "<p style='text-align:center;font-size:28px'>" + cover2 + "</p>"
                        + "<p>🤔 你也可以在编辑器点「表情」按钮挑选更多表情，或点 🎤 直接说话转文字哦！</p>",
                cover2));
    }

    /**
     * 知识库操作流程说明帖：仅在尚无「知识库操作流程」分类文章时写入，作者=管理员 chkk。
     * 内容用 div 内联样式（HtmlSanitizer 仅放行 div/p/span 的 style），「截图占位」框由管理员在编辑器替换真图。
     */
    private void seedOpsDoc() {
        boolean exists = docService.listAll().stream()
                .anyMatch(d -> "知识库操作流程".equals(d.getCategory()));
        if (exists) return;
        Long ownerId = userRepo.findByUsername("chkk").map(User::getId).orElse(1L);

        Document d = new Document();
        d.setTitle("知识库操作流程");
        d.setCategory("知识库操作流程");
        d.setTags("操作流程,知识库,使用指南");
        d.setContent("""
                <div style="font-size:24px;font-weight:700;color:#0f172a;margin:0 0 12px;">知识库操作流程</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">本文整理知识库系统的常用操作路径，供新用户快速上手。文中标注「截图占位」处请替换为对应界面截图。</div>
                <div style="font-size:18px;font-weight:600;margin-top:28px;padding-left:10px;border-left:4px solid #6366f1;color:#0f172a;">一、登录与进入</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">打开系统首页，点击右上角「登录」，使用账号密码完成登录；登录后左侧菜单展开个人相关入口。</div>
                <div style="border:2px dashed #cbd5e1;border-radius:10px;padding:22px;text-align:center;color:#94a3b8;background:#f8fafc;font-size:14px;margin:10px 0 18px;">📷 截图占位：首页右上角「登录」入口 / 登录弹窗</div>
                <div style="font-size:18px;font-weight:600;margin-top:28px;padding-left:10px;border-left:4px solid #6366f1;color:#0f172a;">二、浏览与检索知识</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">在首页顶部搜索框输入关键词回车，进入搜索结果页；也可通过左侧分类、标签浏览。空输入时点搜索会有提示，不会刷新页面。</div>
                <div style="border:2px dashed #cbd5e1;border-radius:10px;padding:22px;text-align:center;color:#94a3b8;background:#f8fafc;font-size:14px;margin:10px 0 18px;">📷 截图占位：首页搜索框 / 搜索结果页</div>
                <div style="background:#eef2ff;border-left:3px solid #4f46e5;padding:10px 14px;border-radius:8px;color:#4f46e5;font-size:13px;margin:10px 0;">提示：检索默认走本地数据库（LIKE），命中即列出相关文章与摘要。</div>
                <div style="font-size:18px;font-weight:600;margin-top:28px;padding-left:10px;border-left:4px solid #6366f1;color:#0f172a;">三、发布文章（写文章）</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">点击左侧菜单「写文章」，填写标题、选择分类（如本分类「知识库操作流程」），在正文中编写内容、插入图片与附件，确认后发布。</div>
                <div style="border:2px dashed #cbd5e1;border-radius:10px;padding:22px;text-align:center;color:#94a3b8;background:#f8fafc;font-size:14px;margin:10px 0 18px;">📷 截图占位：写文章编辑器（标题 / 分类选择 / 正文工具栏）</div>
                <div style="background:#eef2ff;border-left:3px solid #4f46e5;padding:10px 14px;border-radius:8px;color:#4f46e5;font-size:13px;margin:10px 0;">提示：分类需提前在「内容管理 → 分类标签」中创建；本文即发布在「知识库操作流程」分类下。</div>
                <div style="font-size:18px;font-weight:600;margin-top:28px;padding-left:10px;border-left:4px solid #6366f1;color:#0f172a;">四、分类与标签管理（管理员）</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">管理员进入「内容管理 → 分类标签」，可新建、重命名、调整分类与标签，作为知识组织的层级骨架。</div>
                <div style="border:2px dashed #cbd5e1;border-radius:10px;padding:22px;text-align:center;color:#94a3b8;background:#f8fafc;font-size:14px;margin:10px 0 18px;">📷 截图占位：分类标签管理页</div>
                <div style="font-size:18px;font-weight:600;margin-top:28px;padding-left:10px;border-left:4px solid #6366f1;color:#0f172a;">五、互动：评论 / 点赞 / 收藏</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">在文章详情页底部可发表评论；通过右上角按钮对文章点赞或加入收藏，收藏可在「我的收藏」中统一查看。</div>
                <div style="border:2px dashed #cbd5e1;border-radius:10px;padding:22px;text-align:center;color:#94a3b8;background:#f8fafc;font-size:14px;margin:10px 0 18px;">📷 截图占位：文章详情页互动区（评论框 + 点赞/收藏按钮）</div>
                <div style="font-size:18px;font-weight:600;margin-top:28px;padding-left:10px;border-left:4px solid #6366f1;color:#0f172a;">六、积分与排行榜</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">发布文章、评论、被点赞等行为累积积分；进入「积分排行榜」查看个人与全站排名，积分明细可在「个人资料」中分页查看。</div>
                <div style="border:2px dashed #cbd5e1;border-radius:10px;padding:22px;text-align:center;color:#94a3b8;background:#f8fafc;font-size:14px;margin:10px 0 18px;">📷 截图占位：积分排行榜页 / 个人资料积分明细</div>
                <div style="font-size:18px;font-weight:600;margin-top:28px;padding-left:10px;border-left:4px solid #6366f1;color:#0f172a;">七、AI 智能问答</div>
                <div style="margin:8px 0;color:#0f172a;line-height:1.8;">点击左侧菜单「AI 问答」，输入问题即可。系统优先基于知识库内容作答；库中没有的资料会转为通用问答，不会编造。</div>
                <div style="border:2px dashed #cbd5e1;border-radius:10px;padding:22px;text-align:center;color:#94a3b8;background:#f8fafc;font-size:14px;margin:10px 0 18px;">📷 截图占位：AI 问答对话框（问题 + 回答气泡）</div>
                <div style="background:#eef2ff;border-left:3px solid #4f46e5;padding:10px 14px;border-radius:8px;color:#4f46e5;font-size:13px;margin:10px 0;">提示：答案中结构化的列表、代码块会自动排版；右上角「复制」可一键拷贝回答。</div>
                """);
        d.setOwnerId(ownerId);
        d.setStatus("PUBLISHED");
        docService.create(d);
    }

    private Document mkRich(String title, String category, String tags, String content, String cover) {
        Document d = mk(title, category, tags, content);
        d.setTags(tags);
        return d;
    }

    /**
     * 示例/历史遗留帖子的固定标题集合。
     * 这些帖子由 seedDocs / seedRichDocs 直接写入（ownerId=1，状态写死 PUBLISHED），
     * 不是通过「写文章 → 提交审核」流程产生的，本就没有发布/审核生命周期。
     * 一旦被测试或异常操作改成 DRAFT / PENDING_REVIEW / BLOCKED，就会从「仅展示 PUBLISHED」的
     * 首页消失并 404。每次启动按标题把它们拉回 PUBLISHED，保证历史内容始终可见；不影响其他帖子。
     */
    private static final Set<String> SEED_TITLES = Set.of(
            "SpringBoot 入门指南",
            "MySQL 索引优化实战",
            "Vue3 组合式 API 速记",
            "RESTful 接口设计要点",
            "知识库检索方案对比",
            "知识库使用图解 🚀",
            "表情包与写作小技巧 🎉",
            "知识库操作流程"
    );

    /** 自愈：把被改成非发布态的示例帖恢复为 PUBLISHED（仅更新状态，不动标题/正文，也不重复发积分） */
    private void healSeedStatus() {
        for (Document d : docService.listAll()) {
            if (SEED_TITLES.contains(d.getTitle()) && !"PUBLISHED".equals(d.getStatus())) {
                Document patch = new Document();
                patch.setStatus("PUBLISHED");
                docService.update(d.getId(), patch, 1L);
            }
        }
    }

    /** 生成一张内联 SVG 封面图（base64 data URI），无需外网，且避免 # 被当作锚点 */
    private String svgCover(String color, String emoji, String text) {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='160'>"
                + "<rect width='320' height='160' rx='14' fill='" + color + "'/>"
                + "<text x='160' y='78' font-size='52' text-anchor='middle'>" + emoji + "</text>"
                + "<text x='160' y='120' font-size='18' fill='#ffffff' text-anchor='middle' "
                + "font-family='sans-serif'>" + text + "</text>"
                + "</svg>";
        String b64 = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return "<img src=\"data:image/svg+xml;base64," + b64 + "\" "
                + "style='max-width:320px;width:100%;border-radius:14px'/>";
    }

    private void seedComments() {
        if (docService.listAll().isEmpty()) return;
        Long firstDocId = docService.listAll().get(0).getId();
        addComment(firstDocId, "zhangsan", "写得很清楚，跟着跑了一遍就成功了，感谢分享！");
        addComment(firstDocId, "lisi", "建议补充一下多模块项目的目录结构示例。");
    }

    private void addComment(Long docId, String username, String content) {
        Comment c = new Comment();
        c.setDocId(docId);
        c.setUsername(username);
        c.setContent(content);
        commentRepo.save(c);
    }
}
