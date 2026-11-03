import AVFoundation
import AVKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import YiPaiCore

@main
struct YiPaiApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .onAppear { Signing.scheduleReminder() }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            switch model.screen {
            case .record: RecordView()
            case .settings: SettingsView()
            case let .processing(stage, progress): ProcessingView(stage: stage, progress: progress)
            case let .done(result): DoneView(result: result)
            case let .failed(message, input): FailedView(message: message, input: input)
            }
            if let toast = model.toast {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.callout)
                        .padding(12)
                        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 10))
                        .foregroundStyle(.white)
                        .padding(.bottom, 120)
                        .padding(.horizontal, 24)
                }
                .task(id: toast) {
                    try? await Task.sleep(nanoseconds: 3_500_000_000)
                    model.toast = nil
                }
            }
        }
    }
}

// MARK: - 拍摄页

struct RecordView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var camera = CameraController()
    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @State private var denied = false

    var body: some View {
        VStack(spacing: 0) {
            // 9:16 取景框，与成品画面一致；标题位置也按成品比例摆放
            GeometryReader { geo in
                ZStack(alignment: .top) {
                    CameraPreview(session: camera.session)
                    TitlePreview(canvasWidth: geo.size.width, imageMaxHeight: geo.size.height * CaptionLayout.titleMaxHeight)
                        .frame(maxWidth: geo.size.width * 0.9)
                        .offset(y: geo.size.height * CaptionLayout.titleTop)
                    HStack {
                        SigningBadge()
                        Spacer()
                        if camera.isRecording {
                            Text(timeText(camera.elapsed))
                                .font(.callout.monospacedDigit())
                                .padding(.horizontal, 8).padding(.vertical, 2)
                                .background(Color.red.opacity(0.85), in: RoundedRectangle(cornerRadius: 6))
                        }
                    }
                    .padding(12)
                }
            }
            .aspectRatio(9 / 16, contentMode: .fit)
            .clipped()

            Spacer(minLength: 0)
            controls
        }
        .background(Color.black.ignoresSafeArea())
        .overlay {
            if denied {
                VStack(spacing: 12) {
                    Text("需要相机和麦克风权限才能拍摄")
                    Button("去设置开启") {
                        UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
                    }
                }
                .padding()
                .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .task {
            let video = await AVCaptureDevice.requestAccess(for: .video)
            let audio = await AVCaptureDevice.requestAccess(for: .audio)
            denied = !(video && audio)
            camera.onFinished = { url, ok in
                if !ok {
                    model.toast = "录制失败"
                } else if camera.elapsed < 1.5 {
                    model.toast = "录制太短"
                } else {
                    model.process(url)
                }
            }
            if !denied { camera.start() }
        }
        .onDisappear { camera.stopSession() }
        .onChange(of: photoItem) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) { model.setTitleImage(data: data) }
                photoItem = nil
            }
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.png]) { result in
            guard case let .success(url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) { model.setTitleImage(data: data) }
        }
    }

    private var controls: some View {
        VStack(spacing: 12) {
            if !camera.isRecording {
                HStack(spacing: 8) {
                    if model.useTitleImage {
                        Text("标题：PNG 图片").frame(maxWidth: .infinity, alignment: .leading)
                        Button("改用文字") { model.clearTitleImage() }
                    } else {
                        VStack(spacing: 6) {
                            TextField("主标题（大字黄色，回车换行）", text: $model.titleText, axis: .vertical)
                                .lineLimit(1...3)
                                .textFieldStyle(.roundedBorder)
                            TextField("副标题（白字，可不填，回车换行）", text: $model.subTitleText, axis: .vertical)
                                .lineLimit(1...3)
                                .textFieldStyle(.roundedBorder)
                        }
                    }
                    PhotosPicker(selection: $photoItem, matching: .images) { Text("相册图") }
                    Button("文件图") { showFileImporter = true }
                }
                .font(.callout)
            }
            HStack {
                Button("设置") { model.screen = .settings }
                    .frame(maxWidth: .infinity)
                    .disabled(camera.isRecording)
                RecordButton(isRecording: camera.isRecording) {
                    if camera.isRecording { camera.stopRecording() } else { camera.startRecording(to: model.recordingURL) }
                }
                Button("翻转") { camera.switchCamera() }
                    .frame(maxWidth: .infinity)
                    .disabled(camera.isRecording)
            }
        }
        .padding(16)
        .background(Color.black.opacity(0.6))
    }

    private func timeText(_ t: Double) -> String {
        let s = Int(t)
        return String(format: "%02d:%02d / 10:00", s / 60, s % 60)
    }
}

struct TitlePreview: View {
    @EnvironmentObject private var model: AppModel
    /// 取景框宽度（点）；艺术字按这个宽度渲染，与成品比例一致
    let canvasWidth: CGFloat
    /// PNG 标题图的最大高度（与成品一致）；文字标题按行数自然增高
    let imageMaxHeight: CGFloat

