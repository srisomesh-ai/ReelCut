import AVFoundation
import SwiftUI
import XCTest
@testable import ReelCut

/// Makes real reels in the iPhone simulator from the test clips (same runs as the Android emulator test, plus a
/// 1-minute and a 2-minute reel), exports a timeline project, and takes screenshots of the screens.
/// Results go to the folder in RC_OUT (CI prints them into the log).
final class RenderTests: XCTestCase {

    private var outDir: URL {
        let p = ProcessInfo.processInfo.environment["RC_OUT"] ?? NSTemporaryDirectory() + "rc-out"
        let u = URL(fileURLWithPath: p, isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    /// Test files copied into the app (like picked files).
    private func inputs() throws -> (media: [URL], dir: URL) {
        let bundle = Bundle(for: RenderTests.self)
        let dir = Paths.cache.appendingPathComponent("test-in", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let names = ["clip1.mp4", "clip2.mp4", "clip3.mp4", "clip4.mp4", "clip5.mp4", "photo1.jpg", "photo2.jpg",
                     "song_fast.mp3", "song_slow.m4a", "song_long.m4a"]
        for n in names {
            let u = (n as NSString)
            guard let src = bundle.url(forResource: u.deletingPathExtension, withExtension: u.pathExtension) else {
                throw ReelError("test file missing: \(n)")
            }
            try FileManager.default.copyItem(at: src, to: dir.appendingPathComponent(n))
        }
        let media = names.filter { $0.hasSuffix(".mp4") || $0.hasSuffix(".jpg") }.sorted().map { dir.appendingPathComponent($0) }
        return (media, dir)
    }

    struct Run {
        let song: String
        let len: Double
        let look: String?
        var opts = ReelOptions()
        var edited = false
    }

    func testMakesReels() async throws {
        let (media, dir) = try inputs()
        let runs: [Run] = [
            Run(song: "song_fast.mp3", len: 15, look: nil),
            Run(song: "song_slow.m4a", len: 15, look: nil, opts: ReelOptions(title: "Wedding Day")),
            Run(song: "song_fast.mp3", len: 10, look: "mono"),
            Run(song: "song_fast.mp3", len: 15, look: nil, edited: true),
            Run(song: "song_fast.mp3", len: 15, look: "vhs", opts: ReelOptions(pace: .fast, title: "DJ Night")),
            Run(song: "song_slow.m4a", len: 15, look: "vintage", opts: ReelOptions(pace: .smooth, shape: .square)),
            Run(song: "song_fast.mp3", len: 15, look: "sunset", opts: ReelOptions(pace: .normal, shape: .wide)),
            Run(song: "song_long.m4a", len: 60, look: nil, opts: ReelOptions(pace: .normal)),
            Run(song: "song_long.m4a", len: 120, look: "golden", opts: ReelOptions(pace: .smooth, title: "Two Minutes")),
        ]
        var report = ""
        for (i, run) in runs.enumerated() {
            let t0 = Date()
            let s = try await ReelEngine.prepare(media: media, song: dir.appendingPathComponent(run.song), reelLen: run.len) { _, _ in }
            let t1 = Date()
            var plan = ReelEngine.plan(s, variation: 0, forcedLook: run.look, opts: run.opts)
            if run.edited {
                var sh = plan.shots
                sh = try XCTUnwrap(ShotEdits.changeLength(sh, plan.grid, 1, 1), "+1 beat failed")
                sh = try XCTUnwrap(ShotEdits.changeLength(sh, plan.grid, 4, -1), "-1 beat failed")
                sh = try XCTUnwrap(ShotEdits.remove(sh, plan.grid, 6), "remove failed")
                sh = ShotEdits.setMoment(sh, 2, 99)
                plan = Plan(style: plan.style, shots: sh, grid: plan.grid)
            }
            let out = outDir.appendingPathComponent("reel_\(i + 1).mp4")
            let o = run.opts
            try await ReelEngine.render(s, plan, width: o.shape.pw, height: o.shape.ph, out: out, title: o.title) { _, _ in }
            let t2 = Date()

            let asset = AVURLAsset(url: out)
            let durMs = try await asset.load(.duration).seconds * 1000
            let vtracks = try await asset.loadTracks(withMediaType: .video)
            let vt = try XCTUnwrap(vtracks.first)
            let size = try await vt.load(.naturalSize)
            let tf = try await vt.load(.preferredTransform)
            let hasAudio = !(try await asset.loadTracks(withMediaType: .audio)).isEmpty
            let bytes = (try? FileManager.default.attributesOfItem(atPath: out.path)[.size] as? Int) ?? 0
            let a = s.analysis
            let lens = plan.shots.map { $0.t1 - $0.t0 }
            let line = "reel_\(i + 1): song=\(run.song) len=\(run.len) look=\(plan.style.look.name) pace=\(plan.style.pace.rawValue) " +
                "shape=\(o.shape.label) title=\(o.title.isEmpty ? "-" : o.title) " +
                "dissolves=\(plan.shots.filter { $0.trans == .fade || $0.trans == .zoom }.count) " +
                "glitches=\(plan.shots.filter { $0.trans == .glitch }.count) punch=\(String(format: "%.1f", a.punch)) " +
                "bpm=\(String(format: "%.1f", a.bpm)) brightness=\(String(format: "%.3f", a.brightness)) start=\(String(format: "%.2f", a.start)) " +
                "reelLen=\(String(format: "%.2f", a.len)) shots=\(plan.shots.count) shotMin=\(String(format: "%.2f", lens.min()!)) " +
                "shotMax=\(String(format: "%.2f", lens.max()!)) outDurMs=\(Int(durMs)) size=\(Int(size.width))x\(Int(size.height)) " +
                "upright=\(tf.isIdentity) audio=\(hasAudio) bytes=\(bytes) analyseMs=\(Int(t1.timeIntervalSince(t0) * 1000)) " +
                "renderMs=\(Int(t2.timeIntervalSince(t1) * 1000)) " +
                "plan=\(plan.shots.map { String(format: "%.2f-%.2f:%@:%@@%.2f", $0.t0, $0.t1, $0.trans.rawValue, $0.media?.url.lastPathComponent ?? "-", $0.src0) }.joined(separator: ";"))\n"
            report += line
            try? report.write(to: outDir.appendingPathComponent("report.txt"), atomically: true, encoding: .utf8)
            XCTAssert(bytes > 50_000, "reel \(i + 1) too small")
            XCTAssert(tf.isIdentity, "reel \(i + 1) saved with a rotate note")
            XCTAssertEqual("\(Int(size.width))x\(Int(size.height))", "\(o.shape.pw)x\(o.shape.ph)", "reel \(i + 1) size")
            XCTAssert(abs(durMs - a.len * 1000) < 700, "reel \(i + 1) wrong length \(durMs)")
            XCTAssert(hasAudio, "reel \(i + 1) has no sound")
        }
    }

    /// A timeline project from all test clips, edited, exported; kept as "test_timeline" for the editor screenshot.
    func testTimelineExport() async throws {
        let (media, _) = try inputs()
        // probe moves files into the media folder, so give it copies
        let copies = try media.map { u -> URL in
            let c = Paths.cache.appendingPathComponent("tl-" + u.lastPathComponent)
            try? FileManager.default.removeItem(at: c)
            try FileManager.default.copyItem(at: u, to: c)
            return c
        }
        let clips = await ProjectStore.probe(copies)
        XCTAssertEqual(clips.count, media.count, "every test file is read")
        var p = ProjectStore.newProject(clips)
        p.id = "test_timeline"; p.name = "Test timeline"
        let firstVideo = p.clips.first { !$0.isImage }!
        p = TimelineEdits.trimLeft(p, firstVideo.id, 0.8)!
        p = TimelineEdits.trimRight(p, firstVideo.id, -0.5)!
        let photo = p.clips.first { $0.isImage }!
        p = TimelineEdits.trimRight(p, photo.id, -1)!
        p = TimelineEdits.split(p, p.starts()[0] + 1)!.0
        p = TimelineEdits.move(p, p.clips.last!.id, 0)!
        p = TimelineEdits.duplicate(p, p.clips[1].id)!.0
        p = TimelineEdits.delete(p, p.clips[3].id)!
        ProjectStore.save(p)
        await ProjectStore.writeCover(p)
        XCTAssertEqual(ProjectStore.load("test_timeline"), p)

        let out = outDir.appendingPathComponent("timeline.mp4")
        let t0 = Date()
        try await TimelineComposer.export(p, to: out) { _ in }
        let asset = AVURLAsset(url: out)
        let durMs = try await asset.load(.duration).seconds * 1000
        let vtracks = try await asset.loadTracks(withMediaType: .video)
        let vt = try XCTUnwrap(vtracks.first)
        let size = try await vt.load(.naturalSize)
        let tf = try await vt.load(.preferredTransform)
        let hasAudio = !(try await asset.loadTracks(withMediaType: .audio)).isEmpty
        let line = "timeline: clips=\(p.clips.count) total=\(String(format: "%.2f", p.total)) outDurMs=\(Int(durMs)) audio=\(hasAudio) " +
            "size=\(Int(size.width))x\(Int(size.height)) upright=\(tf.isIdentity) renderMs=\(Int(Date().timeIntervalSince(t0) * 1000)) " +
            "clipLens=\(p.clips.map { String(format: "%.2f", $0.dur) + ($0.isImage ? "p" : "v") }.joined(separator: ","))\n"
        try line.write(to: outDir.appendingPathComponent("timeline.txt"), atomically: true, encoding: .utf8)
        XCTAssertEqual(durMs, p.total * 1000, accuracy: 150, "export length = timeline length")
        XCTAssert(hasAudio, "export keeps a sound track")
        XCTAssertEqual("\(Int(size.width))x\(Int(size.height))", "1080x1920")
    }

    /// Screenshots of Home, Setup and Fine-tune (the real SwiftUI screens, drawn in a window).
    @MainActor
    func testScreens() async throws {
        let (media, dir) = try inputs()
        let model = AppModel()
        let editor = EditorModel()
        let win = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        let host = UIHostingController(rootView: AnyView(RootView().environmentObject(model).environmentObject(editor)))
        win.rootViewController = host
        win.makeKeyAndVisible()

        func shot(_ name: String) async throws {
            try await Task.sleep(nanoseconds: 2_500_000_000)
            win.layoutIfNeeded()
            let img = UIGraphicsImageRenderer(bounds: win.bounds).image { _ in win.drawHierarchy(in: win.bounds, afterScreenUpdates: true) }
            try img.pngData()?.write(to: outDir.appendingPathComponent("\(name).png"))
        }

        model.screen = .home
        try await shot("home")

        model.setMedia(media)
        model.songPicked(dir.appendingPathComponent("song_fast.mp3"))
        model.setTitle("Wedding Day")
        try await shot("setup")

        let s = try await ReelEngine.prepare(media: media, song: dir.appendingPathComponent("song_fast.mp3"), reelLen: 15) { _, _ in }
        let plan = ReelEngine.plan(s, variation: 0, forcedLook: nil)
        let sel = plan.shots.firstIndex { !($0.media?.isImage ?? true) } ?? 0
        model.loadForTest(s, plan, sel: sel)
        try await shot("finetune")
        win.isHidden = true
    }
}
