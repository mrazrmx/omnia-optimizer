param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr",
    [string]$KeystorePath = "$PSScriptRoot\local-dev.keystore",
    [string]$KeystorePass = "android",
    [string]$KeyAlias = "omniauniversal",
    [string]$OutApk = "$PSScriptRoot\OmniaOptimizer-Universal-v4.4.1.apk"
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = $JavaHome

$btDir = Get-ChildItem "$SdkRoot\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $btDir) { throw "Android build-tools not found under $SdkRoot\build-tools" }
$BT = $btDir.FullName

$platDir = Get-ChildItem "$SdkRoot\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $platDir) { throw "Android platform not found under $SdkRoot\platforms" }
$PLAT = Join-Path $platDir.FullName "android.jar"

$JDK = Join-Path $JavaHome "bin"
$BUILD = Join-Path $PSScriptRoot "build"

if (-not (Test-Path $KeystorePath)) {
    & "$JDK\keytool.exe" -genkeypair -v -keystore $KeystorePath -alias $KeyAlias `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $KeystorePass -keypass $KeystorePass `
        -dname "CN=OmniaOptimizer,O=OpenSource,C=BR" | Out-Null
}

if (Test-Path $BUILD) { Remove-Item -Recurse -Force $BUILD }
New-Item -ItemType Directory -Force -Path "$BUILD\gen", "$BUILD\obj", "$BUILD\dex" | Out-Null

& "$BT\aapt2.exe" compile --dir "$PSScriptRoot\res" -o "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& "$BT\aapt2.exe" link -o "$BUILD\unaligned.apk" -I $PLAT --manifest "$PSScriptRoot\AndroidManifest.xml" `
    --java "$BUILD\gen" --min-sdk-version 26 --target-sdk-version 34 -R "$BUILD\res.zip" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$javaFiles = @(Get-ChildItem -Recurse "$BUILD\gen", "$PSScriptRoot\src" -Filter *.java | ForEach-Object { $_.FullName })
& "$JDK\javac.exe" --release 17 -encoding UTF-8 -classpath $PLAT -d "$BUILD\obj" @javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& "$JDK\jar.exe" cf "$BUILD\classes.jar" -C "$BUILD\obj" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& "$BT\d8.bat" --release --min-api 26 --lib $PLAT --output "$BUILD\dex" "$BUILD\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path "$BUILD\unaligned.apk").Path, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Resolve-Path "$BUILD\dex\classes.dex").Path, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

& "$BT\zipalign.exe" -f -p 4 "$BUILD\unaligned.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& "$BT\apksigner.bat" sign --ks $KeystorePath --ks-pass "pass:$KeystorePass" --out $OutApk "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$BT\apksigner.bat" verify $OutApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Remove-Item -Recurse -Force $BUILD
Write-Host "Universal APK built successfully: $OutApk"
