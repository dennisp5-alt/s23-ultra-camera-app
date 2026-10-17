# AutoPhoto S23 v0.3.1 — Full AI natural-light safeguards

Built from working Full AI v0.3.0, retaining the genuine pretrained MIRNet network and the exact existing v0.2.2 Auto editing pipeline. All inference remains offline, through TensorFlow Lite. Photo Picker, original/after comparison, Save and current launcher icon remain intact.

### Changes since v0.3.0
- Distinguishes dark backgrounds with bright subjects from genuinely dark low-light scenes, using the median and 90th percentile of the phone image's luminance histogram.
- Reduces AI strength on high-contrast, daylight subjects such as the supplied pink rose.
- Introduces smooth shadow and highlight masks and hard per-pixel neural luminance caps: typically no more than 14/255 positive change in midtones, ~3/255 on very bright highlights and very little change to darkest backgrounds. Genuinely dark scenes permit somewhat more lifting.
- Preserves natural hue and local colour: RGB chroma changes learned by MIRNet are restrained to ±2.5/255 per channel, with extra protection for saturated petals and leaves.
- Maintains native photo detail with overlapped MIRNet low-resolution guidance transferred to the higher-resolution decoded input; no new sharpening, no fake generative details.
- Keeps `ImageProcessor.correct` (Auto) unchanged. Full AI runs its own inference and blending.

### Model and limitations
MIRNet is a neural low-light enhancement/restoration model bundled in the APK during GitHub Actions build, with no model download or network requirement on Android. This v0.3.1 change is an experimental model-output integration and **has not yet been compared against saved Full AI v0.3.1 results from the phone**. It cannot reconstruct truly clipped highlights or deliver generative object repair. Full AI may be slow on the Galaxy S23 Ultra.

### Practical image-quality tests
Compare original / Auto / Full AI with the same rose and low-light photos. Prefer saved JPEGs over screenshots for evaluation. Score pink petal detail, natural greens, dark background mood, halos and overall colour fidelity.

Development on `autophoto-full-ai`; other app branches remain unchanged.
