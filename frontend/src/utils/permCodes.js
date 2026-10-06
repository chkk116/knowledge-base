// 细粒度权限码清单（与后端 security/Perm.WHITELIST 保持一致，权限码变更时两处同步）
// 按业务域分组：角色管理的勾选区按此分组渲染，顺序即页面呈现顺序。
// PERM_OPTIONS 由分组摊平导出，供「完全管理员判定」等需要全量清单的地方使用。
export const PERM_GROUPS = [
  {
    title: '内容与审核',
    perms: [
      { value: 'DOC_REVIEW', label: '文章审核' },
      { value: 'POST_MANAGE', label: '帖子管理' },
      { value: 'TAXONOMY_MANAGE', label: '分类标签管理' },
      { value: 'BANNED_WORDS_MANAGE', label: '违禁词管理' },
      { value: 'AVATAR_REVIEW', label: '头像审核' }
    ]
  },
  {
    title: '共享文档',
    perms: [
      { value: 'SHARE_CREATE', label: '创建团队空间' },
      { value: 'SHARE_VIEW', label: '共享空间（查看）' },
      { value: 'SHARE_DELETE', label: '共享空间（删除）' },
      { value: 'SHARE_CAPACITY', label: '共享空间（容量分配）' }
    ]
  },
  {
    title: '培训与考核',
    perms: [
      { value: 'COURSE_MANAGE', label: '培训管理' },
      { value: 'EXAM_MANAGE', label: '考试管理' }
    ]
  },
  {
    title: '系统与运营',
    perms: [
      { value: 'USER_MANAGE', label: '用户管理' },
      { value: 'STATS_VIEW', label: '数据看板' },
      { value: 'ANNOUNCEMENT_MANAGE', label: '公告管理' },
      { value: 'DOC_EXPORT', label: '文档导出' },
      { value: 'ASSET_MANAGE', label: '资产管理' },
      { value: 'BLACKLIST_MANAGE', label: '黑名单管理' },
      { value: 'CORE_MAINTAIN', label: '功能开关与站点设置' },
      { value: 'AI_MANAGE', label: 'AI 模型管理' }
    ]
  },
  {
    title: '演示与约束',
    perms: [
      { value: 'ADMIN_VIEW', label: '后台只读浏览' },
      { value: 'DEMO_MODE', label: '演示权限（只读，禁止改动数据）' }
    ]
  }
]

// 演示权限（DEMO_MODE）：不是「能力」，而是「只读约束」标记。
// 给账号/角色勾上后，后台一切页面照常可看，但保存/删除/发布等写操作会被服务端拒绝并提示「演示账户不能…」。
// ADMIN_VIEW 是真实权限码（可查看审计/登录日志/附件/检索测试等原本仅管理员可见的只读页面）。
export const DEMO_PERM = { value: 'DEMO_MODE', label: '演示权限（只读，禁止改动数据）' }

/** 摊平的真实权限码（顺序 = 分组呈现顺序；**不含 DEMO_MODE**——它是只读约束标记而非能力，
 *  故「覆盖全集 = 完全管理员」判定不应把它算进去） */
export const PERM_OPTIONS = PERM_GROUPS.flatMap((g) => g.perms).filter((o) => o.value !== 'DEMO_MODE')

/** 判定一组权限码是否覆盖全部管理员权限码（完全管理员判定，忽略大小写/空格） */
export function isFullAdminPerms(perms) {
  const set = new Set((perms || []).map((s) => String(s).trim().toUpperCase()).filter(Boolean))
  return PERM_OPTIONS.every((o) => set.has(o.value))
}

/** 把多个角色的权限串合并成一个 Set（用于「所选角色合计权限」的覆盖判定） */
export function unionRolePerms(roleList) {
  const set = new Set()
  for (const r of roleList || []) {
    if (!r) continue
    const perms = typeof r.permissions === 'string'
      ? r.permissions.split(',').map((s) => s.trim()).filter(Boolean)
      : (r.permissions || [])
    perms.forEach((p) => set.add(p.toUpperCase()))
  }
  return set
}
