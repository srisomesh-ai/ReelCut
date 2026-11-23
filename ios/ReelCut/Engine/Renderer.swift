import AVFoundation
import CoreImage
import ImageIO
import UIKit

/// The Core Image kernels from Kernels.ci.metal.
final class Kernels {
    let look: CIKernel
    let finish: CIKernel
    let mix: CIColorKernel
    let dim: CIColorKernel

    static let shared: Kernels? = try? Kernels()

    private init() throws {
        guard let url = Bundle(for: Kernels.self).url(forResource: "default", withExtension: "metallib") else {
            throw ReelError("Video effects are missing from the app")
        }
        let data = try Data(contentsOf: url)
        look = try CIKernel(functionName: "rcLook", fromMetalLibraryData: data)
        finish = try CIKernel(functionName: "rcFinish", fromMetalLibraryData: data)
        mix = try CIColorKernel(functionName: "rcMix", fromMetalLibraryData: data)
        dim = try CIColorKernel(functionName: "rcDim", fromMetalLibraryData: data)
    }
}

func ease(_ x: Double) -> Double { x < 0.5 ? 2 * x * x : 1 - pow(-2 * x + 2, 2) / 2 }

/// What one shot needs to draw itself: look, transition, punches, bass hits. A tail is the previous shot
/// carrying on under the next one while it dissolves in; zoomTail = it zooms through as it fades.
struct ShotFx {
    let style: Style
    let shot: Shot
    let isImage: Bool
    let dur: Double
    let hits: [Hit]           // relative to the shot start
    var tail = false
    var zoomTail = false
    var index = 0

    struct Params { var zoom: Double; var offX: Double; var offY: Double; var blur: Double; var glitch: Double; var seed: Double }

    /// t = time since this shot started (s). Same maths as the Android LookShaderProgram.
    func params(_ t: Double) -> Params {
        let st = style, look = st.look, sh = shot
        let p = (t / dur).clamped(0, 1)
        var pulse = 0.0, shake = 0.0
        for h in hits {
            let dt = t - h.t
            if dt >= 0 && dt < 0.3 && h.t > 0.05 {
                pulse += 0.045 * h.s * exp(-dt / 0.08)
                shake += h.s * exp(-dt / 0.09)
            }
        }
        if sh.punch { pulse += 0.14 * exp(-t / 0.09) }
        if !st.energetic { pulse *= st.smooth ? 0 : 0.3 }
        let amp = (look.shake || st.heavy) && st.energetic ? 16.0 * shake : 0
        var offX = amp * sin(t * 91) / 720
        var offY = amp * cos(t * 73) / 1280
        var zoom: Double
        if isImage { zoom = 1.04 + 0.12 * (sh.kb > 0 ? p : 1 - p) }
        else if st.energetic { zoom = 1.0 }
        else { zoom = 1.0 + 0.05 * p }
        if isImage {
            offX += sh.panX * 40 * (p - 0.5) / 720
            offY += sh.panY * 60 * (p - 0.5) / 1280
        }
        zoom += pulse
        var blur = 0.0, glitch = 0.0, seed = 0.0
        if tail {
            if zoomTail { zoom += st.zoomAmt * ease(((t - dur) / st.zoomLen).clamped(0, 1)) }
        } else {
            switch sh.trans {
            case .whip: if t < 0.18 { let e = 1 - ease(t / 0.18); offX += 0.9 * e; blur = 0.012 * e }
            case .glitch: if t < 0.16 { glitch = 1; seed = Double(index * 977 + Int(floor(t * 60))) }
            default: break
            }
        }
        return Params(zoom: zoom, offX: offX, offY: offY, blur: blur, glitch: glitch, seed: seed)
    }
}

/// Reads one video clip in order and gives the frame showing at a clip time.
final class ClipReader {
    private let reader: AVAssetReader
    private let output: AVAssetReaderTrackOutput
    private let orient: CGImagePropertyOrientation
    private var cur: CMSampleBuffer?
    private var nxt: CMSampleBuffer?
    private var done = false

