import UIKit

/// 标题艺术字：参照 title.png —— 主标题黄色渐变粗体，副标题白色粗体，都带黑色粗描边和投影。
/// 成品导出和拍摄预览共用这一份，保证所见即所得（安卓版对应 CaptionPainter.drawStyledLine）。
enum TitleArt {
    /// 主标题字号占画面宽度的比例
    static let mainSize: CGFloat = 0.15

    /// 按画面宽度 `canvasWidth`（像素）渲染；没有文字时返回 nil。图片 scale 固定为 1。
    static func render(main: String, sub: String, canvasWidth: CGFloat) -> UIImage? {
        let main = main.trimmingCharacters(in: .whitespaces)
        let sub = sub.trimmingCharacters(in: .whitespaces)
        guard !main.isEmpty || !sub.isEmpty else { return nil }
        let maxWidth = canvasWidth * 0.9
        let mainFont = main.isEmpty ? canvasWidth * mainSize : fitSize(main, max: canvasWidth * mainSize, width: maxWidth)

        var parts: [UIImage] = []
        if !main.isEmpty { parts.append(line(main, size: mainFont, yellow: true)) }
        if !sub.isEmpty {
            parts.append(line(sub, size: fitSize(sub, max: mainFont * 0.62, width: maxWidth), yellow: false))
        }
        // 两行紧挨着：上下各让出一部分留白（描边的空白区）
        let overlap = (parts.map { $0.size.height }.max() ?? 0) * 0.12
        let totalW = parts.map { $0.size.width }.max() ?? 0
        let totalH = parts.reduce(0) { $0 + $1.size.height } - overlap * CGFloat(parts.count - 1)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: totalW, height: totalH), format: format).image { _ in
            var y: CGFloat = 0
            for p in parts {
                p.draw(at: CGPoint(x: (totalW - p.size.width) / 2, y: y))
                y += p.size.height - overlap
            }
        }
    }

    private static func font(_ size: CGFloat) -> UIFont { UIFont.systemFont(ofSize: size, weight: .black) }

    /// 字号：不超过 max，且一行放得下 width
    private static func fitSize(_ text: String, max maxSize: CGFloat, width: CGFloat) -> CGFloat {
        let w = (text as NSString).size(withAttributes: [.font: font(maxSize)]).width
        return w > width ? maxSize * width / w : maxSize
    }

    private static func line(_ text: String, size: CGFloat, yellow: Bool) -> UIImage {
        let f = font(size)
        let textSize = (text as NSString).size(withAttributes: [.font: f])
        let outline = size * 0.13
        let pad = outline + size * 0.15
        let canvas = CGSize(width: ceil(textSize.width + pad * 2), height: ceil(textSize.height + pad * 2))

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        // 竖向渐变（1 像素宽），作为文字的图案颜色；渐变范围正好覆盖文字高度
        let gradient = UIGraphicsImageRenderer(size: CGSize(width: 1, height: canvas.height), format: format).image { ctx in
            let colors = [
                UIColor(red: 1, green: 0.957, blue: 0.431, alpha: 1).cgColor,
                UIColor(red: 1, green: 0.804, blue: 0.078, alpha: 1).cgColor,
                UIColor(red: 1, green: 0.588, blue: 0, alpha: 1).cgColor,
            ]
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 0.55, 1]) {
                ctx.cgContext.drawLinearGradient(
                    g, start: CGPoint(x: 0, y: pad), end: CGPoint(x: 0, y: pad + textSize.height), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            }
        }

        return UIGraphicsImageRenderer(size: canvas, format: format).image { ctx in
            let origin = CGPoint(x: pad, y: pad)
            // 黑色描边 + 投影（正的 strokeWidth = 只描边，单位是字号的百分比）
            ctx.cgContext.setShadow(offset: CGSize(width: 0, height: size * 0.07), blur: size * 0.1,
                                    color: UIColor.black.withAlphaComponent(0.8).cgColor)
            (text as NSString).draw(at: origin, withAttributes: [
                .font: f, .obliqueness: 0.12,
                .strokeColor: UIColor.black, .strokeWidth: outline * 2 / size * 100,
            ])
            ctx.cgContext.setShadow(offset: .zero, blur: 0, color: nil)
            (text as NSString).draw(at: origin, withAttributes: [
                .font: f, .obliqueness: 0.12,
                .foregroundColor: yellow ? UIColor(patternImage: gradient) : UIColor.white,
            ])
        }
    }
}
