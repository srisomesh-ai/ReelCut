import Foundation

struct Hit { let t: Double; let s: Double }

/// Everything the edit needs to know about the song. Times are seconds.
struct SongAnalysis {
    let start: Double          // where the reel starts inside the song
    let len: Double            // reel length, ends on a beat
    let rel: [Double]          // beat times relative to start
    let energy: [Double]       // energy (0..1) at each of those beats
    let hits: [Hit]            // bass hits relative to start
    let bpm: Double
    let brightness: Double
    let bass: Double
    let beatStrength: Double
    let seed: Int
    let songDur: Double
    var punch: Double = 15
}

struct ReelError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}

/// Beat tracking (Ellis 2007 dynamic programming) and song character - same as the Android app.
enum SongAnalyzer {
    static let sr = 22050
    static let hop = 512
    static let fr = Double(sr) / Double(hop)

    static func analyze(_ x: [Float], reelLen: Double) throws -> SongAnalysis {
        let songDur = Double(x.count) / Double(sr)
        if songDur < 6 { throw ReelError("This song is too short. Pick a song longer than 6 seconds.") }

        let low = biquad(x, lowpass: true, freq: 150)
        let high = biquad(x, lowpass: false, freq: 3000)
        let rF = frameRms(x), rL = frameRms(low), rH = frameRms(high)
        let fF = flux(rF), fL = flux(rL), fH = flux(rH)
        let n = rF.count

        var raw = [Double](repeating: 0, count: n)
        for i in 0..<n { raw[i] = 0.5 * fL[i] + 0.3 * fF[i] + 0.2 * fH[i] }
        var o = [Double](repeating: 0, count: n)
        var acc = 0.0
        let k = 16
        for i in 0..<n {
            acc += raw[i]
            if i >= k { acc -= raw[i - k] }
            o[i] = max(0, raw[i] - acc / Double(min(i + 1, k)))
        }
        var sd = (o.reduce(0) { $0 + $1 * $1 } / Double(max(1, n))).squareRoot()
        if sd == 0 { sd = 1 }
        for i in 0..<n { o[i] /= sd }

        let bf = trackBeats(o)
        if bf.count < 8 { throw ReelError("Could not find a clear beat in this song. Try another song.") }
        let beats = bf.map { Double($0) * Double(hop) / Double(sr) }
        let ivals = (1..<beats.count).map { beats[$0] - beats[$0 - 1] }.sorted()
        let bpm = 60.0 / ivals[ivals.count / 2]

        var eRaw = [Double](repeating: 0, count: bf.count)
        for i in 0..<bf.count {
            let f = bf[i]
            let g = i + 1 < bf.count ? bf[i + 1] : min(n, f + 20)
            var s = 0.0
            if g > f { for j in f..<g { s += log(1 + 1000 * rF[j]) } }
            eRaw[i] = s / Double(max(1, g - f))
        }
        let e5 = pct(eRaw, 5), e95 = pct(eRaw, 95)
        let energy = eRaw.map { (($0 - e5) / (e95 - e5 + 1e-9)).clamped(0, 1) }

        let mF = avg(rF) + 1e-9
        let brightness = avg(rH) / mF
        let bass = avg(rL) / mF
        let beatStrength = avg(bf.map { o[$0] }) / (avg(o) + 1e-9)
        var riseL = 0.0
        if rL.count > 2 { for i in 2..<rL.count { riseL += max(0, rL[i] - rL[i - 2]) } }
        let punch = riseL / (avg(rL) + 1e-9) / Double(max(1, rL.count)) * 100

        // most exciting window of the reel length, starting on a beat
        var bestI = 0
        var bestS = -1.0
        for i in 0..<beats.count {
            if beats[i] + reelLen > beats.last! { break }
            var j = i, s = 0.0, c = 0
            while j < beats.count && beats[j] < beats[i] + reelLen { s += energy[j]; c += 1; j += 1 }
            var rise = 0.0
            var q = i + 4
            while q < j - 3 {
                let up = (energy[q] + energy[q + 1] + energy[q + 2] + energy[q + 3]) / 4
                let before = (energy[q - 1] + energy[q - 2] + energy[q - 3] + energy[q - 4]) / 4
                rise = max(rise, up - before)
                q += 1
            }
            let sc = s / Double(c) + 0.8 * rise + 0.05 * min(2.0, o[bf[i]])
            if sc > bestS { bestS = sc; bestI = i }
        }
        let start = beats[bestI]
        var lastI = bestI
        while lastI + 1 < beats.count && beats[lastI + 1] - start <= reelLen + 0.01 { lastI += 1 }
        var len = beats[lastI] - start
        if len < reelLen - 1.2 { len = min(reelLen, songDur - start - 0.05) }
        let rel = (bestI...lastI).map { beats[$0] - start }
        let en = (bestI...lastI).map { energy[$0] }

        // bass hits inside the window, for pulses and shakes between cuts
        let thr = pct(fL, 92)
        var hits: [Hit] = []
        let f0 = Int((start * fr).rounded())
        let f1 = min(Int(((start + len) * fr).rounded()), n - 5)
        if f1 - 2 > f0 + 2 {
            for f in (f0 + 2)..<(f1 - 2) {
                if f < 4 || fL[f] < thr { continue }
                var peak = true
                for d in -4...4 where fL[f + d] > fL[f] { peak = false; break }
                let t = Double(f) / fr - start
                if peak && (hits.isEmpty || t - hits.last!.t > 0.18) { hits.append(Hit(t: t, s: fL[f])) }
            }
        }
        let hmax = max(1e-9, hits.map { $0.s }.max() ?? 1)
        let hitsN = hits.map { Hit(t: $0.t, s: ($0.s / hmax).clamped(0.3, 1)) }

        var seed: Int32 = 0
        var i = 0
        while i < x.count { seed = seed &* 31 &+ Int32(clamping: Int((x[i] * 1e4).rounded())); i += 997 }

        return SongAnalysis(start: start, len: len, rel: rel, energy: en, hits: hitsN, bpm: bpm, brightness: brightness,
                            bass: bass, beatStrength: beatStrength, seed: Int(seed), songDur: songDur, punch: punch)
    }