    private init(reader: AVAssetReader, output: AVAssetReaderTrackOutput, orient: CGImagePropertyOrientation) {
        self.reader = reader; self.output = output; self.orient = orient
    }

    static func open(_ url: URL, from: Double, length: Double) async throws -> ClipReader {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw ReelError("A clip has no video") }
        let tf = try await track.load(.preferredTransform)
        let reader = try AVAssetReader(asset: asset)
        reader.timeRange = CMTimeRange(start: CMTime(seconds: max(0, from), preferredTimescale: 600),
                                       duration: CMTime(seconds: length, preferredTimescale: 600))
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ])
        out.alwaysCopiesSampleData = false
        reader.add(out)
        guard reader.startReading() else { throw reader.error ?? ReelError("A clip could not be read") }
        return ClipReader(reader: reader, output: out, orient: orientation(tf))
    }

    /// The upright frame showing at clip time t (s); the last frame stays when the clip has ended.
    func frame(at t: Double) -> CIImage? {
        if nxt == nil && !done { nxt = output.copyNextSampleBuffer(); if nxt == nil { done = true } }
        while let n = nxt, CMSampleBufferGetPresentationTimeStamp(n).seconds <= t + 0.002 {
            cur = n
            nxt = output.copyNextSampleBuffer()
            if nxt == nil { done = true }
        }
        guard let sb = cur ?? nxt, let pb = CMSampleBufferGetImageBuffer(sb) else { return nil }
        let img = CIImage(cvPixelBuffer: pb).oriented(orient)
        return img.transformed(by: CGAffineTransform(translationX: -img.extent.minX, y: -img.extent.minY))
    }

    func close() { reader.cancelReading(); cur = nil; nxt = nil }

    /// The video's rotation note as a picture orientation (phones store portrait video sideways + a note).
    static func orientation(_ t: CGAffineTransform) -> CGImagePropertyOrientation {
        let deg = (atan2(Double(t.b), Double(t.a)) * 180 / .pi).rounded()
        switch Int(deg) {
        case 90: return .right
        case -90: return .left
        case 180, -180: return .down
        default: return .up
        }
    }
}

/// Picture helpers.
enum Pictures {
    /// A photo, upright, at most maxSide pixels.
    static func load(_ url: URL, maxSide: Int) -> CIImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return CIImage(cgImage: cg)
    }

    /// Fills w x h (scaled to cover, centred, cropped) - the Android "scale to fit with crop".
    static func cover(_ img: CIImage, _ w: Int, _ h: Int) -> CIImage {
        let e = img.extent
        let s = max(CGFloat(w) / e.width, CGFloat(h) / e.height)
        let tx = (CGFloat(w) - e.width * s) / 2 - e.minX * s
        let ty = (CGFloat(h) - e.height * s) / 2 - e.minY * s
        return img.transformed(by: CGAffineTransform(a: s, b: 0, c: 0, d: s, tx: tx, ty: ty))
            .cropped(to: CGRect(x: 0, y: 0, width: w, height: h))
    }
}

/// The title (first 2.4 s, with glow) and the VHS "PLAY ▶" + time code.
enum ReelOverlays {
    private static func rgb(_ c: UInt32, _ a: CGFloat = 1) -> UIColor {
        UIColor(red: CGFloat((c >> 16) & 255) / 255, green: CGFloat((c >> 8) & 255) / 255, blue: CGFloat(c & 255) / 255, alpha: a)
    }

