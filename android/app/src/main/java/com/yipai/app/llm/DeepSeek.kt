package com.yipai.app.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 用户在设置里填写的大模型接口（DeepSeek，也兼容其他 OpenAI 格式的接口） */
data class LlmConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
) {
    val enabled: Boolean get() = apiKey.isNotBlank()
}

/** [lines] 与输入一一对应；某行为 null 表示模型没返回。 */
data class RefineResponse(val lines: List<String?>, val summary: String, val keywords: List<String>)

object DeepSeek {
    private const val SYSTEM_PROMPT = """你是中文短视频的字幕校对助手。输入是语音识别的结果，说话人口音很重，识别有很多明显不通顺的错字、同音字、近音字。
请根据上下文猜测说话人真正想说的话，修正这些错误，不追求完美。
硬性规则：
1. 每一行的字数必须与原文完全相同（标点不计入字数）：只能一个字换一个字，不能增删字，不能合并或拆分行。
2. 保留原有的标点；语气词（嗯、呃、啊）原样保留，不要删除。
3. 行数和顺序必须与输入一致。
同时根据全文给出：
- summary：内容概要，一两句话，适合作为短视频发布文案（60 字以内）
- keywords：3 到 6 个关键字（不带 # 号）
只输出 JSON：{"lines":["..."],"summary":"...","keywords":["..."]}"""

    /** 阻塞调用，请在后台线程使用；失败抛异常，由调用方决定是否忽略。 */
    fun refine(cfg: LlmConfig, lines: List<String>): RefineResponse {
        val body = JSONObject().apply {
            put("model", cfg.model.ifBlank { "deepseek-chat" })
            put("temperature", 0.3)
            put("max_tokens", 8000)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", JSONArray(lines).toString()))
            })
        }
        val base = cfg.baseUrl.trim().ifBlank { "https://api.deepseek.com" }.trimEnd('/')
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey.trim()}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("大模型接口返回 $code：${text.take(120)}")
            return parse(text, lines.size)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(response: String, expectedLines: Int): RefineResponse {
        val content = JSONObject(response).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val json = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
        val arr = json.optJSONArray("lines")
        val lines = List(expectedLines) { i -> arr?.optString(i, null) }
        val kw = json.optJSONArray("keywords")
        val keywords = if (kw == null) emptyList() else
            List(kw.length()) { kw.optString(it).trim().trimStart('#') }.filter { it.isNotEmpty() }
        return RefineResponse(lines, json.optString("summary").trim(), keywords)
    }
}
