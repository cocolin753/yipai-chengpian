// 与安卓版 ScriptAlignerTest.kt 相同的用例，保证两端行为一致。
import XCTest
@testable import YiPaiCore

final class ScriptAlignerTests: XCTestCase {
    /// 每个字一个 token，句末标点挂到最后一个字
    private func utt(_ text: String, _ start: Double) -> Utterance {
        let chars = Array(text)
        let tokens = chars.enumerated().map { i, c in
            Token(text: String(c), start: start + Double(i), end: start + Double(i) + 1,
                  punctAfter: i == chars.count - 1 ? "。" : "")
        }
        return Utterance(start: start, end: start + Double(chars.count), tokens: tokens)
    }

    private func texts(_ r: ScriptAligner.Result) -> [String] {
        r.utterances.map { $0.tokens.map(\.text).joined() }
    }

    func testWrongCharsFixedFromScript() {
        let us = [utt("大家号我是小王", 0), utt("今天跟大家谈谈剪集", 10)]
        let r = ScriptAligner.apply(us, script: "大家好，我是小王。今天跟大家聊聊剪辑。")
        XCTAssertEqual(texts(r), ["大家好我是小王", "今天跟大家聊聊剪辑"])
        XCTAssertEqual(r.matched, [0, 1])
        XCTAssertEqual(r.changed, 2)
        XCTAssertEqual(us[1].tokens.map(\.start), r.utterances[1].tokens.map(\.start))
        XCTAssertEqual(us[1].tokens.map(\.heard), r.utterances[1].tokens.map(\.heard))
    }

    func testUnspokenScriptIsIgnored() {
        let script = "大家好我是小王。这一段我临时决定不念了它很长很长。今天跟大家聊聊剪辑。最后这句也没念。"
        let r = ScriptAligner.apply([utt("大家好我是小王", 0), utt("今天跟大家聊聊剪集", 10)], script: script)
        XCTAssertEqual(texts(r), ["大家好我是小王", "今天跟大家聊聊剪辑"])
    }

    func testAdLibKeptAsSpoken() {
        let us = [utt("大家好我是小王", 0), utt("先说一下天气不错", 8), utt("今天跟大家聊聊剪集", 20)]
        let r = ScriptAligner.apply(us, script: "大家好我是小王。今天跟大家聊聊剪辑。")
        XCTAssertEqual(texts(r), ["大家好我是小王", "先说一下天气不错", "今天跟大家聊聊剪辑"])
        XCTAssertEqual(r.matched, [0, 2])
    }

    func testExtraWordsInsideSentenceKept() {
        let r = ScriptAligner.apply([utt("这个方法真的非常好拥", 0)], script: "这个方法非常好用。")
        XCTAssertEqual(texts(r), ["这个方法真的非常好用"])
    }

    func testMissedCharFilledButSkippedPhraseNot() {
        let r = ScriptAligner.apply(
            [utt("我们天来聊聊", 0), utt("首先打开软件然后导入视频", 10)],
            script: "我们今天来聊聊。首先打开软件，点击右上角的设置按钮，然后导入视频。")
        XCTAssertEqual(texts(r), ["我们今天来聊聊", "首先打开软件然后导入视频"])
    }

    func testRepeatedSentenceStaysInOrder() {
        let us = [utt("第一点要注意光现", 0), utt("第一点要注意光线", 10), utt("第二点要注意声因", 20)]
        let r = ScriptAligner.apply(us, script: "第一点要注意光线。第二点要注意声音。")
        XCTAssertEqual(texts(r)[2], "第二点要注意声音")
        XCTAssertTrue(r.matched.contains(2))
    }

    func testEmptyScriptChangesNothing() {
        let r = ScriptAligner.apply([utt("大家好", 0)], script: "  \n ")
        XCTAssertEqual(texts(r), ["大家好"])
        XCTAssertTrue(r.matched.isEmpty)
    }
}
