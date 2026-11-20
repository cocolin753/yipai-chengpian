import AVFoundation
import SwiftUI

/// 拍摄补光：自动（录制开始时环境偏暗才开）/ 常开 / 关
enum FillLight: String, CaseIterable {
    case auto, on, off

    var label: String {
        switch self {
        case .auto: return "自动"
        case .on: return "开"
        case .off: return "关"
        }
    }

    var next: FillLight {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

/// 相机：默认后置，竖屏 1080p 录制，最长 20 分钟
final class CameraController: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    static let maxDuration: Double = 1200

    let session = AVCaptureSession()
    @Published private(set) var isRecording = false
    @Published private(set) var elapsed: Double = 0
    @Published private(set) var position: AVCaptureDevice.Position = .back
    /// 当前画面偏暗（按相机的曝光参数估算）
    @Published private(set) var isDark = false
    /// 前置屏幕补光（取景框四周光带 + 屏幕最亮）是否打开
    @Published private(set) var screenLight = false

    /// 补光模式，由界面设置
    var fillLight: FillLight = .auto {
        didSet { updateLight() }
    }
    /// 自动模式只在按下录制时判断一次，录制中不开关，免得成片忽明忽暗
    private var takeLight = false
    private var savedBrightness: CGFloat?
    private var darkTimer: Timer?

    /// 环境亮度低于这个 EV100（约 30 lux）算偏暗；lux ≈ 2.5 × 2^EV
    private static let darkEV = log2(30.0 / 2.5)

    /// 录制完成回调：(文件, 是否成功)
    var onFinished: ((URL, Bool) -> Void)?

    private let output = AVCaptureMovieFileOutput()
    private let queue = DispatchQueue(label: "yipai.camera")
    private var videoInput: AVCaptureDeviceInput?
    private var configured = false
    private var timer: Timer?

    func start() {
        queue.async { [self] in
            if !configured { configure() }
            if !session.isRunning { session.startRunning() }
        }
        DispatchQueue.main.async { [self] in
            darkTimer?.invalidate()
            darkTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                self?.measureDarkness()
            }
            updateLight()
        }
    }

    func stopSession() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
        darkTimer?.invalidate()
        darkTimer = nil
        setScreenLight(false)
    }

    private func configure() {
        session.beginConfiguration()
        session.sessionPreset = session.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
        setVideoInput(position)
        if let mic = AVCaptureDevice.default(for: .audio),
           let input = try? AVCaptureDeviceInput(device: mic),
           session.canAddInput(input) {
            session.addInput(input)
        }
        if session.canAddOutput(output) { session.addOutput(output) }
        output.maxRecordedDuration = CMTime(seconds: Self.maxDuration, preferredTimescale: 600)
        session.commitConfiguration()
        configured = true
    }

    private func setVideoInput(_ position: AVCaptureDevice.Position) {
        if let old = videoInput { session.removeInput(old) }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        videoInput = input
        // 系统暗光增强（部分机型支持），补光“关”时也关掉
        if device.isLowLightBoostSupported, (try? device.lockForConfiguration()) != nil {
            device.automaticallyEnablesLowLightBoostWhenAvailable = fillLight != .off
            device.unlockForConfiguration()
        }
    }

    func switchCamera() {
        let next: AVCaptureDevice.Position = position == .back ? .front : .back
        position = next
        queue.async { [self] in
            session.beginConfiguration()
            setVideoInput(next)
            session.commitConfiguration()
            DispatchQueue.main.async { self.updateLight() }
        }
    }

    /// 用相机当前的光圈、快门、ISO 估算环境亮度（EV100）；补光已开时画面变亮，不再更新
    private func measureDarkness() {
        guard !isRecording, !takeLight, fillLight == .auto,
              let d = videoInput?.device else { return }
        let t = d.exposureDuration.seconds
        guard t > 0, d.iso > 0 else { return }
        let ev = log2(Double(d.lensAperture * d.lensAperture) / t) - log2(Double(d.iso) / 100)
        let dark = ev < Self.darkEV
        if dark != isDark { isDark = dark }
    }

    /// 根据模式和录制状态开关补光；在主线程调用
    private func updateLight() {
        let active: Bool
        switch fillLight {
        case .on: active = true
        case .off: active = false
        case .auto: active = takeLight
        }
        let front = position == .front
        setScreenLight(active && front)
        let torch = active && !front
        let lowLight = fillLight != .off
        queue.async { [self] in
            guard let d = videoInput?.device, (try? d.lockForConfiguration()) != nil else { return }
            if d.isLowLightBoostSupported { d.automaticallyEnablesLowLightBoostWhenAvailable = lowLight }
            if d.hasTorch {
                // 亮度用 60%，避免太刺眼、太烫；过热时系统会自动关灯
                if torch, d.isTorchModeSupported(.on) {
                    try? d.setTorchModeOn(level: 0.6)
                } else if d.torchMode != .off {
                    d.torchMode = .off
                }
            }
            d.unlockForConfiguration()
        }
    }

    private func setScreenLight(_ on: Bool) {
        if on, savedBrightness == nil {
            savedBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = 1
        } else if !on, let b = savedBrightness {
            UIScreen.main.brightness = b
            savedBrightness = nil
        }
        if screenLight != on { screenLight = on }
    }

    func startRecording(to url: URL) {
        // 在主线程调用：先按需开补光（同一个串行队列，灯会先于录制打开）
        takeLight = fillLight == .auto && isDark
        updateLight()
        queue.async { [self] in
            guard !output.isRecording else { return }
            if let conn = output.connection(with: .video) {
                if #available(iOS 17.0, *) {
                    if conn.isVideoRotationAngleSupported(90) { conn.videoRotationAngle = 90 }
                } else if conn.isVideoOrientationSupported {
                    conn.videoOrientation = .portrait
                }
            }
            try? FileManager.default.removeItem(at: url)
            output.startRecording(to: url, recordingDelegate: self)
        }
    }

    func stopRecording() {
        queue.async { [self] in
            if output.isRecording { output.stopRecording() }
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
        DispatchQueue.main.async { [self] in
            isRecording = true
            elapsed = 0
            let started = Date()
            timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                self?.elapsed = Date().timeIntervalSince(started)
            }
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        // 达到 20 分钟上限时也会带 error，但文件是完整的
        var ok = error == nil
        if let error = error as NSError?,
           let finished = error.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool {
            ok = finished
        }
        DispatchQueue.main.async { [self] in
            timer?.invalidate()
            timer = nil
            isRecording = false
            takeLight = false
            updateLight()
            onFinished?(outputFileURL, ok)
        }
    }
}

/// 相机预览（等比缩放，放在 9:16 框内与成品画面一致）
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
