import PhotosUI
import SwiftUI

private let trackTop: CGFloat = 22     // ruler height above the clips
private let trackH: CGFloat = 56

struct EditorView: View {
    @EnvironmentObject var evm: EditorModel
    let onBack: () -> Void
    @State private var renaming = false
    @State private var name = ""
    @State private var addItems: [PhotosPickerItem] = []
    @State private var showAdd = false
    @State private var sharing: URL?
    @State private var loading: String?

    var body: some View {
        if let p = evm.project {
            content(p)
        } else {
            Text("Opening…").frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func content(_ p: Project) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 4) {
                Button(action: onBack) { Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold)).frame(width: 40, height: 40) }
                    .accessibilityLabel("Back")
                Text(p.name).font(.system(size: 17, weight: .semibold)).lineLimit(1)
                    .onTapGesture { name = p.name; renaming = true }
                Spacer()
                Button { evm.export() } label: {
                    Text("Export").font(.system(size: 15, weight: .semibold)).foregroundStyle(RC.onPrimary)
                        .padding(.horizontal, 16).padding(.vertical, 9).background(RC.primary, in: RoundedRectangle(cornerRadius: 10))
                }
            }
            ZStack {
                Color.black
                if p.clips.isEmpty || evm.player == nil {
                    Text(p.clips.isEmpty ? "Add clips to start" : "Loading…").font(.system(size: 13)).foregroundStyle(.white)
                } else {
                    PlayerSurface(player: evm.player).aspectRatio(9.0 / 16.0, contentMode: .fit)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .frame(maxHeight: .infinity)

            HStack {
                Text("\(fmt(evm.playhead)) / \(fmt(p.total))").font(.system(size: 13).monospacedDigit()).foregroundStyle(RC.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button { evm.togglePlay() } label: {
                    Image(systemName: evm.playing ? "pause.fill" : "play.fill").font(.system(size: 26)).frame(width: 48, height: 44)
                }
                .accessibilityLabel(evm.playing ? "Pause" : "Play")
                HStack(spacing: 4) {
                    Button { evm.undo() } label: { Image(systemName: "arrow.uturn.backward").frame(width: 40, height: 40) }.disabled(!evm.canUndo)
                        .accessibilityLabel("Undo")
                    Button { evm.redo() } label: { Image(systemName: "arrow.uturn.forward").frame(width: 40, height: 40) }.disabled(!evm.canRedo)
                        .accessibilityLabel("Redo")
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
            }

            TimelineStrip(p: p).frame(height: trackTop + trackH + 14)
            Text("Pinch to zoom · long-press a clip to move it").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
            Divider()
            HStack {
                tool("scissors", "Split") { evm.split() }
                tool("plus.square.on.square", "Duplicate") { evm.duplicate() }
                tool("trash", "Delete") { evm.delete() }
                tool("plus", "Add") { showAdd = true }
            }
            .padding(.bottom, 4)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .foregroundStyle(RC.onBackground)
        .photosPicker(isPresented: $showAdd, selection: $addItems, maxSelectionCount: maxPick,
                      selectionBehavior: .ordered, matching: .any(of: [.images, .videos]), photoLibrary: .shared())
        .onChange(of: addItems) { items in
            guard !items.isEmpty else { return }
            addItems = []
            Task {
                let urls = await Picking.load(items) { i, n in Task { @MainActor in loading = "Loading clips \(i + 1) of \(n)" } }
                loading = nil
                evm.add(urls)
            }
        }
        .alert("Project name", isPresented: $renaming) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) {}
            Button("Save") { evm.rename(name) }
        }
        .overlay {
            if let l = loading {
                VStack(spacing: 12) { ProgressView(); Text(l).font(.system(size: 14)) }
                    .padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            } else if let f = evm.exporting {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Exporting 1080p").font(.system(size: 17, weight: .semibold))
                    ProgressView(value: f)
                    Text("\(Int((f * 100).rounded()))% · keep the app open").font(.system(size: 13))
                    Button("Cancel") { evm.cancelExport() }.frame(maxWidth: .infinity, alignment: .trailing)
                }
                .padding(20).frame(width: 300).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            }
        }
        .alert("Saved to Photos", isPresented: Binding(get: { evm.exported != nil && sharing == nil }, set: { if !$0 { } })) {
            Button("Share") { sharing = evm.exported; evm.doneExported() }
            Button("Done", role: .cancel) { evm.doneExported() }
        } message: { Text(p.name) }
        .sheet(item: Binding(get: { sharing.map { ShareItem(url: $0) } }, set: { sharing = $0?.url })) { s in ShareSheet(items: [s.url]) }
    }

    private func tool(_ icon: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: icon).font(.system(size: 20))
                Text(label).font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }
}

