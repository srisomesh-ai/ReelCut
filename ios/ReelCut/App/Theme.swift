import AVKit
import SwiftUI

/// The Android app's colours (light / dark).
enum RC {
    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { t in
            let c = t.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((c >> 16) & 255) / 255, green: CGFloat((c >> 8) & 255) / 255, blue: CGFloat(c & 255) / 255, alpha: 1)
        })
    }
    static let primary = dyn(0x2563C9, 0x7DA9F0)
    static let onPrimary = dyn(0xFFFFFF, 0x0B2350)
    static let primaryContainer = dyn(0xDCE8FB, 0x1B3561)
    static let onPrimaryContainer = dyn(0x123B7A, 0xDCE8FB)
    static let background = dyn(0xF5F4F0, 0x151513)
    static let onBackground = dyn(0x1C1B19, 0xEDEBE5)
    static let surfaceVariant = dyn(0xEFEDE7, 0x292825)
    static let onSurfaceVariant = dyn(0x5F5E5A, 0xB4B2A9)
    static let outline = dyn(0xCFCCC3, 0x4A4945)
    static let tile = Color(red: 0x26 / 255, green: 0x21 / 255, blue: 0x5C / 255)
}

/// Card used on Home / Setup / Fine-tune.
struct CardBox<Content: View>: View {
    var spacing: CGFloat = 8
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RC.surfaceVariant, in: RoundedRectangle(cornerRadius: 14))
    }
}

/// A round choice chip (like the Android FilterChip).
struct Chip: View {
    let label: String
    let selected: Bool
    var disabled = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .lineLimit(1)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .foregroundStyle(selected ? RC.onPrimaryContainer : RC.onSurfaceVariant)
                .background(Capsule().fill(selected ? RC.primaryContainer : Color.clear))
                .overlay(Capsule().stroke(selected ? Color.clear : RC.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.5 : 1)
    }
}

/// Equal-width choice (Cut speed), four fit on a phone.
struct Pill: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 15, weight: .semibold))
                .lineLimit(1).minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity).frame(height: 34)
                .foregroundStyle(selected ? RC.onPrimaryContainer : RC.onSurfaceVariant)
                .background(Capsule().fill(selected ? RC.primaryContainer : Color.clear))
                .overlay(Capsule().stroke(selected ? Color.clear : RC.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct BigButton: View {
    let label: String
    var icon: String? = nil
    var outlined = false
    var disabled = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon) }
                Text(label).font(.system(size: 16, weight: .semibold)).lineLimit(1)
            }
            .frame(maxWidth: .infinity).frame(height: outlined ? 44 : 50)
            .foregroundStyle(outlined ? RC.primary : RC.onPrimary)
            .background(RoundedRectangle(cornerRadius: 12).fill(outlined ? Color.clear : RC.primary))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(outlined ? RC.outline : Color.clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.45 : 1)
    }
}

struct TopBar<Trailing: View>: View {
    let title: String
    let back: () -> Void
    @ViewBuilder var trailing: Trailing
    var body: some View {
        HStack(spacing: 4) {
            Button(action: back) { Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold)).frame(width: 40, height: 40) }
                .accessibilityLabel("Back")
            Text(title).font(.system(size: 17, weight: .semibold)).lineLimit(1)
            Spacer(minLength: 4)
            trailing
        }
        .foregroundStyle(RC.onBackground)
    }
}

extension TopBar where Trailing == EmptyView {
    init(title: String, back: @escaping () -> Void) { self.init(title: title, back: back) { EmptyView() } }
}

/// AVPlayer on screen without controls (fill or fit).
struct PlayerSurface: UIViewRepresentable {
    let player: AVPlayer?
    var fill = false

    final class V: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var pl: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> V {
        let v = V()
        v.backgroundColor = .black
        return v
    }

    func updateUIView(_ v: V, context: Context) {
        if v.pl.player !== player { v.pl.player = player }
        v.pl.videoGravity = fill ? .resizeAspectFill : .resizeAspect
    }
}

/// A looping video with the system controls (Result / Viewer).
struct LoopingPlayer: View {
    let url: URL
    @State private var player: AVQueuePlayer?
    @State private var looper: AVPlayerLooper?

    var body: some View {
        VideoPlayer(player: player)
            .background(Color.black)
            .onAppear { start() }
            .onChange(of: url) { _ in start() }
            .onDisappear { player?.pause(); player = nil; looper = nil }
    }

    private func start() {
        let q = AVQueuePlayer()
        looper = AVPlayerLooper(player: q, templateItem: AVPlayerItem(url: url))
        player = q
        q.play()
    }
}

/// Shares a file (WhatsApp, Instagram, Files...).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// Async thumbnail of a clip or photo.
struct ThumbView: View {
    let url: URL?
    let isImage: Bool
    let at: Double
    var maxSide = 160
    @State private var img: UIImage?

    var body: some View {
        ZStack {
            Color(white: 0.13)
            if let img { Image(uiImage: img).resizable().scaledToFill() }
        }
        .clipped()
        .task(id: "\(url?.path ?? "")|\(Int((at * 4).rounded()))|\(maxSide)") {
            guard let url else { img = nil; return }
            if let c = Thumbs.cached(url, isImage: isImage, at: at, maxSide: maxSide) { img = c; return }
            img = await Thumbs.image(url, isImage: isImage, at: at, maxSide: maxSide)
        }
    }
}
