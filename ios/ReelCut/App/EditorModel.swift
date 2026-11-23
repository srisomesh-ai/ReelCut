import AVFoundation
import SwiftUI

/// Timeline editor: one open project, its undo history, the live preview and export.
@MainActor
final class EditorModel: ObservableObject {
    @Published var project: Project?
    @Published var selected: Int64?
    @Published var canUndo = false
    @Published var canRedo = false
    @Published var playhead = 0.0            // seconds on the timeline
    @Published var playing = false
    @Published var player: AVPlayer?
    @Published var exporting: Double?        // 0..1, nil = not exporting
    @Published var exported: URL?
    @Published var toast: String?

    private var undoStack: [Project] = []
    private var redoStack: [Project] = []
    private var timeObs: Any?
    private var endObs: NSObjectProtocol?
    private var buildJob: Task<Void, Never>?
    private var exportJob: Task<Void, Never>?
    private var lastSeek = Date.distantPast

    func say(_ m: String) {
        toast = m
        Task { try? await Task.sleep(nanoseconds: 2_800_000_000); if toast == m { toast = nil } }
    }

    // ---------------------------------------------------------- open / close ---

    func open(_ id: String) {
        if project?.id == id { return }
        guard let p = ProjectStore.load(id) else { say("This project could not be opened"); return }
        undoStack.removeAll(); redoStack.removeAll()
        project = p
        selected = p.clips.first?.id
        canUndo = false; canRedo = false
        playhead = 0
        exported = nil
        rebuild()
    }

    /// Saves, writes the cover picture, frees the player; done runs when the cover is written.
    func close(_ done: @escaping () -> Void) {
        pause()
        dropPlayer()
        guard let p = project else { done(); return }
        project = nil
        Task {
            ProjectStore.save(p)
            await ProjectStore.writeCover(p)
            done()
        }
    }

    // --------------------------------------------------------------- preview ---

    private func dropPlayer() {
        if let o = timeObs { player?.removeTimeObserver(o) }
        timeObs = nil
        if let e = endObs { NotificationCenter.default.removeObserver(e) }
        endObs = nil
        player?.pause()
        player = nil
    }

