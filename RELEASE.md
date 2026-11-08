# GitHub 배포: v0.2.1 개발판

공개 소스 및 설치 파일 저장소: [ssfssd/dex-tablet](https://github.com/ssfssd/dex-tablet). [릴리스 v0.2.1](https://github.com/ssfssd/dex-tablet/releases/tag/v0.2.1).

## 배포 파일

| 파일 | 용도 |
| --- | --- |
| `DeXTablet-0.2.1-debug.apk` | 태블릿에 설치하는 Android 앱 |
| `DeXTablet-0.2.1-debug.apk.sha256` | APK 무결성 확인 |
| `DXManager-2.5.0-custom-icons.zip` | Windows용 DX Manager 2.5.0 개인용 아이콘 수정 사본 |
| `DXManager-2.5.0-custom-icons.zip.sha256` | Windows ZIP 무결성 확인 |
| `DeXTablet-0.2.1-source.zip` | 0.2.1 앱 소스·아이콘·빌드 스크립트·Obtainium 설정 |
| `DeXTablet-0.2.1-source.zip.sha256` | 소스 ZIP 무결성 확인 |

이 릴리스는 **pre-release**입니다. 공개된 APK는 하나이므로 Obtainium이 Windows 파일과 혼동하지 않도록 APK만 선택할 수 있습니다. GitHub의 자동 `Source code` ZIP은 설치 파일이 아닙니다.

최신 소스는 공개 저장소의 `main` 또는 첨부된 `DeXTablet-0.2.1-source.zip`에서 확인합니다. 기존 `v0.2.1` 태그는 설치 파일을 먼저 배포했을 때의 0.2.0 커밋을 유지하므로, GitHub가 자동 생성하는 `Source code` 압축에는 새 아이콘이 없습니다. 0.2.1 소스가 필요하면 이름이 명시된 첨부 소스 ZIP을 사용하세요.

## Android 업데이트

- 패키지: `dev.dextablet`
- 버전: `0.2.1`, versionCode `3` (이전 `0.2.0`은 `2`)
- 0.2.0과 같은 개발 서명 인증서 유지 및 검증
- 런처·원형·단색 테마 아이콘 추가. 통신·입력 코드는 이전 APK와 같음
- APK SHA-256: `60c55cedc584264c9e454800a176d4b7cf109788bc23c0b06575ef5b9b87b020`

[Obtainium 설정 포함 추가 링크와 설명](obtainium/README.md)을 사용하세요. 이미 등록되어 있다면 개발판 포함을 켜고 업데이트 확인을 실행합니다. `includePrereleases=true`, `verifyLatestTag=false`, `includeZips=false`, APK 이름 필터 `^DeXTablet-.*\.apk$`를 사용합니다.

앱 자체에 자동 업데이트 코드를 추가하지 않았습니다. Obtainium이 GitHub Releases에서 버전과 APK를 확인합니다. 설치 확인과 자동 설치 가능 여부는 Android 권한 및 Obtainium 설정에 따라 달라집니다. 실제 태블릿 업데이트 설치는 미검증입니다.

## Windows 사본

공식 [DX Manager 2.5.0](https://github.com/maze-mei/DX-Manager/releases/tag/v2.5.0) 배포 ZIP을 검증한 뒤 별도 사본에 아이콘만 반영했습니다. upstream의 새 버전이나 공식 배포본이 아닙니다.

- 변경: `DXManager.exe` 네이티브 아이콘 리소스, `tools/scrcpy/scrcpy.png`
- 보존: `.rsrc` 외 실행 파일 섹션, 비아이콘 네이티브 리소스, 나머지 37개 원본 파일
- ZIP SHA-256: `b09b7debe985686b97021709cf365926a8e7d55b6002a940a680f39cccdcd362`
- 원본 및 제삼자 라이선스 고지 포함. 기존 설치 앱과 사용자 설정은 변경하지 않음
- Windows 창·작업표시줄·트레이와 연결된 DeX 화면에서의 실제 표시는 미검증

## 검증과 서명 관리

APK 빌드, v3 서명, zipalign, 패키지·버전·기존 서명 일치, 아이콘 포맷과 Windows 아이콘 추출을 확인했습니다. 실제 기기 호환성이나 지연 개선을 검증한 릴리스는 아닙니다. 기존 합성 코어 테스트 34개는 0.2.0에서 실행한 기록이며 아이콘 작업에서 다시 실행하지 않았습니다.

소스 ZIP 및 Git에는 `build/`, `dist/`, 서명 키, 개인 설정을 포함하지 않습니다. `build/signing/debug.jks`를 공개하지 않으며 후속 업데이트에도 같은 서명을 유지해야 합니다. 기존 v0.2.0 릴리스 파일은 보존합니다.
