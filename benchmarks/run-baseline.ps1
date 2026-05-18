param(
    [string[]]$Profiles = @("read-heavy", "write-heavy", "bulk-heavy", "mixed", "metadata-heavy"),
    [int]$Concurrency = 10,
    [int]$WarmupSeconds = 15,
    [int]$DurationSeconds = 120,
    [int]$Keyspace = 1000,
    [int]$SeedCount = 1000,
    [string]$HostName = "127.0.0.1",
    [int]$ShimPort = 40405,
    [int]$HealthPort = 8081,
    [string]$Region = "helloWorld",
    [switch]$SkipVerify,
    [switch]$SkipDockerRestart
)

$ErrorActionPreference = "Stop"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

# This script is intended to live at:
#   benchmarks/run-baseline.ps1
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..")
Set-Location $repoRoot

$resultRoot = Join-Path $repoRoot "benchmarks\results\$timestamp"
New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null

function Write-Section {
    param([string]$Name)
    Write-Host ""
    Write-Host "============================================================"
    Write-Host $Name
    Write-Host "============================================================"
}

function Run-And-Capture {
    param(
        [string]$Command,
        [string]$OutputFile
    )

    Write-Host "Running: $Command"
    $fullPath = Join-Path $resultRoot $OutputFile

    # Redirect stderr inside cmd.exe so PowerShell does not convert normal native
    # stderr output, such as Docker Compose progress lines, into NativeCommandError
    # records when $ErrorActionPreference is Stop.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"

    try {
        cmd.exe /d /s /c "$Command 2>&1" | Tee-Object -FilePath $fullPath
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        throw "Command failed with exit code ${exitCode}: $Command"
    }
}