    private static func renderer(_ w: Int, _ h: Int) -> UIGraphicsImageRenderer {
        let f = UIGraphicsImageRendererFormat()
        f.scale = 1; f.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: f)
    }

    static func title(_ text: String, look: Look, w: Int, h: Int) -> CIImage? {
        let t = text.trimmingCharacters(in: .whitespaces).uppercased()
        if t.isEmpty { return nil }
        let u = CGFloat(min(w, h)) / 720
        var fs = 84 * u
        var font = UIFont.systemFont(ofSize: fs, weight: .bold)
        while (t as NSString).size(withAttributes: [.font: font]).width > CGFloat(w) * 0.86 && fs > 30 {
            fs -= 4; font = UIFont.systemFont(ofSize: fs, weight: .bold)
        }
        let size = (t as NSString).size(withAttributes: [.font: font])
        let rect = CGRect(x: (CGFloat(w) - size.width) / 2, y: CGFloat(h) * 0.74 - size.height / 2, width: size.width, height: size.height)
        let img = renderer(w, h).image { ctx in
            // dark outline, then the glowing white text
            (t as NSString).draw(in: rect, withAttributes: [
                .font: font, .strokeColor: UIColor(white: 0, alpha: 140.0 / 255), .strokeWidth: 8 * u / fs * 100,
            ])
            if look.glow != 0 {
                ctx.cgContext.setShadow(offset: .zero, blur: 30 * u, color: rgb(look.glow).cgColor)
            }
            (t as NSString).draw(in: rect, withAttributes: [.font: font, .foregroundColor: UIColor.white])
        }
        return img.cgImage.map { CIImage(cgImage: $0) }
    }

    /// Alpha of the title at reel time tt.
    static func titleAlpha(_ tt: Double) -> Double { (tt * 4).clamped(0, 1) * ((2.4 - tt) * 3).clamped(0, 1) }

    static func stamp(second s: Int, w: Int, h: Int) -> CIImage? {
        let u = CGFloat(min(w, h)) / 720
        let font = UIFont.monospacedSystemFont(ofSize: (34 * u).rounded(), weight: .bold)
        let shadow = NSShadow()
        shadow.shadowBlurRadius = 6 * u
        shadow.shadowColor = UIColor(white: 0, alpha: 0.6)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(white: 1, alpha: 230.0 / 255), .shadow: shadow]
        let code = "00:00:" + String(format: "%02d", s)
        let img = renderer(w, h).image { _ in
            ("PLAY ▶" as NSString).draw(at: CGPoint(x: 36 * u, y: 40 * u), withAttributes: attrs)
            let sz = (code as NSString).size(withAttributes: attrs)
            (code as NSString).draw(at: CGPoint(x: CGFloat(w) - 36 * u - sz.width, y: CGFloat(h) - 40 * u - sz.height), withAttributes: attrs)
        }
        return img.cgImage.map { CIImage(cgImage: $0) }
    }
}

/// Turns the planned shots into an MP4: Core Image (GPU) draws every frame, the phone's encoder saves it.
/// Saved upright (width x height, no rotation note), H.264 + AAC.
enum Renderer {

