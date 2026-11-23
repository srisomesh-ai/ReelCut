import AVFoundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

struct Sample { let t: Double; let sharpN: Double; let motN: Double; let light: Double }

/// One picked clip or photo, with the scores of its moments.
final class MediaInfo {
    let url: URL
    let isImage: Bool
    var dur: Double = .infinity
    var samples: [Sample] = []
    var ok = false
    var uses = 0
    var used: [(Double, Double)] = []
    init(url: URL, isImage: Bool) { self.url = url; self.isImage = isImage }
    func reset() { uses = 0; used.removeAll() }
}

enum ClipScanner {

    static func isImage(_ url: URL) -> Bool {
        if let t = UTType(filenameExtension: url.pathExtension.lowercased()) { return t.conforms(to: .image) }
        return ["jpg", "jpeg", "png", "webp", "heic", "heif"].contains(url.pathExtension.lowercased())
    }

    static func scan(_ urls: [URL], onProgress: @escaping (Double) -> Void) async throws -> [MediaInfo] {
        let list = urls.map { MediaInfo(url: $0, isImage: isImage($0)) }
        var done = 0
        // three at a time
        try await withThrowingTaskGroup(of: Void.self) { g in
            var next = 0
            func add() {
                guard next < list.count else { return }
                let m = list[next]; next += 1
                g.addTask { await scanOne(m) }
            }
            for _ in 0..<3 { add() }
            while try await g.next() != nil {
                done += 1
                onProgress(Double(done) / Double(list.count))
                add()
            }
        }
        if !list.contains(where: { $0.ok }) { throw ReelError("None of these files could be read. Try other videos or photos.") }
        return list
    }

    private static func scanOne(_ m: MediaInfo) async {
        if m.isImage {
            guard let src = CGImageSourceCreateWithURL(m.url as CFURL, nil), CGImageSourceGetCount(src) > 0 else { return }
            m.samples = [Sample(t: 0, sharpN: 0.5, motN: 0.5, light: 0.8)]
            m.ok = true
            return
        }
        let asset = AVURLAsset(url: m.url)
        guard let dur = try? await asset.load(.duration), let tracks = try? await asset.loadTracks(withMediaType: .video), !tracks.isEmpty else { return }
        m.dur = dur.seconds
        if !(m.dur >= 0.4) { return }
        let k = Int((m.dur / 0.8).rounded()).clamped(4, 14)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 96, height: 96)
        gen.requestedTimeToleranceBefore = CMTime(seconds: 0.5, preferredTimescale: 600)
        gen.requestedTimeToleranceAfter = CMTime(seconds: 0.5, preferredTimescale: 600)
        var sharp = [Double](repeating: 0, count: k), motion = sharp, bright = sharp, ts = sharp
        var prev: [Int]? = nil
        var got = 0
        for i in 0..<k {
            let t = (Double(i) + 0.5) * m.dur / Double(k)
            ts[i] = t
            guard let img = try? await gen.image(at: CMTime(seconds: t, preferredTimescale: 600)).image,
                  let y = luma48(img) else { continue }
            var s = 0.0, s2 = 0.0, b = 0.0
            for row in 1..<47 { for col in 1..<47 {
                let j = row * 48 + col
                let l = Double(y[j - 1] + y[j + 1] + y[j - 48] + y[j + 48] - 4 * y[j])
                s += l; s2 += l * l
            } }
            for v in y { b += Double(v) }
            let nn = 46.0 * 46.0
            sharp[i] = s2 / nn - (s / nn) * (s / nn)
            bright[i] = b / Double(y.count)
            if let p = prev { var d = 0.0; for j in y.indices { d += Double(abs(y[j] - p[j])) }; motion[i] = d / Double(y.count) }
            prev = y
            got += 1
        }
        if got == 0 { return }
        let shN = norm(sharp), moN = norm(motion)
        m.samples = (0..<k).map { i in
            Sample(t: ts[i], sharpN: shN[i], motN: moN[i] > 0.92 ? 0.7 : moN[i], light: 1 - (abs(bright[i] - 125) / 125).clamped(0, 1))
        }
        m.ok = true
    }

    /// 48 x 48 brightness values of a picture.
    static func luma48(_ img: CGImage) -> [Int]? {
        var px = [UInt8](repeating: 0, count: 48 * 48)
        let ok: Bool = px.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: 48, height: 48, bitsPerComponent: 8, bytesPerRow: 48,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: 48, height: 48))
            return true
        }
        return ok ? px.map { Int($0) } : nil
    }

    private static func norm(_ a: [Double]) -> [Double] {
        let lo = a.min() ?? 0, hi = a.max() ?? 0
        return a.map { hi - lo < 1e-6 ? 0.5 : ($0 - lo) / (hi - lo) }
    }

    /// The best-looking moment of need seconds in a clip (0 for photos); used when a shot's clip is changed by hand.
    static func bestMoment(_ m: MediaInfo, _ need: Double) -> Double {
        if m.isImage || m.samples.isEmpty { return 0 }
        guard let s = m.samples.max(by: { 0.4 * $0.motN + 0.45 * $0.sharpN + 0.15 * $0.light < 0.4 * $1.motN + 0.45 * $1.sharpN + 0.15 * $1.light }) else { return 0 }
        return (s.t - need / 2).clamped(0, max(0, m.dur - need - 0.05))
    }

    /// Gives every shot its clip and the best unused moment in it.
    static func assign(_ shots: inout [Shot], _ media: [MediaInfo], _ r: Rng) {
        let ok = media.filter { $0.ok }
        ok.forEach { $0.reset() }
        var prev: MediaInfo? = nil
        var prev2: MediaInfo? = nil
        for idx in shots.indices {
            let sh = shots[idx]
            let need = (sh.t1 - sh.t0) + 0.1
            var bestScore = -Double.infinity
            var bestM: MediaInfo? = nil
            var bestT = 0.0
            for m in ok {
                if ok.count > 2 && (m === prev || m === prev2) { continue }
                if ok.count == 2 && m === prev { continue }
                if m.isImage {
                    let sc = 0.8 + (sh.hot ? -0.05 : 0.1) - 0.3 * Double(m.uses) + r.nextDouble() * 0.05
                    if sc > bestScore { bestScore = sc; bestM = m; bestT = 0 }
                    continue
                }
                for s in m.samples {
                    let t = (s.t - need / 2).clamped(0, max(0, m.dur - need - 0.05))
                    if m.used.contains(where: { t < $0.1 && t + need > $0.0 }) { continue }
                    let sc = (sh.hot ? 0.55 * s.motN + 0.3 * s.sharpN : 0.3 * s.motN + 0.55 * s.sharpN) +
                        0.15 * s.light - 0.2 * Double(m.uses) + r.nextDouble() * 0.05 - (m.dur < need ? 0.5 : 0)
                    if sc > bestScore { bestScore = sc; bestM = m; bestT = t }
                }
            }
            if bestM == nil {
                ok.forEach { $0.used.removeAll() }
                bestM = ok.first { $0 !== prev } ?? ok[0]
                bestT = 0
            }
            let bm = bestM!
            shots[idx].media = bm
            shots[idx].src0 = bestT
            bm.uses += 1
            bm.used.append((bestT, bestT + need))
            prev2 = prev; prev = bm
        }
    }
}
