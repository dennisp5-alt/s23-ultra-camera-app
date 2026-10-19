# AutoPhoto Polished v0.4.6 — native-resolution source/export audit

**Date:** 19 October 2026  
**Study:** One user-supplied ZIP with untouched Samsung Galaxy S23 Ultra camera JPEG and matching AutoPhoto Polished JPEG. Private source pictures, GPS EXIF, and raw metadata **are not stored in this public repository**.

## Confirmed technical facts
| Property | Samsung original | AutoPhoto Polished |
|---|---:|---:|
| Raw JPEG dimensions | 4000 × 3000 | 3000 × 4000 |
| Orientation when displayed | 3000 × 4000 (EXIF orientation 6) | 3000 × 4000 (pixels already upright) |
| Native pixel count | 12 megapixels | 12 megapixels |
| File size | 4.67 MB | 6.82 MB |
| Embedded colour profile | P3 (D65 with sRGB transfer) | sRGB |
| EXIF/camera capture data | Present | Absent |
| GPS metadata | Present in original; contents not recorded | Absent |

The `decodePhoto` maximum 4096-pixel long side **did not resample this specific 12 MP source**. It remains a significant constraint for larger 50/200 MP inputs. No proof exists yet about processing such sources safely at native size.

## Quantitative metrics — correctly colour managed
The two JPEGs were rotated as indicated by EXIF, then both interpreted using their **own embedded ICC profiles and converted to sRGB**. Direct subtraction of stored P3 pixel codes from sRGB pixel codes would be misleading and has been rejected.

| Metric | Result |
|---|---:|
| Mean source luminance | 118.78 / 255 |
| Mean AutoPhoto luminance | 127.66 / 255 |
| Mean luminance difference | +8.88 / 255 |
| Mean absolute RGB-channel difference | 9.56 / 255 |
| Pixels with any channel different by more than 10 | 58.0% |
| Pixels with any channel at 250+ (source → export) | 21.16% → 23.13% |
| Pixels with **all** RGB channels at 250+ (source → export) | 0.003% → 0.005% |

Near-maximum **individual** RGB channels primarily occur in vivid areas such as blue sky. They are **not** equivalent to blown white highlights or proven texture loss.

High-frequency Laplacian energy in representative native-resolution regions changed by approximately:
- Fine branches +18.5%
- Bark +18.4%
- Foliage +18.8%
- Water ripples +39.4%

Higher local edge energy means stronger contrast and/or sharpening; it **does not prove that missing optical information was recovered**. Water is most susceptible to a visually overprocessed appearance.

## Visual judgement
- Bark/branches are clearer, with recognisable fine structure intact.
- Sky is rendered more vivid; watch colour-gamut pressure and blue-channel saturation in additional examples.
- Water reflections become notably more crisply defined; avoid an automatic extra sharpening pass.
- No major broad white-clipping or gross geometric-detail failure was apparent in selected 100%-pixel crops.
- This is a single photograph, so do **not** declare all photographic content validated.

## Engineering conclusion
**KEEP v0.4.6 Polished unchanged. DO NOT launch another Android build based solely on this audit.** There is no evidence here justifying a more aggressive enhancement algorithm.

The proven, separate engineering issue is **EXIF metadata loss** in `writeJpeg`. A future independently installable experiment could copy only useful, non-sensitive data (capture time, camera/lens/exposure details) with orientation corrected to 1, while *excluding GPS by default* for privacy; preserve the current rendering algorithm and verify its exported pixels remain identical. Another separate experiment could explore optional P3 output without changing the current sRGB appearance.

For large input resolution tests, request an untouched 50 MP/200 MP original and its existing Polished output as another pair.

## Audit script correction
`scripts/autophoto_quality_audit.py` was patched to correctly convert embedded colour profiles to common sRGB before any comparison. This is a **research branch only**. It does not change any working Android APK, installed app, GitHub release or camera photographs.