    static func render(
        a: SongAnalysis, style: Style, shots: [Shot], song: URL, out: URL, width w: Int, height h: Int,
        title: String = "", progress: @escaping (Double) -> Void
    ) async throws {
        guard let K = Kernels.shared else { throw ReelError("Video effects could not start on this phone") }
        let n = shots.count
        guard n > 0, shots.allSatisfy({ $0.media != nil }) else { throw ReelError("Nothing to render") }

        // shot lengths in frames; a clip shorter than its shot gives the rest to the next shot, so cuts stay on time
        var durF = shots.map { Int((($0.t1 - $0.t0) * 30).rounded()) }
        for i in 0..<n {
            let m = shots[i].media!
            if m.isImage { continue }
            let avail = Int(floor((m.dur - shots[i].src0) * 30)) - 1
            if durF[i] > avail && avail > 4 {
                let short = durF[i] - avail
                durF[i] = avail
                if i + 1 < n { durF[i + 1] += short }
            }
        }
        var startsF = [Int](repeating: 0, count: n)
        for i in 1..<max(n, 1) { startsF[i] = startsF[i - 1] + durF[i - 1] }
        let totalF = startsF[n - 1] + durF[n - 1]
        let total = Double(totalF) / 30
        func blend(_ k: Int) -> Bool { k > 0 && (shots[k].trans == .fade || shots[k].trans == .zoom) }
        var fadeF = [Int](repeating: 0, count: n)
        for k in 0..<n where blend(k) {
            let len = shots[k].trans == .fade ? style.fadeLen : style.zoomLen
            fadeF[k] = Int((len * 30).rounded()).clamped(3, max(3, durF[k] - 3))
        }
        func hitsFor(_ i: Int) -> [Hit] {
            let t0 = shots[i].t0, d = Double(durF[i]) / 30
            return a.hits.filter { $0.t >= t0 - 0.3 && $0.t < t0 + d }.map { Hit(t: $0.t - t0, s: $0.s) }
        }
        let fxs = (0..<n).map { i in
            ShotFx(style: style, shot: shots[i], isImage: shots[i].media!.isImage, dur: Double(durF[i]) / 30, hits: hitsFor(i), index: i)
        }
        let flashAt = (0..<n).filter { shots[$0].trans == .flash }.map { Double(startsF[$0]) / 30 }

        // writer
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        let writer = try AVAssetWriter(outputURL: out, fileType: .mp4)
        let bitrate = w >= 1080 ? 14_000_000 : 6_000_000
        let vIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: w,
            AVVideoHeightKey: h,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitrate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                AVVideoMaxKeyFrameIntervalKey: 60,
                AVVideoExpectedSourceFrameRateKey: 30,
            ] as [String: Any],
        ])
        vIn.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: vIn, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: w,
            kCVPixelBufferHeightKey as String: h,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ])
        let aIn = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 192_000,
        ])
        aIn.expectsMediaDataInRealTime = false
        writer.add(vIn)
        writer.add(aIn)

        // song part with a short fade-in and a 1.2 s fade-out
        let pcm = try await AudioDecoder.read(song, rate: 44100, channels: 2, float: false, start: a.start, length: total)
        var samples = pcm.shorts
        let totalAF = Int(total * 44100)
        if samples.count < totalAF * 2 { samples.append(contentsOf: [Int16](repeating: 0, count: totalAF * 2 - samples.count)) }
        let fadeIn = 0.06 * 44100, fadeOut = 1.2 * 44100
        for f in 0..<totalAF {
            var g = 1.0
            if Double(f) < fadeIn { g = Double(f) / fadeIn }
            let left = Double(totalAF - f)
            if left < fadeOut { g = min(g, max(0, left) / fadeOut) }
            if g < 1 {
                samples[2 * f] = Int16(Double(samples[2 * f]) * g)
                samples[2 * f + 1] = Int16(Double(samples[2 * f + 1]) * g)
            }
        }

        guard writer.startWriting() else { throw writer.error ?? ReelError("Could not start saving the video") }
        writer.startSession(atSourceTime: .zero)

        // audio is fed on its own queue whenever the writer wants more
        let audioDone = AudioFeeder(input: aIn, samples: samples, frames: totalAF)
        audioDone.start()

        let ctx = CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull(), .cacheIntermediates: false])
        let R = CGRect(x: 0, y: 0, width: w, height: h)
        let size = CIVector(x: CGFloat(w), y: CGFloat(h))
        let rng = Rng(7)
        let titleImg = ReelOverlays.title(title, look: style.look, w: w, h: h)
        var stampSec = -1
        var stampImg: CIImage? = nil
        var readers: [Int: ClipReader] = [:]
        var photos: [ObjectIdentifier: CIImage] = [:]
        let look = style.look

        func source(_ i: Int, _ clipT: Double) async throws -> CIImage {
            let m = shots[i].media!
            if m.isImage {
                if let p = photos[ObjectIdentifier(m)] { return p }
                guard let img = Pictures.load(m.url, maxSide: Int(Double(max(w, h)) * 1.3)) else { throw ReelError("A photo could not be read") }
                let c = Pictures.cover(img, w, h)
                photos[ObjectIdentifier(m)] = c
                return c
            }
            if readers[i] == nil {
                let tail = i + 1 < n ? Double(fadeF[i + 1]) / 30 + 0.2 : 0.1
                readers[i] = try await ClipReader.open(m.url, from: shots[i].src0, length: Double(durF[i]) / 30 + tail)
            }
            guard let f = readers[i]!.frame(at: clipT) else { return CIImage(color: .black).cropped(to: R) }
            return Pictures.cover(f, w, h)
        }

        func drawn(_ img: CIImage, _ fx: ShotFx, _ t: Double) -> CIImage {
            let p = fx.params(t)
            return K.look.apply(extent: R, roiCallback: { _, _ in R }, arguments: [
                img, size, Float(p.zoom), CIVector(x: CGFloat(p.offX), y: CGFloat(p.offY)), Float(p.blur), Float(p.glitch), Float(p.seed),
                look.sepia, look.contrast, look.saturation, look.brightness, Float(look.mono ? 1 : 0),
            ]) ?? img
        }

        func rgbV(_ c: UInt32) -> CIVector {
            CIVector(x: CGFloat((c >> 16) & 255) / 255, y: CGFloat((c >> 8) & 255) / 255, z: CGFloat(c & 255) / 255)
        }

        do {
            var k = 0
            for f in 0..<totalF {
                try Task.checkCancellation()
                if writer.status == .failed { throw writer.error ?? ReelError("Saving the video failed") }
                while f >= startsF[k] + durF[k] && k + 1 < n { k += 1 }
                // readers no longer needed
                for key in readers.keys where key < k - 1 || (key == k - 1 && f - startsF[k] >= fadeF[k]) {
                    readers[key]?.close(); readers[key] = nil
                }
                let local = Double(f - startsF[k]) / 30
                var img = drawn(try await source(k, shots[k].src0 + local), fxs[k], local)
                if blend(k) && f - startsF[k] < fadeF[k] {
                    // the previous shot carries on underneath while this one fades in
                    var tf = fxs[k - 1]
                    tf.tail = true
                    tf.zoomTail = shots[k].trans == .zoom
                    let tt = fxs[k - 1].dur + local
                    let lower = drawn(try await source(k - 1, shots[k - 1].src0 + tt), tf, tt)
                    let alpha = ease(Double(f - startsF[k]) / Double(fadeF[k]))
                    img = K.mix.apply(extent: R, arguments: [lower, img, Float(alpha)]) ?? img
                }
                let tt = Double(f) / 30
                var flash = 0.0
                for fa in flashAt { let l = tt - fa; if l >= 0 && l < 0.14 { flash = 0.75 * (1 - l / 0.14) } }
                let lx = Double(w) * (0.15 + 0.7 * (0.5 + 0.5 * sin(tt * 0.6)))
                let ly = Double(h) * (0.25 + 0.15 * sin(tt * 0.9))
                let leakA = look.leak ? 0.28 + 0.12 * sin(tt * 1.7) : 0
                img = K.finish.apply(extent: R, roiCallback: { _, _ in R }, arguments: [
                    img, size, rgbV(look.tint), look.tintAmount, Float(look.tintMode), rgbV(look.gradTop), rgbV(look.gradBottom),
                    look.gradAmount, look.lift, CIVector(x: CGFloat(lx), y: CGFloat(ly)), Float(leakA), look.vignette, look.grain,
                    rng.nextFloat() * 1000, look.scan, Float(flash), Float(look.bars ? 1 : 0),
                ]) ?? img
                if look.stamp {
                    let s = Int(tt)
                    if s != stampSec { stampSec = s; stampImg = ReelOverlays.stamp(second: s, w: w, h: h) }
                    if let st = stampImg { img = st.composited(over: img) }
                }
                if let ti = titleImg {
                    let al = ReelOverlays.titleAlpha(tt)
                    if al > 0.001 {
                        img = ti.applyingFilter("CIColorMatrix", parameters: ["inputAVector": CIVector(x: 0, y: 0, z: 0, w: CGFloat(al))])
                            .composited(over: img)
                    }
                }
                if total - tt < 0.5 {
                    let dark = (1 - (total - tt) / 0.5).clamped(0, 1)
                    img = K.dim.apply(extent: R, arguments: [img, Float(1 - dark)]) ?? img
                }

                while !vIn.isReadyForMoreMediaData {
                    if writer.status == .failed { throw writer.error ?? ReelError("Saving the video failed") }
                    try await Task.sleep(nanoseconds: 2_000_000)
                }
                guard let pool = adaptor.pixelBufferPool else { throw ReelError("Could not start saving the video") }
                var pbOut: CVPixelBuffer?
                CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pbOut)
                guard let pb = pbOut else { throw ReelError("Out of memory while saving the video") }
                ctx.render(img.cropped(to: R), to: pb, bounds: R, colorSpace: nil)
                if !adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(f), timescale: 30)) {
                    throw writer.error ?? ReelError("Saving the video failed")
                }
                if f % 10 == 0 { progress(Double(f) / Double(totalF)) }
            }
            readers.values.forEach { $0.close() }
            vIn.markAsFinished()
            try await audioDone.wait()
            await writer.finishWriting()
            if writer.status != .completed { throw writer.error ?? ReelError("Saving the video failed") }
            progress(1)
        } catch {
            readers.values.forEach { $0.close() }
            audioDone.stop()
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: out)
            throw error
        }
    }
}

