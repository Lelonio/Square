// Ported line for line from liquid_glass_widgets' shaders, MIT:
//
//   shaders/sdf.glsl
//   shaders/displacement_encoding.glsl
//   shaders/render.glsl
//   shaders/liquid_glass_geometry_blended.frag
//   shaders/liquid_glass_render.frag
//   https://github.com/sdegenaar/liquid_glass_widgets (commit 59118973)
//
//   Copyright 2025, Tim Lehmann for whynotmake.it
//   Copyright 2026, Sebastian Degenaar for pixel-innovations.com (liquid_glass_widgets)
//
// See LICENSE-liquid-glass.txt beside this file.
//
// What differs from upstream, and why:
//
// - GLSL to AGSL: `vec` is `float`, `texture()` is a child shader's `eval()`
//   at pixel coordinates, uniforms carry no layout slots.
// - The geometry pass runs inline. Upstream renders it into a texture the
//   render pass reads at the same pixel; here the render pass evaluates it at
//   that pixel itself. The numbers are the same, without the texture's 8 bits
//   per channel.
// - sceneSDF's unrolled folds are loops: the same left folds, forwards and
//   backwards, averaged. Upstream unrolled them for glslang on Windows only.
// - The backdrop is two pictures, sharp and blurred, combined where the blur
//   pass would have been clipped: upstream blurs the backdrop inside the
//   union of the shapes' paths before this shader reads it.
// - Not carried over: the frost passes and the platform-view modes, which the
//   settings this app uses leave off (frost 0, fallback colour off).

package dev.lelonio.square.ui.glass.morph

internal object LiquidGlassShader {

    const val MAX_SHAPES = 4
    const val SHAPE_STRIDE = 7

    /**
     * The same shader for a surface whose backdrop arrives already blurred,
     * as the one input of a RenderEffect: the bar, the mini player, a button.
     * Upstream's blur pass is clipped to the shape; here the whole recorded
     * layer is the surface and its edge, so the blurred backdrop stands for
     * both sides of the outline.
     */
    val SOURCE_SINGLE: String by lazy {
        val single = SOURCE
            .replace(
                "uniform shader sharpBackdrop;\nuniform shader blurredBackdrop;",
                "uniform shader content;",
            )
            .replace(
                Regex("float4 backdropWithClip\\(float2 p, float clip\\) \\{[^}]*\\}"),
                "float4 backdropWithClip(float2 p, float clip) { return float4(content.eval(p)); }",
            )
            // The page under the whole layer is one picture here, so there is
            // no clip to look up: the coverage tests and their four extra
            // scene evaluations per sample were dead weight that Android 14's
            // compiler still counted towards its program size limit ("program
            // is too large", which left the glass off on those phones).
            .replace(
                Regex("float4 backdropAt\\(float2 p\\) \\{[^}]*\\}"),
                "float4 backdropAt(float2 p) { return float4(content.eval(p)); }",
            )
        replaceBetween(
            single,
            "float4 textureBilinear(float2 uv, float2 size, float2 invSize) {",
            "// Nearest texel at pixel [p] (whole pixels) as straight RGB.",
            TEXTURE_BILINEAR_SINGLE,
        )
            // Every shape this program is given is a rounded rectangle (the
            // bars, the buttons, the groups, the menus' blobs), so the
            // squircle and the ellipse were never drawn, only counted.
            .replace(
                Regex("float getShapeSDF\\(float type, float2 p, float2 center, float2 size, float rTop, float rBottom\\) \\{[^}]*\\}"),
                "float getShapeSDF(float type, float2 p, float2 center, float2 size, float rTop, float rBottom) { return sdfRRectAsym(p - center, size / 2.0, rTop, rBottom); }",
            )
    }

    /**
     * [SOURCE_SINGLE] for one surface on its own, the shape the material is
     * given everywhere but a blend group: the scene is that one shape, not a
     * blend of four, which is most of what the program does five times a
     * pixel.
     */
    val SOURCE_SINGLE_ONE: String by lazy {
        SOURCE_SINGLE
            .replace(
                Regex("float sceneSDF\\(float2 p, float k\\) \\{[^}]*\\}"),
                "float sceneSDF(float2 p, float k) { return sdf0(p); }",
            )
            .replace(
                Regex("float clipSDF\\(float2 p\\) \\{[^}]*\\}"),
                "float clipSDF(float2 p) { return sdf0(p); }",
            )
    }

    /** Between [from] and [to], exclusive of [to], [with] in place. */
    private fun replaceBetween(text: String, from: String, to: String, with: String): String {
        val a = text.indexOf(from)
        val b = text.indexOf(to, a)
        require(a >= 0 && b > a) { "markers not found" }
        return text.substring(0, a) + with + text.substring(b)
    }

