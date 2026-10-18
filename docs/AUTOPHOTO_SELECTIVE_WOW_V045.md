# AutoPhoto — Option B Selective Wow v0.4.5
## Development checkpoint — 19 October 2026

Source checkpoint: v0.4.4 commit `42ba2df59c832b13918b8f2949db124eafa7317e` on `autophoto-polished-impact-v044`.
New independent branch: `autophoto-polished-selective-v045`.

**Reason:** The user's red bottlebrush side-by-side trial favored a softer Polished rendering over stronger v0.4.4 Wow, so a stronger global curve or more saturation was not appropriate.

### Retained
- `Polished` style parameters, processing expressions, original image input, learned 3D LUT and JPEG pipeline remain identical to v0.4.4 and the working original photographic baseline.
- Original simplified Open, Save, Run Polished AI + three-look interface; old algorithms and model assets remain in source and APK.
- Existing branches, releases, APKs and unrelated camera apps are never changed.

### Deliberate new looks
- `Polished+`: neural blend 0.80, contrast 0.12, lift 6.0, vibrance 0.14, detail 0.27; restrained regional midtone separation and chroma + gamut fitting.
- `Wow`: same base parameters as Polished, but independent texture-adaptive local finishing using existing 512px luminance/structure map.
    - Up to 5.2 luma units of guarded lift in existing structured areas.
    - Up to -4.6 luma units of soft-background depth when scene structure permits.
    - Up to ±3.8 locally gated relief without sharpening smooth bokeh.
    - Additional chroma only in textured midtones, capped by in-gamut fitting.
    - Significant protection for pure red flowers, magenta petals, skin, neon greens, single-channel highlights and night scene mood.
    - Does not invent subject masks: texture-structured areas are NOT person/flower segmentation.

### Acceptance tests
Use the *same original photo* and test all three versions; do not infer quality from CI success.
1. Red bottlebrush: preserve individual red filament tips; Wow should not look forced or wash out pale stamens. Compare with original, Polished and v0.4.4 Wow.
2. Portrait: preserve pores, hair strands and natural skin colour.
3. Rose/orange-pink flower: avoid per-channel highlight clipping and artificial red/magenta outlines.
4. Park/water: background depth should be subtle, not dirty or cartoonish.
5. Sky/powerlines/night fire: no halos, white clipping or lifting deep black.
6. Check stored JPEG at full resolution, not only Android preview; note processing time and gamut-pressure diagnostics.

If v0.4.5 Wow does not beat Polished consistently, retain Polished as the default; restore via prior APK/release. Built APK does not validate photographic aesthetics.
