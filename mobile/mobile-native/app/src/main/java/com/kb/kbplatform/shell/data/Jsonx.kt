/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import org.json.JSONArray
import org.json.JSONObject

/** org.json 的 JSONArray 没实现 Iterable，统一走这个转换（跳过非对象元素） */
fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { i -> optJSONObject(i) }