func fmt(_ t: Double) -> String {
    let s = max(0, t)
    let m = Int(floor(s / 60))
    return String(format: "%02d:%04.1f", m, s - Double(m) * 60)
}

// --------------------------------------------------------------- timeline ---

/// The playhead stays in the middle; the clips slide under it.
/// Drag = move the playhead, pinch = zoom, tap = select, long-press + drag = move a clip, white edges = trim.
struct TimelineStrip: View {
    @EnvironmentObject var evm: EditorModel
    let p: Project
    @State private var pps: CGFloat = 60          // points per second
    @State private var pinchBase: CGFloat?
    @State private var scrubBase: Double?
    @State private var trim: (id: Int64, left: Bool, dx: CGFloat)?
    @State private var moving: (id: Int64, x: CGFloat)?

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let center = w / 2
            let starts = p.starts()
            let ph = evm.playhead
            let xOf = { (t: Double) -> CGFloat in center + CGFloat(t - ph) * pps }
            let tAt = { (x: CGFloat) -> Double in ph + Double((x - center) / pps) }

            ZStack(alignment: .topLeading) {
                // gestures on the whole strip
                Color.clear.contentShape(Rectangle())
                    .gesture(SpatialTapGesture().onEnded { e in
                        let t = tAt(e.location.x)
                        let hit = t >= 0 && t <= p.total ? p.clipAt(t)?.0 : nil
                        evm.select(hit.map { p.clips[$0].id })
                    })
                    .simultaneousGesture(DragGesture(minimumDistance: 4).onChanged { g in
                        if moving != nil { return }
                        if scrubBase == nil { scrubBase = evm.playhead }
                        evm.scrubTo(scrubBase! - Double(g.translation.width / pps))
                    }.onEnded { g in
                        if moving == nil, let b = scrubBase { evm.scrubTo(b - Double(g.translation.width / pps), final: true) }
                        scrubBase = nil
                    })
                    .simultaneousGesture(MagnificationGesture().onChanged { s in
                        if pinchBase == nil { pinchBase = pps }
                        pps = (pinchBase! * s).clamped(8, 600)
                    }.onEnded { _ in pinchBase = nil })
                    .simultaneousGesture(LongPressGesture(minimumDuration: 0.4).sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
                        .onChanged { v in
                            if case .second(true, let drag?) = v {
                                if moving == nil {
                                    let t = tAt(drag.startLocation.x)
                                    if t >= 0 && t <= p.total, case let (i, _)? = p.clipAt(t) {
                                        evm.select(p.clips[i].id)
                                        moving = (p.clips[i].id, drag.location.x)
                                    }
                                } else { moving!.x = drag.location.x }
                            }
                        }
                        .onEnded { _ in
                            if let m = moving { evm.move(m.id, to: TimelineEdits.dropIndex(p, m.id, tAt(m.x))) }
                            moving = nil
                        })

                Ruler(pps: pps, playhead: ph, total: p.total, center: center).frame(height: trackTop).allowsHitTesting(false)

                ForEach(Array(p.clips.enumerated()), id: \.element.id) { i, c in
                    let geom = clipGeom(i, c, starts, xOf)
                    if geom.x + geom.w >= -w && geom.x <= 2 * w {
                        ClipBlock(c: c, width: geom.w, selected: c.id == evm.selected)
                            .opacity(moving?.id == c.id ? 0.35 : 1)
                            .offset(x: geom.x, y: trackTop)
                            .allowsHitTesting(false)
                        if c.id == evm.selected && moving == nil {
                            handle(c, left: true).offset(x: geom.x - 16, y: trackTop)
                            handle(c, left: false).offset(x: geom.x + geom.w, y: trackTop)
                        }
                    }
                }

                if let m = moving, let c = p.clips.first(where: { $0.id == m.id }) {
                    let wpx = CGFloat(c.dur) * pps
                    ClipBlock(c: c, width: wpx, selected: true).offset(x: m.x - wpx / 2, y: trackTop - 6).allowsHitTesting(false)
                }

                Rectangle().fill(RC.onBackground).frame(width: 2).offset(x: center - 1).allowsHitTesting(false)
            }
            .clipped()
        }
    }

    /// Where a clip sits, following a trim drag live within what the clip allows.
    private func clipGeom(_ i: Int, _ c: TClip, _ starts: [Double], _ xOf: (Double) -> CGFloat) -> (x: CGFloat, w: CGFloat) {
        var x0 = xOf(starts[i])
        var w = CGFloat(c.dur) * pps
        if let tr = trim, tr.id == c.id {
            let delta = Double(tr.dx / pps)
            let q = tr.left ? TimelineEdits.trimLeft(p, c.id, delta) : TimelineEdits.trimRight(p, c.id, delta)
            let nd = q?.clips[i].dur ?? c.dur
            if tr.left { x0 += CGFloat(c.dur - nd) * pps }
            w = CGFloat(nd) * pps
        }
        return (x0, w)
    }

    private func handle(_ c: TClip, left: Bool) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4).fill(Color.white)
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(RC.primary, lineWidth: 1))
                .frame(width: 10, height: trackH - 12)
        }
        .frame(width: 16, height: trackH)
        .contentShape(Rectangle())
        .highPriorityGesture(DragGesture(minimumDistance: 1).onChanged { g in
            trim = (c.id, left, g.translation.width)
        }.onEnded { g in
            trim = nil
            if abs(g.translation.width) > 1 { evm.trim(c.id, left: left, Double(g.translation.width / pps)) }
        })
    }
}

