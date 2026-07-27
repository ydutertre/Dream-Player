param(
    [Parameter(Mandatory = $true)]
    [string] $NdkRoot,

    [string] $ApiLevel = "29",

    [string] $OutputDirectory = "build\edge264-android\arm64-v8a"
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
$edgeRoot = Join-Path $workspace "third_party\edge264"
$output = Join-Path $workspace $OutputDirectory
$toolchain = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$compiler = Join-Path $toolchain "aarch64-linux-android$ApiLevel-clang.cmd"

if (-not (Test-Path -LiteralPath $compiler)) {
    throw "Android arm64 compiler not found: $compiler"
}
if (-not (Test-Path -LiteralPath (Join-Path $edgeRoot "src\edge264.c"))) {
    throw "Edge264 source not found under $edgeRoot"
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
$object = Join-Path $output "edge264.o"
$library = Join-Path $output "libedge264.so"
$testExecutable = Join-Path $output "edge264_android_frame_test"

& $compiler `
    -std=gnu11 -O3 -flax-vector-conversions -Wno-override-init `
    -pthread -fPIC `
    "-I$edgeRoot" "-I$(Join-Path $edgeRoot 'src')" `
    -c (Join-Path $edgeRoot "src\edge264.c") `
    -o $object
if ($LASTEXITCODE -ne 0) {
    throw "Edge264 compilation failed with exit code $LASTEXITCODE"
}

& $compiler -shared -pthread $object -o $library
if ($LASTEXITCODE -ne 0) {
    throw "Edge264 shared-library link failed with exit code $LASTEXITCODE"
}

& $compiler `
    -std=gnu11 -O2 "-I$edgeRoot" `
    (Join-Path $PSScriptRoot "edge264_android_frame_test.c") `
    $object -pthread -o $testExecutable
if ($LASTEXITCODE -ne 0) {
    throw "Offline frame-test link failed with exit code $LASTEXITCODE"
}

Get-Item -LiteralPath $library, $testExecutable |
    Select-Object FullName, Length, LastWriteTime
