import AVFoundation
import SwiftUI

/// While dragging on the clip bar: what the shot would become, and which clip time the preview shows.
struct LiveEdit: Equatable { let src0: Double; let dur: Double; let beats: Int; let scrubAt: Double }

struct FineTuneView: View {
    @EnvironmentObject var vm: AppModel
    let ft: FineTuneState
    @State private var picking = false
    @State private var live: LiveEdit?

    private var shot: Shot { ft.shots[ft.sel] }

    /// Length of this shot if it gets d beats longer (nil = not possible); cuts stay on the beat grid.
    private func durFor(_ d: Int) -> Double? {
        let sh = shot, g = ft.grid
        let beats = sh.b1 - sh.b0
        let end = ft.shots.last!.b1
        let isLast = ft.sel == ft.shots.count - 1
        let prevB0 = ft.sel > 0 ? ft.shots[ft.sel - 1].b0 : -1
        if d == 0 { return sh.t1 - sh.t0 }
        if d < 1 - beats { return nil }
        if !isLast { let nb = sh.b1 + d; return nb >= end ? nil : Styles.frameT(g[nb]) - Styles.frameT(g[sh.b0]) }
        if ft.shots.count == 1 { return nil }
        let nb = sh.b0 - d
        return nb <= prevB0 || nb >= sh.b1 ? nil : Styles.frameT(g[sh.b1]) - Styles.frameT(g[nb])
    }

    var body: some View {
        let media = shot.media
        let dur = shot.t1 - shot.t0
        let beats = shot.b1 - shot.b0
        let shownBeats = live?.beats ?? beats
        let shownDur = live?.dur ?? dur
        VStack(spacing: 10) {
            TopBar(title: "Fine-tune", back: { vm.back() }) {
                Text("Shot \(ft.sel + 1) of \(ft.shots.count)").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
            }
            ShotPreview(shot: shot, song: vm.sessionSong, songStart: vm.songStart, muted: vm.previewMuted,
                        scrubAt: live?.scrubAt) { vm.toggleMute() }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .frame(maxHeight: .infinity)
            ShotStrip(shots: ft.shots, sel: ft.sel) { vm.selectShot($0) }
            ScrollView {
                VStack(spacing: 10) {
                    CardBox {
                        if let m = media, !m.isImage {
                            HStack {
                                Text("\(vm.mediaName(m)) · moment").font(.system(size: 15, weight: .semibold))
                                Spacer()
                                Text("from \(String(format: "%.1f", live?.src0 ?? shot.src0))s").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                            }
                            MomentBar(media: m, src0: shot.src0, shotDur: dur, beats: beats, durFor: durFor,
                                      onLive: { live = $0 }, onMove: { vm.setMoment($0) }, onResize: { vm.resizeShot($0, $1) })
                                .id("\(ft.sel)|\(shot.src0)|\(dur)")
                            Text("Drag the box to pick the part. Drag its edges to make the shot longer or shorter.")
                                .font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                        } else {
                            Text("\(vm.mediaName(media)) · photo").font(.system(size: 15, weight: .semibold))
                            Text("Photos play with a slow zoom. Use Change clip to put a video here.")
                                .font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                        }
                    }
                    CardBox {
                        HStack {
                            Text("Length").font(.system(size: 15, weight: .semibold))
                            Spacer()
                            Text("\(shownBeats) \(shownBeats == 1 ? "beat" : "beats") · \(String(format: "%.2f", shownDur))s")
                                .font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                        }
                        HStack(spacing: 8) {
                            BigButton(label: "− 1 beat", outlined: true) { vm.changeLength(-1) }
                            BigButton(label: "+ 1 beat", outlined: true) { vm.changeLength(1) }
                        }
                    }
                    HStack(spacing: 8) {
                        BigButton(label: "Change clip", icon: "arrow.left.arrow.right", outlined: true) { picking = true }
                        BigButton(label: "Remove shot", icon: "trash", outlined: true) { vm.removeShot() }
                    }
                }
            }
            .frame(maxHeight: 290)
            BigButton(label: "Apply changes") { vm.applyFineTune() }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .foregroundStyle(RC.onBackground)
        .onChange(of: ft.sel) { _ in live = nil }
        .sheet(isPresented: $picking) {
            ClipPicker(current: media) { vm.setClip($0); picking = false } close: { picking = false }
        }
    }
}

// ---------------------------------------------------------- shot strip ---

struct ShotStrip: View {
    let shots: [Shot]
    let sel: Int
    let onSelect: (Int) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(shots.enumerated()), id: \.offset) { i, sh in
                        let selected = i == sel
                        ZStack(alignment: .bottom) {
                            ThumbView(url: sh.media?.url, isImage: sh.media?.isImage ?? true, at: sh.src0 + (sh.t1 - sh.t0) / 2)
                            Text(String(format: "%.1f", sh.t0)).font(.system(size: 10)).foregroundStyle(.white)
                                .frame(maxWidth: .infinity).background(Color.black.opacity(0.53))
                        }
                        .overlay(alignment: .top) {
                            if selected {
                                Text("Editing").font(.system(size: 9, weight: .semibold)).foregroundStyle(.white)
                                    .padding(.horizontal, 4).background(RC.primary, in: RoundedRectangle(cornerRadius: 4)).padding(.top, 4)
                            }
                        }
                        .frame(width: selected ? 50 : 40, height: selected ? 74 : 62)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? RC.primary : Color.clear, lineWidth: 3))
                        .opacity(selected ? 1 : 0.45)
                        .padding(2)
                        .id(i)
                        .onTapGesture { onSelect(i) }
                    }
                }
            }
            .frame(height: 80)
            .onChange(of: sel) { s in withAnimation { proxy.scrollTo(max(0, s - 2), anchor: .leading) } }
            .onAppear { proxy.scrollTo(max(0, sel - 2), anchor: .leading) }
        }
    }
}

