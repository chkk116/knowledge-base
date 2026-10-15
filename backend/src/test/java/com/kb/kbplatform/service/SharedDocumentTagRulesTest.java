/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedDocumentTagRulesTest {

    @Test
    @DisplayName("标签会去空格、忽略空项并按大小写不敏感去重")
    void normalizeTags_trimsAndDeduplicates() {
        assertEquals("项目资料,待阅读", DocShareService.normalizeDocTags(
                List.of(" 项目资料 ", "待阅读", "项目资料", "项目资料".toUpperCase())));
    }

    @Test
    @DisplayName("空标签列表清除标签；标签长度和数量受限")
    void normalizeTags_clearsAndEnforcesLimits() {
        assertEquals("", DocShareService.normalizeDocTags(List.of(" ", "")));
        assertThrows(ResponseStatusException.class,
                () -> DocShareService.normalizeDocTags(List.of("过长".repeat(13))));
        assertThrows(ResponseStatusException.class,
                () -> DocShareService.normalizeDocTags(List.of("a,b")));
        assertThrows(ResponseStatusException.class,
                () -> DocShareService.normalizeDocTags(List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10", "t11", "t12", "t13")));
    }

    @Test
    @DisplayName("从富文本文档识别图片、视频、附件与纯文本类型")
    void classifyContentType_detectsContentTypes() {
        assertEquals("IMAGE", DocShareService.classifyContentType("<p><img src='/uploads/a.png'></p>"));
        assertEquals("VIDEO", DocShareService.classifyContentType("<video src='/uploads/a.mp4'></video>"));
        assertEquals("FILE", DocShareService.classifyContentType("<a href='/uploads/a.pdf'>PDF</a>"));
        assertEquals("TEXT", DocShareService.classifyContentType("<p>meeting notes</p>"));
    }
}