    private static func avg(_ a: [Double]) -> Double { a.isEmpty ? 0 : a.reduce(0, +) / Double(a.count) }

    private static func biquad(_ x: [Float], lowpass: Bool, freq: Double) -> [Float] {
        let w0 = 2 * Double.pi * freq / Double(sr)
        let alpha = sin(w0) / (2 * 0.707)
        let cw = cos(w0)
        let b0, b1, b2: Double
        if lowpass { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 } else { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
        let a0 = 1 + alpha, a1 = -2 * cw, a2 = 1 - alpha
        let nb0 = b0 / a0, nb1 = b1 / a0, nb2 = b2 / a0, na1 = a1 / a0, na2 = a2 / a0
        var y = [Float](repeating: 0, count: x.count)
        var x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0
        x.withUnsafeBufferPointer { xp in
            y.withUnsafeMutableBufferPointer { yp in
                for i in 0..<xp.count {
                    let xi = Double(xp[i])
                    let yi = nb0 * xi + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
                    x2 = x1; x1 = xi; y2 = y1; y1 = yi
                    yp[i] = Float(yi)
                }
            }
        }
        return y
    }

    private static func frameRms(_ x: [Float]) -> [Double] {
        let n = x.count / hop
        var out = [Double](repeating: 0, count: n)
        x.withUnsafeBufferPointer { xp in
            for i in 0..<n {
                var s = 0.0
                let o = i * hop
                for j in 0..<hop { let v = Double(xp[o + j]); s += v * v }
                out[i] = (s / Double(hop)).squareRoot()
            }
        }
        return out
    }

    private static func flux(_ r: [Double]) -> [Double] {
        var f = [Double](repeating: 0, count: r.count)
        if r.isEmpty { return f }
        var prev = log(1 + 1000 * r[0])
        for i in 1..<r.count {
            let c = log(1 + 1000 * r[i])
            f[i] = max(0, c - prev)
            prev = c
        }
        return f
    }

    private static func trackBeats(_ o: [Double]) -> [Int] {
        let n = o.count
        var best = -1.0
        var bestL = 0
        var scores: [Int: Double] = [:]
        let lo = Int(floor(fr * 60 / 185))
        let hi = Int(ceil(fr * 60 / 68))
        o.withUnsafeBufferPointer { op in
            for l in lo...hi {
                if l >= n { break }
                var s = 0.0
                for i in 0..<(n - l) { s += op[i] * op[i + l] }
                s /= Double(n - l)
                let bpm = 60 * fr / Double(l)
                let w = exp(-0.5 * pow(log2(bpm / 120) / 0.9, 2))
                scores[l] = s * w
                if s * w > best { best = s * w; bestL = l }
            }
        }
        if bestL == 0 { return [] }
        var p = Double(bestL)
        if let a = scores[bestL - 1], let c = scores[bestL + 1] {
            let d = a - 2 * best + c
            if d < 0 { p = Double(bestL) + 0.5 * (a - c) / d }
        }
        var cum = [Double](repeating: 0, count: n)
        var back = [Int](repeating: -1, count: n)
        let alpha = 100.0
        let far = Int((2 * p).rounded())
        let near = Int((p / 2).rounded())
        // penalty table for every gap (t - q) in near...far
        var pen = [Double](repeating: 0, count: far + 1)
        if near <= far { for g in max(1, near)...far { pen[g] = alpha * pow(log(Double(g) / p), 2) } }
        for t in 0..<n {
            var m = 0.0
            var arg = -1
            let s0 = max(0, t - far)
            let s1 = t - near
            if s1 >= s0 {
                for q in s0...s1 {
                    let gap = t - q
                    let v = cum[q] - (gap >= 1 ? pen[gap] : alpha * pow(log(Double(max(gap, 1)) / p), 2))
                    if arg < 0 || v > m { m = v; arg = q }
                }
            }
            cum[t] = o[t] + (arg >= 0 ? max(0, m) : 0)
            back[t] = arg >= 0 && m > 0 ? arg : -1
        }
        var end = n - 1
        var bv = -1.0
        for t in max(0, n - Int(p.rounded()))..<n where cum[t] > bv { bv = cum[t]; end = t }
        var out: [Int] = []
        var t = end
        while t >= 0 { out.append(t); t = back[t] }
        return out.reversed()
    }

    static func pct(_ arr: [Double], _ p: Double) -> Double {
        if arr.isEmpty { return 0 }
        let s = arr.sorted()
        return s[min(s.count - 1, Int(floor(p / 100 * Double(s.count))))]
    }
}
