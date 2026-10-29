# 프로토콜과 의존성 출처

2026-10-29 확인. 아래 자료는 구현 근거이며 이 프로젝트의 실기기 검증 결과를 뜻하지 않습니다.

| 항목 | 출처 |
|---|---|
| scrcpy 4.1 서버 | https://github.com/Genymobile/scrcpy/releases/tag/v4.1 |
| 포함 바이너리 | https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1 |
| 미디어 헤더 | https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java |
| 제어 메시지 | https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java |
| 키보드 보고서 참고 | https://github.com/Genymobile/scrcpy/blob/v4.1/app/src/hid/hid_keyboard.c |
| PCM 구성 | https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/audio/AudioConfig.java |
| ADB 프로토콜 | https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt |
| ADB RSA 공개키 형식 | https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcrypto_utils/android_pubkey.cpp |
| USB ZLP 처리 참고 | https://github.com/aosp-mirror/platform_system_core/blob/android-10.0.0_r1/adb/client/usb_libusb.cpp |
| 최신 One UI 가상화면 / 보조화면 제약 보고 | https://github.com/sam1am/localdex/blob/main/docs/TECHNICAL.md |
| Windows 측 비교 자료 | https://github.com/maze-mei/DX-Manager |

서버 버전은 클라이언트 메시지 형식과 함께 **4.1에 고정**했습니다. DX Manager의 번들 버전과 별개입니다. 임의의 다른 버전 바이너리만 교체하면 호환되지 않을 수 있습니다.

```
app/src/main/assets/scrcpy-server-v4.1
size: 733706 bytes
SHA256: deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae
```

서버는 공식 릴리스 바이너리를 수정하지 않고 포함했습니다. 서버 프로토콜과 키보드 descriptor를 참고했으며 다른 Android 수신 앱의 소스는 가져오지 않았습니다. 서버의 Apache-2.0 라이선스는 `app/src/main/assets/LICENSE-scrcpy.txt`, 고지는 `app/src/main/assets/NOTICE.txt`에 있습니다.
