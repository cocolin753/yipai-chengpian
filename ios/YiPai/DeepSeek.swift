import Foundation

/// 用户在设置里填写的大模型接口（DeepSeek，也兼容其他 OpenAI 格式的接口）
struct LlmConfig: Codable, Equatable {
    var apiKey = ""
    var baseUrl = "https://api.deepseek.com"
    var model = "deepseek-chat"

    var enabled: Bool { !apiKey.trimmingCharacters(in: .whitespaces).isEmpty }
}

/// `lines` 与输入一一对应；某行为 nil 表示模型没返回
struct RefineResponse {
    var lines: [String?]
    var summary: String
    var keywords: [String]
}

enum DeepSeek {
    private static let systemPrompt = """
    你是中文短视频的字幕校对助手。输入是语音识别的结果，说话人口音很重，识别有很多明显不通顺的错字、同音字、近音字。
    请根据上下文猜测说话人真正想说的话，把读起来不通顺、意思不对的地方都改过来，宁可多改，不要放过明显的错字。
    规则：
    1. 以“一个字换一个字”为主，字数尽量与原文相同；识别明显多字、漏字时可以增删一两个字，但不要改写句子、不要调换语序、不要合并或拆分行。
    2. 保留原有的标点；语气词（嗯、呃、啊）原样保留，不要删除。
    3. 行数和顺序必须与输入一致，没有错的行原样返回。
    同时根据全文给出：
    - summary：内容概要，一两句话，适合作为短视频发布文案（60 字以内）
    - keywords：3 到 6 个关键字（不带 # 号）
    只输出 JSON：{"lines":["..."],"summary":"...","keywords":["..."]}
    """

    static func refine(_ cfg: LlmConfig, lines: [String]) async throws -> RefineResponse {
        let userContent = String(data: try JSONSerialization.data(withJSONObject: lines), encoding: .utf8) ?? "[]"
        let body: [String: Any] = [
            "model": cfg.model.isEmpty ? "deepseek-chat" : cfg.model,
            "temperature": 0.3,
            "max_tokens": 8000,
            "response_format": ["type": "json_object"],
            "messages": [
                ["role": "system", "content": systemPrompt],
                ["role": "user", "content": userContent],
            ],
        ]
        var base = cfg.baseUrl.trimmingCharacters(in: .whitespaces)
        if base.isEmpty { base = "https://api.deepseek.com" }
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + "/chat/completions") else { throw PipelineError("接口地址不正确") }

        var req = URLRequest(url: url, timeoutInterval: 120)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer " + cfg.apiKey.trimmingCharacters(in: .whitespaces), forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let text = String(data: data, encoding: .utf8) ?? ""
            throw PipelineError("大模型接口返回 \(code)：\(text.prefix(120))")
        }
        return try parse(data, expectedLines: lines.count)
    }

    static func parse(_ data: Data, expectedLines: Int) throws -> RefineResponse {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = root["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              var content = message["content"] as? String else { throw PipelineError("大模型返回格式不对") }
        content = content.trimmingCharacters(in: .whitespacesAndNewlines)
        for fence in ["```json", "```"] where content.hasPrefix(fence) { content.removeFirst(fence.count) }
        if content.hasSuffix("```") { content.removeLast(3) }
        guard let json = try JSONSerialization.jsonObject(with: Data(content.utf8)) as? [String: Any] else {
            throw PipelineError("大模型返回格式不对")
        }
        let arr = json["lines"] as? [Any] ?? []
        let lines: [String?] = (0..<expectedLines).map { i in i < arr.count ? arr[i] as? String : nil }
        let keywords = (json["keywords"] as? [String] ?? [])
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "# ")) }
            .filter { !$0.isEmpty }
        let summary = (json["summary"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return RefineResponse(lines: lines, summary: summary, keywords: keywords)
    }
}