    /// New preview player for the current project, kept at the playhead.
    private func rebuild() {
        let wasPlaying = playing
        pause()
        buildJob?.cancel()
        guard let p = project, !p.clips.isEmpty else { dropPlayer(); playhead = 0; return }
        playhead = playhead.clamped(0, p.total)
        buildJob = Task {
            do {
                let b = try await TimelineComposer.build(p, width: 720, height: 1280)
                if Task.isCancelled { return }
                let item = AVPlayerItem(asset: b.asset)
                item.videoComposition = b.video
                dropPlayer()
                let pl = AVPlayer(playerItem: item)
                pl.actionAtItemEnd = .pause
                timeObs = pl.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 25), queue: .main) { [weak self] t in
                    Task { @MainActor in
                        guard let self, self.playing else { return }
                        self.playhead = t.seconds
                    }
                }
                endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                    Task { @MainActor in
                        guard let self else { return }
                        self.playing = false
                        self.playhead = self.project?.total ?? 0
                    }
                }
                await pl.seek(to: CMTime(seconds: playhead, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                player = pl
                if wasPlaying { play() }
            } catch {
                say("Preview problem: \(error.localizedDescription)")
            }
        }
    }

    func togglePlay() { playing ? pause() : play() }

    func play() {
        guard let pl = player, let p = project else { return }
        if playhead >= p.total - 0.05 {
            playhead = 0
            pl.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        pl.play()
        playing = true
    }

    func pause() {
        player?.pause()
        playing = false
    }

    /// Moves the playhead (timeline drag); the preview follows, at most every 80 ms while dragging.
    func scrubTo(_ t: Double, final: Bool = false) {
        guard let p = project else { return }
        if playing { pause() }
        playhead = t.clamped(0, p.total)
        if final || Date().timeIntervalSince(lastSeek) > 0.08 {
            lastSeek = Date()
            player?.seek(to: CMTime(seconds: playhead, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    // ----------------------------------------------------------------- edits ---

    private func commit(_ next: Project, select: Int64?? = .none) {
        guard let cur = project else { return }
        undoStack.append(cur)
        if undoStack.count > 60 { undoStack.removeFirst() }
        redoStack.removeAll()
        var n = next
        n.updated = Paths.nowMs()
        set(n, select: select ?? selected)
    }

    private func set(_ p: Project, select: Int64?) {
        project = p
        selected = select.flatMap { id in p.clips.contains { $0.id == id } ? id : nil }
        canUndo = !undoStack.isEmpty
        canRedo = !redoStack.isEmpty
        ProjectStore.save(p)
        rebuild()
    }

    func select(_ id: Int64?) { selected = id }

    private func selectedOrSay() -> Int64? {
        if let s = selected { return s }
        say("Tap a clip first")
        return nil
    }

    func trim(_ id: Int64, left: Bool, _ delta: Double) {
        guard let p = project,
              let n = left ? TimelineEdits.trimLeft(p, id, delta) : TimelineEdits.trimRight(p, id, delta),
              let i = n.indexOf(id) else { return }
        commit(n, select: id)
        let s = n.starts()[i]
        playhead = left ? s : s + n.clips[i].dur - 0.04
    }

    func split() {
        guard let p = project else { return }
        guard case let (n, newId)? = TimelineEdits.split(p, playhead) else { say("Move the line inside a clip, away from its ends"); return }
        commit(n, select: newId)
    }

    func duplicate() {
        guard let p = project, let id = selectedOrSay(), case let (n, newId)? = TimelineEdits.duplicate(p, id) else { return }
        commit(n, select: newId)
    }

    func delete() {
        guard let p = project, let id = selectedOrSay(), let i = p.indexOf(id), let n = TimelineEdits.delete(p, id) else { return }
        let next = i < n.clips.count ? n.clips[i].id : n.clips.last?.id
        commit(n, select: next)
    }

    func move(_ id: Int64, to: Int) {
        guard let p = project, let n = TimelineEdits.move(p, id, to) else { return }
        commit(n, select: id)
    }

    func add(_ files: [URL]) {
        guard project != nil else { return }
        Task {
            let clips = await ProjectStore.probe(files)
            if clips.isEmpty { say("These files could not be read"); return }
            guard let cur = project else { return }
            let at = selected.flatMap { cur.indexOf($0) }.map { $0 + 1 } ?? cur.clips.count
            guard case let (n, first)? = TimelineEdits.add(cur, at, clips) else { return }
            commit(n, select: first)
            if clips.count < files.count { say("\(files.count - clips.count) file(s) could not be read") }
        }
    }

    func rename(_ name: String) {
        guard var p = project else { return }
        let clean = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        if clean.isEmpty || clean == p.name { return }
        p.name = clean
        commit(p)
    }

    func undo() {
        guard let prev = undoStack.popLast() else { return }
        if let p = project { redoStack.append(p) }
        set(prev, select: selected)
    }

    func redo() {
        guard let next = redoStack.popLast() else { return }
        if let p = project { undoStack.append(p) }
        set(next, select: selected)
    }

    // ---------------------------------------------------------------- export ---

    func export() {
        guard let p = project else { return }
        if p.clips.isEmpty { say("Add clips first"); return }
        if exporting != nil { return }
        pause()
        exporting = 0
        exportJob = Task {
            do {
                let out = Library.newFile()
                try await TimelineComposer.export(p, to: out) { f in Task { @MainActor in if self.exporting != nil { self.exporting = f } } }
                await Library.finish(out, label: p.name)
                do { try await Library.saveToPhotos(out) } catch { say(error.localizedDescription) }
                exported = out
            } catch is CancellationError {
            } catch {
                say("Export failed: \(error.localizedDescription)")
            }
            exporting = nil
        }
    }

    func cancelExport() { exportJob?.cancel(); exporting = nil }
    func doneExported() { exported = nil }
}
