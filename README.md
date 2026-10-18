# AutoPhoto Quality Lab v0.4.1 — Three-strength Local Detail A/B Experiment

Separate offline Android experiment on the Galaxy S23 Ultra.
App ID: `com.example.autophoto.qualitylab.v041`; installs alongside working v0.4.0, v0.3.9.1, and all other versions.

## Three A/B strengths
Pick **Gentle**, **Balanced** (default) or **Strong**, then tap **Run Local Detail Lab (v0.4.1)**. The app always runs from the **original decoded image**, never a previously edited result. The selected preset is displayed in the permanent result report.

### Processing improvements compared with v0.4.0
- At 1024px analysis resolution, softer confidence threshold for meaningful local texture and less aggressive suppression of high-detail regions.
- Per-pixel original-resolution 3x3 edge-guided filtering. Threshold tiny high-frequency fluctuations (possible sensor/JPEG noise) and cap real fine and medium-scale contrast adjustments.
- Strong red flower and skin-like tones receive soft protection, but the mask does not suppress nearly all corrections as it did on v0.4.0.
- Detail gain and hard per-channel limit vary by selected mode; no learned/restored/generated detail. Channel clipping, deep blacks, bright highlights, bokeh and strong edge-overshoot safeguards remain.
- Persistent diagnostics include processing time, fraction of image actually changed, mean and maximum absolute luminance change, texture mask coverage and the original/output decoded-image mean luminances. These are measured **before JPEG export**, so no export colour-management error is implied by a different brightness measurement between screenshots.
- Fix progress callbacks posted late from the worker thread overwriting the completed status with `processing 100%`.

## Critical regression guarantee
- Original **Auto**, **MobileIE**, **Learned 3D LUT**, **Adaptive Masked AI**, **Semantic Person AI**, and **Scene Semantic AI** processors are unmodified. Only the new local detail algorithm and its new strength selection UI are changed.
- The original v0.4.0 Local Detail app remains installed separately and should be used as A/B baseline.
- No new third-party model or dependency; bundled pretrained models are the same as v0.4.0.

## Test sequence
1. With the *original bottlebrush photo*, try Gentle → Save; Balanced → Save; Strong → Save. Use full image and zoom into high-contrast red filaments to check any doubled/halo edges. Check bokeh remains soft.
2. Repeat on rose highlights, portrait skin/hair, and pond landscape water/trees/ducks. Subtle enhancement alone is not proof of improved quality.
3. Compare each candidate with v0.4.0 and original at equal zoom and identical screen brightness. Share processed JPEGs and status screenshot.
4. If Strong produces increased noise/edge halos or strange bright red tips, reject Strong and prefer Balanced/Gentle. If all are too subtle, revisit methodology rather than blindly increasing gain.

This build is an experimental candidate, not a claimed photographic quality improvement. Real S23 Ultra comparisons are still required.
