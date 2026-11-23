import XCTest
@testable import ReelCut

/// The automatic shot-length rule, Fine-tune edits and timeline edits (same checks as the Android unit tests),
/// on made-up songs from 60 to 170 BPM and reels from 10 s to 2 minutes.
final class EngineTests: XCTestCase {

    private func song(_ bpm: Double, _ len: Double, _ strength: Double, _ seed: Int) -> SongAnalysis {
        let beat = 60.0 / bpm
        let n = Int(len / beat)
        let rel = (0...n).map { Double($0) * beat }
        let r = Rng(seed)
        let energy = rel.map { _ in r.nextDouble() }
        return SongAnalysis(start: 0, len: Double(n) * beat, rel: rel, energy: energy, hits: [], bpm: bpm, brightness: 0.1,
                            bass: 0.6, beatStrength: strength, seed: seed, songDur: 200)
    }

    private lazy var songs: [SongAnalysis] = {
        var out: [SongAnalysis] = []
        for bpm in [60.0, 72, 78, 90, 100, 123, 128, 140, 150, 170] {
            for len in [10.0, 15, 30, 60, 120] {
                for strength in [1.0, 11] { out.append(song(bpm, len, strength, Int(bpm * len) + Int(strength))) }
            }
        }
        return out
    }()

    private func plans() -> [(SongAnalysis, Style, [Shot])] {
        var out: [(SongAnalysis, Style, [Shot])] = []
        for a in songs {
            for pace in [Pace.auto, .fast, .normal, .smooth] {
                for v in 0..<3 {
                    let r = Rng(a.seed + v * 7919)
                    let st = Styles.choose(a, r, nil, pace, 6)
                    out.append((a, st, Styles.planShots(a, st, r)))
                }
            }
        }
        return out
    }

    func testShotsStayBetweenMinAndMax() {
        let frame = 1.0 / 30 + 1e-6
        for (a, st, shots) in plans() {
            for s in shots {
                let d = s.t1 - s.t0
                XCTAssert(d >= st.minShot - frame, "bpm \(a.bpm) \(st.pace): shot \(d) shorter than \(st.minShot)")
                XCTAssert(d <= st.maxShot + frame, "bpm \(a.bpm) \(st.pace): shot \(d) longer than \(st.maxShot)")
            }
            if st.pace == .fast { XCTAssert(st.minShot == Styles.minShot && st.maxShot == Styles.maxShot) }
        }
    }

    func testSmoothIsSlowAndSoft() {
        for (a, st, shots) in plans() where st.pace == .smooth {
            let avg = shots.reduce(0) { $0 + $1.t1 - $1.t0 } / Double(shots.count)
            XCTAssert(avg >= 2.0, "bpm \(a.bpm): smooth shots average \(avg)")
            XCTAssert(shots.dropFirst().allSatisfy { $0.trans == .fade || $0.trans == .zoom }, "smooth has only dissolves / zooms")
            XCTAssert(!shots.contains { $0.punch }, "no punch-ins at smooth speed")
        }
    }

    func testAutoPaceFollowsTheSong() {
        let soft = song(70, 15, 11, 1)
        XCTAssertEqual(Styles.songPace(soft), .smooth)
        func with(_ bpm: Double, _ br: Double, _ punch: Double) -> SongAnalysis {
            SongAnalysis(start: 0, len: 15, rel: soft.rel, energy: soft.energy, hits: [], bpm: bpm, brightness: br, bass: 0.6,
                         beatStrength: 11, seed: 1, songDur: 200, punch: punch)
        }
        XCTAssertEqual(Styles.songPace(with(128, 0.2, 18)), .fast)
        XCTAssertEqual(Styles.songPace(with(105, 0.13, 15)), .normal)
        XCTAssertEqual(Styles.songPace(with(172, 0.10, 15)), .smooth)
        XCTAssertEqual(Looks.all.count, 13)
    }

