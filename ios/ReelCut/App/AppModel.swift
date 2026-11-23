import AVFoundation
import SwiftUI

struct ResultState {
    var file: URL
    var lookKey: String
    var lookName: String
    var bpm: Int
    var pace: String = ""
    var exporting = false
    var exportProgress = 0.0
    var exported: URL? = nil
}

/// Fine-tune: the shots being edited (not yet applied), the selected one, and the result to go back to.
struct FineTuneState {
    var shots: [Shot]
    var sel: Int
    var grid: [Double]
    var back: ResultState
    var changed = false
}

enum Screen {
    case home
    case working(String, Double)
    case setup(String)
    case result(ResultState)
    case viewer(Library.Item)
    case editor(String)
    case fineTune(FineTuneState)
}

/// Same flow as the Android ReelViewModel: pick clips -> song -> setup -> reel -> fine-tune / export.
@MainActor
final class AppModel: ObservableObject {
    @Published var screen: Screen = .home
    @Published var options: ReelOptions
    @Published var reelLen: Int
    @Published var recent: [Library.Item] = Library.list()
    @Published var projects: [Project] = ProjectStore.list()
    @Published var toast: String?
    @Published var loading: String?          // picked files still being copied in
    @Published var previewMuted: Bool

    private let defaults = UserDefaults.standard
    private var pendingMedia: [URL] = []
    private var pendingSong: URL?
    private(set) var session: ReelSession?
    private var variation = 0
    private var lookKey: String?
    private var plan: Plan?
    private var edited = false
    private var job: Task<Void, Never>?
    private var toastJob: Task<Void, Never>?

    init() {
        let d = UserDefaults.standard
        options = ReelOptions(pace: Pace(rawValue: d.string(forKey: "pace") ?? "") ?? .auto,
                              shape: Shape(rawValue: d.string(forKey: "shape") ?? "") ?? .portrait)
        let len = d.integer(forKey: "len")
        reelLen = reelLengths.contains(len) ? len : 15
        previewMuted = d.bool(forKey: "ft_muted")
    }

    func say(_ m: String) {
        toast = m
        toastJob?.cancel()
        toastJob = Task { try? await Task.sleep(nanoseconds: 2_800_000_000); if !Task.isCancelled { toast = nil } }
    }

    func setLen(_ s: Int) { reelLen = s; defaults.set(s, forKey: "len") }
    func setPace(_ p: Pace) { options.pace = p; defaults.set(p.rawValue, forKey: "pace") }
    func setShape(_ s: Shape) { options.shape = s; defaults.set(s.rawValue, forKey: "shape") }
    func setTitle(_ t: String) { options.title = String(t.prefix(28)) }

    var sessionSong: URL? { session?.song }
    var songStart: Double { session?.analysis.start ?? 0 }
    var pickedMedia: [MediaInfo] { session?.media.filter { $0.ok } ?? [] }

    /// "Clip 2" / "Photo 1", numbered in the order they were picked.
    func mediaName(_ m: MediaInfo?) -> String {
        guard let m, let all = session?.media else { return "" }
        let same = all.filter { $0.isImage == m.isImage }
        return (m.isImage ? "Photo " : "Clip ") + String((same.firstIndex { $0 === m } ?? 0) + 1)
    }

    // ------------------------------------------------------------ auto reel ---

    /// Clips picked (already copied into the app): next the song.
    func setMedia(_ urls: [URL]) { pendingMedia = urls }
    var hasMedia: Bool { pendingMedia.count >= 2 }

    /// Song picked: show the setup screen.
    func songPicked(_ url: URL) {
        if pendingMedia.count < 2 { say("Pick at least 2 clips or photos"); return }
        pendingSong = url
        options.title = ""
        screen = .setup(url.lastPathComponent)
    }

    private func newPlan() {
        guard let s = session else { return }
        plan = ReelEngine.plan(s, variation: variation, forcedLook: lookKey, opts: options)
        edited = false
    }

