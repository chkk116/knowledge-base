/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.bootstrap;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.PasswordUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 首次启动管理员引导回归测试。纯 Mockito，不依赖数据库。
 * 运行：mvn test -Dtest=AdminBootstrapTest
 */
class AdminBootstrapTest {

    private UserRepository repo() { return mock(UserRepository.class); }

    @Test
    @DisplayName("配置了 KB_ADMIN_* 且用户不存在 → 创建一个启用状态的管理员")
    void createsAdminWhenAbsent() {
        UserRepository repo = repo();
        when(repo.findByUsername("ops")).thenReturn(Optional.empty());

        new AdminBootstrap(repo, "ops", "Ops@123456").run(null);

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(repo).save(cap.capture());
        User saved = cap.getValue();
        assertEquals("ops", saved.getUsername());
        assertEquals("ADMIN", saved.getRole());
        assertTrue(saved.isEnabled());
        assertTrue(PasswordUtil.matches("Ops@123456", saved.getPassword()), "密码应为环境变量里的值");
    }

    @Test
    @DisplayName("用户已存在 → 不做任何变更（绝不提权 / 启用 / 覆盖密码）")
    void doesNotOverwritePassword() {
        UserRepository repo = repo();
        User existing = new User();
        existing.setUsername("ops");
        existing.setPassword(PasswordUtil.encode("OriginalPwd@1"));
        existing.setRole("USER");
        existing.setEnabled(false);
        when(repo.findByUsername("ops")).thenReturn(Optional.of(existing));

        new AdminBootstrap(repo, "ops", "EnvPwd@999").run(null);

        // 2026-10-06 安全审查后语义收敛：已存在账号一律不动，防开放注册抢注引导名后重启提权
        assertEquals("USER", existing.getRole());
        assertFalse(existing.isEnabled());
        verify(repo, never()).save(any());
        assertTrue(PasswordUtil.matches("OriginalPwd@1", existing.getPassword()), "原密码必须保持不变");
        assertFalse(PasswordUtil.matches("EnvPwd@999", existing.getPassword()), "不得被环境变量里的密码覆盖");
    }

    @Test
    @DisplayName("未配置环境变量 → 不建号；但会检查是否存在管理员")
    void noEnvNoCreate() {
        UserRepository repo = repo();
        when(repo.countByRoleAndEnabled("ADMIN", true)).thenReturn(1L);

        new AdminBootstrap(repo, "", "").run(null);

        verify(repo, never()).save(any());
        verify(repo).countByRoleAndEnabled("ADMIN", true);
    }

    @Test
    @DisplayName("未配置环境变量且库中无管理员 → 仍不建号（只提示），且不误调 save")
    void noEnvNoAdminStillNoCreate() {
        UserRepository repo = repo();
        when(repo.countByRoleAndEnabled("ADMIN", true)).thenReturn(0L);

        new AdminBootstrap(repo, "", "").run(null);

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("只配置了用户名没配置密码 → 视为未配置，不建号")
    void halfConfiguredIsIgnored() {
        UserRepository repo = repo();
        when(repo.countByRole("ADMIN")).thenReturn(1L);

        new AdminBootstrap(repo, "ops", "").run(null);

        verify(repo, never()).findByUsername(any());
        verify(repo, never()).save(any());
    }
}
