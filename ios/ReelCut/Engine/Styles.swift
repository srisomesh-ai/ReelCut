import Foundation

/// Seeded random numbers (SplitMix64): the same seed always gives the same edit, so the export matches the preview.
final class Rng {
    private var s: UInt64
    init(_ seed: Int) { s = UInt64(bitPattern: Int64(seed)) &+ 0x9E37_79B9_7F4A_7C15 }
    func next() -> UInt64 {
        s &+= 0x9E37_79B9_7F4A_7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    /// 0 ..< 1
    func nextDouble() -> Double { Double(next() >> 11) / Double(1 << 53) }
    func nextFloat() -> Float { Float(nextDouble()) }
}

/// A colour look, the same 13 as the Android app and the ReelCut web page.
struct Look: Equatable {
    static let softLight = 0, screen = 1, overlay = 2

    let key: String
    let name: String
    let contrast: Float
    let saturation: Float
    let brightness: Float
    let tint: UInt32        // 0xRRGGBB
    let tintAmount: Float
    let mono: Bool
    let vignette: Float
    let flash: Bool
    let shake: Bool
    let bars: Bool
    var sepia: Float = 0
    var tintMode: Int = Look.softLight
    var gradTop: UInt32 = 0
    var gradBottom: UInt32 = 0
    var gradAmount: Float = 0
    var lift: Float = 0
    var leak = false
    var grain: Float = 0
    var scan: Float = 0
    var stamp = false
    var glitch = false
    var glow: UInt32 = 0
}

enum Looks {
    static let all: [Look] = [
        Look(key: "neon", name: "Neon Night", contrast: 1.15, saturation: 1.55, brightness: 1.03, tint: 0xFF2BD6, tintAmount: 0.10, mono: false, vignette: 0.45, flash: true, shake: false, bars: false, glow: 0xFF2BD6),
        Look(key: "street", name: "Street Punch", contrast: 1.30, saturation: 1.20, brightness: 1.00, tint: 0xFFB000, tintAmount: 0.08, mono: false, vignette: 0.55, flash: true, shake: true, bars: false, tintMode: Look.overlay),
        Look(key: "teal", name: "Teal Cinema", contrast: 1.20, saturation: 0.88, brightness: 1.00, tint: 0x009AA6, tintAmount: 0.16, mono: false, vignette: 0.50, flash: false, shake: false, bars: true),
        Look(key: "mono", name: "Mono Drama", contrast: 1.35, saturation: 0.00, brightness: 1.00, tint: 0x808080, tintAmount: 0.00, mono: true, vignette: 0.60, flash: true, shake: false, bars: false),
        Look(key: "golden", name: "Golden Warm", contrast: 1.06, saturation: 1.25, brightness: 1.04, tint: 0xFF9A3C, tintAmount: 0.12, mono: false, vignette: 0.30, flash: false, shake: false, bars: false, sepia: 0.18),
        Look(key: "dreamy", name: "Dreamy Soft", contrast: 0.94, saturation: 1.12, brightness: 1.07, tint: 0xFFC0D9, tintAmount: 0.10, mono: false, vignette: 0.25, flash: false, shake: false, bars: false, tintMode: Look.screen),
        Look(key: "vintage", name: "Vintage Film", contrast: 1.05, saturation: 0.90, brightness: 1.02, tint: 0xFFCF8A, tintAmount: 0.10, mono: false, vignette: 0.55, flash: false, shake: false, bars: false, sepia: 0.35, leak: true, grain: 0.22),
        Look(key: "vhs", name: "Retro VHS", contrast: 1.12, saturation: 1.35, brightness: 1.00, tint: 0x7B2CFF, tintAmount: 0.07, mono: false, vignette: 0.40, flash: false, shake: false, bars: false, tintMode: Look.screen, grain: 0.12, scan: 0.16, stamp: true, glitch: true),
        Look(key: "pop", name: "Pop Colour", contrast: 1.12, saturation: 1.80, brightness: 1.04, tint: 0, tintAmount: 0, mono: false, vignette: 0.20, flash: true, shake: false, bars: false),
        Look(key: "blue", name: "Cool Blue", contrast: 1.12, saturation: 0.95, brightness: 1.02, tint: 0x2F6BFF, tintAmount: 0.16, mono: false, vignette: 0.45, flash: false, shake: false, bars: false),
        Look(key: "sunset", name: "Sunset Glow", contrast: 1.05, saturation: 1.25, brightness: 1.04, tint: 0, tintAmount: 0, mono: false, vignette: 0.35, flash: false, shake: false, bars: false, gradTop: 0xFF7A45, gradBottom: 0xFF2B8A, gradAmount: 0.2, leak: true),
        Look(key: "matte", name: "Matte Film", contrast: 0.90, saturation: 0.80, brightness: 1.03, tint: 0, tintAmount: 0, mono: false, vignette: 0.30, flash: false, shake: false, bars: false, lift: 34.0 / 255.0, grain: 0.10),
        Look(key: "bolly", name: "Bollywood Gold", contrast: 1.10, saturation: 1.35, brightness: 1.05, tint: 0xFFB23C, tintAmount: 0.12, mono: false, vignette: 0.35, flash: true, shake: false, bars: false, leak: true, glow: 0xFFB23C),
    ]
    static func byKey(_ k: String) -> Look { all.first { $0.key == k } ?? all[0] }
}

/// Cut speed: smooth = ~3-4 s shots with soft dissolves; normal ~1.4-2.6 s; fast = on the beat.
enum Pace: String, CaseIterable, Codable {
    case auto = "AUTO", smooth = "SMOOTH", normal = "NORMAL", fast = "FAST"
    var label: String {
        switch self { case .auto: return "Auto"; case .smooth: return "Smooth"; case .normal: return "Normal"; case .fast: return "Fast" }
    }
}

struct Style {
    let look: Look
    let energetic: Bool
    let bright: Bool
    let heavy: Bool
    let calm: Int   // beats per cut in calm parts
    let hot: Int    // beats per cut in loud parts
    let pace: Pace
    let beat: Double