    var body: some View {
        if model.useTitleImage {
            // 用 titleImageVersion 作为 id，换图后重新加载
            if let image = UIImage(contentsOfFile: model.titleImageURL.path) {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxHeight: imageMaxHeight)
                    .id(model.titleImageVersion)
            }
        } else if let art = TitleArt.render(main: model.titleText, sub: model.subTitleText, canvasWidth: canvasWidth) {
            Image(uiImage: art)
        }
    }
}

struct RecordButton: View {
    let isRecording: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().stroke(.white, lineWidth: 4).frame(width: 72, height: 72)
                RoundedRectangle(cornerRadius: isRecording ? 6 : 28)
                    .fill(Color.red)
                    .frame(width: isRecording ? 28 : 56, height: isRecording ? 28 : 56)
            }
        }
    }
}

// MARK: - 处理中 / 成品 / 失败 / 设置

struct ProcessingView: View {
    let stage: String
    let progress: Float

    var body: some View {
        VStack(spacing: 20) {
            Text("正在生成成品").font(.title2.bold())
            ProgressView(value: Double(progress))
            Text("\(stage)  \(Int(progress * 100))%")
            Text("请保持在本页面，不要锁屏").foregroundStyle(.secondary)
        }
        .padding(32)
    }
}

struct DoneView: View {
    @EnvironmentObject private var model: AppModel
    let result: PipelineResult
    @State private var player: AVPlayer?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VideoPlayer(player: player)
                .background(Color.black)
            Text("已保存到相册")
            let plan = result.plan
            Text(String(format: "时长 %.0f 秒 → %.0f 秒　语速 %.2fx　去语气词 %ld 处　去重复 %ld 处",
                        result.inputDuration, plan.outputDuration, plan.speed, plan.removedFillers, plan.removedRepeats))
                .font(.caption)
                .foregroundStyle(.secondary)
            if !result.llmNote.isEmpty {
                Text(result.llmNote).font(.caption).foregroundStyle(.secondary)
            }
            let publishText = model.publishText(result)
            if !publishText.isEmpty {
                Text(publishText)
                    .font(.footnote)
                    .lineLimit(4)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
            }
            Text("点下面的平台会自动复制上面的文案，到发布页直接粘贴")
                .font(.caption).foregroundStyle(.secondary)
            Text("发布到").font(.subheadline.bold())
            HStack(spacing: 8) {
                ForEach(Platform.allCases) { platform in
                    Button(platform.rawValue) {
                        Publisher.publish(platform, video: result.fileURL, title: publishText) { model.toast = $0 }
                    }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                }
            }
            Button {
                model.screen = .record
            } label: {
                Text("再拍一条").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(16)
        .onAppear {
            let p = AVPlayer(url: result.fileURL)
            p.play()
            player = p
        }
        .onDisappear { player?.pause() }
    }
}

struct FailedView: View {
    @EnvironmentObject private var model: AppModel
    let message: String
    let input: URL

    var body: some View {
        VStack(spacing: 16) {
            Text("生成失败").font(.title2.bold())
            Text(message).foregroundStyle(.secondary)
            HStack {
                Button("返回拍摄") { model.screen = .record }.buttonStyle(.bordered)
                Button("重试") { model.process(input) }.buttonStyle(.borderedProminent)
            }
        }
        .padding(32)
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showRenew = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("剪掉语气词（嗯、呃、啊…）", isOn: $model.settings.removeFillers)
                    Toggle("剪掉重复词（我们我们…）", isOn: $model.settings.removeRepeats)
                    Toggle("自动调节语速", isOn: $model.settings.adjustSpeed)
                }
                Section {
                    sliderRow(String(format: "目标语速：%.1f 字/秒", model.settings.targetCharsPerSecond),
                              value: $model.settings.targetCharsPerSecond, range: 3.5...5.5, step: 0.1)
                    sliderRow(String(format: "超过 %.2f 秒的停顿才剪", model.settings.pauseThreshold),
                              value: $model.settings.pauseThreshold, range: 0.25...1.0, step: 0.05)
                    sliderRow(String(format: "停顿剪完后保留 %.2f 秒", model.settings.keepGap),
                              value: $model.settings.keepGap, range: 0.05...0.4, step: 0.05)
                }
                Section {
                    SecureField("API Key", text: $model.llm.apiKey)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("接口地址", text: $model.llm.baseUrl)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("模型", text: $model.llm.model)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } header: {
                    Text("大模型文字优化（DeepSeek）")
                } footer: {
                    Text("识别后自动纠正口音造成的错字（字数不变），并生成内容概要和关键字，发布时自动复制。不填 Key 则不启用。")
                }
                Section("签名") {
                    Button("签名续期") { showRenew = true }
                }
            }
            .navigationTitle("设置")
            .toolbar { Button("完成") { model.screen = .record } }
            .sheet(isPresented: $showRenew) { RenewView() }
        }
    }

    private func sliderRow(_ label: String, value: Binding<Double>, range: ClosedRange<Double>, step: Double) -> some View {
        VStack(alignment: .leading) {
            Text(label)
            Slider(value: value, in: range, step: step)
        }
    }
}