    /// Setup -> Make reel.
    func start() {
        guard let song = pendingSong else { return }
        let media = pendingMedia
        if media.count < 2 { say("Pick at least 2 clips or photos"); return }
        variation = 0; lookKey = nil; session = nil
        job?.cancel()
        screen = .working("Starting", 0)
        let len = Double(reelLen)
        job = Task {
            do {
                let s = try await ReelEngine.prepare(media: media, song: song, reelLen: len) { st, p in
                    Task { @MainActor in self.update(st, p) }
                }
                try Task.checkCancellation()
                session = s
                newPlan()
                try await renderPreview()
            } catch is CancellationError {
            } catch { fail(error) }
        }
    }

    private func update(_ stage: String, _ p: Double) {
        if case .working = screen { screen = .working(stage, p) }
    }

    private func renderPreview() async throws {
        guard let s = session, let pl = plan else { return }
        screen = .working("Making your reel", 0.4)
        let old = (try? FileManager.default.contentsOfDirectory(at: Paths.cache, includingPropertiesForKeys: nil)) ?? []
        old.filter { $0.lastPathComponent.hasPrefix("preview_") }.forEach { try? FileManager.default.removeItem(at: $0) }
        let out = Paths.cache.appendingPathComponent("preview_\(Paths.nowMs()).mp4")
        let o = options
        try await ReelEngine.render(s, pl, width: o.shape.pw, height: o.shape.ph, out: out, title: o.title) { st, p in
            Task { @MainActor in self.update(st, p) }
        }
        try Task.checkCancellation()
        screen = .result(ResultState(file: out, lookKey: pl.style.look.key, lookName: pl.style.look.name,
                                     bpm: Int(s.analysis.bpm.rounded()), pace: pl.style.pace.label))
    }

    private func rerender() {
        guard session != nil else { return }
        job?.cancel()
        job = Task {
            do { try await renderPreview() } catch is CancellationError {} catch { fail(error) }
        }
    }

    func anotherEdit() { variation += 1; lookKey = nil; newPlan(); rerender() }

    func pickLook(_ key: String) {
        guard case .result(let r) = screen, !r.exporting, key != r.lookKey else { return }
        lookKey = key
        if edited { plan = plan?.withLook(key) } else { newPlan() }
        rerender()
    }

    func export() {
        guard case .result(var r) = screen, let s = session, let pl = plan, !r.exporting else { return }
        r.exporting = true; r.exportProgress = 0
        screen = .result(r)
        let o = options
        let label = "\(r.lookName) · \(Int(s.analysis.len.rounded()))s"
        job = Task {
            do {
                let out = Library.newFile()
                try await ReelEngine.render(s, pl, width: o.shape.w, height: o.shape.h, out: out, title: o.title) { _, p in
                    Task { @MainActor in
                        if case .result(var cur) = self.screen, cur.exporting {
                            cur.exportProgress = ((p - 0.4) / 0.6).clamped(0, 1)
                            self.screen = .result(cur)
                        }
                    }
                }
                await Library.finish(out, label: label)
                var saved = true
                do { try await Library.saveToPhotos(out) } catch { saved = false; say(error.localizedDescription) }
                recent = Library.list()
                if case .result(var cur) = screen { cur.exporting = false; cur.exported = out; screen = .result(cur) }
                if saved { say("Saved to Photos") }
            } catch is CancellationError {
            } catch {
                if case .result(var cur) = screen { cur.exporting = false; screen = .result(cur) }
                say("Export failed: \(friendly(error))")
            }
        }
    }

    // ------------------------------------------------------------ fine-tune ---

    func openFineTune() {
        guard case .result(let r) = screen, let pl = plan, !r.exporting else { return }
        screen = .fineTune(FineTuneState(shots: pl.shots, sel: 0, grid: pl.grid, back: r))
    }

    private func editFt(_ change: (FineTuneState) -> [Shot]?, _ failMsg: String? = nil) {
        guard case .fineTune(var ft) = screen else { return }
        guard let next = change(ft) else { if let m = failMsg { say(m) }; return }
        ft.shots = next
        ft.sel = min(ft.sel, next.count - 1)
        ft.changed = true
        screen = .fineTune(ft)
    }

    func selectShot(_ i: Int) {
        guard case .fineTune(var ft) = screen, ft.shots.indices.contains(i) else { return }
        ft.sel = i
        screen = .fineTune(ft)
    }

