package com.dennis.pixelrepair;

import java.util.Arrays;

/**
 * Experimental single-pixel impulse and isolated chroma repair.
 * It makes no claim to recover missing structural image detail.
 * Input and output are full-resolution ARGB pixel arrays.
 */
public final class PixelRepairEngine {
    private PixelRepairEngine() {}

    public static final class Result {
        public final int[] pixels;
        public final int changedPixels;
        Result(int[] pixels, int changedPixels) {
            this.pixels = pixels;
            this.changedPixels = changedPixels;
        }
    }

    public static Result repair(int[] src, int width, int height, int sensitivity) {
        if (src == null || width <= 0 || height <= 0 ||
            (long) width * height != src.length) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
        int[] out = src.clone();
        if (width < 3 || height < 3) return new Result(out, 0);
        int s = Math.max(0, Math.min(100, sensitivity));
        double base = 60.0 - s * 0.35;
        double mix = 0.62 + s * 0.0038;
        final int[] offsets = {-width - 1, -width, -width + 1, -1, 1,
                               width - 1, width, width + 1};
        int[] rs = new int[8], gs = new int[8], bs = new int[8], ys = new int[8];
        int changed = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x, p = src[idx];
                if ((p >>> 24) != 255) continue;
                int r = (p >>> 16) & 255, g = (p >>> 8) & 255, b = p & 255;
                int sumR = 0, sumG = 0, sumB = 0;
                boolean opaque = true;
                for (int i = 0; i < 8; i++) {
                    int q = src[idx + offsets[i]];
                    if ((q >>> 24) != 255) { opaque = false; break; }
                    int nr = (q >>> 16) & 255, ng = (q >>> 8) & 255, nb = q & 255;
                    rs[i] = nr; gs[i] = ng; bs[i] = nb;
                    ys[i] = luminance(nr, ng, nb);
                    sumR += nr; sumG += ng; sumB += nb;
                }
                if (!opaque) continue;

                // Quick rejection; full medians are calculated only for suspect pixels.
                int maxMeanDiff = Math.max(Math.abs(r - sumR / 8),
                    Math.max(Math.abs(g - sumG / 8), Math.abs(b - sumB / 8)));
                if (maxMeanDiff < base * 0.65) continue;

                Arrays.sort(rs); Arrays.sort(gs); Arrays.sort(bs); Arrays.sort(ys);
                int mr = (rs[3] + rs[4]) / 2;
                int mg = (gs[3] + gs[4]) / 2;
                int mb = (bs[3] + bs[4]) / 2;
                int my = (ys[3] + ys[4]) / 2;
                int cy = luminance(r, g, b);

                int chromaDifference = Math.max(Math.abs(r - mr),
                    Math.max(Math.abs(g - mg), Math.abs(b - mb)));
                int luminanceDifference = Math.abs(cy - my);
                double luminanceVariation = 0, colourVariation = 0;
                for (int i = 0; i < 8; i++) {
                    luminanceVariation += Math.abs(ys[i] - my);
                    colourVariation += Math.max(Math.abs(rs[i] - mr),
                        Math.max(Math.abs(gs[i] - mg), Math.abs(bs[i] - mb)));
                }
                luminanceVariation /= 8.0;
                colourVariation /= 8.0;

                // High-contrast edges and irregular legitimate texture receive extra protection.
                boolean impulse = luminanceDifference > base + 2.8 * luminanceVariation
                    && chromaDifference > base * 0.85 + colourVariation;
                boolean chromaSpeck = chromaDifference > base + 2.8 * colourVariation
                    && luminanceDifference < Math.max(20, base * 0.85)
                    && luminanceVariation < 26.0;
                if (!impulse && !chromaSpeck) continue;

                int targetR = mr, targetG = mg, targetB = mb;
                if (chromaSpeck && !impulse) {
                    // Preserve original luminance when correcting a colour-only outlier.
                    int delta = cy - luminance(mr, mg, mb);
                    targetR = clamp(mr + delta);
                    targetG = clamp(mg + delta);
                    targetB = clamp(mb + delta);
                }
                int rr = clamp((int) Math.round(r + (targetR - r) * mix));
                int gg = clamp((int) Math.round(g + (targetG - g) * mix));
                int bb = clamp((int) Math.round(b + (targetB - b) * mix));
                int repaired = (0xff << 24) | (rr << 16) | (gg << 8) | bb;
                if (repaired != p) {
                    out[idx] = repaired;
                    changed++;
                }
            }
        }
        return new Result(out, changed);
    }

    private static int luminance(int r, int g, int b) {
        return (77 * r + 150 * g + 29 * b) >> 8;
    }

    private static int clamp(int n) {
        return Math.max(0, Math.min(255, n));
    }
}
