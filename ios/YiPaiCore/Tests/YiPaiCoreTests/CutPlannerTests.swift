// 与安卓版 CutPlannerTest.kt 相同的用例，保证两端行为一致。
import XCTest
@testable import YiPaiCore

final class CutPlannerTests: XCTestCase {

    /// 按每字 charDur 秒生成一段话；文本中的标点会挂到前一个字上
    private func utter(_ text: String, _ start: Double, charDur: Double = 0.2) -> Utterance {
        var tokens: [Token] = []
        var t = start
        for c in text {
            if "，。！？、".contains(c) {
                tokens[tokens.count - 1].punctAfter = String(c)
            } else {
                tokens.append(Token(text: String(c), start: t, end: t + charDur))
                t += charDur
            }
        }
        return Utterance(start: start, end: t, tokens: tokens)
    }

    private func keptText(_ utterances: [Utterance], _ plan: EditPlan) -> String {
        utterances.flatMap(\.tokens)
            .filter { tk in plan.keep.contains { tk.start >= $0.start - 1e-9 && tk.end <= $0.end + 1e-9 } }
            .map(\.text).joined()
    }

    private var noSpeed: EditSettings {
        var s = EditSettings()
        s.adjustSpeed = false
        return s
    }

    func testLongPauseIsCompressed() {
        let u = [utter("你好", 0.5), utter("世界", 3.0)]
        let plan = CutPlanner.plan(u, duration: 5, settings: noSpeed)
        XCTAssertEqual(plan.keep.count, 2)
        let kept = (plan.keep[0].end - 0.9) + (3.0 - plan.keep[1].start)
        XCTAssertEqual(kept, 0.15, accuracy: 1e-6)
        XCTAssertEqual(plan.keep[0].start, 0.25, accuracy: 1e-6)
    }

    func testShortPauseIsKept() {
        let u = [utter("你好", 0.5), utter("世界", 1.2)]
        XCTAssertEqual(CutPlanner.plan(u, duration: 3, settings: noSpeed).keep.count, 1)
    }

    func testStrongFillerRemoved() {
        let u = [utter("我觉得嗯这个好", 0)]
        let plan = CutPlanner.plan(u, duration: 2, settings: noSpeed)
        XCTAssertEqual(keptText(u, plan), "我觉得这个好")
        XCTAssertEqual(plan.removedFillers, 1)
    }

    func testWeakFillerOnlyWhenIsolated() {
        let u = [utter("啊，我们开始。", 0), utter("是啊。额外的", 3)]
        let plan = CutPlanner.plan(u, duration: 6, settings: noSpeed)
        XCTAssertEqual(keptText(u, plan), "我们开始是啊额外的")
    }

    func testRepeatedWordsRemoved() {
        let u = [utter("我们我们今天讲", 0)]
        XCTAssertEqual(keptText(u, CutPlanner.plan(u, duration: 3, settings: noSpeed)), "我们今天讲")
    }

    func testReduplicationKept() {
        let u = [utter("谢谢大家一点一点来", 0)]
        XCTAssertEqual(keptText(u, CutPlanner.plan(u, duration: 3, settings: noSpeed)), "谢谢大家一点一点来")
    }

    func testStutterRemoved() {
        let u = [utter("我我我觉得", 0), utter("是", 2), utter("是对的", 3)]
        XCTAssertEqual(keptText(u, CutPlanner.plan(u, duration: 5, settings: noSpeed)), "我觉得是对的")
    }

    func testSentenceEndIsNotRepeat() {
        let u = [utter("这是我。", 0), utter("我来讲", 1.5)]
        XCTAssertEqual(keptText(u, CutPlanner.plan(u, duration: 3, settings: noSpeed)), "这是我我来讲")
    }

    func testSpeedIsClamped() {
        let fast = [utter("一二三四五六七八九十一二三四五六七八九十", 0, charDur: 0.1)]
        XCTAssertEqual(CutPlanner.plan(fast, duration: 3).speed, 0.9, accuracy: 1e-9)
        let slow = [utter("一二三四五六七八九十一二三四五六七八九十", 0, charDur: 0.5)]
        XCTAssertEqual(CutPlanner.plan(slow, duration: 11).speed, 1.3, accuracy: 1e-9)
    }

    func testSubtitlesFollowOutputTimeline() {
        let u = [utter("大家好，", 0.5), utter("今天讲剪辑。", 5)]
        let plan = CutPlanner.plan(u, duration: 8, settings: noSpeed)
        XCTAssertEqual(plan.subtitles.map(\.text), ["大家好", "今天讲剪辑"])
        XCTAssertEqual(plan.subtitles[0].start, 0.25, accuracy: 1e-6)
        XCTAssertLessThan(plan.subtitles[1].start, 1.5)
        XCTAssertLessThanOrEqual(plan.subtitles[1].end, plan.outputDuration + 1e-6)
    }

    func testLongLineIsSplit() {
        let u = [utter("一二三四五六七八九十一二三四五六七八九十", 0)]
        let plan = CutPlanner.plan(u, duration: 5, settings: noSpeed)
        XCTAssertEqual(plan.subtitles.map(\.text.count), [14, 6])
    }

    func testNoSpeechKeepsWholeVideo() {
        XCTAssertEqual(CutPlanner.plan([], duration: 4).keep, [TimeRange(start: 0, end: 4)])
    }

    func testTimelineMapsAcrossCuts() {
        let tl = Timeline(keep: [TimeRange(start: 1, end: 2), TimeRange(start: 5, end: 7)], speed: 2)
        XCTAssertEqual(tl.map(1), 0, accuracy: 1e-9)
        XCTAssertEqual(tl.map(2), 0.5, accuracy: 1e-9)
        XCTAssertEqual(tl.map(6), 1, accuracy: 1e-9)
    }
}
