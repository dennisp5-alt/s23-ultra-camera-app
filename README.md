# Dennis AI Chat 0.1 — Galaxy S23 Ultra

Android native local AI chat. Requires compatible LiteRT-LM .litertlm model imported from local storage. No inference API account is required. Web search is optional and uses a user-provided Brave Search API key.

- On-device LiteRT-LM (CPU first).
- Chats and retrieved source excerpts saved locally in SQLite.
- Opt-in Brave Search HTTPS research, protected API key via Android Keystore.
- Offline conversations after model import.
- Search results are untrusted excerpts, **not** independently verified claims, and do not modify model weights.

## Install
Download the GitHub Actions debug APK. Import an Android-compatible CPU `.litertlm` model, for example Gemma 3 1B from the [LiteRT community](https://huggingface.co/litert-community/Gemma3-1B-IT). Licence acceptance may be required. For optional online research, acquire your own key from [Brave Search](https://api-dashboard.search.brave.com/app/plans) and enable Research.

## Build
Uses JDK 17, Android SDK 36, Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.20. `gradle :app:assembleDebug`.

**This is experimental.** Import, inference, device thermal performance, and long conversations must be validated on a Galaxy S23 Ultra.
