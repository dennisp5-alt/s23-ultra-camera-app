# AutoPhoto S23 v0.3.0 — Full AI Offline Experiment

Built from working AutoPhoto S23 v0.2.2. **Existing AUTO processing is unchanged.** New separate **Full AI** button invokes a genuine pretrained MIRNet neural network on-device via TensorFlow Lite.

The `mirnet_fp16.tflite` model is downloaded **during GitHub Actions compilation only** from [Sayak Paul's MIRNet TFLite release](https://github.com/sayakpaul/MIRNet-TFLite-TRT/releases/tag/v0.1.0), and then packaged inside the APK. The installed app makes zero remote requests and requires no model downloads during use. MIRNet implementation and weights originate from [Soumik Rakshit MIRNet (Apache-2.0)](https://github.com/soumik12345/MIRNet) and the original [MIRNet research](https://arxiv.org/abs/2003.06792).

## Modes

- **AUTO:** exactly the existing v0.2.2 HDR-aware conventional correction code.
- **FULL AI:** float32 TFLite inference of fp16-weight MIRNet, performed in overlapping 400x400 neural tiles at a working-resolution maximum 1280-pixel edge; seamless neural correction map transferred onto original 4096px-decoded photo to maintain original fine detail, and blended according to global scene brightness/highlight protection.

## Honest limitations

- MIRNet is trained for **low-light image restoration and enhancement**, not every kind of photo edit.
- Full AI can improve low-light tone but may alter hues and be inferior to AUTO on some photos.
- It is not generative content replacement, super-resolution, RAW recovery, subject segmentation, or guaranteed face repair; fully clipped highlights cannot be reconstructed.
- Working-resolution 1280-pixel AI guidance is used to bound memory and avoid model OOM. Full resolution output retains original texture but is not full-resolution learned denoising.
- Processing can take minutes and should be tested for quality, heat, memory and successful inference on the Galaxy S23 Ultra. No claim of completed device testing.
- Existing image picker, native-resolution photo preview, icon and JPEG save pipeline are preserved. App runs offline.

App ID `com.example.autophoto.s23u.fullai` (separate install from AutoPhoto S23 v0.2.2). Experimental branch `autophoto-full-ai` leaves all other versions intact.
