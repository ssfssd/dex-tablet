# DeX Tablet / DX Manager icons

Two related icons distinguish the Android receiver (mint tablet) from the Windows manager (blue monitor). Each includes a connected white phone. The two applications remain separate products; these personal icons do not imply an official DX Manager release or Samsung affiliation.

| Use | Android / DeX Tablet | Windows / DX Manager |
| --- | --- | --- |
| Representative image | `dex-tablet/DeXTablet-1024.png` | `dx-manager/DXManager-1024.png` |
| Small PNGs | 16, 20, 24, 32, 40, 48, 64, 96, 128, 256, 512 px | Same sizes |
| Windows icon file | `dex-tablet/DeXTablet.ico` | `dx-manager/DXManager.ico` |
| ICO embedded sizes | 16, 20, 24, 32, 40, 48, 64, 96, 128, 256 px | Same sizes |

## Android integration

The app manifest uses `@mipmap/ic_launcher` for both icon and roundIcon. Density-specific PNGs provide the legacy image. Android 8+ adaptive resources use a clean vector adaptation of the generated mark over navy; the symbol fits inside the central safe region. Android 13+ adds a monochrome vector for themed icons. Launcher/taskbar/recents presentation remains under the device launcher and Android shell's control.

`dist/DeXTablet-0.2.1-debug.apk` uses versionCode 3 and the existing private development signing key. Both the old and new APK have the same signer certificate. The 0.2.0 APK is preserved. No physical device installation or launcher display has been verified.

## Windows integration

`dist/DXManager-2.5.0-custom-icons/` is an isolated personal copy of the official Windows 2.5.0 portable release. It is distributed with [DeX Tablet v0.2.1](https://github.com/ssfssd/dex-tablet/releases/tag/v0.2.1), not as an upstream DX Manager release. Existing installations/settings are not changed. Run the `DXManager.exe` inside its `DX Manager v2.5.0` folder; retain the bundled files and license notices.

The official archive SHA-256 is `ebb91a332b603c4096b1b1fb6d179f4d45b55f5a63fc657f8b989bea8de08764`.

Only two original files change:

- `DXManager.exe`: native icon resources. Upstream `AppIconProvider` reads this executable icon for the main form and tray, so a loose shortcut icon alone would be insufficient.
- `tools/scrcpy/scrcpy.png`: the actual DeX/single-app window icon loaded by portable scrcpy 5.0.1.

All non-resource executable sections, all non-icon native resources, and the other 37 bundled files were compared and preserved. The actual Windows shell/taskbar and connected DeX session were not opened or tested. Existing pinned shortcuts may still point to the previous executable and Windows can cache their old icon; pin the new executable if you choose to use this copy. No shell cache or pinned item was changed by this task.

For a source build of DX Manager, replace `DexManager/Resources/DXManager.ico`; its existing `ApplicationIcon` and `AppIconProvider` wiring already consume it. Include the new `scrcpy.png` in the portable scrcpy directory for the streamed window.

## Reproduction and provenance

Built-in `image_gen` produced the two opaque raster masters in `source/`. The initial transparent draft was rejected for rough edges and is not shipped. `scripts/build-icons.py` uses Pillow only to resize and convert formats; it does not retouch the generated artwork. The Android adaptive and monochrome icons are repo-native vector adaptations to keep small launcher sizes sharp and respect masks.

Run `scripts/build-icons.py` with Python and Pillow to regenerate PNG/ICO resources, then `scripts/build.ps1` for the APK. `scripts/package-windows-icons.py` reads the hash-checked original archive from `build/upstream-icons/` and refuses to overwrite an existing output directory. It updates native icon resources through Windows APIs without running the upstream program and checks that code and other resources are unchanged.

Prompts are recorded in `PROMPTS.md`. `manifest.json` lists final icon asset hashes. Generated images were normalized from 1254-square output to the requested icon sizes.

Sources checked for the Windows integration:

- https://github.com/maze-mei/DX-Manager/releases/tag/v2.5.0
- https://github.com/maze-mei/DX-Manager/blob/v2.5.0/DexManager/Utils/AppIconProvider.cs
- https://github.com/maze-mei/DX-Manager/blob/v2.5.0/DexManager/Services/TrayService.cs
- https://github.com/Genymobile/scrcpy/blob/v5.0.1/app/src/icon.c
- https://github.com/Genymobile/scrcpy/blob/v5.0.1/app/src/icon.h

## 한국어

- 대표 이미지는 각 앱 폴더의 `1024.png`, Windows 탐색기·바로가기용은 `.ico`입니다.
- Android APK에는 적응형·원형·단색 테마 아이콘을 연결했습니다.
- Windows 사본에는 실행 파일 아이콘과 실제 DeX 창 아이콘을 함께 넣었습니다. 공식 배포본과 구별되는 개인용 아이콘 수정본입니다.
- 설치 중인 앱, 사용자 설정, 작업표시줄 고정 항목은 바꾸지 않았습니다. 실제 태블릿 런처와 Windows 작업표시줄 표시는 아직 확인하지 않았습니다.
- 배포 파일과 Obtainium 업데이트 안내는 저장소의 `RELEASE.md` 및 `obtainium/README.md`를 참고하세요.
