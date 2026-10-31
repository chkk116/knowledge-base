/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.diagram

import org.json.JSONArray
import org.json.JSONObject

/**
 * 图块数据的**逆向编辑**层。
 *
 * 网页端 schema（Vue Flow）：
 *   { nodes:[{id,type:'fcNode',position:{x,y},width?,height?,data:{label,shape,...},style?,...}],
 *     edges:[{id,source,target,sourceHandle?,targetHandle?,type:'fcEdge',data:{label}}] }
 *
 * ⚠️ 铁律：**只在 JSON 层面改所需字段，其余原样保留**。
 * 反序列化成自己的 model 再序列化，会把 type/style/handle 等字段写丢，
 * 网页端再打开时节点样式、连线锚点全丢——这是"逆向"最容易翻车的地方。
 */
object DiagramOps {

    private fun root(json: String): JSONObject = JSONObject(json)

    fun nodes(o: JSONObject): JSONArray = o.optJSONArray("nodes") ?: JSONArray()
    fun edges(o: JSONObject): JSONArray = o.optJSONArray("edges") ?: JSONArray()

    private fun indexOf(arr: JSONArray, id: String): Int {
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("id") == id) return i
        }
        return -1
    }

    /** 改节点文字（只写 data.label，data 里其它字段保留） */
    fun setLabel(json: String, nodeId: String, text: String): String {
        val o = root(json)
        val arr = nodes(o)
        val i = indexOf(arr, nodeId)
        if (i >= 0) {
            val n = arr.getJSONObject(i)
            val d = n.optJSONObject("data") ?: JSONObject().also { n.put("data", it) }
            d.put("label", text)
        }
        o.put("nodes", arr)
        return o.toString()
    }

    /** 拖动后写回坐标 */
    fun setPosition(json: String, nodeId: String, x: Float, y: Float): String {
        val o = root(json)
        val arr = nodes(o)
        val i = indexOf(arr, nodeId)
        if (i >= 0) {
            val n = arr.getJSONObject(i)
            val p = n.optJSONObject("position") ?: JSONObject().also { n.put("position", it) }
            p.put("x", (x * 10).toInt() / 10.0)
            p.put("y", (y * 10).toInt() / 10.0)
        }
        o.put("nodes", arr)
        return o.toString()
    }

    /** 新增节点：与网页端一致的最小字段集（缺省 rect） */
    fun addNode(json: String, label: String, x: Float = 40f, y: Float = 40f): Pair<String, String> {
        val o = root(json)
        val arr = nodes(o)
        val id = "n" + (System.currentTimeMillis() % 100000)
        arr.put(
            JSONObject()
                .put("id", id)
                .put("type", "fcNode")
                .put("position", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()))
                .put("data", JSONObject().put("label", label).put("shape", "rect"))
        )
        o.put("nodes", arr)
        return o.toString() to id
    }

    /** 删节点：连带删掉与它相连的边（否则网页端会画出悬空线） */
    fun removeNode(json: String, nodeId: String): String {
        val o = root(json)
        val arr = nodes(o)
        val i = indexOf(arr, nodeId)
        if (i >= 0) arr.remove(i)
        o.put("nodes", arr)
        val es = edges(o)
        val kept = JSONArray()
        for (k in 0 until es.length()) {
            val e = es.optJSONObject(k) ?: continue
            if (e.optString("source") == nodeId || e.optString("target") == nodeId) continue
            kept.put(e)
        }
        o.put("edges", kept)
        return o.toString()
    }

    fun addEdge(json: String, source: String, target: String, label: String = ""): String {
        val o = root(json)
        val es = edges(o)
        val id = "e" + (System.currentTimeMillis() % 100000)
        val e = JSONObject()
            .put("id", id)
            .put("source", source)
            .put("target", target)
            .put("type", "fcEdge")
        if (label.isNotEmpty()) e.put("data", JSONObject().put("label", label))
        es.put(e)
        o.put("edges", es)
        return o.toString()
    }

    fun removeEdge(json: String, edgeId: String): String {
        val o = root(json)
        val es = edges(o)
        val kept = JSONArray()
        for (k in 0 until es.length()) {
            val e = es.optJSONObject(k) ?: continue
            if (e.optString("id") == edgeId) continue
            kept.put(e)
        }
        o.put("edges", kept)
        return o.toString()
    }

    /** 把正文里第 index 个图块的 data 属性替换成新 JSON（保持属性引号风格） */
    fun replaceBlockData(html: String, index: Int, newJson: String): String {
        val re = Regex("(<div[^>]*data-type=\"(?:flowchart|mindmap)-block\"[^>]*?data=)(['\"])(.*?)\\2",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        var i = 0
        return re.replace(html) { m ->
            if (i++ != index) m.value
            else {
                val quote = m.groupValues[2]
                // JSON 里的单引号在单引号属性中安全；双引号需转义
                val safe = if (quote == "'") newJson.replace("'", "\\'") else newJson.replace("\"", "&quot;")
                m.groupValues[1] + quote + safe + quote
            }
        }
    }
}
