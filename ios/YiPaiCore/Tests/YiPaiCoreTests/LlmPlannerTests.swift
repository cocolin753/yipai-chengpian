import XCTest
@testable import YiPaiCore

final class LlmPlannerTests: XCTestCase {
    func testSkipsLockedAndEmptyLines() {
        XCTAssertEqual(LlmPlanner.batches(["甲", "乙", "", "丁"], locked: [1]), [[0, 3]])
    }

    func testAllLockedMeansSummaryOnly() {
        XCTAssertEqual(LlmPlanner.batches(["甲", "乙"], locked: [0, 1]), [])
    }

    func testSplitsByChars() {
        XCTAssertEqual(LlmPlanner.batches(["一二三", "四五六", "七八", "九"], locked: [], maxChars: 5), [[0], [1, 2], [3]])
    }

    func testOverlongLineGetsOwnBatch() {
        XCTAssertEqual(LlmPlanner.batches(["一二三四五六", "七"], locked: [], maxChars: 5), [[0], [1]])
    }
}
