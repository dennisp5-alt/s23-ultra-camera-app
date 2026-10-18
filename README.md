# AutoPhoto Quality Lab v0.4.2 — Adaptive Strong Detail

A **separate offline experimental APK** for Samsung Galaxy S23 Ultra, branched from v0.4.1 commit `7d690756e8c22bb1fcefb94695b62936a1498a69`.
Package ID `com.example.autophoto.qualitylab.v042`; earlier versions preserved.

## New independent Adaptive Strong mode

Tap **Adaptive Strong Detail (v0.4.2)** to run from the untouched original decoded photograph.

- Uses original **Strong gain** on credible local detail, with bilateral-like 5x5 confidence and native-resolution 3x3 texture reconstruction from actual pixels (no hallucination).
- Extra **soft blue/pale upper-region confidence** to reduce sharp outlines against sky and clouds, especially poles/powerlines. It's a chromatic/spatial heuristic, **not trained sky segmentation**.
- Extra **warm skin-like colour proximity** to preserve skin texture and smooth subject boundaries; **not face recognition or person segmentation**.
- Smoothly attenuates corrections in high-risk areas, supplementing existing protection for saturation, natural bokeh, deep shadows and clipping.
- Additional local 3x3 edge overshoot safeguard caps any newly created extrema to about 1.4–2.4/255 outside their original neighbourhood.
- Reports average/max absolute luminance change, changed-pixel %, original vs output brightness, texture-mask coverage and how often the new safeguard limited a correction.

## A/B controls and preservation

The existing three **Gentle / Balanced / Strong v0.4.1 presets remain unmodified** and can still be run through "Run Local Detail (v0.4.1 presets)". The newly selected default preset is **Strong**, matching user preference. The new Adaptive Strong button is a distinct route, not a replacement for the older buttons.

**Auto, MobileIE, Learned 3D LUT, Adaptive Masked AI, Semantic Person AI (ML Kit), and Scene Semantic AI (SegFormer)** are unmodified and bundled offline. All older APKs remain installable. This branch adds no external models/dependencies. Any references to scene guidance in Adaptive Strong mean colour/tone heuristics, not actual four-class semantic inference.

## Testing priorities

1. Bottlebrush flower: preserve red filaments, avoid bokeh hardening.
2. Rooftops/powerlines: no bright sky/cable halos, clouds remain natural.
3. People/inflatables/water: natural skin, real water ripples, no rough noise.
4. Rose/portrait/park: highlight, hair, skin and distant-texture protection.

Compare **Strong v0.4.1** vs **Adaptive Strong v0.4.2**, starting from the original for each. Run side-by-side saved JPG comparisons at the same scale. Claim quality improvement only after actual testing, not from percentage of pixels changed.
