// 技能手册拼装：只把启用技能组的工具条目放进 prompt，全局工作方式规则常驻。
// 拆出来的唯一目的：文档问答场景不用再为「图片/视频/出题/流程图」的手册付 token。

/** 与工具无关的行为约束：任何技能组合下都必须常驻，裁掉会让 Agent 行为退化 */
export const WORK_RULES = `工作方式要求：
- 用户说「审查/优化/改写我某篇文档」时：先用 list_my_docs（或 search_kb）定位到文档 → read_doc 读全文 → 分析 → 用 update_doc 写回优化后的**完整正文**（不是只给建议，要真正落地）。
- 用户说「写一篇…」「帮我整理成文档」「存进知识库」时：用 create_doc 真正新建，不要只在回答里复述内容。
- 想让用户看某篇文档时，用 navigate 直接跳过去；**路径优先用工具返回的「打开地址」**，别自己拼。
- 正文用 Markdown 写（标题 #、列表 -、加粗 **、代码块 \`\`\`）。
- **预计消耗大的操作先问用户**：例如要连续读 3 篇以上长文档、反复多次调用工具、或写入很长的正文时，
  先用 ask_user 说明「这一步会消耗较多 token」并确认，别闷头把额度烧掉。
- 工具返回「失败 / 超时」时，**不要用同样的参数再调一次**；最多调整一次参数，仍失败就如实告诉用户失败原因并停下。
  把步数全耗在重复同一个调用上，用户只会看到一条报错。
- 信息足够时直接给出最终答案（不要再输出 TOOL 行）。不要编造工具。`

const HEADER = `可用工具（需要时输出一行 TOOL 调用，格式严格为：
TOOL: {"tool":"工具名","args":{...}}
工具列表：`

/**
 * 按启用的技能组拼手册。
 * @param {Array} tools  registry 里的工具（保持声明顺序）
 * @param {string[]|null} enabledGroupIds null / 空 = 全部启用
 * @returns {{ text: string, tools: Array }} text 可直接进 prompt；tools 为本次实际可用的工具（执行器用同一份过滤结果）
 */
export function buildManual(tools, enabledGroupIds = null) {
  const enabled = (!enabledGroupIds || !enabledGroupIds.length)
    ? tools
    : tools.filter((t) => enabledGroupIds.includes(t.group))
  // 编号按本次实际可用的工具重排（老手册里 8/9 各出现两次，模型容易选错）
  const lines = enabled.map((t, i) => `${i + 1}. ${t.manual}`)
  return {
    text: [HEADER, ...lines, '', WORK_RULES].join('\n'),
    tools: enabled
  }
}

/** 粗略估算 token：中文按 ~1.6 字符/token，ASCII 按 ~4 字符/token（仅用于对比降幅，不是计费值） */
export function estimateTokens(text) {
  let cjk = 0
  let other = 0
  for (const ch of text) {
    if (/[\u3400-\u9fff\u3000-\u303f\uff00-\uffef]/.test(ch)) cjk++
    else other++
  }
  return Math.round(cjk / 1.6 + other / 4)
}