function Capture-Text {
    param(
        [string]$Command,
        [string]$OutputFile,
        [switch]$IgnoreFailure
    )

    $fullPath = Join-Path $resultRoot $OutputFile
    Write-Host "Capturing: $Command -> $OutputFile"

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"

    try {
        cmd.exe /d /s /c "$Command > `"$fullPath`" 2>&1"
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0 -and -not $IgnoreFailure) {
        throw "Command failed with exit code ${exitCode}: $Command"
    }

    if ($exitCode -ne 0 -and $IgnoreFailure) {
        Add-Content -Path $fullPath -Value ""
        Add-Content -Path $fullPath -Value "CAPTURE EXIT CODE: $exitCode"
    }
}

function Curl-Capture {
    param(
        [string]$Url,
        [string]$OutputFile
    )

    $fullPath = Join-Path $resultRoot $OutputFile
    Write-Host "Fetching: $Url -> $OutputFile"

    try {
        Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10 |
                Select-Object -ExpandProperty Content |
                Out-File -FilePath $fullPath -Encoding utf8
    } catch {
        "HTTP CAPTURE FAILED: $($_.Exception.Message)" | Out-File -FilePath $fullPath -Encoding utf8
        throw
    }
}

function Curl-Capture-Profile {
    param(
        [string]$Profile,
        [string]$Phase
    )

    Curl-Capture `
        -Url "http://${HostName}:${HealthPort}/metrics/json" `
        -OutputFile "$Profile-$Phase-metrics.json"

    Curl-Capture `
        -Url "http://${HostName}:${HealthPort}/metrics" `
        -OutputFile "$Profile-$Phase-metrics.prom"
}

function Capture-Environment {
    Write-Section "Capturing environment"

    Capture-Text "git rev-parse --abbrev-ref HEAD" "git-branch.txt" -IgnoreFailure
    Capture-Text "git rev-parse HEAD" "git-commit.txt" -IgnoreFailure
    Capture-Text "git status --short" "git-status.txt" -IgnoreFailure
    Capture-Text "java -version" "java-version.txt" -IgnoreFailure
    Capture-Text "mvn -version" "maven-version.txt" -IgnoreFailure
    Capture-Text "docker version" "docker-version.txt" -IgnoreFailure
    Capture-Text "docker compose version" "docker-compose-version.txt" -IgnoreFailure
    Capture-Text "docker ps" "docker-ps-before.txt" -IgnoreFailure

    $config = @{
        timestamp = $timestamp
        profiles = $Profiles
        concurrency = $Concurrency
        warmupSeconds = $WarmupSeconds
        durationSeconds = $DurationSeconds
        keyspace = $Keyspace
        seedCount = $SeedCount
        hostName = $HostName
        shimPort = $ShimPort
        healthPort = $HealthPort
        region = $Region
        skipVerify = [bool]$SkipVerify
        skipDockerRestart = [bool]$SkipDockerRestart
    }

    $config | ConvertTo-Json -Depth 5 | Out-File -FilePath (Join-Path $resultRoot "baseline-config.json") -Encoding utf8
}

function Validate-Health {
    Write-Section "Validating health endpoints"

    Curl-Capture -Url "http://${HostName}:${HealthPort}/live" -OutputFile "live.json"
    Curl-Capture -Url "http://${HostName}:${HealthPort}/ready" -OutputFile "ready.json"
    Curl-Capture -Url "http://${HostName}:${HealthPort}/metrics/json" -OutputFile "initial-metrics.json"
    Curl-Capture -Url "http://${HostName}:${HealthPort}/metrics" -OutputFile "initial-metrics.prom"
}

function Run-BenchmarkProfile {
    param([string]$Profile)

    Write-Section "Running benchmark profile: $Profile"

    Curl-Capture-Profile -Profile $Profile -Phase "before"

    $env:BENCH_PROFILE = $Profile
    $env:BENCH_HOST = $HostName
    $env:BENCH_PORT = "$ShimPort"
    $env:BENCH_REGION = $Region
    $env:BENCH_CONCURRENCY = "$Concurrency"
    $env:BENCH_WARMUP_SECONDS = "$WarmupSeconds"
    $env:BENCH_DURATION_SECONDS = "$DurationSeconds"
    $env:BENCH_KEYSPACE = "$Keyspace"
    $env:BENCH_SEED = "true"
    $env:BENCH_SEED_COUNT = "$SeedCount"
    $env:BENCH_PROGRESS_SECONDS = "15"

    $benchmarkCommand = "mvn -q exec:java -Dexec.mainClass=com.protogemcouch.benchmark.ConcurrentBenchmarkRunner"
    Run-And-Capture $benchmarkCommand "$Profile-benchmark-output.txt"

    Curl-Capture-Profile -Profile $Profile -Phase "after"

    Capture-Text "docker logs protogemcouch-shim --tail 500" "$Profile-shim-logs-tail.txt" -IgnoreFailure
    Capture-Text "docker stats --no-stream protogemcouch-shim protogemcouch-couchbase" "$Profile-docker-stats.txt" -IgnoreFailure
}

Write-Section "ProtoGemCouch baseline run"
Write-Host "Repo root: $repoRoot"
Write-Host "Result directory: $resultRoot"

Capture-Environment

if (-not $SkipVerify) {
    Write-Section "Running tests"
    Run-And-Capture "mvn test" "mvn-test.txt"
    Run-And-Capture "mvn clean verify" "mvn-clean-verify.txt"
}

if (-not $SkipDockerRestart) {
    Write-Section "Restarting Docker Compose stack"
    Run-And-Capture "docker compose down -v" "docker-compose-down.txt"
    Run-And-Capture "mvn clean package -DskipTests" "mvn-package-skip-tests.txt"
    Run-And-Capture "docker compose up -d --build" "docker-compose-up.txt"

    Write-Host "Waiting 20 seconds for services..."
    Start-Sleep -Seconds 20
}

Validate-Health

foreach ($profile in $Profiles) {
    Run-BenchmarkProfile -Profile $profile
}

Write-Section "Final capture"
Capture-Text "docker ps" "docker-ps-after.txt" -IgnoreFailure
Capture-Text "docker logs protogemcouch-shim --tail 1000" "final-shim-logs-tail.txt" -IgnoreFailure
Capture-Text "docker stats --no-stream protogemcouch-shim protogemcouch-couchbase" "final-docker-stats.txt" -IgnoreFailure
Curl-Capture -Url "http://${HostName}:${HealthPort}/metrics/json" -OutputFile "final-metrics.json"
Curl-Capture -Url "http://${HostName}:${HealthPort}/metrics" -OutputFile "final-metrics.prom"

Write-Section "Baseline run complete"
Write-Host "Artifacts saved to: $resultRoot"
