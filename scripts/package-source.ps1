$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$path = Join-Path $dist 'DeXTablet-0.2.0-source.zip'
$files = @()
foreach ($directory in @('app\src','scripts','tests')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $root $directory) -Recurse -File
}
foreach ($name in @('.gitignore','build.gradle','settings.gradle','app\build.gradle','LICENSE','README.md','SOURCES.md','VALIDATION.md','RELEASE.md')) {
    $files += Get-Item -LiteralPath (Join-Path $root $name)
}
foreach ($file in $files) {
    if ($file.Extension -in @('.jks','.keystore','.pk8','.der','.pem','.p12','.pfx')) { throw "Refusing to package key material: $($file.Name)" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($path,[IO.FileMode]::Create,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$zip = [IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($root.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose();$stream.Dispose() }
Write-Output "Source package: $path"
Write-Output "Files: $($files.Count)"