/// Feeds the song (16-bit stereo, 44.1 kHz) to the writer in 1024-frame pieces.
final class AudioFeeder {
    private let input: AVAssetWriterInput
    private let samples: [Int16]
    private let frames: Int
    private var pos = 0
    private let queue = DispatchQueue(label: "reelcut.audio")
    private var finished = false
    private var stopped = false
    private var waiter: CheckedContinuation<Void, Error>?
    private let lock = NSLock()
    private var format: CMAudioFormatDescription?

    init(input: AVAssetWriterInput, samples: [Int16], frames: Int) {
        self.input = input; self.samples = samples; self.frames = frames
        var asbd = AudioStreamBasicDescription(
            mSampleRate: 44100, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
            mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4, mChannelsPerFrame: 2, mBitsPerChannel: 16, mReserved: 0)
        CMAudioFormatDescriptionCreate(allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil, magicCookieSize: 0,
                                       magicCookie: nil, extensions: nil, formatDescriptionOut: &format)
    }

    func start() {
        input.requestMediaDataWhenReady(on: queue) { [self] in
            while input.isReadyForMoreMediaData && !stopped {
                if pos >= frames { finish(nil); return }
                let count = min(1024, frames - pos)
                guard let sb = buffer(pos, count), input.append(sb) else { finish(ReelError("Saving the sound failed")); return }
                pos += count
            }
        }
    }

