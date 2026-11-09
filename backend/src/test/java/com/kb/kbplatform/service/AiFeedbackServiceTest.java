/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiQaLog;
import com.kb.kbplatform.repository.AiFeedbackActionRepository;
import com.kb.kbplatform.repository.AiQaLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 回答反馈的评分规则测试：取消（rating=0）语义、reason 保留规则、非法 rating 拒绝。
 *
 * 不用 Mockito（本机 JDK 下 inline/subclass MockMaker 均初始化失败）：repository 接口
 * 用 JDK 动态代理手写 stub，只拦 rate() 实际调用的 findByIdAndUserId / save 两个方法。
 */
class AiFeedbackServiceTest {

    private AiQaLog log;
    /** 内存存储：模拟 save 写回，验证「取消清空」确实落库 */
    private Map<Long, AiQaLog> store;

    private AiFeedbackService newSvc() {
        AiQaLogRepository repo = (AiQaLogRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{AiQaLogRepository.class},
                (p, method, args) -> switch (method.getName()) {
                    case "findByIdAndUserId" -> {
                        Long qaId = (Long) args[0];
                        Long userId = (Long) args[1];
                        AiQaLog found = (qaId.equals(1L) && userId.equals(8L)) ? store.get(1L) : null;
                        yield Optional.ofNullable(found);
                    }
                    case "save" -> args[0];
                    case "toString" -> "stub-repo";
                    default -> null;   // rate() 只用上面两个方法，其余不触达
                });
        // 二期加了处理状态仓库；本测试只覆盖 rate()，用空代理即可（不触达其方法）
        AiFeedbackActionRepository actionRepo = (AiFeedbackActionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{AiFeedbackActionRepository.class},
                (p, method, args) -> switch (method.getName()) {
                    case "toString" -> "stub-actions";
                    default -> null;
                });
        return new AiFeedbackService(repo, null, actionRepo);
    }

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        log = new AiQaLog();
        log.setId(1L);
        log.setUserId(8L);
        store.put(1L, log);
    }

    /** rating=0 = 取消评价：rating/reason/ratedAt 全部清空 */
    @Test
    void cancelClearsRatingReasonAndTime() {
        log.setRating(-1);
        log.setReason("答非所问");
        log.setRatedAt(LocalDateTime.now());

        newSvc().rate(8L, 1L, 0, "");

        assertNull(log.getRating());
        assertNull(log.getReason());
        assertNull(log.getRatedAt());
    }

    /** 未评价过的记录也能安全取消（幂等，不抛错） */
    @Test
    void cancelOnUnratedIsIdempotent() {
        log.setRating(null);
        assertDoesNotThrow(() -> newSvc().rate(8L, 1L, 0, ""));
        assertNull(log.getRating());
        assertNull(log.getRatedAt());
    }

    /** 踩保留 reason；赞/取消清空 reason */
    @Test
    void reasonOnlyKeptForDislike() {
        newSvc().rate(8L, 1L, -1, "没有依据");
        assertEquals(-1, log.getRating());
        assertEquals("没有依据", log.getReason());
        assertNotNull(log.getRatedAt());

        newSvc().rate(8L, 1L, 1, "");
        assertEquals(1, log.getRating());
        assertNull(log.getReason());

        newSvc().rate(8L, 1L, 0, "");
        assertNull(log.getRating());
        assertNull(log.getReason());
    }

    /** 0/±1 之外的 rating 直接 400（含 null） */
    @Test
    void invalidRatingRejected() {
        for (Integer bad : new Integer[]{2, -2, null}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> newSvc().rate(8L, 1L, bad, ""));
            assertEquals(400, e.getStatusCode().value());
        }
    }

    /** 只能评价自己的记录：他人 qaId → 404 */
    @Test
    void foreignQaIdRejected() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> newSvc().rate(8L, 9L, 1, ""));
        assertEquals(404, e.getStatusCode().value());
    }
}