    /** The bilinear sample, over one picture: four taps and no coverage. */
    private const val TEXTURE_BILINEAR_SINGLE = """
float4 textureBilinear(float2 uv, float2 size, float2 invSize) {
    float2 px = uv * size - 0.5;
    float2 f = fract(px);
    float2 p0 = floor(px);
    float4 c0 = float4(content.eval(p0 + 0.5));
    float4 c1 = float4(content.eval(p0 + float2(1.5, 0.5)));
    float4 c2 = float4(content.eval(p0 + float2(0.5, 1.5)));
    float4 c3 = float4(content.eval(p0 + 1.5));
    return mix(mix(c0, c1, f.x), mix(c2, c3, f.x), f.y);
}

"""

    /**
     * Upstream's frost rows: what the frost pass leaves in the backdrop the
     * render pass reads. [uRowsCloud] 1 keeps the cloud on the odd rows
     * inside the shapes' outlines; 0 keeps the sharp backdrop everywhere
     * else. The two are added together, which is upstream's frost pass
     * clipped to the shapes and to alternate rows.
     */
    val SOURCE_ROWS: String by lazy {
        SOURCE_SINGLE.substringBefore("half4 main(float2 fragCoord) {") + """
uniform float uRowsCloud;

half4 main(float2 fragCoord) {
    float inside = clamp(0.5 - clipSDF(fragCoord), 0.0, 1.0);
    float cloud = inside * mod(floor(fragCoord.y), 2.0);
    float keep = uRowsCloud > 0.5 ? cloud : 1.0 - cloud;
    return content.eval(fragCoord) * half(keep);
}
"""
    }

