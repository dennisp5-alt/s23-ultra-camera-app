# AutoPhoto — quality-first open-source research checkpoint (18 Oct 2026)

**Research only — the existing AutoPhoto apps are not modified by this branch.**

## Exact restoration point

- Known-good code branch: [autophoto-ai-scene-v0391](https://github.com/dennisp5-alt/s23-ultra-camera-app/tree/autophoto-ai-scene-v0391)
- Verified head at research start: `bf678ad14263c0e6baddaf0851cb938fdaf8a684`
- Working release: [AutoPhoto AI Lab v0.3.9.1](https://github.com/dennisp5-alt/s23-ultra-camera-app/releases/tag/autophoto-ai-lab-v0.3.9.1)
- App ID: `com.example.autophoto.alternatives.v0391`; all earlier versions remain preserved.
- v0.3.8's bundled ML Kit person segmentation was reported working on the device.
- v0.3.9.1's *Scene Semantic AI* `ACTIVE`/`FALLBACK` report, its exact scene model coverage and its on-device speed are **still unconfirmed**. Do not assert they worked based on successful photo export alone.

## Ranked candidate technologies

| Priority | Open source | Specific benefit | Licensing and mobile caveat |
|---|---|---|---|
| **1** | [OpenCV](https://github.com/opencv/opencv), [OpenCV contrib](https://github.com/opencv/opencv_contrib), [Advanced Image Enhancer](https://github.com/Shega-PT/advanced-image-enhancer) | Edge-aware luminance base/detail separation, restrained local contrast, selectively gated denoise | Apache-2.0 (OpenCV), MIT (Advanced Image Enhancer). Adapt algorithms, not Python desktop UI. Guard against contrast halos, amplified noise and artificial sharpening. |
| **2** | [Google HDRNet](https://github.com/google/hdrnet) | Architecture ideas for learned, spatially smooth, local bilateral-grid grading | Apache-2.0, but archived research prototype with old dependencies and several pretrained models requiring specialized RAW input. Study and prototype independently. |
| **3** | [NAFNet](https://github.com/megvii-research/NAFNet) | Actual pretrained SIDD denoiser when an image demonstrably contains objectionable noise | MIT in source LICENSE, with BasicSR notices. Python/PyTorch source requires conversion, mobile validation and independent weight licensing review. Use only if noise detection warrants it. |
| **4** | [Retinexformer](https://github.com/caiyuanhao1998/Retinexformer) | Serious pretrained low-light enhancement to compare against MobileIE | MIT source. PyTorch transformer weights need independent review, model export and on-device benchmarking. Do not use for normal daylight by default. |
| **5** | [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN), [NCNN Vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan), [ncnn](https://github.com/Tencent/ncnn) | Optional upscale/restore for genuinely low-resolution source images | BSD-3-Clause core, MIT upscaler backend, BSD-3-Clause inference engine. Android native adaptation, risk of invented detail; never auto-upscale sharp S23 Ultra originals. |
| Existing | [SkyWater-Seg weights](https://huggingface.co/Realcat/skywater_seg) | Pretrained person/sky/water background segmentation used in v0.3.9.x | Model card states MIT; GitHub source repository lists MIT in metadata but root LICENSE was not accessible in inspection. Recheck exact code/model licence and attribution before further redistribution. |
| **Avoid** | [Zero-DCE](https://github.com/Li-Chongyi/Zero-DCE), [IQA-PyTorch](https://github.com/chaofengc/IQA-PyTorch) | Ideas only, not reusable code under an unrestricted licence | Zero-DCE official README: non-commercial research; IQA-PyTorch: PolyForm Noncommercial 1.0.0. Do not embed without permission. |

The [photo-enhancer topic](https://github.com/topics/photo-enhancer) contains a small and noisy collection; investigate underlying algorithms and model availability rather than selecting by repository labels.

## Recommended next standalone experiment: Quality Lab v0.4.0 (NOT BUILT)

1. Confirm scene model ACTIVE/FALLBACK on a real S23 Ultra photo. Do **not** modify its existing code merely because average brightness changed.
2. On a new development branch with a separate app ID, implement one **locally contrast-aware, edge-preserving detail enhancement** route. Compare guided/bilateral base/detail maps and a restrained CLAHE L* alternative. Do not perform indiscriminate RGB CLAHE or global sharpening.
3. Mask out highlights, smooth bokeh, sensitive red bottlebrush filaments, pink rose petals, skin and near-black areas with soft boundaries. Apply edits only where there is useful existing detail and not just sensor/JPEG noise.
4. Add local before/after safety checks: edge overshoot/halos, noise amplification, channel clipping and hue drift. Preserve original image and user-controlled compare view.
5. A/B against **Original, Auto, v0.3.6 masked, v0.3.9.1** on bottlebrush, rose, portrait and park/pond, plus several noisy night/daylight scenes. Process at original decoded resolution. A lower average RGB difference by itself is **not** proof of superior photographic quality.
6. If quality truly improves, retain the best selective operation. Only then trial NAFNet *or* Retinexformer as independent options, not additive default model passes. Real-ESRGAN remains separate optional upscaling.

### Four quality regression photographs

- **Red bottlebrush:** preserve fine filaments and clipped-red guard; no crunchy background or sharpened bokeh.
- **Pink rose:** protect pink/lavender folds and dark background, no unnatural brightening or colour shift.
- **Close portrait:** natural skin/hair texture and selective subject exposure, no plastic skin/halo.
- **Park landscape:** preserve grass, roof, people, ducks; improve actual existing water contrast without merely brightening haze.

## Go / no-go rule

Continue only if side-by-side original-resolution images clearly improve across varied photos without harming naturalness; modest extra processing time is acceptable, particularly for an S23 Ultra offline workflow. Abandon any new method that consistently invents edges, clips colour, over-smooths hair/skin or brightens haze without texture.

**This checkpoint branch contains only this research note. No AutoPhoto code, dependencies, original Auto mode or working APK are changed.**
