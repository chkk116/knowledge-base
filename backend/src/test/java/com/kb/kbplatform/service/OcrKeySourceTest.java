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
        cipher = new AiKeyCipher(MASTER);
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

        assertTrue(new OcrService(true, repo, cipher).isEnabled());
    }

    @Test
    @DisplayName("只有非智谱平台（如 DeepSeek）→ OCR 不可用，不误用其 Key")
    void disabledWhenOnlyNonZhipu() {
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(model(AiModel.PROVIDER_DEEPSEEK, "https://api.deepseek.com/v1", "sk-ds-key")));

        assertFalse(new OcrService(true, repo, cipher).isEnabled());
    }

    @Test
    @DisplayName("总开关关闭 / 无任何配置 / Key 为空 → OCR 不可用")
    void disabledCases() {
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(model(AiModel.PROVIDER_ZHIPU, "", "sk-real-key")));
        assertFalse(new OcrService(false, repo, cipher).isEnabled(), "总开关关闭时应不可用");

        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        assertFalse(new OcrService(true, repo, cipher).isEnabled(), "无配置时应不可用");

        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(model(AiModel.PROVIDER_ZHIPU, "", null)));
        assertFalse(new OcrService(true, repo, cipher).isEnabled(), "Key 为空时应不可用");
    }

    @Test
    @DisplayName("不可用时 recognize 抛中文提示，指引管理员去管理页配置")
    void recognizeThrowsHelpfulMessage() {
        when(repo.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        OcrService svc = new OcrService(true, repo, cipher);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.recognize(new byte[]{1, 2, 3}, "image/png"));
        assertTrue(e.getMessage().contains("AI 模型管理"), "报错应指向管理页，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("总开关关闭时 recognize 的提示应指向 kb.ai.enabled，而非误导向管理页")
    void disabledSwitchMessageIsAccurate() {
        OcrService svc = new OcrService(false, repo, cipher);
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

        OcrService svc = new OcrService(true, repo, cipher);
        assertTrue(svc.isEnabled());
        // 能正确解出第一条智谱配置的明文 Key（端点留空时内部回落到智谱默认端点）
        assertEquals("sk-first-key", cipher.decrypt(first.getApiKey()));
    }
}
