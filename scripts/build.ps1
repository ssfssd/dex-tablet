param([string]$Sdk = $env:ANDROID_HOME, [string]$Java = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
if (!$Sdk) { $Sdk = 'C:\Android\Sdk' }
if (!$Java) { $Java = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot' }
$bt = Join-Path $Sdk 'build-tools\35.0.0'
$android = Join-Path $Sdk 'platforms\android-35\android.jar'
foreach ($file in @("$bt\aapt2.exe", "$bt\d8.bat", "$bt\zipalign.exe", "$bt\apksigner.bat", "$Java\bin\javac.exe", $android)) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing build tool: $file" }
}
$env:JAVA_HOME = $Java
$work = Join-Path $root ('build\apk-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
$dist = Join-Path $root 'dist'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
New-Item -ItemType Directory -Force -Path $work,$dist,$classes,$dex | Out-Null
function Run-Tool([string]$tool, [string[]]$arguments) {
    & $tool @arguments
    if ($LASTEXITCODE -ne 0) { throw "$tool failed ($LASTEXITCODE)" }
}
$main = Join-Path $root 'app\src\main'
$server = Join-Path $main 'assets\scrcpy-server-v4.1'
$expected = 'DEACB991ED2509715160FFDC7907E47B4160EB30D1566217E9047FD5B8850CAE'
if ((Get-FileHash -LiteralPath $server -Algorithm SHA256).Hash -ne $expected) { throw 'scrcpy server integrity check failed' }
Run-Tool "$bt\aapt2.exe" @('compile','--dir',"$main\res",'-o',"$work\resources.zip")
Run-Tool "$bt\aapt2.exe" @('link','-o',"$work\resources.apk",'-I',$android,'--manifest',"$main\AndroidManifest.xml",'-A',"$main\assets",'--min-sdk-version','28','--target-sdk-version','35',"$work\resources.zip")
$sources = @(Get-ChildItem -LiteralPath "$main\java" -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
Run-Tool "$Java\bin\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-Xlint:unchecked','-classpath',$android,'-d',$classes) + $sources)
Run-Tool "$Java\bin\jar.exe" @("-J-Djava.io.tmpdir=$work",'--create','--file',"$work\classes.jar",'-C',$classes,'.')
Run-Tool "$bt\d8.bat" @('--lib',$android,'--min-api','28','--output',$dex,"$work\classes.jar")
Copy-Item -LiteralPath "$work\resources.apk" -Destination "$work\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$work\unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose() }
Run-Tool "$bt\zipalign.exe" @('-f','-p','4',"$work\unsigned.apk","$work\aligned.apk")
$signing = Join-Path $root 'build\signing'
New-Item -ItemType Directory -Force -Path $signing | Out-Null
$key = Join-Path $signing 'debug.jks'
if (!(Test-Path -LiteralPath $key)) {
    Run-Tool "$Java\bin\keytool.exe" @('-genkeypair','-noprompt','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Android Debug,O=DeX Tablet Development,C=KR')
}
$apkName = 'DeXTablet-0.2.0-debug.apk'
$apk = Join-Path $dist $apkName
Run-Tool "$bt\apksigner.bat" @('sign','--ks',$key,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,"$work\aligned.apk")
Run-Tool "$bt\apksigner.bat" @('verify','--verbose',$apk)
Run-Tool "$bt\zipalign.exe" @('-c','-p','4',$apk)
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$apk.sha256", "$hash  $apkName`n", [Text.UTF8Encoding]::new($false))
Write-Output "APK: $apk"
Write-Output "SHA256: $hash"
