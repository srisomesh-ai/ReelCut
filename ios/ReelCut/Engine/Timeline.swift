import Foundation

/// Timeline editor model: a clip plays inS..outS seconds of its source. Photos have no source length:
/// inS is 0 and outS is how long the photo shows. uri = file name inside the app's media folder.
struct TClip: Codable, Equatable, Identifiable {
    var id: Int64
    var uri: String
    var isImage: Bool
    var srcDur: Double
    var inS: Double
    var outS: Double
    var dur: Double { outS - inS }

    enum CodingKeys: String, CodingKey { case id, uri, isImage = "img", srcDur = "src", inS = "in", outS = "out" }
}

struct Project: Codable, Equatable, Identifiable {
    var v = 1
    var id: String
    var name: String
    var clips: [TClip]
    var created: Int64
    var updated: Int64

    var total: Double { clips.reduce(0) { $0 + $1.dur } }

    func starts() -> [Double] {
        var s: [Double] = []
        var t = 0.0
        for c in clips { s.append(t); t += c.dur }
        return s
    }

    func indexOf(_ id: Int64) -> Int? { clips.firstIndex { $0.id == id } }

    /// The clip playing at timeline time t and the time inside it.
    func clipAt(_ t: Double) -> (Int, Double)? {
        if clips.isEmpty { return nil }
        let s = starts()
        for i in clips.indices where t < s[i] + clips[i].dur { return (i, max(0, t - s[i])) }
        return (clips.count - 1, clips.last!.dur)
    }
}

/// Every edit returns a new project, or nil when it is not possible.
enum TimelineEdits {
    static let minClip = 0.2
    static let photoDefault = 3.0
    static let photoMax = 30.0

    private static func nextId(_ p: Project) -> Int64 { (p.clips.map { $0.id }.max() ?? 0) + 1 }

    static func trimLeft(_ p: Project, _ id: Int64, _ delta: Double) -> Project? {
        guard let i = p.indexOf(id) else { return nil }
        let c = p.clips[i]
        var n = c
        if c.isImage { n.outS = (c.outS - delta).clamped(minClip, photoMax) } else { n.inS = (c.inS + delta).clamped(0, c.outS - minClip) }
        if n == c { return nil }
        var q = p; q.clips[i] = n; return q
    }

    static func trimRight(_ p: Project, _ id: Int64, _ delta: Double) -> Project? {
        guard let i = p.indexOf(id) else { return nil }
        let c = p.clips[i]
        var n = c
        if c.isImage { n.outS = (c.outS + delta).clamped(minClip, photoMax) } else { n.outS = (c.outS + delta).clamped(c.inS + minClip, c.srcDur) }
        if n == c { return nil }
        var q = p; q.clips[i] = n; return q
    }

    static func split(_ p: Project, _ t: Double) -> (Project, Int64)? {
        guard case let (i, local)? = p.clipAt(t) else { return nil }
        let c = p.clips[i]
        if local < minClip || c.dur - local < minClip { return nil }
        let id2 = nextId(p)
        var a = c, b = c
        b.id = id2
        if c.isImage { a.outS = local; b.outS = c.dur - local } else { a.outS = c.inS + local; b.inS = c.inS + local }
        var q = p
        q.clips[i] = a
        q.clips.insert(b, at: i + 1)
        return (q, id2)
    }

    static func duplicate(_ p: Project, _ id: Int64) -> (Project, Int64)? {
        guard let i = p.indexOf(id) else { return nil }
        let id2 = nextId(p)
        var c = p.clips[i]; c.id = id2
        var q = p; q.clips.insert(c, at: i + 1)
        return (q, id2)
    }

    static func delete(_ p: Project, _ id: Int64) -> Project? {
        guard let i = p.indexOf(id) else { return nil }
        var q = p; q.clips.remove(at: i); return q
    }

    static func move(_ p: Project, _ id: Int64, _ to: Int) -> Project? {
        guard let i = p.indexOf(id) else { return nil }
        let target = to.clamped(0, p.clips.count - 1)
        if target == i { return nil }
        var q = p
        let c = q.clips.remove(at: i)
        q.clips.insert(c, at: target)
        return q
    }

    static func add(_ p: Project, _ at: Int, _ clips: [TClip]) -> (Project, Int64)? {
        if clips.isEmpty { return nil }
        var id = nextId(p)
        let first = id
        let fresh = clips.map { c -> TClip in var n = c; n.id = id; id += 1; return n }
        var q = p
        q.clips.insert(contentsOf: fresh, at: at.clamped(0, q.clips.count))
        return (q, first)
    }

    /// Where clip id would land if dropped with its centre at timeline time t.
    static func dropIndex(_ p: Project, _ id: Int64, _ t: Double) -> Int {
        let others = p.clips.filter { $0.id != id }
        var acc = 0.0
        for (k, c) in others.enumerated() {
            if t < acc + c.dur / 2 { return k }
            acc += c.dur
        }
        return others.count
    }
}