    func testCutsAreOnTheBeatAndLengthIsKept() {
        for (a, _, shots) in plans() {
            let g = Styles.grid(a)
            XCTAssertEqual(shots.first!.t0, 0, accuracy: 1e-9)
            XCTAssertEqual(shots.last!.t1, Styles.frameT(a.len), accuracy: 1e-9)
            for i in shots.indices {
                XCTAssertEqual(Styles.frameT(g[shots[i].b0]), shots[i].t0, accuracy: 1e-9)
                XCTAssertEqual(Styles.frameT(g[shots[i].b1]), shots[i].t1, accuracy: 1e-9)
                if i > 0 { XCTAssertEqual(shots[i - 1].b1, shots[i].b0) }
            }
        }
    }

    func testTwoMinuteReelsHaveEnoughShots() {
        // a 2-minute reel at smooth speed still changes shot at least every ~4.6 s
        for (a, st, shots) in plans() where a.len > 100 {
            XCTAssert(Double(shots.count) >= a.len / st.maxShot - 1, "\(a.len)s at \(st.pace): only \(shots.count) shots")
        }
    }

    func testEditsKeepBeatsAndLength() {
        for (a, _, start) in plans() {
            let g = Styles.grid(a)
            let end = start.last!.t1
            var shots = start
            let r = Rng(a.seed)
            for _ in 0..<30 {
                let k = Int(r.nextDouble() * Double(shots.count))
                let next: [Shot]?
                switch Int(r.nextDouble() * 3) {
                case 0: next = ShotEdits.changeLength(shots, g, k, 1)
                case 1: next = ShotEdits.changeLength(shots, g, k, -1)
                default: next = shots.count > 3 ? ShotEdits.remove(shots, g, k) : nil
                }
                if let n = next { shots = n }
                XCTAssertFalse(shots.isEmpty)
                XCTAssertEqual(shots.last!.t1, end, accuracy: 1e-9, "reel length kept")
                XCTAssertEqual(shots.first!.t0, 0, accuracy: 1e-9)
                for i in shots.indices {
                    XCTAssert(shots[i].b1 > shots[i].b0, "shot at least 1 beat")
                    XCTAssertEqual(Styles.frameT(g[shots[i].b0]), shots[i].t0, accuracy: 1e-9)
                    if i > 0 { XCTAssertEqual(shots[i - 1].b1, shots[i].b0, "no gaps") }
                }
            }
        }
    }

    func testLengthChangeMovesLaterShots() {
        let a = song(120, 15, 11, 1)
        let r = Rng(3)
        let st = Styles.choose(a, r, nil)
        let shots = Styles.planShots(a, st, r)
        let g = Styles.grid(a)
        let longer = ShotEdits.changeLength(shots, g, 1, 1)!
        XCTAssertEqual(longer[1].b1, shots[1].b1 + 1)
        XCTAssertEqual(longer[2].b1, shots[2].b1 + 1)
        XCTAssertEqual(longer[2].b1 - longer[2].b0, shots[2].b1 - shots[2].b0)
        if let one = shots.firstIndex(where: { $0.b1 - $0.b0 == 1 }), one < shots.count - 1 {
            XCTAssertNil(ShotEdits.changeLength(shots, g, one, -1))
        }
    }

    // ------------------------------------------------------------ timeline ---

    private func video(_ id: Int64, _ src: Double, _ inS: Double = 0, _ outS: Double? = nil) -> TClip {
        TClip(id: id, uri: "v\(id)", isImage: false, srcDur: src, inS: inS, outS: outS ?? src)
    }
    private func photo(_ id: Int64, _ d: Double = 3) -> TClip { TClip(id: id, uri: "p\(id)", isImage: true, srcDur: 0, inS: 0, outS: d) }
    private var p: Project { Project(id: "x", name: "Test", clips: [video(1, 10, 2, 6), photo(2), video(3, 5)], created: 0, updated: 0) }

