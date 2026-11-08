# Obtainium으로 DeX Tablet 업데이트

[설정까지 포함해 Obtainium에 추가](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522dev.dextablet%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fssfssd%252Fdex-tablet%2522%252C%2522author%2522%253A%2522ssfssd%2522%252C%2522name%2522%253A%2522DeX%2520Tablet%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Atrue%252C%255C%2522verifyLatestTag%255C%2522%253Afalse%252C%255C%2522includeZips%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255EDeXTablet-.*%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D)

Obtainium을 설치한 태블릿에서 위 링크를 열고 앱 추가를 확인하세요. 이미 이 저장소를 등록했다면 다시 추가하지 말고 업데이트 확인을 실행하면 됩니다.

- 소스: `https://github.com/ssfssd/dex-tablet`
- 앱 ID: `dev.dextablet`
- 개발판 포함: 켜기 (`includePrereleases=true`)
- 최신 태그 확인: 끄기 (`verifyLatestTag=false`)
- ZIP 포함: 끄기 (`includeZips=false`)
- APK 이름 필터: `^DeXTablet-.*\.apk$`

Windows ZIP과 소스 ZIP은 Android 업데이트 후보에서 제외합니다. 새 릴리스에는 APK 한 개만 첨부하며 버전 코드와 동일한 서명 인증서를 유지해야 합니다. 0.2.1은 versionCode 3이고 이전 0.2.0은 2입니다.

Obtainium의 업데이트 확인 후 설치를 진행하세요. 설치 확인이나 자동 설치 여부는 태블릿의 Android 권한과 Obtainium 설정에 따릅니다. 실제 태블릿에서의 업데이트 설치는 아직 검증하지 않았습니다.

`dex-tablet.json`은 위 추가 링크에 들어가는 설정 원본입니다. Android 앱 자체에 자동 업데이트 코드를 추가한 것은 아닙니다.
