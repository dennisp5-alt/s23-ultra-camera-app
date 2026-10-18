# AutoPhoto Polished AI Lab v0.4.3 — Three-Look Offline Test

**New independent Android app:** `com.example.autophoto.polished.v043`. All earlier quality-lab and AutoPhoto versions are preserved. Based on the existing v0.4.2 source, at commit `df1c53c9a465156cab2aa344b3ad4ebdfba88771`.

## Why this version

The user prefers a premium, polished, visually impressive finish and is willing to accept a slightly artificial look, then dial the intensity backward to the optimal balance. Natural capture fidelity is still available through the untouched **Auto** button.

## Three new looks

Choose **Polished**, **Polished+** (default) or **Wow**, then press **Run Polished AI (v0.4.3)**. Every run starts from the untouched decoded source; the effects are **not stacked**.

The new mode uses **actual local/offline neural inference** via our existing pretrained `AlternativeAIProcessor.adaptiveLut` ONNX trained image classifier and three learned 3D LUT bases. It then adds a deterministic finishing pass:

1. Learned neural scene-dependent colour grading (blended 75%, 90% or 100%).
2. S-shaped contrast and lift in the middle tones, with preserved deep black and gradual highlight protection.
3. Selective, not blanket, vibrance; more boost for muted colours, less for highly saturated red, warm skin-like colours and near-white reflections.
4. Edge-aware 512px bilateral-style tonal base and texture-confidence mask, interpolated into original full-resolution pixels for a measured detail-pop enhancement; preserve naturally blurred bokeh.
5. Detect very dark scene histograms (e.g. fire against black) and restrict midtone/shadow lift. Stronger aesthetic processing must not brighten black backgrounds to grey.
6. Report processed-pixel percentage, actual neural-inference time, total time, original/result image mean luminance and potential channel-clipping percentage.

This *finishing stage* is **not a generative model and does not reconstruct missing real details**; `Polished AI` denotes a real learned-LUT base with an additional stylized tone/color/detail finish. Warm-skin and sky/deep-shadow protections are photographic heuristics, **not** additional segmentation predictions.

### Regression guarantee

All previously working buttons and processors — Auto, MobileIE, Learned 3D LUT, Adaptive Masked AI, Semantic Person AI, Scene Semantic AI, Gentle/Balanced/Strong and Adaptive Strong — are retained without changing their algorithms.

### Acceptance tests on S23 Ultra

- **Fire**: retain deep black without greying shadows, maintain orange flame colour and highlight structure.
- **Sports group**: lively grass, readable jerseys, natural skin, no crushed shadows.
- **Portrait and water**: clean skin and richer pink/blue clothing without neon halos or clipped reflections.
- **Bottlebrush and rose**: vivid but not posterized reds/pinks; preserve individual filaments and folds.
- **Rooftops/powerlines**: sky gradients without sharpening rings, cables remain authentic.

Save all three looks from identical originals and compare with the original and Strong detail. Prefer subjective quality only after viewing actual A/B saved photographs at matching zoom. A model file in an APK and successful build-time tests do not prove subjective photo quality.

GitHub Actions builds, validates pretrained models and publishes a direct downloadable APK GitHub Release at `autophoto-polished-lab-v0.4.3`.
