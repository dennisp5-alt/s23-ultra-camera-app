# AutoPhoto v0.4.9 — Studio Clean controlled restoration build

Base: v0.4.8 `911d1ff646b45fa327351a4079e0f69aca90ebf1`; independent branch `autophoto-studio-clean-v049`.

## Goal
Better camera-JPEG cleanup with the genuine pretrained **KAIR DnCNN color blind** model (20 convolutional layers), WITHOUT changing the proven user-preferred neural 3D-LUT Polished colour/tonal/texture implementation. Source model from `https://github.com/cszn/KAIR/releases/download/v1.0/dncnn_color_blind.pth`. NAFNet was investigated but the available mobile fp16 model has CPU support concerns with existing TFLite deployment. For mobile CPU-first v0.4.9, DnCNN is smaller. It is trained for blind additive Gaussian noise — **not universally for real JPEG artifacts**. Studio Clean therefore uses very conservative patch-local luminance-only fusion, not global denoising.

## What's in the build
- Original `PolishedAIProcessor.kt` remains Git-blob-equal to approved v0.4.6: `ea92bd74ef01997e228a5c8de7ddd08e2aa7644a`.
- `scripts/prepare_studio_clean.py`: downloads upstream published pretrained DnCNN weights ONLY during the GitHub build; strict PyTorch state dict, exports fixed 256×256 RGB NCHW ONNX, runs ONNX CPU smoke inference. Build fails if wrong model, missing weights, malformed tensors, or untrained identity result. Assets packaged for offline use.
- `StudioCleanProcessor.kt`: calls EXACT original Polished path first. Estimates image highpass residual in smooth regions; selects up to **12 worst noisy 256px tiles** out of the whole photograph (224px stride); runs genuine pretrained ONNX DnCNN on those only; blends a modest **luminance** residual into Polished result. Structure/skin/red flowers/highlight/edges suppressed, feather 20px borders. Max correction ±9 before further bounded gain. No actual face or flower semantics; no invented detail.
- Clean/low-noise photos: zero inference, standard Polished output. Any missing model, unsupported ORT operation or inference error: **fallback to unchanged Polished**, with explicit on-screen reason.
- Simplified two-button A/B UI **Run Polished AI** / **Studio Clean AI**. Historical Polished+ and Wow remain in code, hidden.
- Persistent accurate *Viewing: ORIGINAL* or *Viewing: POLISHED/STUDIO CLEAN*, synchronized on open/run/tap. “Pixels numerically changed” replaces the misleading improvement metric; “pre-clamp gamut-pressure candidates” replaces implied proof of blown highlights. Tile count and bypass/AI status visible.
- Always-visible `Input: sRGB/P3/other` and `Saved: Display P3 verified/sRGB fallback/sRGB` from export. The v0.4.8 colour bridge and its integrity checks are not modified; P3 still experimental until phone file verification.
- Safe EXIF whitelist from v0.4.7; camera date/exposure/lens retained, GPS and sensitive metadata excluded. Approved JPEG 98 retained. Standard 12MP decoding, hi-res setting hidden.

## Acceptance
1. Install independently beside v0.4.6, v0.4.8. Polished button produces EXACT SAME appearance as v0.4.6/0.4.8 for the same input, with correct Original/Processed labels on every preview tap.
2. Use 12MP untouched original of fabric/hair/skin/sky/saturated bottlebrush, landscape grass/water and a **genuinely noisy low-light JPEG**. Test both modes on the SAME photo.
3. Studio Clean should say **AI bypassed** on clean photos (if confidence threshold met) and process only genuinely noisy patches on noisy photos. This is not promised until source tests.
4. 100%-pixel inspect for luminance patch seams, halos, blurred hair/flowers, plastic skin, noise residue, JPEG artifacts and altered colour. Reject if no quality improvement.
5. Check retained source and saved profiles via independent metadata inspection (ICC), not only UI. P3 must decode P3, sRGB decode sRGB, unknown source convert correctly, saved GPS absent.
6. Measure end-to-end processing time, RAM/thermals and any NAFNet/DnCNN fallback. Successful CI is NOT evidence of noise reduction on photos.
7. Keep v0.4.6 as approved rollback. v0.4.9 is independently installable and does not touch other apps or APKs.

## Licensing
Verify applicable DnCNN/KAIR model licence and redistribution before public/commercial use. This experimental APK bundles the upstream weights converted at build; retain attribution and link to original project.