// ---------------------------------------------------------- moment bar ---

/// The whole clip as a bar; the box is the part this shot plays. Drag the box (or tap) to move it;
/// drag its left / right handle to make the shot longer or shorter, snapped to beats.
struct MomentBar: View {
    let media: MediaInfo
    let src0: Double
    let shotDur: Double
    let beats: Int
    let durFor: (Int) -> Double?
    let onLive: (LiveEdit?) -> Void
    let onMove: (Double) -> Void
    let onResize: (Int, Double?) -> Void

    private enum Grab { case move, left, right }
    @State private var liveSrc0: Double?
    @State private var liveDur: Double?
    @State private var grab: Grab = .move
    @State private var startSrc = 0.0
    @State private var resizeD = 0
    @State private var dragging = false

    /// A wanted length snapped to a whole number of beats that fits in the clip.
    private func snap(_ want: Double, _ maxDur: Double) -> (Int, Double) {
        var best = (0, shotDur)
        var d = 1 - beats
        while d <= 64 {
            guard let v = durFor(d) else { if d > 0 { break }; d += 1; continue }
            if v <= maxDur + 1e-6 && abs(v - want) < abs(best.1 - want) { best = (d, v) }
            d += 1
        }
        return best
    }

    var body: some View {
        GeometryReader { geo in
            let clipDur = max(media.dur, 0.1)
            let pad: CGFloat = 12
            let barW = max(geo.size.width - 2 * pad, 1)
            let handleZone: CGFloat = 26
            let xOf = { (t: Double) -> CGFloat in pad + CGFloat(t / clipDur) * barW }
            let tOf = { (x: CGFloat) -> Double in (Double((x - pad) / barW) * clipDur).clamped(0, clipDur) }
            let s0 = liveSrc0 ?? src0
            let d0 = liveDur ?? shotDur
            let left = xOf(s0)
            let right = max(xOf(s0 + d0), left + 14)
            ZStack(alignment: .topLeading) {
                HStack(spacing: 0) {
                    ForEach(0..<6, id: \.self) { i in ThumbView(url: media.url, isImage: false, at: clipDur * (Double(i) + 0.5) / 6) }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.6)))
                .padding(.horizontal, pad)
                RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.25))
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(red: 0.54, green: 0.71, blue: 0.97), lineWidth: 3))
                    .frame(width: right - left, height: geo.size.height)
                    .offset(x: left)
                ForEach(Array([left, right].enumerated()), id: \.offset) { _, e in
                    RoundedRectangle(cornerRadius: 5).fill(Color.white)
                        .overlay(RoundedRectangle(cornerRadius: 5).stroke(Color(red: 0.09, green: 0.37, blue: 0.65), lineWidth: 1))
                        .frame(width: 10, height: 24)
                        .offset(x: e - 5, y: 12)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                let x = g.location.x
                let endT = src0 + shotDur
                if !dragging {
                    dragging = true
                    resizeD = 0
                    let sx = g.startLocation.x
                    if sx >= left && sx <= right && right - left > 2 * handleZone { grab = .move }
                    else if abs(sx - right) <= handleZone && sx >= (left + right) / 2 { grab = .right }
                    else if abs(sx - left) <= handleZone { grab = .left }
                    else if abs(sx - right) <= handleZone { grab = .right }
                    else { grab = .move }
                    startSrc = grab == .move && !(sx >= left && sx <= right) ? tOf(sx) - shotDur / 2 : src0
                }
                switch grab {
                case .move:
                    let maxStart = max(0, clipDur - shotDur - 0.05)
                    liveSrc0 = (startSrc + Double((x - g.startLocation.x) / barW) * clipDur).clamped(0, maxStart)
                    liveDur = shotDur
                case .right:
                    let (d, v) = snap(tOf(x) - src0, clipDur - src0 - 0.05)
                    resizeD = d; liveDur = v; liveSrc0 = src0
                case .left:
                    let (d, v) = snap(endT - tOf(x), endT)
                    resizeD = d; liveDur = v; liveSrc0 = max(0, endT - v)
                }
                let s = liveSrc0 ?? src0, dd = liveDur ?? shotDur
                let at = grab == .right ? max(s, s + dd - 0.04) : s
                onLive(LiveEdit(src0: s, dur: dd, beats: beats + resizeD, scrubAt: at))
            }.onEnded { g in
                let s = liveSrc0, gr = grab, d = resizeD
                let moved = hypot(g.translation.width, g.translation.height) > 4
                dragging = false
                liveSrc0 = nil; liveDur = nil
                onLive(nil)
                if !moved {
                    // a tap away from the handles moves the box there
                    let x = g.location.x
                    if abs(x - left) > handleZone && abs(x - right) > handleZone {
                        let maxStart = max(0, clipDur - shotDur - 0.05)
                        onMove((tOf(x) - shotDur / 2).clamped(0, maxStart))
                    }
                    return
                }
                switch gr {
                case .move: if let s { onMove(s) }
                case .right: if d != 0 { onResize(d, nil) }
                case .left: if d != 0 { onResize(d, s) }
                }
            })
        }
        .frame(height: 48)
    }
}