    var smooth: Bool { pace == .smooth }
    /// Dissolve length (smooth: about a third of the shot), zoom-through length and amount.
    var fadeLen: Double { smooth ? (0.35 * Double(calm) * beat).clamped(0.6, 1.1) : 0.35 }
    var zoomLen: Double { smooth ? 0.8 : 0.3 }
    var zoomAmt: Double { smooth ? 0.25 : 0.6 }
    var minShot: Double { pace == .smooth ? 1.2 : pace == .normal ? 0.9 : Styles.minShot }
    var maxShot: Double { pace == .smooth ? 4.6 : pace == .normal ? 2.7 : Styles.maxShot }
}

enum Trans: String { case cut, flash, fade, zoom, whip, glitch }

/// One cut of the reel. b0/b1 are positions on the beat grid, so edits stay on the beat.
struct Shot: Equatable {
    var t0: Double
    var t1: Double
    var hot: Bool
    var trans: Trans
    var punch: Bool
    var kb: Int
    var panX: Double
    var panY: Double
    var b0: Int = 0
    var b1: Int = 0
    var media: MediaInfo? = nil
    var src0: Double = 0

    static func == (a: Shot, b: Shot) -> Bool {
        a.t0 == b.t0 && a.t1 == b.t1 && a.trans == b.trans && a.b0 == b.b0 && a.b1 == b.b1 && a.media === b.media && a.src0 == b.src0
    }
}

extension Comparable {
    func clamped(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) }
}

enum Styles {
    static let minShot = 0.45
    static let maxShot = 2.5

    /// Beat times of the reel, plus the reel end if it is not on a beat. Cuts are always on this grid.
    static func grid(_ a: SongAnalysis) -> [Double] {
        if let last = a.rel.last, last >= a.len - 0.02 { return a.rel }
        return a.rel + [a.len]
    }

    /// A grid time rounded to a whole video frame (30 fps).
    static func frameT(_ t: Double) -> Double { (t * 30).rounded() / 30 }

    static func withLook(_ s: Style, _ key: String) -> Style {
        Style(look: Looks.byKey(key), energetic: s.energetic, bright: s.bright, heavy: s.heavy, calm: s.calm, hot: s.hot, pace: s.pace, beat: s.beat)
    }

    /// The tempo a listener feels: soft picked notes heard as a fast beat (e.g. 172) count as half.
    static func feelBpm(_ a: SongAnalysis) -> Double { a.bpm > 140 && a.brightness < 0.12 ? a.bpm / 2 : a.bpm }

    /// Auto cut speed from the song.
    static func songPace(_ a: SongAnalysis) -> Pace {
        let bpm = feelBpm(a)
        if (a.brightness < 0.09 && a.punch < 14) || (bpm < 90 && a.brightness < 0.12) { return .smooth }
        if bpm >= 118 && (a.brightness >= 0.15 || a.punch >= 16) { return .fast }
        return .normal
    }