    func changeLength(_ d: Int) {
        editFt({ ShotEdits.changeLength($0.shots, $0.grid, $0.sel, d) },
               d < 0 ? "This shot is already 1 beat" : "No room to make this shot longer")
    }

    /// Edge handles: shot d beats longer/shorter; newSrc0 = new clip start when the left edge was dragged.
    func resizeShot(_ d: Int, _ newSrc0: Double?) {
        editFt({ ft in
            var cur = ft.shots
            var done = 0
            let step = d > 0 ? 1 : -1
            while done < abs(d) {
                guard let n = ShotEdits.changeLength(cur, ft.grid, ft.sel, step) else { break }
                cur = n; done += 1
            }
            if done == 0 && newSrc0 == nil { return nil }
            if let s = newSrc0 { return ShotEdits.setMoment(cur, ft.sel, s) }
            return cur
        }, "No room to change this shot")
    }

    func toggleMute() { previewMuted.toggle(); defaults.set(previewMuted, forKey: "ft_muted") }

    func removeShot() { editFt({ ShotEdits.remove($0.shots, $0.grid, $0.sel) }, "A reel needs at least one shot") }

    func setMoment(_ src0: Double) { editFt { ShotEdits.setMoment($0.shots, $0.sel, src0) } }

    func setClip(_ m: MediaInfo) {
        editFt { ft in
            let sh = ft.shots[ft.sel]
            return ShotEdits.setMedia(ft.shots, ft.sel, m, ClipScanner.bestMoment(m, sh.t1 - sh.t0))
        }
    }

    func applyFineTune() {
        guard case .fineTune(let ft) = screen, let pl = plan else { return }
        if !ft.changed { screen = .result(ft.back); return }
        plan = Plan(style: pl.style, shots: ft.shots, grid: ft.grid)
        edited = true
        rerender()
    }

    /// For the screenshot test only: a made session + plan, Fine-tune open on shot sel.
    func loadForTest(_ s: ReelSession, _ p: Plan, sel: Int) {
        session = s; plan = p
        let r = ResultState(file: Paths.cache.appendingPathComponent("none.mp4"), lookKey: p.style.look.key,
                            lookName: p.style.look.name, bpm: Int(s.analysis.bpm.rounded()))
        screen = .fineTune(FineTuneState(shots: p.shots, sel: sel, grid: p.grid, back: r))
    }

    // ------------------------------------------------------- timeline projects ---

    func refreshLists() { projects = ProjectStore.list(); recent = Library.list() }

    func newProject(_ files: [URL]) {
        Task {
            let clips = await ProjectStore.probe(files)
            if clips.isEmpty { say("These files could not be read"); return }
            let p = ProjectStore.newProject(clips)
            ProjectStore.save(p)
            projects = ProjectStore.list()
            screen = .editor(p.id)
            if clips.count < files.count { say("\(files.count - clips.count) file(s) could not be read") }
        }
    }

    func openProject(_ id: String) {
        if ProjectStore.load(id) == nil { say("This project could not be opened"); return }
        screen = .editor(id)
    }

    func renameProject(_ p: Project, _ name: String) {
        let clean = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        if clean.isEmpty { return }
        var q = p; q.name = clean; q.updated = Paths.nowMs()
        ProjectStore.save(q)
        projects = ProjectStore.list()
    }

    func deleteProject(_ p: Project) {
        ProjectStore.delete(p.id)
        ProjectStore.cleanMedia()
        projects = ProjectStore.list()
        say("Project deleted")
    }

    func open(_ item: Library.Item) { screen = .viewer(item) }

    func delete(_ item: Library.Item) {
        Library.delete(item)
        recent = Library.list()
        screen = .home
        say("Reel deleted from the app")
    }

    func back() {
        switch screen {
        case .working, .result: job?.cancel(); screen = .home
        case .viewer, .setup: screen = .home
        case .editor: screen = .home; refreshLists()
        case .fineTune(let ft):
            screen = .result(ft.back)
            if ft.changed { say("Changes not applied") }
        case .home: break
        }
    }

    private func fail(_ e: Error) {
        screen = .home
        say(friendly(e))
    }

    private func friendly(_ e: Error) -> String {
        if let r = e as? ReelError { return r.message }
        return "Could not make the reel. Try other clips or another song. (\(e.localizedDescription))"
    }
}
