import AVFoundation
import CoreImage

/// One timeline project as an AVFoundation composition: the same thing is previewed (AVPlayer) and exported.
/// Photos become still videos (made once, kept next to the photo), so everything plays as video.
enum TimelineComposer {

    struct Built {
        let asset: AVMutableComposition
        let video: AVMutableVideoComposition
    }

    static func build(_ p: Project, width W: Int, height H: Int) async throws -> Built {
        let comp = AVMutableComposition()
        guard let vt = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let at = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw ReelError("Could not build the preview")
        }
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: vt)
        var cursor = CMTime.zero
        for c in p.clips {
            let src = c.isImage ? try await StillVideo.make(for: ProjectStore.url(c)) : ProjectStore.url(c)
            let asset = AVURLAsset(url: src)
            guard let track = try await asset.loadTracks(withMediaType: .video).first else { continue }
            let range = CMTimeRange(start: CMTime(seconds: c.isImage ? 0 : c.inS, preferredTimescale: 600),
                                    duration: CMTime(seconds: c.dur, preferredTimescale: 600))
            try vt.insertTimeRange(range, of: track, at: cursor)
            if !c.isImage, let a = try await asset.loadTracks(withMediaType: .audio).first {
                try? at.insertTimeRange(range, of: a, at: cursor)
            }
            let nat = try await track.load(.naturalSize)
            let tf = try await track.load(.preferredTransform)
            layer.setTransform(coverTransform(nat, tf, W, H), at: cursor)
            cursor = cursor + range.duration
        }
        let ins = AVMutableVideoCompositionInstruction()
        ins.timeRange = CMTimeRange(start: .zero, duration: cursor)
        ins.layerInstructions = [layer]
        let vc = AVMutableVideoComposition()
        vc.renderSize = CGSize(width: W, height: H)
        vc.frameDuration = CMTime(value: 1, timescale: 30)
        vc.instructions = [ins]
        return Built(asset: comp, video: vc)
    }

    /// Upright, scaled to cover W x H, centred.
    static func coverTransform(_ nat: CGSize, _ t: CGAffineTransform, _ W: Int, _ H: Int) -> CGAffineTransform {
        let r = CGRect(origin: .zero, size: nat).applying(t)
        var tf = t.concatenating(CGAffineTransform(translationX: -r.minX, y: -r.minY))
        let s = max(CGFloat(W) / r.width, CGFloat(H) / r.height)
        tf = tf.concatenating(CGAffineTransform(scaleX: s, y: s))
        return tf.concatenating(CGAffineTransform(translationX: (CGFloat(W) - r.width * s) / 2, y: (CGFloat(H) - r.height * s) / 2))
    }

    /// Exports the project as an upright 1080 x 1920 MP4 with the clips' own sound.
    static func export(_ p: Project, to out: URL, progress: @escaping (Double) -> Void) async throws {
        let b = try await build(p, width: 1080, height: 1920)
        try? FileManager.default.removeItem(at: out)
        guard let s = AVAssetExportSession(asset: b.asset, presetName: AVAssetExportPresetHighestQuality) else {
            throw ReelError("Export is not possible on this phone")
        }
        s.videoComposition = b.video
        s.outputURL = out
        s.outputFileType = .mp4
        let poll = Task {
            while !Task.isCancelled {
                progress(Double(s.progress))
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
        defer { poll.cancel() }
        try await withTaskCancellationHandler {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in s.exportAsynchronously { c.resume() } }
        } onCancel: { s.cancelExport() }
        try Task.checkCancellation()
        if s.status != .completed { throw s.error ?? ReelError("Export failed") }
        progress(1)
    }
}

/// A photo as a 30 s still video (1080 x 1920, cover-cropped), so the timeline can play it like a clip.
enum StillVideo {
    static func make(for photo: URL) async throws -> URL {
        let out = photo.deletingPathExtension().appendingPathExtension("still.mp4")
        if FileManager.default.fileExists(atPath: out.path) { return out }
        let tmp = out.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".mp4")
        let W = 1080, H = 1920
        guard let img = Pictures.load(photo, maxSide: 2400) else { throw ReelError("A photo could not be read") }
        let pic = Pictures.cover(img, W, H)
        let writer = try AVAssetWriter(outputURL: tmp, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: W, AVVideoHeightKey: H,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 4_000_000, AVVideoMaxKeyFrameIntervalKey: 10] as [String: Any],
        ])
        let ad = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: W, kCVPixelBufferHeightKey as String: H,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? ReelError("Could not prepare a photo") }
        writer.startSession(atSourceTime: .zero)
        guard let pool = ad.pixelBufferPool else { throw ReelError("Could not prepare a photo") }
        var pbOut: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pbOut)
        guard let pb = pbOut else { throw ReelError("Could not prepare a photo") }
        CIContext().render(pic, to: pb, bounds: CGRect(x: 0, y: 0, width: W, height: H), colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        let fps: Int32 = 5
        let frames = Int(TimelineEdits.photoMax) * Int(fps) + 1
        for f in 0..<frames {
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 2_000_000) }
            ad.append(pb, withPresentationTime: CMTime(value: CMTimeValue(f), timescale: fps))
        }
        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else { throw writer.error ?? ReelError("Could not prepare a photo") }
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.moveItem(at: tmp, to: out)
        return out
    }
}