    /// Look + cut rhythm for a song. pacePick = the Cut speed button (auto = from the song).
    static func choose(_ a: SongAnalysis, _ r: Rng, _ forcedLook: String?, _ pacePick: Pace = .auto, _ mediaCount: Int = 6) -> Style {
        let pace = pacePick == .auto ? songPace(a) : pacePick
        let energetic = pace == .fast
        let smooth = pace == .smooth
        let bright = a.brightness >= 0.14
        let heavy = a.bass >= 0.5
        let pool: [String]
        if energetic && bright { pool = heavy ? ["street", "neon", "pop", "bolly"] : ["neon", "golden", "pop", "sunset"] }
        else if energetic { pool = heavy ? ["street", "mono", "vhs"] : ["teal", "mono", "blue", "vhs"] }
        else if bright { pool = ["golden", "dreamy", "sunset", "vintage"] }
        else { pool = ["teal", "mono", "matte", "blue"] }
        let roll = r.nextDouble()
        let pick = roll < 0.5 ? pool[0] : pool[1 + min(Int(r.nextDouble() * Double(pool.count - 1)), pool.count - 2)]
        let key = forcedLook ?? pick
        let beat = 60.0 / a.bpm
        func fit(_ maxSec: Double) -> Int {
            [4, 2, 1].first { Double($0) * beat <= maxSec && Double($0) * beat >= minShot }
                ?? [1, 2, 4].first { Double($0) * beat >= minShot } ?? 4
        }
        func near(_ t: Double, _ opts: [Int]) -> Int { opts.min { abs(Double($0) * beat - t) < abs(Double($1) * beat - t) } ?? opts[0] }
        let share = a.len / Double(max(mediaCount, 1))
        let calm: Int, hot: Int
        if energetic { calm = fit(1.0); hot = fit(0.5) }
        else if smooth {
            var opts = [4, 8, 12, 16].filter { Double($0) * beat >= 2.4 }
            if opts.isEmpty { opts = [16] }
            calm = near(share.clamped(2.6, 4.5), opts); hot = calm
        } else {
            calm = near(share.clamped(1.4, 2.6), [2, 4, 8])
            hot = calm > 1 && Double(calm / 2) * beat >= 0.9 ? calm / 2 : calm
        }
        return Style(look: Looks.byKey(key), energetic: energetic, bright: bright, heavy: heavy, calm: calm, hot: hot, pace: pace, beat: beat)
    }

    static func planShots(_ a: SongAnalysis, _ s: Style, _ r: Rng) -> [Shot] {
        let hotTh = SongAnalyzer.pct(a.energy, 55)
        let g = grid(a)
        let end = g.count - 1
        var cuts = [0]
        var hots: [Bool] = []
        func addSplit(_ to: Int, _ hot: Bool) {
            while g[to] - g[cuts.last!] > s.maxShot {
                let from = cuts.last!
                var pick = -1
                var j = from + 1
                while j < to {
                    let d = g[j] - g[from]
                    if d >= s.minShot && d <= s.maxShot && g[to] - g[j] >= s.minShot { pick = j }
                    j += 1
                }
                if pick < 0 { break }
                cuts.append(pick); hots.append(hot)
            }
            cuts.append(to); hots.append(hot)
        }
        var i = 0
        while i < a.rel.count {
            let hot = a.energy[i] >= hotTh
            i += hot ? s.hot : s.calm
            let nx = i < a.rel.count ? i : end
            if g[nx] - g[cuts.last!] < s.minShot { continue }
            addSplit(nx, hot)
            if nx == end { break }
        }
        if cuts.last! < end {
            if g[end] - g[cuts.last!] < s.minShot && cuts.count > 1 {
                cuts.removeLast()
                addSplit(end, hots.removeLast())
            } else { addSplit(end, false) }
        }
        let frames = cuts.map { Int((g[$0] * 30).rounded()) }
        var shots: [Shot] = []
        for k in 0..<(frames.count - 1) {
            if frames[k + 1] <= frames[k] { continue }
            let t0 = Double(frames[k]) / 30, t1 = Double(frames[k + 1]) / 30
            let hot = k < hots.count ? hots[k] : false
            var trans = Trans.cut
            if k > 0 {
                if s.energetic {
                    if hot {
                        if s.look.flash && r.nextDouble() < 0.5 { trans = .flash }
                        else if s.look.glitch && r.nextDouble() < 0.6 { trans = .glitch } else { trans = .cut }
                    } else {
                        if r.nextDouble() < 0.3 { trans = .whip }
                        else if s.look.glitch && r.nextDouble() < 0.3 { trans = .glitch } else { trans = .cut }
                    }
                } else if s.smooth {
                    trans = r.nextDouble() < 0.8 ? .fade : .zoom
                } else {
                    if s.look.glitch && r.nextDouble() < 0.3 { trans = .glitch }
                    else { trans = r.nextDouble() < 0.7 ? .fade : .zoom }
                }
            }
            let punch = s.energetic && (hot || k % 2 == 0)
            let kb = r.nextDouble() < 0.5 ? 1 : -1
            let px = r.nextDouble() * 2 - 1
            let py = r.nextDouble() * 2 - 1
            shots.append(Shot(t0: t0, t1: t1, hot: hot, trans: trans, punch: punch, kb: kb, panX: px, panY: py, b0: cuts[k], b1: cuts[k + 1]))
        }
        return shots
    }
}