    private func buffer(_ at: Int, _ count: Int) -> CMSampleBuffer? {
        guard let fmt = format else { return nil }
        let bytes = count * 4
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: bytes, blockAllocator: nil,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: bytes, flags: 0,
                                                 blockBufferOut: &block) == noErr, let bb = block else { return nil }
        samples.withUnsafeBytes { raw in
            _ = CMBlockBufferReplaceDataBytes(with: raw.baseAddress! + at * 4, blockBuffer: bb, offsetIntoDestination: 0, dataLength: bytes)
        }
        var sb: CMSampleBuffer?
        CMAudioSampleBufferCreateReadyWithPacketDescriptions(allocator: nil, dataBuffer: bb, formatDescription: fmt, sampleCount: count,
                                                            presentationTimeStamp: CMTime(value: CMTimeValue(at), timescale: 44100),
                                                            packetDescriptions: nil, sampleBufferOut: &sb)
        return sb
    }

    private var error: Error?

    private func finish(_ e: Error?) {
        lock.lock()
        if finished { lock.unlock(); return }
        finished = true
        error = e
        if e == nil { input.markAsFinished() }
        let w = waiter; waiter = nil
        lock.unlock()
        if let w { if let e { w.resume(throwing: e) } else { w.resume() } }
    }

    func stop() { lock.lock(); stopped = true; lock.unlock() }

    func wait() async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            lock.lock()
            if finished {
                let e = error
                lock.unlock()
                if let e { c.resume(throwing: e) } else { c.resume() }
            } else {
                waiter = c
                lock.unlock()
            }
        }
    }
}