    func testStartsAndClipAt() {
        XCTAssertEqual(p.total, 12, accuracy: 1e-9)
        XCTAssertEqual(p.starts(), [0, 4, 7])
        XCTAssertEqual(p.clipAt(5)!.0, 1); XCTAssertEqual(p.clipAt(5)!.1, 1, accuracy: 1e-9)
        XCTAssertEqual(p.clipAt(99)!.0, 2); XCTAssertEqual(p.clipAt(99)!.1, 5, accuracy: 1e-9)
    }

    func testTrim() {
        XCTAssertEqual(TimelineEdits.trimLeft(p, 1, -5)!.clips[0].inS, 0, accuracy: 1e-9)
        let b = TimelineEdits.trimRight(p, 1, 100)!
        XCTAssertEqual(b.clips[0].outS, 10, accuracy: 1e-9)
        XCTAssertEqual(TimelineEdits.trimLeft(p, 1, 100)!.clips[0].dur, TimelineEdits.minClip, accuracy: 1e-9)
        XCTAssertNil(TimelineEdits.trimRight(b, 1, 1))
        XCTAssertEqual(TimelineEdits.trimRight(p, 2, 2)!.clips[1].dur, 5, accuracy: 1e-9)
        let c = TimelineEdits.trimLeft(p, 2, 1)!
        XCTAssertEqual(c.clips[1].dur, 2, accuracy: 1e-9)
        XCTAssertEqual(c.clips[1].inS, 0, accuracy: 1e-9)
    }

    func testSplit() {
        let (q, newId) = TimelineEdits.split(p, 3)!
        XCTAssertEqual(q.clips.count, 4)
        XCTAssertEqual(q.total, p.total, accuracy: 1e-9)
        XCTAssertEqual(q.clips[0].outS, 5, accuracy: 1e-9)
        XCTAssertEqual(q.clips[1].inS, 5, accuracy: 1e-9)
        XCTAssertEqual(q.clips[1].id, newId)
        XCTAssertNil(TimelineEdits.split(p, 4.05))
        let (r, _) = TimelineEdits.split(p, 5)!
        XCTAssertEqual(r.total, p.total, accuracy: 1e-9)
        XCTAssert(r.clips[1].isImage && r.clips[2].isImage)
    }

    func testMoveDuplicateDeleteAddDrop() {
        XCTAssertEqual(TimelineEdits.move(p, 3, 0)!.clips.map { $0.id }, [3, 1, 2])
        XCTAssertNil(TimelineEdits.move(p, 3, 2))
        let (d, id) = TimelineEdits.duplicate(p, 2)!
        XCTAssertEqual(d.clips.map { $0.id }, [1, 2, id, 3])
        XCTAssertEqual(id, 4)
        XCTAssertEqual(TimelineEdits.delete(p, 1)!.clips.map { $0.id }, [2, 3])
        let (a, first) = TimelineEdits.add(p, 1, [photo(1), photo(1)])!
        XCTAssertEqual(a.clips.map { $0.id }, [1, 4, 5, 2, 3])
        XCTAssertEqual(first, 4)
        XCTAssertEqual(TimelineEdits.dropIndex(p, 3, 1), 0)
        XCTAssertEqual(TimelineEdits.dropIndex(p, 3, 4.5), 1)
        XCTAssertEqual(TimelineEdits.dropIndex(p, 3, 6), 2)
    }

    func testProjectJsonIsTheAndroidFormat() throws {
        let d = try JSONEncoder().encode(p)
        let o = try JSONSerialization.jsonObject(with: d) as! [String: Any]
        let c = (o["clips"] as! [[String: Any]])[0]
        XCTAssertEqual(Set(c.keys), ["id", "uri", "img", "src", "in", "out"])
        XCTAssertEqual(try JSONDecoder().decode(Project.self, from: d), p)
    }
}
