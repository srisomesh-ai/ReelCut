import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// A picked photo or video, copied into the app (the picker's own copy is temporary).
struct PickedFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { r in PickedFile(url: try keep(r.file)) }
        FileRepresentation(importedContentType: .image) { r in PickedFile(url: try keep(r.file)) }
    }

    static var inbox: URL {
        let u = Paths.cache.appendingPathComponent("picked", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static func keep(_ f: URL) throws -> URL {
        let ext = f.pathExtension.isEmpty ? "bin" : f.pathExtension.lowercased()
        let dest = inbox.appendingPathComponent(UUID().uuidString + "." + ext)
        try FileManager.default.copyItem(at: f, to: dest)
        return dest
    }
}

/// Max clips / photos per pick (2-minute reels need more than the Android app's 20).
let maxPick = 40

enum Picking {
    /// Copies the picked items in, in the order they were picked. Items that fail are left out.
    static func load(_ items: [PhotosPickerItem], progress: @escaping (Int, Int) -> Void) async -> [URL] {
        var out: [URL] = []
        for (i, it) in items.enumerated() {
            progress(i, items.count)
            if let f = try? await it.loadTransferable(type: PickedFile.self) { out.append(f.url) }
        }
        return out
    }

    /// A song from Files, copied into the app.
    static func keepSong(_ url: URL) -> URL? {
        let ok = url.startAccessingSecurityScopedResource()
        defer { if ok { url.stopAccessingSecurityScopedResource() } }
        let dir = Paths.cache.appendingPathComponent("song", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(url.lastPathComponent)
        do { try FileManager.default.copyItem(at: url, to: dest); return dest } catch { return nil }
    }

    /// Clears what was picked for the last auto reel.
    static func clearInbox(except keep: [URL] = []) {
        let k = Set(keep.map { $0.lastPathComponent })
        let files = (try? FileManager.default.contentsOfDirectory(at: PickedFile.inbox, includingPropertiesForKeys: nil)) ?? []
        for f in files where !k.contains(f.lastPathComponent) { try? FileManager.default.removeItem(at: f) }
    }
}