// ---------------------------------------------------------- clip picker ---

struct ClipPicker: View {
    @EnvironmentObject var vm: AppModel
    let current: MediaInfo?
    let onPick: (MediaInfo) -> Void
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Change clip").font(.system(size: 18, weight: .semibold))
                Spacer()
                Button("Close", action: close)
            }
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(Array(vm.pickedMedia.enumerated()), id: \.offset) { _, m in
                        VStack(spacing: 4) {
                            ThumbView(url: m.url, isImage: m.isImage, at: m.isImage ? 0 : m.dur / 2)
                                .aspectRatio(9.0 / 14.0, contentMode: .fit)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(m === current ? RC.primary : Color.clear, lineWidth: 2))
                            Text(vm.mediaName(m)).font(.system(size: 13)).lineLimit(1)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { onPick(m) }
                    }
                }
            }
        }
        .padding(20)
        .foregroundStyle(RC.onBackground)
    }
}

// ------------------------------------------------------------- preview ---

/// Plays just this shot (the clip part, or the photo) with its piece of the song, on a loop.
/// While the user drags on the clip bar (scrubAt set) it shows that exact frame instead.
struct ShotPreview: View {
    let shot: Shot
    let song: URL?
    let songStart: Double
    let muted: Bool
    let scrubAt: Double?
    let onToggleMute: () -> Void

