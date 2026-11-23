// Core Image kernels (built with -fcikernel): the same pictures as the Android app's GL shaders
// (LookEffect.kt per clip, FinishEffect.kt over the whole reel). Coordinates: 0,0 = bottom left, like GL.
#include <CoreImage/CoreImage.h>

using namespace metal;

extern "C" { namespace coreimage {

static float3 samp(sampler src, float2 uv, float2 size) {
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return float3(0.0);
    return src.sample(src.transform(uv * size)).rgb;
}

static float h1(float x) { return fract(sin(x) * 43758.5453); }

/// Per clip: zoom / pan / shake / whip blur / glitch slices, then the look's colour filter
/// (sepia, contrast, saturation, mono, brightness - the web page's order).
float4 rcLook(sampler src, float2 size, float zoom, float2 offset, float blur, float glitch, float seed,
              float sepiaA, float contrast, float sat, float bright, float mono, destination dest) {
    float2 v = dest.coord() / size;
    float2 uv = (v - 0.5) / zoom + 0.5 - offset;
    float3 c = samp(src, uv, size);
    if (blur > 0.0001) {
        c = (c + samp(src, uv + float2(blur, 0.0), size) + samp(src, uv - float2(blur, 0.0), size)
               + samp(src, uv + float2(2.0 * blur, 0.0), size) + samp(src, uv - float2(2.0 * blur, 0.0), size)) / 5.0;
    }
    if (glitch > 0.5) {
        float shift = 0.0;
        for (int k = 0; k < 7; k++) {
            float fk = float(k);
            float y0 = h1(seed * 1.31 + fk * 3.17);
            float hh = 0.02 + h1(seed * 0.71 + fk * 5.73) * 0.08;
            float dx = (h1(seed * 0.37 + fk * 9.31) - 0.5) * 0.12;
            if (v.y >= y0 && v.y < y0 + hh) shift = dx;
        }
        c = samp(src, uv - float2(shift, 0.0), size);
        float3 g = samp(src, uv - float2(0.012, 0.0), size) * 0.35;
        c = 1.0 - (1.0 - c) * (1.0 - g);
    }
    float3 sep = float3(dot(c, float3(0.393, 0.769, 0.189)), dot(c, float3(0.349, 0.686, 0.168)), dot(c, float3(0.272, 0.534, 0.131)));
    c = mix(c, clamp(sep, 0.0, 1.0), sepiaA);
    c = clamp((c - 0.5) * contrast + 0.5, 0.0, 1.0);
    float l = dot(c, float3(0.299, 0.587, 0.114));
    c = clamp(mix(float3(l), c, sat), 0.0, 1.0);
    c = mix(c, float3(dot(c, float3(0.299, 0.587, 0.114))), mono);
    c = clamp(c * bright, 0.0, 1.0);
    return float4(c, 1.0);
}

static float3 softLight(float3 b, float3 s) {
    float3 lo = 2.0 * b * s + b * b * (1.0 - 2.0 * s);
    float3 hi = sqrt(b) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s);
    return mix(lo, hi, step(0.5, s));
}
static float3 overlayB(float3 b, float3 s) { return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b)); }
static float3 screenB(float3 b, float3 s) { return 1.0 - (1.0 - b) * (1.0 - s); }

/// Over the whole reel: tint, colour wash, faded blacks, light leak, vignette, grain, TV lines, flash, bars.
float4 rcFinish(sampler src, float2 size, float3 tint, float tintAmt, float tintMode, float3 gradTop, float3 gradBot,
                float gradAmt, float lift, float2 leakPos, float leakA, float vig, float grain, float seed, float scan,
                float flash, float bars, destination dest) {
    float2 dc = dest.coord();
    float3 c = src.sample(src.transform(dc)).rgb;
    float2 v = dc / size;
    float2 px = float2(dc.x, size.y - dc.y);     // pixels from the top left
    float L = max(size.x, size.y);

    float3 tinted = tintMode < 0.5 ? softLight(c, tint) : (tintMode < 1.5 ? screenB(c, tint) : overlayB(c, tint));
    c = mix(c, tinted, tintAmt);
    float3 g = mix(gradTop, gradBot, px.y / size.y);
    c = mix(c, softLight(c, g), gradAmt);
    c = max(c, float3(lift));
    float r = distance(px, leakPos) / (0.45 * L);
    float4 lk = r < 0.45 ? mix(float4(1.0, 0.667, 0.353, 0.9), float4(1.0, 0.353, 0.235, 0.35), r / 0.45)
                         : mix(float4(1.0, 0.353, 0.235, 0.35), float4(1.0, 0.235, 0.235, 0.0), clamp((r - 0.45) / 0.55, 0.0, 1.0));
    c = screenB(c, lk.rgb * lk.a * leakA);
    float d = distance(px, size * 0.5) / L;
    c *= 1.0 - vig * clamp((d - 0.28) / 0.44, 0.0, 1.0);
    float2 gp = floor(px / 1.5);
    float n = fract(sin(dot(gp, float2(12.9898, 78.233)) + seed) * 43758.5453);
    c = mix(c, overlayB(c, float3(n)), grain);
    float period = max(2.0, floor(size.y / 320.0 + 0.5));
    c *= 1.0 - scan * step(fmod(px.y, period), period * 0.5 - 0.01);
    c = mix(c, float3(1.0), flash);
    float bar = clamp((1.0 - step(0.07, v.y)) + step(0.93, v.y), 0.0, 1.0);
    c *= 1.0 - bars * bar;
    return float4(clamp(c, 0.0, 1.0), 1.0);
}

/// Dissolve: b over a by t (0 = a, 1 = b).
float4 rcMix(sample_t a, sample_t b, float t) { return float4(mix(a.rgb, b.rgb, t), 1.0); }

/// Multiplies the picture by k (fade to black at the end).
float4 rcDim(sample_t a, float k) { return float4(a.rgb * k, 1.0); }

}}
