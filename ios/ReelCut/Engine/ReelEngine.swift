import Foundation

/// The song and clips, analysed once; every edit (style, variation, export) reuses this.
final class ReelSession {
    let song: URL
    let analysis: SongAnalysis
    let media: [MediaInfo]
    init(song: URL, analysis: SongAnalysis, media: [MediaInfo]) { self.song = song; self.analysis = analysis; self.media = media }
}

/// Reel shape: export size and the lighter preview size.
enum Shape: String, CaseIterable, Codable {
    case portrait = "PORTRAIT", fourFive = "FOUR_FIVE", square = "SQUARE", wide = "WIDE"
    var label: String { switch self { case .portrait: return "9:16"; case .fourFive: return "4:5"; case .square: return "1:1"; case .wide: return "16:9" } }
    var w: Int { switch self { case .portrait: return 1080; case .fourFive: return 1088; case .square: return 1088; case .wide: return 1920 } }
    var h: Int { switch self { case .portrait: return 1920; case .fourFive: return 1360; case .square: return 1088; case .wide: return 1080 } }
    var pw: Int { switch self { case .portrait: return 720; case .fourFive: return 832; case .square: return 960; case .wide: return 1280 } }
    var ph: Int { switch self { case .portrait: return 1280; case .fourFive: return 1040; case .square: return 960; case .wide: return 720 } }
}

/// Choices from the setup screen.
struct ReelOptions {
    var pace: Pace = .auto
    var shape: Shape = .portrait
    var title: String = ""
}

/// Reel lengths on the home screen (seconds). 1 and 2 minutes are iPhone-only for now.
let reelLengths = [10, 15, 30, 60, 120]

func lengthLabel(_ s: Int) -> String { s >= 60 ? "\(s / 60) min" : "\(s)s" }

enum ReelEngine {

    static func prepare(media: [URL], song: URL, reelLen: Double, progress: @escaping (String, Double) -> Void) async throws -> ReelSession {
        progress("Listening to the song", 0.03)
        let pcm = try await AudioDecoder.decodeMono(song, rate: SongAnalyzer.sr, maxSeconds: 480)
        progress("Finding the beats", 0.10)
        let analysis = try await Task.detached(priority: .userInitiated) { try SongAnalyzer.analyze(pcm, reelLen: reelLen) }.value
        progress("Finding the best moments", 0.15)
        let scanned = try await ClipScanner.scan(media) { f in progress("Finding the best moments", 0.15 + 0.25 * f) }
        return ReelSession(song: song, analysis: analysis, media: scanned)
    }

    /// Same session + variation + look always gives the same plan, so the export matches the preview.
    static func plan(_ s: ReelSession, variation: Int, forcedLook: String?, opts: ReelOptions = ReelOptions()) -> Plan {
        let r = Rng(s.analysis.seed &+ variation &* 7919)
        let style = Styles.choose(s.analysis, r, forcedLook, opts.pace, s.media.filter { $0.ok }.count)
        var shots = Styles.planShots(s.analysis, style, r)
        ClipScanner.assign(&shots, s.media, r)
        return Plan(style: style, shots: shots, grid: Styles.grid(s.analysis))
    }

    /// Renders a plan (automatic or fine-tuned by hand).
    static func render(_ s: ReelSession, _ plan: Plan, width: Int, height: Int, out: URL, title: String = "",
                       progress: @escaping (String, Double) -> Void) async throws {
        progress("Making your reel", 0.4)
        try await Renderer.render(a: s.analysis, style: plan.style, shots: plan.shots, song: s.song, out: out,
                                  width: width, height: height, title: title) { f in progress("Making your reel", 0.4 + 0.6 * f) }
    }
}