    @StateObject private var pm = ShotPreviewModel()

    var body: some View {
        let isVideo = shot.media.map { !$0.isImage } ?? false
        ZStack(alignment: .topLeading) {
            Color.black
            Group {
                if isVideo {
                    PlayerSurface(player: scrubAt != nil ? pm.scrub : pm.player, fill: true)
                } else {
                    ThumbView(url: shot.media?.url, isImage: true, at: 0, maxSide: 720)
                }
            }
            .aspectRatio(9.0 / 16.0, contentMode: .fit)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if muted {
                Text("Sound off").font(.system(size: 12)).foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 6)).padding(10)
            }
            Button(action: onToggleMute) {
                Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill").foregroundStyle(.white)
                    .frame(width: 40, height: 40).background(Color.black.opacity(0.55), in: Circle())
            }
            .accessibilityLabel(muted ? "Turn sound on" : "Turn sound off")
            .padding(6)
            .frame(maxWidth: .infinity, alignment: .topTrailing)
        }
        .task(id: "\(shot.media?.url.path ?? "")|\(shot.src0)|\(shot.t0)|\(shot.t1)") {
            await pm.load(shot: shot, song: song, songStart: songStart, muted: muted)
        }
        .onChange(of: muted) { m in pm.player?.isMuted = m }
        .onChange(of: scrubAt) { t in pm.scrubTo(t) }
        .onDisappear { pm.stop() }
    }
}

@MainActor
final class ShotPreviewModel: ObservableObject {
    @Published var player: AVQueuePlayer?
    @Published var scrub: AVPlayer?
    private var looper: AVPlayerLooper?
    private var scrubURL: URL?
    private var seeking = false
    private var pending: Double?

    func load(shot: Shot, song: URL?, songStart: Double, muted: Bool) async {
        stop()
        guard let m = shot.media else { return }
        let dur = CMTime(seconds: shot.t1 - shot.t0, preferredTimescale: 600)
        let comp = AVMutableComposition()
        if !m.isImage {
            let a = AVURLAsset(url: m.url)
            if let t = try? await a.loadTracks(withMediaType: .video).first,
               let vt = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) {
                try? vt.insertTimeRange(CMTimeRange(start: CMTime(seconds: shot.src0, preferredTimescale: 600), duration: dur), of: t, at: .zero)
                vt.preferredTransform = (try? await t.load(.preferredTransform)) ?? .identity
            }
            if scrubURL != m.url {
                scrubURL = m.url
                let s = AVPlayer(url: m.url)
                s.isMuted = true
                scrub = s
            }
        }
        if let song, let st = try? await AVURLAsset(url: song).loadTracks(withMediaType: .audio).first,
           let at = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
            try? at.insertTimeRange(CMTimeRange(start: CMTime(seconds: songStart + shot.t0, preferredTimescale: 600), duration: dur), of: st, at: .zero)
        }
        if Task.isCancelled { return }
        let q = AVQueuePlayer()
        q.isMuted = muted
        looper = AVPlayerLooper(player: q, templateItem: AVPlayerItem(asset: comp))
        player = q
        q.play()
    }

    func scrubTo(_ t: Double?) {
        guard let t else { player?.play(); return }
        player?.pause()
        guard let s = scrub else { return }
        if seeking { pending = t; return }
        seeking = true
        s.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.seeking = false
                if let p = self.pending { self.pending = nil; self.scrubTo(p) }
            }
        }
    }

    func stop() {
        player?.pause()
        looper = nil
        player = nil
    }
}
