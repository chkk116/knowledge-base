package com.kb.desktop

/**
 * Agent 内容生成纯函数回归测试（gradle runAgentSmoke）。
 *
 * 覆盖：
 *  - mdToHtml：标题/列表/粗体/代码块转义、外站图片拦截
 *  - buildFlowchartGraphFromJson：节点去重、自环/无效边/重复边过滤、自动排版
 *  - flowchartHtml：TipTap 图块 div 的属性格式（后端 HtmlSanitizer 放行 data-type/data/title）
 */
fun main() {
    val html = AgentContent.mdToHtml(
        "# 标题\n- 列表项 A\n- 列表项 B\n\n这是 **加粗** 正文\n\n```\ncode<>&\n```\n![x](https://evil.com/t.png)",
    )
    println("MD_HTML=$html")
    check(html.contains("<h1>标题</h1>")) { "标题未生成" }
    check(html.contains("<li>列表项 A</li>")) { "列表未生成" }
    check(html.contains("<strong>加粗</strong>")) { "粗体未生成" }
    check(html.contains("<pre>") && html.contains("code&lt;&gt;&amp;")) { "代码块未转义" }
    check(!html.contains("src=\"https://evil.com")) { "外站图片未拦截" }

    val nodesJson = """[{"id":"1","label":"开始","shape":"ellipse"},{"id":"2","label":"审批","shape":"diamond"},{"id":"3","label":"结束","shape":"ellipse"},{"id":"1","label":"重复应去重"}]"""
    val edgesJson = """[{"from":"1","to":"2","label":"提交"},{"from":"2","to":"3","label":"通过"},{"from":"2","to":"2","label":"自环应过滤"},{"from":"9","to":"3","label":"无效节点应过滤"}]"""
    val (json, counts) = AgentContent.buildFlowchartGraphFromJson(nodesJson, edgesJson)!!
    println("GRAPH=$json")
    println("COUNTS=$counts")
    check(counts.first == 3) { "节点去重失败" }
    check(counts.second == 2) { "连线过滤失败" }
    check(json.contains("\"type\":\"fcNode\"")) { "节点类型缺失" }
    check(json.contains("\"type\":\"fcEdge\"")) { "连线类型缺失" }
    check(json.contains("\"x\"")) { "自动排版缺失" }

    val block = AgentContent.flowchartHtml("审批流程", json)
    println("BLOCK=$block")
    check(block.startsWith("<div data-type=\"flowchart-block\" data='")) { "图块属性格式错误" }
    check(block.contains("title=\"审批流程\"")) { "标题缺失" }
    check(!block.contains("<script")) { "未转义脚本标签" }
    println("ALL_OK")
}
