# MultiSplashSkip

A single LSPosed module project based on the user's existing BComic source.

## Included
- Existing Bilibili Comics `ComicModule.java` retained without modification.
- New `VivoGameModule.java` adapter for vivo Game Center startup diagnostics.
- One libxposed API 102 module entry list and one GitHub Actions build.
- Static scope currently lists `com.bilibili.comic` and `com.vivo.game`.

## Important status
The vivo adapter is **diagnostic-only**; it logs Activity lifecycle events. It does not yet skip vivo ads. The package ID `com.vivo.game` must be verified on the user's phone before relying on the adapter. After installing, capture logs tagged `MultiSplashSkip/Vivo`, identify the actual startup/ad page and app version, then implement and test a narrow skip rule.

The original Bilibili Comic source was copied unchanged. Build and test it before replacing the currently working installed APK. This project is built with the same libxposed API dependency and modern `META-INF/xposed` entry metadata as the supplied project.

## GitHub build
1. Upload all repository files to the root of a GitHub repository.
2. Push to `main`, or use Actions -> Build MultiSplashSkip APK -> Run workflow.
3. Download the `MultiSplashSkip-debug` artifact from the successful run.
4. Extract `app-debug.apk`, install, enable in LSPosed, and set scope.

## Log collection
`adb logcat -s "MultiSplashSkip/Vivo:I" "ComicModule:I"`

If no vivo logs appear, verify actual package ID, static scope and LSPosed module compatibility first.
