import Foundation

/// The edit that gets rendered: look + shots + the beat grid the shots sit on.
struct Plan {
    let style: Style
    let shots: [Shot]
    let grid: [Double]
    func withLook(_ key: String) -> Plan { Plan(style: Styles.withLook(style, key), shots: shots, grid: grid) }
}

/// Hand edits from the Fine-tune screen. Every cut stays on the beat grid and the reel keeps its length.
/// Each function returns a new list, or nil when the change is not possible.
enum ShotEdits {

    /// Shot k one beat longer (d = +1) or shorter (d = -1).
    static func changeLength(_ shots: [Shot], _ grid: [Double], _ k: Int, _ d: Int) -> [Shot]? {
        let n = shots.count
        if k < 0 || k >= n || d == 0 { return nil }
        var b = (0...n).map { $0 < n ? shots[$0].b0 : shots[n - 1].b1 }
        let end = b[n]
        if k == n - 1 {
            if n == 1 { return nil }
            let nb = b[k] - d
            if nb <= b[k - 1] || nb >= end { return nil }
            b[k] = nb
        } else {
            let nb = b[k + 1] + d
            if nb <= b[k] || nb >= end { return nil }
            for j in (k + 1)..<n { b[j] += d }
        }
        var out: [Shot] = []
        for j in 0..<n {
            let b0 = min(b[j], end)
            let b1 = j == n - 1 ? end : min(b[j + 1], end)
            if b1 > b0 { var s = shots[j]; s.b0 = b0; s.b1 = b1; out.append(s) }
        }
        return retime(out, grid)
    }

    /// Removes shot k; the shot before it (or after it, for the first) fills its time.
    static func remove(_ shots: [Shot], _ grid: [Double], _ k: Int) -> [Shot]? {
        if shots.count < 2 || k < 0 || k >= shots.count { return nil }
        var out = shots
        if k > 0 { out[k - 1].b1 = shots[k].b1 } else { out[1].b0 = shots[0].b0 }
        out.remove(at: k)
        return retime(out, grid)
    }

    /// Which part of the clip plays (seconds into the clip).
    static func setMoment(_ shots: [Shot], _ k: Int, _ src0: Double) -> [Shot] {
        shots.enumerated().map { i, s in
            guard i == k else { return s }
            var n = s; n.src0 = src0; return clampMoment(n)
        }
    }

    /// Another clip or photo for shot k, starting at src0.
    static func setMedia(_ shots: [Shot], _ k: Int, _ media: MediaInfo, _ src0: Double) -> [Shot] {
        shots.enumerated().map { i, s in
            guard i == k else { return s }
            var n = s; n.media = media; n.src0 = src0; return clampMoment(n)
        }
    }

    /// Times from the grid (whole frames), and every clip moment kept inside its clip.
    static func retime(_ shots: [Shot], _ grid: [Double]) -> [Shot] {
        shots.map { s in
            var n = s
            n.t0 = Styles.frameT(grid[s.b0]); n.t1 = Styles.frameT(grid[s.b1])
            return clampMoment(n)
        }
    }

    private static func clampMoment(_ s: Shot) -> Shot {
        guard let m = s.media else { return s }
        var n = s
        if m.isImage { n.src0 = 0; return n }
        let maxStart = max(0, m.dur - (s.t1 - s.t0) - 0.05)
        n.src0 = s.src0.clamped(0, maxStart)
        return n
    }
}
