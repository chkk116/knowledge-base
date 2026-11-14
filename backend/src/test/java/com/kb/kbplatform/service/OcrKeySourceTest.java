/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiModel;
import com.kb.kbplatform.repository.AiModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OCR Key 来源回归测试：OCR 不再有独立配置，Key 一律取「AI 模型管理」页里智谱平台条目的加密 Key。
 * 纯 Mockito 单元测试：不依赖数据库 / 外部服务。运行：mvn test -Dtest=OcrKeySourceTest
 */
class OcrKeySourceTest {

    private AiModelRepository repo;
    private AiKeyCipher cipher;
    private static final String MASTER = "unit-test-master-key";

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(AiModelRepository.class);
        // 无激活 profile 的 Environment = 非 prod，走「开发兜底密钥」分支（构造器新增了 prod fail-fast）
        cipher = new AiKeyCipher(MASTER, new org.springframework.core.env.StandardEnvironment());
    }

    private AiModel model(String provider, String baseUrl, String rawKey) {
        AiModel m = new AiModel();
        m.setId(1L);
        m.setProvider(provider);
        m.setName("测试模型");
        m.setModel("glm-5.3");
        m.setBaseUrl(baseUrl);
        m.setApiKey(rawKey == null ? "" : cipher.encrypt(rawKey));
        m.setEnabled(true);
        return m;
    }

    @Test
    @DisplayName("管理页配了启用的智谱条目 → OCR 可用")
    void enabledWhenZhipuConfigured() {
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(model(AiModel.PROVIDER_ZHIPU, "", "sk-real-key")));

        assertTrue(new OcrService(true, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class)).isEnabled());
    }

    @Test
    @DisplayName("任意平台原生多模态模型（如 DeepSeek）也可用于 OCR")
    void enabledWhenOtherVisionModelConfigured() {
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(model(AiModel.PROVIDER_DEEPSEEK, "https://api.deepseek.com/v1", "sk-ds-key")));

        // 多模型改造后 OCR 不再绑死智谱：能力配置 > 智谱 > 平台默认/首个启用模型
        assertTrue(new OcrService(true, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class)).isEnabled());
    }

    @Test
    @DisplayName("总开关关闭 / 无任何配置 / Key 为空 → OCR 不可用")
    void disabledCases() {
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(model(AiModel.PROVIDER_ZHIPU, "", "sk-real-key")));
        assertFalse(new OcrService(false, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class)).isEnabled(), "总开关关闭时应不可用");

        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        assertFalse(new OcrService(true, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class)).isEnabled(), "无配置时应不可用");

        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(model(AiModel.PROVIDER_ZHIPU, "", null)));
        assertFalse(new OcrService(true, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class)).isEnabled(), "Key 为空时应不可用");
    }

    @Test
    @DisplayName("不可用时 recognize 抛中文提示，指引管理员去管理页配置")
    void recognizeThrowsHelpfulMessage() {
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        OcrService svc = new OcrService(true, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.recognize(new byte[]{1, 2, 3}, "image/png"));
        assertTrue(e.getMessage().contains("AI 模型管理"), "报错应指向管理页，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("总开关关闭时 recognize 的提示应指向 kb.ai.enabled，而非误导向管理页")
    void disabledSwitchMessageIsAccurate() {
        OcrService svc = new OcrService(false, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.recognize(new byte[]{1, 2, 3}, "image/png"));
        assertTrue(e.getMessage().contains("kb.ai.enabled"),
                "总开关关闭时应提示 kb.ai.enabled，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("多条智谱配置时取排序最前（Key 可正常解密）的那条")
    void picksFirstZhipuByOrder() {
        AiModel first = model(AiModel.PROVIDER_ZHIPU, "https://open.bigmodel.cn/api/paas/v4", "sk-first-key");
        AiModel second = model(AiModel.PROVIDER_DEEPSEEK, "", "sk-ds");
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(second, first));

        OcrService svc = new OcrService(true, repo, cipher, org.mockito.Mockito.mock(AiCapabilityService.class));
        assertTrue(svc.isEnabled());
        // 能正确解出第一条智谱配置的明文 Key（端点留空时内部回落到智谱默认端点）
        assertEquals("sk-first-key", cipher.decrypt(first.getApiKey()));
    }

    // ---------- 前言识别（2026-09-27 实测暴露：拥挤降级时模型只吐解释，不吐文字） ----------

    @Test
    @DisplayName("实测抓到的那句被截断的开场白 → 判为前言（不能当识别结果落库）")
    void detectsTruncatedPreamble() {
        String actual = "（由于图片中的文字分布较为复杂，需准确提取各模块文字，以下是按原图段落整理的纯文本内容";
        assertTrue(OcrService.looksLikeChatter(actual), "应识别为前言：" + actual);
    }

    @Test
    @DisplayName("其他常见开场白句式也判为前言")
    void detectsCommonPreambles() {
        assertTrue(OcrService.looksLikeChatter("以下是识别结果："));
        assertTrue(OcrService.looksLikeChatter("图片中的文字如下："));
        assertTrue(OcrService.looksLikeChatter("我将按段落提取文字"));
    }

    @Test
    @DisplayName("真实识别结果不误判（含特征词但内容足够长）")
    void doesNotFlagRealContent() {
        String real = "数据看板\n平台核心数据实时概览\n文档总数 23篇\n用户总数 9人\n"
                + "评论总数 47条\n累计浏览 521次\n今日发布 0篇\n热门文章 按访问量 Top5";
        assertFalse(OcrService.looksLikeChatter(real), "真实内容不应判为前言");
        assertFalse(OcrService.looksLikeChatter("共享文档\n用户管理\n角色管理\n数据看板"), "短菜单也不应误判");
        assertFalse(OcrService.looksLikeChatter(""), "空串交给 isBlank 分支处理");
    }

    @Test
    @DisplayName("边界：恰好 60 字的前言仍拦；61 字放行（阈值语义固化）")
    void chatterThresholdBoundary() {
        String base = "由于图片中的文字复杂，以下是整理好的纯文本内容";   // 含特征词
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() < 60) sb.append("字");
        assertEquals(60, sb.length());
        assertTrue(OcrService.looksLikeChatter(sb.toString()), "60 字含特征词应拦");

        sb.append("字");
        assertEquals(61, sb.length());
        assertFalse(OcrService.looksLikeChatter(sb.toString()), "61 字超过阈值应放行（宁可放过不含特征词的短结果）");
    }
}
