import AVFoundation
import Photos
import UIKit

/// Where the app keeps its files (inside the app; nothing leaves the phone unless shared or saved to Photos).
enum Paths {
    static let root: URL = {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("ReelCut", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
    static func dir(_ name: String) -> URL {
        let u = root.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    /// Clips and photos of timeline projects.
    static var media: URL { dir("media") }
    static var projects: URL { dir("projects") }
    static var reels: URL { dir("reels") }
    /// Picked files of the current auto reel (replaced each time).
    static var session: URL {
        let u = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("session", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static var cache: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0] }

    static func stamp() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f.string(from: Date())
    }
    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Timeline projects saved as JSON (projects/<id>.json + <id>.jpg cover).
enum ProjectStore {
    private static func file(_ id: String) -> URL { Paths.projects.appendingPathComponent("\(id).json") }
    static func cover(_ id: String) -> URL { Paths.projects.appendingPathComponent("\(id).jpg") }

    static func list() -> [Project] {
        let files = (try? FileManager.default.contentsOfDirectory(at: Paths.projects, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { try? JSONDecoder().decode(Project.self, from: Data(contentsOf: $0)) }
            .sorted { $0.updated > $1.updated }
    }

    static func load(_ id: String) -> Project? { try? JSONDecoder().decode(Project.self, from: Data(contentsOf: file(id))) }

    static func save(_ p: Project) {
        guard let d = try? JSONEncoder().encode(p) else { return }
        try? d.write(to: file(p.id), options: .atomic)
    }

    static func delete(_ id: String) {
        try? FileManager.default.removeItem(at: file(id))
        try? FileManager.default.removeItem(at: cover(id))
    }

    static func newProject(_ clips: [TClip]) -> Project {
        let now = Paths.nowMs()
        let base = Project(id: Paths.stamp(), name: "Project \(list().count + 1)", clips: [], created: now, updated: now)
        return TimelineEdits.add(base, 0, clips)?.0 ?? base
    }

    static func url(_ c: TClip) -> URL { Paths.media.appendingPathComponent(c.uri) }

    /// Moves picked files into the media folder and reads what the timeline needs (photo or video, video length).
    static func probe(_ files: [URL]) async -> [TClip] {
        var out: [TClip] = []
        for f in files {
            let name = UUID().uuidString + "." + (f.pathExtension.isEmpty ? "mov" : f.pathExtension.lowercased())
            let dest = Paths.media.appendingPathComponent(name)
            do { try FileManager.default.moveItem(at: f, to: dest) } catch {
                guard (try? FileManager.default.copyItem(at: f, to: dest)) != nil else { continue }
            }
            if ClipScanner.isImage(dest) {
                if Pictures.load(dest, maxSide: 64) == nil { try? FileManager.default.removeItem(at: dest); continue }
                out.append(TClip(id: 0, uri: name, isImage: true, srcDur: 0, inS: 0, outS: TimelineEdits.photoDefault))
            } else {
                let a = AVURLAsset(url: dest)
                let d = (try? await a.load(.duration))?.seconds ?? 0
                let hasVideo = !((try? await a.loadTracks(withMediaType: .video)) ?? []).isEmpty
                if d < 0.3 || !hasVideo { try? FileManager.default.removeItem(at: dest); continue }
                out.append(TClip(id: 0, uri: name, isImage: false, srcDur: d, inS: 0, outS: d))
            }
        }
        return out
    }

    /// Small cover picture from the first clip, for the Recent list.
    static func writeCover(_ p: Project) async {
        guard let c = p.clips.first else { return }
        let img = await Thumbs.image(url(c), isImage: c.isImage, at: c.inS + c.dur / 2, maxSide: 360)
        if let d = img?.jpegData(compressionQuality: 0.8) { try? d.write(to: cover(p.id)) }
    }

    /// Removes media files no project uses any more.
    static func cleanMedia() {
        let used = Set(list().flatMap { $0.clips.map { $0.uri } })
        let bases = Set(used.map { ($0 as NSString).deletingPathExtension })
        let files = (try? FileManager.default.contentsOfDirectory(at: Paths.media, includingPropertiesForKeys: nil)) ?? []
        for f in files {
            let name = f.lastPathComponent
            if used.contains(name) { continue }
            if name.hasSuffix(".still.mp4") && bases.contains(String(name.dropLast(10))) { continue }
            try? FileManager.default.removeItem(at: f)
        }
    }
}

/// Exported reels kept inside the app (for Recent projects); a copy also goes to Photos.
enum Library {
    struct Item: Identifiable, Equatable {
        let file: URL
        let thumb: URL
        let label: String
        var id: String { file.path }
    }

    static func newFile() -> URL { Paths.reels.appendingPathComponent("ReelCut_\(Paths.stamp()).mp4") }

    static func list() -> [Item] {
        let files = (try? FileManager.default.contentsOfDirectory(at: Paths.reels, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        func date(_ u: URL) -> Date { (try? u.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast }
        return files.filter { $0.pathExtension == "mp4" }.sorted { date($0) > date($1) }.map { f in
            let base = f.deletingPathExtension()
            let label = (try? String(contentsOf: base.appendingPathExtension("txt"), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return Item(file: f, thumb: base.appendingPathExtension("jpg"), label: label.isEmpty ? "Reel" : label)
        }
    }

    static func finish(_ file: URL, label: String) async {
        let base = file.deletingPathExtension()
        try? label.write(to: base.appendingPathExtension("txt"), atomically: true, encoding: .utf8)
        if let img = await Thumbs.image(file, isImage: false, at: 1, maxSide: 480), let d = img.jpegData(compressionQuality: 0.75) {
            try? d.write(to: base.appendingPathExtension("jpg"))
        }
    }

    /// Saves a copy to the phone's Photos.
    static func saveToPhotos(_ file: URL) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw ReelError("Allow ReelCut to add to Photos in Settings to save reels there")
        }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.creationRequestForAssetFromVideo(atFileURL: file)
        }
    }

    static func delete(_ item: Item) {
        let base = item.file.deletingPathExtension()
        for u in [item.file, base.appendingPathExtension("jpg"), base.appendingPathExtension("txt")] { try? FileManager.default.removeItem(at: u) }
    }
}

/// Small pictures of clips and photos, cached.
enum Thumbs {
    private static let cache = NSCache<NSString, UIImage>()

    static func key(_ url: URL, _ isImage: Bool, _ t: Double, _ maxSide: Int) -> NSString {
        "\(url.lastPathComponent)|\(isImage ? 0 : Int((t * 4).rounded()))|\(maxSide)" as NSString
    }

    static func cached(_ url: URL, isImage: Bool, at t: Double, maxSide: Int) -> UIImage? { cache.object(forKey: key(url, isImage, t, maxSide)) }

    static func image(_ url: URL, isImage: Bool, at t: Double, maxSide: Int) async -> UIImage? {
        let k = key(url, isImage, t, maxSide)
        if let c = cache.object(forKey: k) { return c }
        var out: UIImage?
        if isImage {
            if let ci = Pictures.load(url, maxSide: maxSide), let cg = CIContext().createCGImage(ci, from: ci.extent) { out = UIImage(cgImage: cg) }
        } else {
            let g = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            g.appliesPreferredTrackTransform = true
            g.maximumSize = CGSize(width: maxSide, height: maxSide)
            g.requestedTimeToleranceBefore = CMTime(seconds: 0.3, preferredTimescale: 600)
            g.requestedTimeToleranceAfter = CMTime(seconds: 0.3, preferredTimescale: 600)
            if let cg = try? await g.image(at: CMTime(seconds: max(0, t), preferredTimescale: 600)).image { out = UIImage(cgImage: cg) }
        }
        if let o = out { cache.setObject(o, forKey: k) }
        return out
    }
}
