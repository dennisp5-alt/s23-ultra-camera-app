# AutoPhoto v0.4.6 — read-only photographic QC checkpoint
**19 October 2026. Research only. No APK build or processing changes.**

## Locked reference
- Stable APK/version: AutoPhoto Polished Preferred v0.4.6, branch `autophoto-polished-preferred-v046`, commit `a00bce9ad2c44606cb7f9bbf00a83ae4de3ad47c`.
- `PolishedAIProcessor.kt` remains unchanged. User prefers Polished over the v0.4.4/v0.4.5 experimental Wow and Polished+ treatments on bottlebrush, portrait and landscape samples.
- No build from this branch: independent QC documentation/tool only. Never overwrite the known-good branch, releases, camera app or photos.

## Verified findings
- `MainActivity.decodePhoto` caps the longest decoded side to **4096 px**. This can discard native detail from 50 MP and 200 MP source images. Do NOT remove the cap blindly: Android RAM/CPU/thermal limits and downscaled 256px classifier + 512px analysis must be profiled first.
- Output JPEG is encoded at quality **98**, to Pictures/AutoPhoto. A lossy JPEG export and its ICC/EXIF treatment require validation against the source. `writeJpeg` currently builds a new MediaStore JPEG and does not explicitly copy EXIF camera metadata.
- Supplied chat comparison photographs examined here were **1152×1536** or **1536×1152**, not untouched full camera files; their pixel data cannot validate native-resolution texture or accurately quantify the actual app's downscaling.
- Latest two bottlebrush uploads (user labels: **Polished+ then Polished**) differ by average **2.631/255** per RGB channel; mean signed luma difference **+0.9017/255** for Polished+; **15.1366%** of pixels differ by more than 5 in at least one RGB channel. This is a comparison, NOT evidence of objectively superior detail.
- Several earlier bottlebrush reuploads are byte-for-byte identical between rounds (same exact output, not independent evidence that a new processing branch was tested). Do not infer any build failed: could simply be reuploading prior exports.

## Quality-audit tool
`scripts/autophoto_quality_audit.py` is a **read-only Python CLI** using Pillow and NumPy. It records input dimensions, hashes, luminance percentiles, potentially saturated channels, near-black fractions, paired pixel deltas, and warnings about resized/mismatched sources. It does not upload or modify photographs. Run:

```bash
python -m pip install pillow numpy
python scripts/autophoto_quality_audit.py --reference untouched_samsung_original.jpg --candidate autophoto_polished.jpg --out qc_report.json
```

**Interpretation warnings:** brightness, clipping-risk and edge energy do not prove aesthetics; a saturated red channel alone does not prove destroyed flower detail. Reject unequal input/output dimensions for direct pixel-difference comparisons; handle separately as a resolution-loss finding.

## Next required sample
Ask for *one pair of unchanged full-resolution JPEG/HEIC files* (ideally inside a ZIP to avoid ChatGPT image downsizing):
1. untouched Samsung Galaxy S23 Ultra camera original, and
2. the **v0.4.6 Polished** JPEG export generated from exactly that original.

Check dimensions/metadata first, followed by matched 100%-resolution crops: flower filaments, dark hair, skin, grass/foliage, sky/power lines, and shadows. Compare original versus edited with human judgement plus objective diagnostics. For 50/200 MP originals, first test resolution behavior and memory under separate A/B proof-of-concept before modifying the shipping app.

**Stop criterion:** if there is no demonstrated recoverable information loss, no corrective app build is justified.
