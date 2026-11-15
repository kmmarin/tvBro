# JVM smoke test of the UniFFI Kotlin bindings on Windows x64 (PowerShell 5.1, build machine toolchain paths).
#   powershell -File core\tvbro-ffi\jvm-smoke\run.ps1
$ErrorActionPreference = 'Stop'
$env:RUSTUP_HOME = 'C:\tools\native\rust\rustup'; $env:CARGO_HOME = 'C:\tools\native\rust\cargo'
$env:PATH = "C:\tools\native\rust\cargo\bin;$env:PATH"
$env:JAVA_HOME = 'C:\tools\native\jdk17'; $env:GRADLE_USER_HOME = 'C:\tools\native\gradle-home'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\tools\native\tmp'
$core = Resolve-Path "$PSScriptRoot\..\.."
Push-Location $core
try {
    cargo build -p tvbro-ffi
    if ($LASTEXITCODE) { throw "cargo build failed" }
    cargo run -q -p tvbro-ffi --features bindgen --bin uniffi-bindgen -- generate --library target/debug/tvbro_ffi.dll --language kotlin --no-format --out-dir bindings/kotlin
    if ($LASTEXITCODE) { throw "bindgen failed" }
    & "$core\..\gradlew.bat" -p "$PSScriptRoot" --offline --console=plain -q run "-PlibDir=$core\target\debug"
    if ($LASTEXITCODE) { throw "smoke test failed" }
} finally { Pop-Location }
