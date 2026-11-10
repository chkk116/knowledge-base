/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DmMessage;
import com.kb.kbplatform.repository.DmMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 私信附件访问控制的负面测试：拿到文件名不等于拿到图片。
 *
 * 不用 Mockito（本机 JDK 下 MockMaker 初始化失败），repository 用 JDK 动态代理手写 stub，
 * 只拦 attachmentPath / recall 实际触达的方法。
 */
class DmAttachmentTest {

    @TempDir
    Path temp;

    private static final String NAME = "0123456789abcdef0123456789abcdef.png";
    private static final String REF = "dm:" + NAME;

    private DmMessage imageMsg() {
        DmMessage m = new DmMessage();
        m.setId(99L);
        m.setConvKey(DmMessage.convKey(1L, 2L));
        m.setFromId(1L);
        m.setToId(2L);
        m.setKind("IMAGE");
        m.setContent(REF);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    /** 可变的消息列表：撤回后把它清空，模拟 content 已被抹掉 */
    private DmService newSvc(List<DmMessage> rows) {
        DmMessageRepository msgs = (DmMessageRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DmMessageRepository.class},
                (p, method, args) -> switch (method.getName()) {
                    case "findByContent" -> new ArrayList<>(rows);
                    case "findById" -> rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
                    case "save" -> args[0];
                    case "toString" -> "stub-msgs";
                    default -> null;
                });
        // recall 只会用到 msgs 与 files；audit / sse 的调用各自包在 try/catch 里，
        // 传 null 抛出的 NPE 会被吞掉，正好不影响「撤回删附件」这条主线的验证。
        return new DmService(msgs, null, null, null, null, null, null, fileSvc());
    }

    private FileService fileSvc() {
        try {
            return new FileService(temp.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path putImage() throws Exception {
        Path dir = temp.resolve("dm");
        Files.createDirectories(dir);
        Path f = dir.resolve(NAME);
        Files.write(f, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        return f;
    }

    /** 关键负面场景：第三个人即使拿到 dm:<name> 引用，也读不到图 */
    @Test
    void outsiderCannotReadAttachment() throws Exception {
        putImage();
        DmService svc = newSvc(List.of(imageMsg()));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> svc.attachmentPath(3L, REF));
        assertEquals(404, ex.getStatusCode().value());
    }

    /** 会话双方（发送方 / 接收方）都能读到 */
    @Test
    void participantsCanReadAttachment() throws Exception {
        Path f = putImage();
        DmService svc = newSvc(List.of(imageMsg()));
        assertEquals(f, svc.attachmentPath(1L, REF));
        assertEquals(f, svc.attachmentPath(2L, REF));
    }

    /** 引用格式严格白名单：../ 路径穿越与任意明文路径一律解析失败 */
    @Test
    void pathTraversalRejected() {
        FileService files = fileSvc();
        assertNull(files.resolveDmRef("dm:../../etc/passwd"));
        assertNull(files.resolveDmRef("dm:" + NAME + "/../secret.png"));
        assertNull(files.resolveDmRef("/uploads/../../etc/passwd"));
        assertNull(files.resolveDmRef("/uploads/2026/11/10/" + NAME.substring(0, 8) + ";rm"));
        assertNull(files.resolveDmRef("javascript:alert(1)"));
    }

    /** 撤回：content 清空 + 私有目录里的磁盘文件同步删除，且删除后不再可读 */
    @Test
    void recallDeletesAttachment() throws Exception {
        Path f = putImage();
        List<DmMessage> rows = new ArrayList<>(List.of(imageMsg()));
        DmService svc = newSvc(rows);
        assertTrue(Files.exists(f));

        svc.recall(1L, 99L);
        assertFalse(Files.exists(f), "撤回后附件应已从磁盘删除");
        assertEquals("", rows.get(0).getContent());
        assertEquals("RECALL", rows.get(0).getKind());

        rows.clear();   // 数据库里 content 已被清空，按引用查不到任何消息
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> svc.attachmentPath(1L, REF));
        assertEquals(404, ex.getStatusCode().value());
    }
}
