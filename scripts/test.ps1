param([string]$Java = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
if (!$Java) { $Java = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot' }
$out = Join-Path $root ('build\tests-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(Get-ChildItem -LiteralPath "$root\app\src\main\java\dev\dextablet\core","$root\tests" -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& "$Java\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& "$Java\bin\java.exe" -cp $out dev.dextablet.core.CoreTests $out
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