struct Ruler: View {
    let pps: CGFloat
    let playhead: Double
    let total: Double
    let center: CGFloat

    var body: some View {
        Canvas { ctx, size in
            let steps: [Double] = [0.5, 1, 2, 5, 10, 15, 30, 60]
            let step = steps.first { CGFloat($0) * pps >= 64 } ?? 120
            let t0 = max(0, floor((playhead - Double(center / pps)) / step) * step)
            let t1 = min(playhead + Double(center / pps), total + step)
            var t = t0
            while t <= t1 {
                let x = center + CGFloat(t - playhead) * pps
                var path = Path()
                path.move(to: CGPoint(x: x, y: size.height - 6)); path.addLine(to: CGPoint(x: x, y: size.height))
                ctx.stroke(path, with: .color(RC.onSurfaceVariant), lineWidth: 1)
                let label = step < 1 ? String(format: "%.1fs", t) : "\(Int(t.rounded()))s"
                ctx.draw(Text(label).font(.system(size: 10)).foregroundColor(RC.onSurfaceVariant), at: CGPoint(x: x + 3, y: 0), anchor: .topLeading)
                t += step
            }
        }
    }
}

struct ClipBlock: View {
    let c: TClip
    let width: CGFloat
    let selected: Bool

    var body: some View {
        let w = max(width, 2)
        let n = Int(ceil(w / 40)).clamped(1, 40)
        ZStack(alignment: .bottomLeading) {
            (c.isImage ? Color(red: 0x53 / 255, green: 0x4A / 255, blue: 0xB7 / 255) : RC.tile)
            HStack(spacing: 0) {
                ForEach(0..<n, id: \.self) { k in
                    ThumbView(url: ProjectStore.url(c), isImage: c.isImage, at: c.isImage ? 0 : c.inS + c.dur * (Double(k) + 0.5) / Double(n), maxSide: 128)
                        .frame(width: 40, height: trackH)
                }
            }
            .frame(width: w, height: trackH, alignment: .leading)
            .clipped()
            if w > 34 {
                Text(String(format: "%.1fs", c.dur)).font(.system(size: 10)).foregroundStyle(.white)
                    .padding(.horizontal, 3).background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 3)).padding(3)
            }
        }
        .frame(width: w, height: trackH)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(selected ? RC.primary : Color.clear, lineWidth: 2))
    }
}
