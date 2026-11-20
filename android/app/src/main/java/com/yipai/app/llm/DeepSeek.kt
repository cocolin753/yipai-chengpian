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

/** 概要和关键字 */
data class SummaryResponse(val summary: String, val keywords: List<String>)

object DeepSeek {
    private const val CORRECT_PROMPT = """你是中文短视频的字幕校对助手。输入是语音识别的结果，说话人口音很重，识别有很多明显不通顺的错字、同音字、近音字。
请根据上下文猜测说话人真正想说的话，把读起来不通顺、意思不对的地方都改过来，宁可多改，不要放过明显的错字。
输入是要校对的行组成的 JSON 数组；或者是 {"context":"全文","lines":[...]}，context 是整段口播（可能含已校对好的部分），只用来理解上下文，只校对并返回 lines。
规则：
1. 以“一个字换一个字”为主，字数尽量与原文相同；识别明显多字、漏字时可以增删一两个字，但不要改写句子、不要调换语序、不要合并或拆分行。
2. 保留原有的标点；语气词（嗯、呃、啊）原样保留，不要删除。
3. 行数和顺序必须与输入的 lines 一致，没有错的行原样返回。
只输出 JSON：{"lines":["..."]}"""

    private const val SUMMARY_PROMPT = """你是中文短视频的运营助手。输入是一段口播的文字（语音识别结果，可能有少量错字）。
请给出：
- summary：内容概要，一两句话，适合作为短视频发布文案（60 字以内）
- keywords：3 到 6 个关键字（不带 # 号）
只输出 JSON：{"summary":"...","keywords":["..."]}"""

    /** 纠错；[context] 为全文（分批或部分行已按提词稿校对时传），返回与 [lines] 一一对应，某行为 null 表示模型没返回。阻塞调用。 */
    fun correct(cfg: LlmConfig, lines: List<String>, context: String?): List<String?> {
        val input = if (context == null) JSONArray(lines).toString()
        else JSONObject().put("context", context).put("lines", JSONArray(lines)).toString()
        val json = call(cfg, CORRECT_PROMPT, input, maxTokens = 8000)
        val arr = json.optJSONArray("lines")
        return List(lines.size) { i -> arr?.optString(i, null) }
    }

    /** 概要和关键字。阻塞调用。 */
    fun summarize(cfg: LlmConfig, text: String): SummaryResponse {
        val json = call(cfg, SUMMARY_PROMPT, text, maxTokens = 500)
        val kw = json.optJSONArray("keywords")
        val keywords = if (kw == null) emptyList() else
            List(kw.length()) { kw.optString(it).trim().trimStart('#') }.filter { it.isNotEmpty() }
        return SummaryResponse(json.optString("summary").trim(), keywords)
    }

    /** 失败抛异常，由调用方决定是否忽略 */
    private fun call(cfg: LlmConfig, system: String, user: String, maxTokens: Int): JSONObject {
        val body = JSONObject().apply {
            put("model", cfg.model.ifBlank { "deepseek-chat" })
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
        val base = cfg.baseUrl.trim().ifBlank { "https://api.deepseek.com" }.trimEnd('/')
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey.trim()}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("大模型接口返回 $code：${text.take(120)}")
            return parseContent(text)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseContent(response: String): JSONObject {
        val content = JSONObject(response).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
    }
}