    const val SOURCE = """
uniform shader sharpBackdrop;
uniform shader blurredBackdrop;

uniform float2 uSize;
uniform float2 uGeometryOffset;
uniform float2 uGeometrySize;
uniform float4 uGlassColor;
uniform float4 uOpticalProps;
uniform float3 uLightConfig;
uniform float2 uLightDirection;
uniform float uWhiten;
uniform float uWhitenGated;
uniform float uPinchStrength;
uniform float4 uEdgeConfig;
uniform float uBodyMode;
uniform float2 uTouchPosition;
uniform float uTouchIntensity;
uniform float3 uRimConfig;
uniform float uLensModel;
uniform float uBodyShade;
// x: frostOpacity (0 = no frost), y: frostClamp, z: ghost blur sigma in
// physical px, w: blurWeight. Under a frost the backdrop this pass reads
// holds the frost's cloud on odd rows inside the shapes and the sharp
// backdrop everywhere else: see frostAt() and liquidGlassMaterial().
uniform float4 uFrost;

uniform float uNumShapes;
uniform float uDpr;
uniform float uBlend;
uniform float uNativeEdge;
uniform float uShapeData[28];

uniform float uOpacity;
// Light-mode shadows (upstream: GlassShadow.defaults, drawn from the matte).
// x: alpha, y: sigma (px), z: offset x (px), w: offset y (px).
uniform float4 uShadowA;
uniform float4 uShadowB;

const float3 LUMA_WEIGHTS = float3(0.2126, 0.7152, 0.0722);

// ── sdf.glsl ────────────────────────────────────────────────────────────────

float sdfRRect(float2 p, float2 b, float r) {
    float shortest = min(b.x, b.y);
    r = min(r, shortest);
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float sdfRRectAsym(float2 p, float2 b, float rTop, float rBottom) {
    // Equal radii give dT == dB, and mix() returns it: evaluated once.
    if (rTop == rBottom) return sdfRRect(p, b, rTop);
    float dT = sdfRRect(p, b, rTop);
    float dB = sdfRRect(p, b, rBottom);
    float t = sstep(-2.0, 2.0, p.y);
    return mix(dT, dB, t);
}

float fastPow(float x, float n) {
    return exp2(n * log2(max(x, 1e-4)));
}

float squircleShape(float2 p, float2 b, float zone, float n) {
    float2 q = abs(p) - b + zone;
    float2 m = max(q, 0.0);
    float M = max(m.x, m.y);
    float corner = 0.0;
    if (M > 1e-4) {
        float2 mNorm = m / M;
        corner = M * fastPow(fastPow(mNorm.x, n) + fastPow(mNorm.y, n), 1.0 / n);
    }
    return min(max(q.x, q.y), 0.0) + corner - zone;
}

float sdfSquircle(float2 p, float2 b, float r) {
    float boxShort = min(b.x, b.y);
    r = min(r, boxShort);
    float zone = min(r * 1.528, boxShort);
    float base = 1.0 - 0.29289322 * (r / max(zone, 1e-4));
    float n = -1.0 / log2(clamp(base, 0.5, 0.9999));
    return squircleShape(p, b, zone, n);
}

float sdfSquircleAsym(float2 p, float2 b, float rTop, float rBottom) {
    if (rTop == rBottom) {
        return sdfSquircle(p, b, rTop);
    }
    float boxShort = min(b.x, b.y);
    rTop = min(rTop, boxShort);
    rBottom = min(rBottom, boxShort);

    float zoneT = min(rTop * 1.528, boxShort);
    float baseT = 1.0 - 0.29289322 * (rTop / max(zoneT, 1e-4));
    float nT = -1.0 / log2(clamp(baseT, 0.5, 0.9999));
    float dT = squircleShape(p, b, zoneT, nT);

    float zoneB = min(rBottom * 1.528, boxShort);
    float baseB = 1.0 - 0.29289322 * (rBottom / max(zoneB, 1e-4));
    float nB = -1.0 / log2(clamp(baseB, 0.5, 0.9999));
    float dB = squircleShape(p, b, zoneB, nB);

    float t = sstep(-2.0, 2.0, p.y);
    return mix(dT, dB, t);
}

float sdfEllipse(float2 p, float2 r) {
    r = max(r, 1e-4);
    float2 invR = 1.0 / r;
    float2 invR2 = invR * invR;
    float2 pInvR = p * invR;
    float k1 = length(pInvR);
    float2 pInvR2 = p * invR2;
    float k2 = length(pInvR2);
    return (k1 * (k1 - 1.0)) / max(k2, 1e-4);
}

float smoothUnion(float d1, float d2, float k) {
    float e = max(k - abs(d1 - d2), 0.0);
    return min(d1, d2) - e * e * 0.25 / max(k, 1e-5);
}

float getShapeSDF(float type, float2 p, float2 center, float2 size, float rTop, float rBottom) {
    if (type == 1.0) return sdfSquircleAsym(p - center, size / 2.0, rTop, rBottom);
    else if (type == 2.0) return sdfEllipse(p - center, size / 2.0);
    else if (type == 3.0) return sdfRRectAsym(p - center, size / 2.0, rTop, rBottom);
    return 0.0;
}

// Upstream's sceneSDF, unrolled as upstream unrolls it (literal indices
// only), up to the four shapes this app ever groups: forward and backward
// left folds of smoothUnion, averaged.
float sdf0(float2 p) { return getShapeSDF(uShapeData[0], p, float2(uShapeData[1], uShapeData[2]), float2(uShapeData[3], uShapeData[4]), uShapeData[5], uShapeData[6]); }
float sdf1(float2 p) { return getShapeSDF(uShapeData[7], p, float2(uShapeData[8], uShapeData[9]), float2(uShapeData[10], uShapeData[11]), uShapeData[12], uShapeData[13]); }
float sdf2(float2 p) { return getShapeSDF(uShapeData[14], p, float2(uShapeData[15], uShapeData[16]), float2(uShapeData[17], uShapeData[18]), uShapeData[19], uShapeData[20]); }
float sdf3(float2 p) { return getShapeSDF(uShapeData[21], p, float2(uShapeData[22], uShapeData[23]), float2(uShapeData[24], uShapeData[25]), uShapeData[26], uShapeData[27]); }

float sceneSDF(float2 p, float k) {
    if (uNumShapes < 0.5) return 1e9;
    float s0 = sdf0(p);
    if (uNumShapes < 1.5) return s0;
    float s1 = sdf1(p);
    float fwd = smoothUnion(s0, s1, k);
    float bwd = smoothUnion(s1, s0, k);
    if (uNumShapes < 2.5) return mix(fwd, bwd, 0.5);
    float s2 = sdf2(p);
    fwd = smoothUnion(fwd, s2, k);
    float b3a = smoothUnion(s2, s1, k);
    bwd = smoothUnion(b3a, s0, k);
    if (uNumShapes < 3.5) return mix(fwd, bwd, 0.5);
    float s3 = sdf3(p);
    fwd = smoothUnion(fwd, s3, k);
    float b4a = smoothUnion(s3, s2, k);
    float b4b = smoothUnion(b4a, s1, k);
    bwd = smoothUnion(b4b, s0, k);
    return mix(fwd, bwd, 0.5);
}

// The union of the shapes' own outlines, without the blend: the path the
// upstream blur pass is clipped to.
float clipSDF(float2 p) {
    float d = 1e9;
    if (uNumShapes > 0.5) d = sdf0(p);
    if (uNumShapes > 1.5) d = min(d, sdf1(p));
    if (uNumShapes > 2.5) d = min(d, sdf2(p));
    if (uNumShapes > 3.5) d = min(d, sdf3(p));
    return d;
}

// A Gaussian of the given sigma, run over the shape's coverage, at signed
// distance d: the same as upstream's blurred matte, in closed form. Exact
// along a straight edge; at these sigmas, which are a few pixels against
// corners tens of pixels round, the corners agree too.
float erfcApprox(float x) {
    float z = abs(x);
    float t = 1.0 / (1.0 + 0.5 * z);
    float r = t * exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
            + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
            + t * (-0.82215223 + t * 0.17087277)))))))));
    return x >= 0.0 ? r : 2.0 - r;
}

float shadowAt(float2 p, float4 shadow) {
    if (shadow.x <= 0.0) return 0.0;
    float d = sceneSDF(p - shadow.zw, uBlend);
    float coverage = 0.5 * erfcApprox(d / (max(shadow.y, 0.001) * 1.41421356));
    return shadow.x * coverage;
}

// ── render.glsl ─────────────────────────────────────────────────────────────

float3 getHighlightColor(float3 backgroundColor, float targetBrightness) {
    float luminance = dot(backgroundColor, LUMA_WEIGHTS);
    float maxComponent = max(max(backgroundColor.r, backgroundColor.g), backgroundColor.b);
    float lum = luminance * 2.5;
    float lumFactor = lum / (1.0 + lum);
    float sat = maxComponent * 2.5;
    float satFactor = sat / (1.0 + sat);
    float colorInfluence = lumFactor * satFactor;
    float3 tinted = (backgroundColor / max(luminance, 0.001)) * targetBrightness;
    return mix(float3(targetBrightness), tinted, colorInfluence);
}

float3 applySaturation(float3 color, float saturation) {
    float luminance = dot(color, LUMA_WEIGHTS);
    float3 saturatedColor = mix(float3(luminance), color, saturation);
    return clamp(saturatedColor, 0.0, 1.0);
}

float4 applyGlassColor(float4 liquidColor, float4 glassColor, float bodyMode) {
    float3 directMix = mix(liquidColor.rgb, glassColor.rgb, glassColor.a);
    if (bodyMode > 0.5) {
        return float4(directMix, liquidColor.a);
    }
    float backdropLuminance = dot(liquidColor.rgb, LUMA_WEIGHTS);
    float glassLuminance = dot(glassColor.rgb, LUMA_WEIGHTS);
    float3 tinted = clamp(glassColor.rgb + (backdropLuminance - glassLuminance), 0.0, 1.0);
    float chroma = max(max(glassColor.r, glassColor.g), glassColor.b)
                 - min(min(glassColor.r, glassColor.g), glassColor.b);
    float chromaWeight = clamp(chroma * 8.0, 0.0, 1.0);
    float3 luminosityMix = mix(liquidColor.rgb, tinted, glassColor.a);
    return float4(mix(directMix, luminosityMix, chromaWeight), liquidColor.a);
}

// ── liquid_glass_render.frag ────────────────────────────────────────────────

// The backdrop as the render pass sees it: blurred inside the shapes'
// outlines, where the blur pass drew, and sharp outside them.
float4 backdropWithClip(float2 p, float clip) {
    if (clip >= 1.0) return float4(blurredBackdrop.eval(p));
    float4 sharp = float4(sharpBackdrop.eval(p));
    if (clip <= 0.0) return sharp;
    return mix(sharp, float4(blurredBackdrop.eval(p)), clip);
}

float4 backdropAt(float2 p) {
    return backdropWithClip(p, clamp(0.5 - clipSDF(p), 0.0, 1.0));
}

float4 textureBilinear(float2 uv, float2 size, float2 invSize) {
    float2 px = uv * size - 0.5;
    float2 f = fract(px);
    float2 p0 = floor(px);
    float2 p1 = p0 + float2(1.0, 0.0);
    float2 p2 = p0 + float2(0.0, 1.0);
    float2 p3 = p0 + float2(1.0, 1.0);

    // The four texels lie within 1.5 px of the footprint's centre and a
    // distance field changes by no more than the distance moved: where the
    // centre is 2 px inside the outline all four are wholly inside, and 2 px
    // outside all four wholly outside. Only near the outline is each texel's
    // own coverage needed.
    float centre = clipSDF(p0 + 1.0);
    float4 c0;
    float4 c1;
    float4 c2;
    float4 c3;
    if (centre < -2.0 || centre > 2.0) {
        float clip = centre < -2.0 ? 1.0 : 0.0;
        c0 = backdropWithClip(p0 + 0.5, clip);
        c1 = backdropWithClip(p1 + 0.5, clip);
        c2 = backdropWithClip(p2 + 0.5, clip);
        c3 = backdropWithClip(p3 + 0.5, clip);
    } else {
        c0 = backdropAt(p0 + 0.5);
        c1 = backdropAt(p1 + 0.5);
        c2 = backdropAt(p2 + 0.5);
        c3 = backdropAt(p3 + 0.5);
    }

    float4 cTop = mix(c0, c1, f.x);
    float4 cBot = mix(c2, c3, f.x);
    return mix(cTop, cBot, f.y);
}

// Nearest texel at pixel [p] (whole pixels) as straight RGB.
float3 texelAt(float2 p) {
    float4 c = backdropWithClip(p + 0.5, 1.0);
    return c.a > 0.001 ? c.rgb / c.a : c.rgb;
}

// The frost at [p], with the cloud read at [q] (kept a few px inside the
// shape, where the cloud rows are).
//
// Ghost: a Gaussian of uFrost.z px over the sharp (even) rows around p,
// each texel weighted by its luminance (uFrost.w, blurWeight).
// Cloud: the two odd rows either side of q, which hold the frost's blur.
// The ghost is held within frostClamp of the cloud on one side, then the
// cloud is laid over it at frostOpacity:
//   frost = op * cloud + (1 - op) * max(ghost, cloud - clamp)   (clamp > 0)
//   frost = op * cloud + (1 - op) * min(ghost, cloud - clamp)   (clamp < 0)
float3 frostAt(float2 p, float2 q) {
    float2 qb = floor(q);
    float ya = qb.y - mod(qb.y + 1.0, 2.0);
    float t = clamp((q.y - (ya + 0.5)) * 0.5, 0.0, 1.0);
    float3 cloud = mix(texelAt(float2(qb.x, ya)), texelAt(float2(qb.x, ya + 2.0)), t);
    if (uFrost.x >= 1.0) {
        return cloud;
    }

    float sigma = max(uFrost.z, 0.3);
    float2 base = floor(p);
    float cy = base.y - mod(base.y, 2.0);
    float inv2s2 = 1.0 / (2.0 * sigma * sigma);
    float slope = uFrost.w - 1.0;
    float3 acc = float3(0.0);
    float wsum = 0.0;
    // LOCAL CHANGE: always the six taps. Upstream takes them only at its
    // floor sigma and 45 above it; at the iOS 27 material's 1.6 px the 45
    // cost a third of the frames a scroll dropped on this phone, for a ghost
    // that is at most 15 % of what shows (frostOpacity) and held within the
    // clamp of the cloud. Six taps at the real sigma blur it a little less.
    for (int j = 0; j <= 1; j++) {
        float y = cy + 2.0 * float(j);
        float dy = y + 0.5 - p.y;
        float wy = exp(-dy * dy * inv2s2);
        for (int i = 3; i <= 5; i++) {
            float x = base.x + float(i - 4);
            float dx = x + 0.5 - p.x;
            float3 c = texelAt(float2(x, y));
            float w = wy * exp(-dx * dx * inv2s2) * (1.0 + slope * dot(c, LUMA_WEIGHTS));
            acc += w * c;
            wsum += w;
        }
    }
    float3 ghost = acc / max(wsum, 1e-5);

    float k = uFrost.y;
    float3 held = k > 0.0 ? max(ghost, cloud - k)
                : k < 0.0 ? min(ghost, cloud - k)
                : ghost;
    return mix(held, cloud, clamp(uFrost.x, 0.0, 1.0));
}

half4 main(float2 fragCoord) {
    float uRefractiveIndex = uOpticalProps.x;
    float uChromaticAberration = uOpticalProps.y;
    float uThickness = uOpticalProps.z;
    float uLightIntensity = uLightConfig.x;
    float uAmbientStrength = uLightConfig.y;
    float uSaturation = uLightConfig.z;

    // ── Shadows, under the glass (upstream: _drawShadows) ──
    // Where the glass is opaque the shadow is cut away (dstOut), so it is
    // only worked out where some of it can show.
    float sd = sceneSDF(fragCoord, uBlend);
    float shadowAlpha = 0.0;
    // And not where it rounds to nothing: past 2 sigma beyond the offset
    // edge the elevation shadow (0.0588 at most) is under half a level out
    // of 255, and the contact shadow, smaller and fainter, long before.
    float shadowEnd = 2.0 * uShadowA.y + abs(uShadowA.w);
    if ((uShadowA.x > 0.0 || uShadowB.x > 0.0) && sd > -3.0 * max(1.0, uDpr) && sd < shadowEnd) {
        shadowAlpha = shadowAt(fragCoord, uShadowA) + shadowAt(fragCoord, uShadowB);
    }

    // ── Geometry pass (liquid_glass_geometry_blended.frag), at this pixel ──
    float2 geometryUV = (fragCoord - uGeometryOffset) / uGeometrySize;
    if (geometryUV.x < 0.0 || geometryUV.y < 0.0 || geometryUV.x > 1.0 || geometryUV.y > 1.0) {
        return half4(0.0, 0.0, 0.0, half(shadowAlpha * uOpacity));
    }

    float gdx = 0.0;
    float gdy = 0.0;
    float sdN = sd;
    // Deep in the flat face — further in than the bevel and the
    // anti-aliasing reach — the normal is straight up whatever the gradient
    // is, and the field only grows when normalised (its gradient is at most
    // 1), so sdN is past both thresholds either way: the four taps below
    // change nothing there and are skipped.
    float faceDepth = max(uThickness, 1.5 * max(1.0, uDpr)) + 1.0;
    if (sd > -faceDepth) {
        float stepDist = uDpr / 3.0;
        float sdPX = sceneSDF(fragCoord + float2(stepDist, 0.0), uBlend);
        float sdMX = sceneSDF(fragCoord - float2(stepDist, 0.0), uBlend);
        float sdPY = sceneSDF(fragCoord + float2(0.0, stepDist), uBlend);
        float sdMY = sceneSDF(fragCoord - float2(0.0, stepDist), uBlend);
        gdx = (sdPX - sdMX) / (2.0 * stepDist);
        gdy = (sdPY - sdMY) / (2.0 * stepDist);
        float gradMag = sqrt(gdx * gdx + gdy * gdy);
        sdN = (gradMag > 0.1) ? sd / gradMag : sd;
    }

    float smoothing = 1.5 * max(1.0, uDpr);
    if (uNativeEdge > 0.5) {
        smoothing = max(1.0, 0.5 * uDpr);
        sdN -= max(1.0, uDpr / 3.0);
    }
    float foregroundAlpha = sstep(smoothing * 0.5, -smoothing * 0.5, sdN);
    // The shadow is cut out where the matte is (upstream: dstOut).
    shadowAlpha *= 1.0 - foregroundAlpha;
    if (foregroundAlpha < 0.01) {
        return half4(0.0, 0.0, 0.0, half(shadowAlpha * uOpacity));
    }
    if (uThickness <= 0.0) {
        return half4(0.0, 0.0, 0.0, half(shadowAlpha * uOpacity));
    }

    float n_cos = max(uThickness + sdN, 0.0) / uThickness;
    float n_sin = sqrt(max(0.0, 1.0 - n_cos * n_cos));
    float3 gNormal = normalize(float3(gdx * n_cos, gdy * n_cos, n_sin));

    float2 storedNormal;
    float storedHeight;
    if (sdN >= 0.0) {
        storedNormal = length(gNormal.xy) > 1e-4 ? gNormal.xy : float2(0.0);
        storedHeight = 0.0;
    } else {
        float x = uThickness + sdN;
        float sqrtTerm = sqrt(max(0.0, uThickness * uThickness - x * x));
        storedHeight = mix(sqrtTerm, uThickness, (sdN < -uThickness) ? 1.0 : 0.0);
        storedNormal = gNormal.xy;
    }
    // encodeGeometryData / decode, without the texture in between.
    float4 geometryData = float4(
        clamp(storedNormal * 0.5 + 0.5, 0.0, 1.0),
        uThickness > 0.0 ? clamp(storedHeight / uThickness, 0.0, 1.0) : 0.0,
        foregroundAlpha);

    // ── Render pass ──
    float2 physTexSize = uSize;
    float2 invTexSize = 1.0 / physTexSize;
    float2 screenUV = fragCoord * invTexSize;

    if (geometryData.a < 0.01) {
        return half4(0.0);
    }

    float2 normalXY = geometryData.rg * 2.0 - 1.0;
    float normalZSq = max(0.0, 1.0 - dot(normalXY, normalXY));
    float normalZ = sqrt(normalZSq);
    float3 normal = float3(normalXY, normalZ);

    float height = geometryData.b * uThickness;
    float baseHeight = uThickness * 8.0;
    float2 displacement;
    if (uLensModel > 0.5) {
        displacement = -normalXY * length(normalXY) * baseHeight * (uRefractiveIndex - 1.0);
    } else {
        float3 incident = float3(0.0, 0.0, -1.0);
        float invN = 1.0 / max(uRefractiveIndex, 0.001);
        float3 baseRefract = refract(incident, normal, invN);
        float refractLen = (height + baseHeight) / max(0.001, abs(baseRefract.z));
        displacement = baseRefract.xy * refractLen;
    }
    displacement *= uOpticalProps.w;

    float maxReach = 0.5 * min(uGeometrySize.x, uGeometrySize.y);
    float reach = length(displacement);
    if (reach > maxReach) {
        displacement *= maxReach / reach;
    }

    float normalizedHeight = geometryData.b;
    float cosTerm = sqrt(max(0.0, 1.0 - normalizedHeight * normalizedHeight));
    float rimDist = uThickness * (1.0 - cosTerm);
    float dprScale = max(0.1, uEdgeConfig.z);

    float2 lensDisplacement = displacement;
    float hairline = 0.0;
    if (uRimConfig.x > 0.001) {
        hairline = 1.0 - sstep(0.75 * dprScale, 2.0 * dprScale, rimDist);
        hairline *= 1.0 - step(0.999, normalizedHeight);
        float2 outward = normalXY / max(length(normalXY), 1e-4) * (2.5 * dprScale);
        displacement = mix(displacement, outward, hairline);
    }

    if (uPinchStrength > 0.001) {
        float2 centered = clamp(geometryUV, 0.0, 1.0) - float2(0.5);
        float2 absCentered = abs(centered) * 2.0;
        float x2 = absCentered.x * absCentered.x;
        float ax4 = x2 * x2;
        float y2 = absCentered.y * absCentered.y;
        float ay4 = y2 * y2;
        float s = ax4 + ay4;
        float squircleDist = sqrt(sqrt(s));
        float pinchRamp = sstep(0.0, 1.0, squircleDist);
        float2 pinchShift = centered * pinchRamp * uPinchStrength * 0.025;
        pinchShift *= geometryData.a;
        screenUV += pinchShift;
        screenUV = clamp(screenUV, float2(0.001), float2(0.999));
    }

    float4 refractColor;
    if (uFrost.x > 0.0 && hairline <= 0.0) {
        // Frosted body: the frost below replaces the colour, and only
        // whether a backdrop was captured here is kept.
        refractColor = float4(0.0, 0.0, 0.0, backdropWithClip(screenUV * physTexSize, 1.0).a);
    } else if (dot(normalXY, normalXY) < 1e-4) {
        refractColor = textureBilinear(screenUV, physTexSize, invTexSize);
    } else if (uChromaticAberration < 0.01) {
        float2 refractedUV = screenUV + displacement * invTexSize;
        refractColor = textureBilinear(refractedUV, physTexSize, invTexSize);
        if (hairline > 0.0) {
            float2 tangent = float2(-normalXY.y, normalXY.x)
                           / max(length(normalXY), 1e-4)
                           * (1.5 * dprScale) * invTexSize;
            float4 side = textureBilinear(refractedUV + tangent, physTexSize, invTexSize)
                        + textureBilinear(refractedUV - tangent, physTexSize, invTexSize);
            refractColor = mix(refractColor, (refractColor + side) / 3.0, hairline);
        }

    } else {
        float dispersionStrength = uChromaticAberration * 0.5;
        float2 redOffset = displacement * (1.0 + dispersionStrength);
        float2 blueOffset = displacement * (1.0 - dispersionStrength);
        float2 redUV = screenUV + redOffset * invTexSize;
        float2 greenUV = screenUV + displacement * invTexSize;
        float2 blueUV = screenUV + blueOffset * invTexSize;
        float red = textureBilinear(redUV, physTexSize, invTexSize).r;
        float4 greenSample = textureBilinear(greenUV, physTexSize, invTexSize);
        float blue = textureBilinear(blueUV, physTexSize, invTexSize).b;
        refractColor = float4(red, greenSample.g, blue, greenSample.a);
    }

    // Where the page beyond the edge was not recorded (a backdrop held to the
    // surface's own bounds) the line takes the page under it, whichever way
    // it was sampled: through the colour splitting too, which left the edge
    // a black line on any phone with it switched on.
    if (hairline > 0.0 && refractColor.a < 0.999) {
        refractColor += textureBilinear(screenUV, physTexSize, invTexSize) * (1.0 - refractColor.a);
    }

    if (refractColor.a > 0.001) {
        refractColor.rgb /= refractColor.a;
    }

    if (uFrost.x > 0.0) {
        // The frosted, lensed body; the hairline keeps the sharp sample just
        // outside the shape that it was given above.
        float2 fp = fragCoord + lensDisplacement;
        float2 inward = normalXY / max(length(normalXY), 1e-4)
                      * max(0.0, 4.0 * dprScale - rimDist);
        float2 fq = fragCoord - inward;
        float3 frost = frostAt(fp, fq);
        refractColor.rgb = mix(frost, refractColor.rgb, hairline);
        refractColor.a = step(0.001, refractColor.a);
    }

    float4 bodyGlassColor = uGlassColor;
    bodyGlassColor.a *= 1.0 - hairline;
    float4 finalColor = applyGlassColor(refractColor, bodyGlassColor, uBodyMode);

    float backdropLuma = dot(refractColor.rgb, LUMA_WEIGHTS);
    float adaptiveStrength = mix(1.2, 0.8, backdropLuma);

    finalColor.rgb = applySaturation(
        finalColor.rgb, mix(uSaturation * adaptiveStrength, 1.0, hairline));

    finalColor.rgb = mix(finalColor.rgb,
                         uGlassColor.rgb,
                         uGlassColor.a * 0.12 * (adaptiveStrength - 1.0) * (1.0 - uBodyMode)
                         * (1.0 - hairline));

    const float WHITEN_LO = 0.40;
    const float WHITEN_HI = 0.80;
    float whitenLuma = dot(finalColor.rgb, LUMA_WEIGHTS);
    float whitenGate = mix(1.0, sstep(WHITEN_LO, WHITEN_HI, whitenLuma), uWhitenGated);
    finalColor.rgb = mix(finalColor.rgb, float3(1.0),
                         clamp(uWhiten, 0.0, 1.0) * whitenGate * (1.0 - hairline));
    if (uBodyShade > 0.0) {
        float shadeLuma = dot(finalColor.rgb, LUMA_WEIGHTS);
        finalColor.rgb *= 1.0 - uBodyShade * shadeLuma * shadeLuma;
    }

    float baseScale = 40.0 * max(0.1, uEdgeConfig.z);
    float thicknessScale = clamp(baseScale / max(uThickness, 1.0), 1.0, 4.0);
    float edgeThreshold = mix(0.8, 0.5, 1.0 / thicknessScale);
    float edgeFactor = uThickness < 0.01 ? 0.0 : 1.0 - sstep(0.0, edgeThreshold, normalizedHeight);

    float r_rim = clamp(1.0 - normalizedHeight, 0.0, 1.0);
    float lensThickness = uThickness < 0.01 ? 0.0 : sqrt(max(0.0, 1.0 - r_rim * r_rim));
    float rimThickness = 1.0 - lensThickness;

    float len2D = max(length(normalXY), 1e-4);
    float2 rimN = normalXY / len2D;
    float litness = dot(rimN, uLightDirection);
    float dirScale = mix(1.4, 0.6, litness * 0.5 + 0.5);

    float absorption = 1.0 - sqrt(rimThickness) * uEdgeConfig.w * dirScale;
    finalColor.rgb *= max(0.0, absorption);

    if (edgeFactor > 0.01 && (uLightIntensity > 0.0 || uAmbientStrength > 0.0)) {
        float len = max(length(normalXY), 1e-4);
        float2 anisoN = normalXY / len;

        float mainLight = max(0.0, dot(anisoN, uLightDirection));
        float oppositeLight = max(0.0, dot(anisoN, -uLightDirection));
        float totalInfluence = mainLight + oppositeLight * 0.8;

        float directional = totalInfluence * sqrt(totalInfluence) * uLightIntensity * 3.0;
        float ambient = uAmbientStrength * 0.5;

        float brightnessRaw = (directional + ambient) * edgeFactor * thicknessScale * 0.8;
        float brightness = brightnessRaw / (1.0 + brightnessRaw);

        float3 highlightColor = getHighlightColor(refractColor.rgb, 1.0);
        finalColor.rgb = mix(finalColor.rgb, highlightColor, brightness);

        if (uTouchIntensity > 0.001) {
            float2 toFragPx = fragCoord - uTouchPosition;
            float touchDist = length(toFragPx);
            float dpr = max(1.0, uEdgeConfig.z * 3.0);
            float touchRadius = max(70.0 * dpr, uSize.y * 1.5);
            float distFactor = sstep(touchRadius, 0.0, touchDist);
            if (distFactor > 0.001 && touchDist > 1.0) {
                float2 touchDir = toFragPx / touchDist;
                float rimTouchDot = max(0.0, dot(anisoN, touchDir));
                float rtd2 = rimTouchDot * rimTouchDot;
                float tSpec = rtd2 * rtd2 * rtd2 * distFactor;
                float tBrightnessRaw = tSpec * uTouchIntensity * uLightIntensity * 2.5;
                float tBrightness = tBrightnessRaw / (1.0 + tBrightnessRaw);
                finalColor.rgb = mix(finalColor.rgb, highlightColor, tBrightness * edgeFactor);
            }
        }
    }

    float rimBase = (1.0 - normalZ) * edgeFactor;
    float ringWindow = 0.75 * max(0.1, uEdgeConfig.z);
    float ring = (1.0 - sstep(uEdgeConfig.x - ringWindow, uEdgeConfig.x + ringWindow, rimDist))
               * step(0.001, uEdgeConfig.x);
    float fresnel = rimBase * 0.12 * uEdgeConfig.y + ring * 0.45;
    finalColor.rgb = clamp(finalColor.rgb + float3(fresnel), 0.0, 1.0);

    if (uRimConfig.y > 0.001 && rimDist < 30.0 * dprScale) {
        float along = dot(rimN, uLightDirection);
        float lit = max(along, 0.0);
        float opp = max(-along, 0.0);
        float lobe = lit * lit * lit + 0.85 * opp * opp * opp;
        float inset = max(0.0, rimDist - 2.25 * dprScale) / dprScale;
        float core = exp(-inset / 1.5);
        float tail = exp(-inset / 8.0);
        float bodyLuma = clamp(dot(finalColor.rgb, LUMA_WEIGHTS), 0.0, 1.0);
        float ease = pow(1.0 - bodyLuma, 0.3);
        float rimLight = uRimConfig.y * lobe * ease
                       * (0.175 * core + 0.035 * tail) * (1.0 - hairline);
        finalColor.rgb = clamp(finalColor.rgb + float3(rimLight), 0.0, 1.0);
    }

    if (hairline > 0.0) {
        float across = 1.0 - dot(rimN, uLightDirection) * dot(rimN, uLightDirection);
        across = pow(max(across, 0.0), 1.75);
        float shade = uRimConfig.x * 0.314 * mix(uRimConfig.z, 1.0, across);
        // LOCAL CHANGE: the dark material's line eases off over a bright page.
        // Upstream keeps it at full strength, as measured on the native
        // material, and over a light cover it read as a black border round
        // every control; over a dark page it is as it was. The light material
        // (no bodyShade) is left alone.
        if (uBodyShade > 0.0) {
            float under = clamp(dot(refractColor.rgb, LUMA_WEIGHTS), 0.0, 1.0);
            shade *= 1.0 - sstep(0.1, 0.6, under);
        }
        finalColor.rgb = max(finalColor.rgb - float3(shade * hairline), 0.0);
    }

    float alpha = geometryData.a;
    // Glass over its shadow.
    float outA = alpha + shadowAlpha * (1.0 - alpha);
    return half4(half3(finalColor.rgb * alpha * uOpacity), half(outA * uOpacity));
}
"""
}
