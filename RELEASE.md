# GitHub 배포: 0.2.0 개발판

공개 저장소: [ssfssd/dex-tablet](https://github.com/ssfssd/dex-tablet). 릴리스 버전: [v0.2.0](https://github.com/ssfssd/dex-tablet/releases/tag/v0.2.0).

## 설치자에게 제공할 파일

- `DeXTablet-0.2.0-debug.apk`: 태블릿에 설치할 파일
- `DeXTablet-0.2.0-debug.apk.sha256`: APK 무결성 확인용
- README: 연결 방법, 지원 조건, 실기기 미검증 범위

GitHub Releases의 **pre-release**로 표시합니다. 저장소가 공개라면 누구나 내려받을 수 있고, 비공개라면 저장소 접근 권한이 있는 사람만 이용할 수 있습니다. 자동 업데이트 기능은 없으므로 새 릴리스의 APK를 다시 내려받아 설치합니다.

## 릴리스 설명

DeX Tablet 0.2.0은 DeX 지원 갤럭시를 USB로 연결하여 화면·소리·키보드 입력·선택 파일 전송을 이용하기 위한 Android 태블릿 수신 앱입니다.

- 수신 태블릿 제조사/모델 제한 없음. Android 9 이상, USB 호스트/OTG, 지원 H.264 디코더 필요
- 화면 비율과 디코더 지원 범위에 맞춘 자동 해상도 및 최대 프레임률 선택
- 가상화면 및 보조화면 호환 모드, 물리 키보드/포인터 입력, 수동 클립보드, 파일 양방향 전송
- APK 빌드·서명 검증 및 합성 테스트 34개 그룹 통과
- **실제 태블릿과 갤럭시에서 화면·입력·DeX 기능은 아직 미검증**
- USB 주변기기 전체 전달, 마이크/웹캠 전달, 자동 업데이트 미지원

태블릿에서 APK를 내려받아 설치한 뒤, 갤럭시 USB 디버깅을 켜고 태블릿을 USB 호스트로 연결합니다. 갤럭시에 별도 앱을 설치할 필요는 없습니다. 소스 ZIP은 설치 파일이 아닙니다.

## 소스와 서명 관리

`scripts/package-source.ps1`는 소스·테스트·빌드 스크립트·문서·scrcpy 서버 및 라이선스만 묶습니다. `build/`, `dist/`, 서명 키, 합성 인증 키, 로컬 환경 파일은 묶지 않습니다. 이 소스 ZIP은 GitHub에 올릴 내용을 검토하기 위한 것이며 Git 저장소 자체는 아닙니다.

현재 APK는 기존 0.1.0과 같은 로컬 개발 키로 서명했습니다. 기존 개발판에서 업데이트할 때 앱 인증 키/설정을 유지할 수 있도록 버전 코드를 올렸습니다. 실제 기기 업데이트 설치는 미검증입니다. 서명 키를 교체하면 기존 설치 위에 일반 업데이트할 수 없으므로 정식 배포 전 장기 서명 정책을 정해야 합니다. `build/signing/debug.jks`는 저장소 또는 릴리스에 올리지 않습니다.

개발판은 공개 저장소의 pre-release로 배포합니다. 자동 빌드나 외부 유료 서비스는 구성하지 않았습니다.

공식 참고: [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [Android 앱 서명](https://developer.android.com/studio/publish/app-signing).
