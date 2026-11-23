import AVFoundation

/// Decodes any song file the phone can play into samples (Core Audio converts the rate and channels).
enum AudioDecoder {

    /// Mono 32-bit float at the given rate, at most maxSeconds.
    static func decodeMono(_ url: URL, rate: Int, maxSeconds: Double) async throws -> [Float] {
        let pcm = try await read(url, rate: rate, channels: 1, float: true, start: 0, length: maxSeconds)
        return pcm.floats
    }

    struct PCM {
        var floats: [Float] = []
        var shorts: [Int16] = []
    }

    /// Reads [start, start + length] seconds of the file's sound as interleaved PCM.
    static func read(_ url: URL, rate: Int, channels: Int, float: Bool, start: Double, length: Double) async throws -> PCM {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
            throw ReelError("This song file has no sound track")
        }
        let reader = try AVAssetReader(asset: asset)
        reader.timeRange = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 44100),
                                       duration: CMTime(seconds: length, preferredTimescale: 44100))
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: rate,
            AVNumberOfChannelsKey: channels,
            AVLinearPCMBitDepthKey: float ? 32 : 16,
            AVLinearPCMIsFloatKey: float,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
        out.alwaysCopiesSampleData = false
        reader.add(out)
        guard reader.startReading() else { throw reader.error ?? ReelError("Could not read this song") }
        var pcm = PCM()
        let maxCount = Int(length * Double(rate)) * channels
        while let sb = out.copyNextSampleBuffer() {
            guard let bb = CMSampleBufferGetDataBuffer(sb) else { continue }
            let len = CMBlockBufferGetDataLength(bb)
            if float {
                var chunk = [Float](repeating: 0, count: len / 4)
                chunk.withUnsafeMutableBytes { _ = CMBlockBufferCopyDataBytes(bb, atOffset: 0, dataLength: len, destination: $0.baseAddress!) }
                pcm.floats.append(contentsOf: chunk)
                if pcm.floats.count >= maxCount { break }
            } else {
                var chunk = [Int16](repeating: 0, count: len / 2)
                chunk.withUnsafeMutableBytes { _ = CMBlockBufferCopyDataBytes(bb, atOffset: 0, dataLength: len, destination: $0.baseAddress!) }
                pcm.shorts.append(contentsOf: chunk)
                if pcm.shorts.count >= maxCount { break }
            }
        }
        reader.cancelReading()
        if pcm.floats.count > maxCount { pcm.floats.removeLast(pcm.floats.count - maxCount) }
        if pcm.shorts.count > maxCount { pcm.shorts.removeLast(pcm.shorts.count - maxCount) }
        return pcm
    }
}
