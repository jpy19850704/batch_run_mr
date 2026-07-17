param(
    [int]$StartupTimeoutSeconds = 120
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$engineRoot = $PSScriptRoot
$workspaceRoot = Split-Path -Parent $engineRoot
$envFile = Join-Path $workspaceRoot "deploy\env\stack.local.env"
$runtimeDir = Join-Path $engineRoot ".runtime"
$pidFile = Join-Path $runtimeDir "engine.pid"
$outLog = Join-Path $runtimeDir "engine.out.log"
$errLog = Join-Path $runtimeDir "engine.err.log"
$jarPath = Join-Path $engineRoot "mr-app\target\mr-app.jar"
$runtimeConfigPath = Join-Path $engineRoot "config\application-runtime.properties"
$runtimeConfigUri = ([System.Uri]::new($runtimeConfigPath)).AbsoluteUri
$enginePort = 8081

function Import-EnvFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Startup environment file not found: $path"
    }
    Get-Content -LiteralPath $path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            return
        }
        $separator = $line.IndexOf("=")
        if ($separator -le 0) {
            return
        }
        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Assert-RequiredEnvironment {
    $required = @(
        "ENGINE_DB_URL", "ENGINE_DB_USERNAME", "ENGINE_DB_PASSWORD",
        "ENGINE_RESULT_DB_URL", "ENGINE_RESULT_DB_USERNAME", "ENGINE_RESULT_DB_PASSWORD",
        "MR_SECURITY_TOKENS", "MR_ENGINE_AUTH_TOKEN"
    )
    $missing = @($required | Where-Object {
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
    })
    if ($missing.Count -gt 0) {
        throw "Missing required startup parameters: $($missing -join ', ')"
    }
}

function Assert-RuntimeConfig {
    if (-not (Test-Path -LiteralPath $runtimeConfigPath -PathType Leaf)) {
        throw "Engine runtime config not found: $runtimeConfigPath"
    }
}

function Assert-WebTokenMapping {
    $matched = $false
    foreach ($entry in $env:MR_SECURITY_TOKENS.Split(',')) {
        $safeEntry = $entry.Trim()
        $separator = $safeEntry.LastIndexOf(':')
        if ($separator -gt 0) {
            $token = $safeEntry.Substring(0, $separator).Trim()
            if ($token -ceq $env:MR_ENGINE_AUTH_TOKEN) {
                $matched = $true
                break
            }
        }
    }
    if (-not $matched) {
        throw "MR_ENGINE_AUTH_TOKEN is not registered in MR_SECURITY_TOKENS"
    }
}

function Assert-JavaRuntime {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "java"
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $javaVersionOutput = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or $javaVersionOutput -notmatch 'version "17(?:\.|"|-)') {
        throw "JDK 17 is required to start engine"
    }
}

function Stop-ExistingEngineProcess {
    $engineProcesses = @(Get-CimInstance Win32_Process -Filter "name='java.exe'" |
        Where-Object { $_.CommandLine -like '*\mr-app*.jar*' })
    foreach ($process in $engineProcesses) {
        Stop-Process -Id $process.ProcessId -Force
    }

    $deadline = (Get-Date).AddSeconds(15)
    do {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $enginePort -ErrorAction SilentlyContinue
        if ($null -eq $listener) {
            return
        }
        $knownEnginePid = $engineProcesses | Where-Object { $_.ProcessId -eq $listener.OwningProcess }
        if ($null -eq $knownEnginePid) {
            throw "Port $enginePort is occupied by a non-engine process, PID=$($listener.OwningProcess)"
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Port $enginePort was not released after stopping the old engine process"
}

function Build-EngineJar {
    Push-Location $engineRoot
    try {
        & mvn clean package -pl mr-app -am -q "-DskipTests"
        if ($LASTEXITCODE -ne 0) {
            throw "engine clean package failed, exit code: $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "Fat jar was not found after build: $jarPath"
    }
}

function Start-And-VerifyEngine {
    [System.IO.File]::WriteAllText($outLog, "", (New-Object System.Text.UTF8Encoding($false)))
    [System.IO.File]::WriteAllText($errLog, "", (New-Object System.Text.UTF8Encoding($false)))
    $process = Start-Process -FilePath "java" `
        -ArgumentList @(
            "-jar", $jarPath,
            "--spring.config.additional-location=$runtimeConfigUri",
            "--server.port=$enginePort"
        ) `
        -WorkingDirectory $engineRoot `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden `
        -PassThru
    [System.IO.File]::WriteAllText($pidFile, $process.Id.ToString(), (New-Object System.Text.UTF8Encoding($false)))

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        $process.Refresh()
        if ($process.HasExited) {
            $errorTail = if (Test-Path -LiteralPath $errLog) {
                (Get-Content -LiteralPath $errLog -Tail 30 -Encoding UTF8) -join [Environment]::NewLine
            } else { "" }
            throw "Engine process exited during startup, exit code=$($process.ExitCode)`n$errorTail"
        }
        $listener = Get-NetTCPConnection -State Listen -LocalPort $enginePort -ErrorAction SilentlyContinue
        if ($listener -and $listener.OwningProcess -eq $process.Id) {
            try {
                $healthResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$enginePort/healthz" `
                    -UseBasicParsing -TimeoutSec 5
                $readyResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$enginePort/readyz" `
                    -UseBasicParsing -TimeoutSec 5
                $health = $healthResponse.Content | ConvertFrom-Json
                $ready = $readyResponse.Content | ConvertFrom-Json
                if ($healthResponse.StatusCode -eq 200 -and $readyResponse.StatusCode -eq 200 -and
                    $health.data.status -eq "UP" -and $ready.data.status -eq "READY" -and
                    $ready.data.dbReady -eq $true -and $ready.data.executorReady -eq $true -and
                    $ready.data.dispatcherReady -eq $true) {
                    return $process
                }
            } catch {
            }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    throw "Engine did not pass health and readiness verification within $StartupTimeoutSeconds seconds"
}

New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
Assert-RuntimeConfig
Import-EnvFile $envFile
if (-not [string]::IsNullOrWhiteSpace($env:MR_SERVER_PORT)) {
    $enginePort = [int]$env:MR_SERVER_PORT
}
Assert-RequiredEnvironment
Assert-WebTokenMapping
Assert-JavaRuntime
Stop-ExistingEngineProcess
Build-EngineJar
$jar = Get-Item -LiteralPath $jarPath
$startedProcess = Start-And-VerifyEngine

Write-Output "ENGINE_START_OK"
Write-Output "pid=$($startedProcess.Id)"
Write-Output "port=$enginePort"
Write-Output "jar=$($jar.FullName)"
Write-Output "jarLastWriteTime=$($jar.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Output "jarLength=$($jar.Length)"
Write-Output "runtimeConfig=$runtimeConfigPath"
Write-Output "health=http://127.0.0.1:$enginePort/healthz"
Write-Output "ready=http://127.0.0.1:$enginePort/readyz"
