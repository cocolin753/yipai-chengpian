import AVFoundation
import SwiftUI

/// 相机：默认后置，竖屏 1080p 录制，最长 10 分钟
final class CameraController: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    static let maxDuration: Double = 600

    let session = AVCaptureSession()
    @Published private(set) var isRecording = false
    @Published private(set) var elapsed: Double = 0
    @Published private(set) var position: AVCaptureDevice.Position = .back

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
    }

    func stopSession() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
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
    }

    func switchCamera() {
        let next: AVCaptureDevice.Position = position == .back ? .front : .back
        position = next
        queue.async { [self] in
            session.beginConfiguration()
            setVideoInput(next)
            session.commitConfiguration()
        }
    }

    func startRecording(to url: URL) {
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
        // 达到 10 分钟上限时也会带 error，但文件是完整的
        var ok = error == nil
        if let error = error as NSError?,
           let finished = error.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool {
            ok = finished
        }
        DispatchQueue.main.async { [self] in
            timer?.invalidate()
            timer = nil
            isRecording = false
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
