# SPDX-License-Identifier: GPL-3.0-only

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Selector,
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [Parameter(Mandatory = $true)][string]$LogDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$api = 'https://fill.papermc.io/v3/projects/paper'
$headers = @{ 'User-Agent' = 'AMCTimber-paper-smoke/1.0 (+https://github.com/asketmc/AMCTimber)' }
$result = 'FAIL'
$reason = 'script exited before completing the smoke test'
$exitCode = 1
$resolvedVersion = 'unresolved'
$resolvedBuild = 'unresolved'
$paperHash = 'unresolved'
$pluginHash = 'unresolved'
$runtime = $null
$process = $null
$processStarted = $false
$commandPipe = $null
$commandWriter = $null

$logs = [IO.Path]::GetFullPath($LogDir)
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$consoleLog = Join-Path $logs 'console.log'
$summaryFile = Join-Path $logs 'smoke-summary.txt'

function Write-SmokeSummary {
    $summary = @(
        "result=$result",
        "selector=$Selector",
        "minecraft_version=$resolvedVersion",
        "paper_build=$resolvedBuild",
        "paper_sha256=$paperHash",
        "plugin_sha256=$pluginHash",
        "reason=$reason",
        "exit_code=$exitCode"
    )
    [IO.File]::WriteAllLines($summaryFile, $summary)
}

function Read-ServerLog {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return ''
    }
    return Get-Content -Raw -LiteralPath $Path -ErrorAction SilentlyContinue
}

function Send-ServerCommand {
    param(
        [Parameter(Mandatory = $true)][IO.TextWriter]$Writer,
        [Parameter(Mandatory = $true)][string]$Command
    )

    $Writer.WriteLine($Command)
    $Writer.Flush()
}

function Get-StableBuild {
    param([Parameter(Mandatory = $true)][string]$Version)

    $builds = Invoke-RestMethod -Headers $headers -Uri "$api/versions/$Version/builds"
    return $builds |
        Where-Object { $_.channel -eq 'STABLE' -and $null -ne $_.downloads.'server:default' } |
        Sort-Object id -Descending |
        Select-Object -First 1
}

try {
    if ($Selector -ne 'latest-1.21' -and $Selector -notmatch '^1[.]20[.]6$') {
        throw "Unsupported Paper selector: $Selector"
    }

    $plugin = (Resolve-Path -LiteralPath $PluginJar).Path
    $pluginHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $plugin).Hash.ToLowerInvariant()

    $build = $null
    if ($Selector -eq 'latest-1.21') {
        $project = Invoke-RestMethod -Headers $headers -Uri $api
        $releaseVersions = $project.versions.'1.21' |
            Where-Object { $_ -match '^1[.]21([.][0-9]+)?$' }
        foreach ($version in ($releaseVersions | Sort-Object { [version]$_ } -Descending)) {
            $candidate = Get-StableBuild -Version $version
            if ($null -ne $candidate) {
                $resolvedVersion = $version
                $build = $candidate
                break
            }
        }
    } else {
        $resolvedVersion = $Selector
        $build = Get-StableBuild -Version $Selector
    }
    if ($null -eq $build) {
        throw "No stable Paper build for $Selector"
    }
    $resolvedBuild = [string]$build.id

    $download = $build.downloads.'server:default'
    $paperUrl = [string]$download.url
    $paperHash = ([string]$download.checksums.sha256).ToLowerInvariant()
    if (-not $paperUrl.StartsWith('https://fill-data.papermc.io/')) {
        throw "Untrusted Paper download URL: $paperUrl"
    }
    if ($paperHash -notmatch '^[0-9a-f]{64}$') {
        throw 'Paper API returned an invalid SHA-256'
    }

    $tempRoot = if ($env:RUNNER_TEMP) {
        [IO.Path]::GetFullPath($env:RUNNER_TEMP)
    } else {
        [IO.Path]::GetTempPath()
    }
    $cache = Join-Path $tempRoot 'amctimber-paper-cache'
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $cachedPaper = Join-Path $cache "$paperHash.jar"
    $cacheIsValid = (Test-Path -LiteralPath $cachedPaper) -and
        ((Get-FileHash -Algorithm SHA256 -LiteralPath $cachedPaper).Hash.ToLowerInvariant() -eq $paperHash)
    if (-not $cacheIsValid) {
        Invoke-WebRequest -Headers $headers -Uri $paperUrl -OutFile $cachedPaper
    }
    $actualPaperHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $cachedPaper).Hash.ToLowerInvariant()
    if ($actualPaperHash -ne $paperHash) {
        throw 'Downloaded Paper JAR checksum mismatch'
    }

    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 25565)
    try {
        $portProbe.Start()
    } finally {
        $portProbe.Stop()
    }

    $runtime = Join-Path $tempRoot ("amctimber-paper-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path (Join-Path $runtime 'plugins') | Out-Null
    Copy-Item -LiteralPath $cachedPaper -Destination (Join-Path $runtime 'paper.jar')
    Copy-Item -LiteralPath $plugin -Destination (Join-Path $runtime 'plugins\AMCTimber.jar')
    [IO.File]::WriteAllText((Join-Path $runtime 'eula.txt'), "eula=true`n")
    $properties = @'
allow-flight=false
enable-query=false
enable-rcon=false
generate-structures=false
level-name=world
level-seed=1
level-type=minecraft:normal
max-players=1
motd=AMCTimber local smoke
online-mode=false
server-ip=127.0.0.1
server-port=25565
simulation-distance=2
spawn-protection=0
view-distance=2
'@
    [IO.File]::WriteAllText((Join-Path $runtime 'server.properties'), $properties)

    $javaCommand = Get-Command java.exe -ErrorAction Stop
    if ($javaCommand.Source.Contains('"')) {
        throw 'Java path contains an unsupported quote character'
    }
    $pipeName = 'amctimber-paper-' + [guid]::NewGuid().ToString('N')
    $commandPipe = [IO.Pipes.NamedPipeServerStream]::new(
        $pipeName,
        [IO.Pipes.PipeDirection]::Out,
        1,
        [IO.Pipes.PipeTransmissionMode]::Byte,
        [IO.Pipes.PipeOptions]::Asynchronous
    )
    $processInfo = [Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $env:ComSpec
    $processInfo.Arguments = "/d /s /c `"`"$($javaCommand.Source)`" -Xms512M -Xmx2G -Dterminal.jline=false -Dterminal.ansi=false -jar paper.jar --nogui < `"\\.\pipe\$pipeName`"`""
    $processInfo.WorkingDirectory = $runtime
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardInput = $false
    $processInfo.RedirectStandardOutput = $false
    $processInfo.RedirectStandardError = $false
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $processInfo
    $latestLog = Join-Path $runtime 'logs\latest.log'

    if (-not $process.Start()) {
        throw 'Paper process did not start'
    }
    $processStarted = $true
    $connectTask = $commandPipe.WaitForConnectionAsync()
    if (-not $connectTask.Wait(30000)) {
        throw 'Paper process did not connect to its command pipe within 30 seconds'
    }
    $commandWriter = [IO.StreamWriter]::new(
        $commandPipe,
        [Text.ASCIIEncoding]::new(),
        1024,
        $true
    )
    $commandWriter.NewLine = "`n"
    $commandWriter.AutoFlush = $true

    $startupDeadline = [DateTime]::UtcNow.AddSeconds(300)
    do {
        if ($process.HasExited) {
            throw "Paper exited during startup with status $($process.ExitCode)"
        }
        $text = Read-ServerLog -Path $latestLog
        if ($text -match 'Done \([^)]*\)! For help, type "help"') {
            break
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $startupDeadline)
    if ($text -notmatch 'Done \([^)]*\)! For help, type "help"') {
        throw 'Paper did not complete startup within 300 seconds'
    }

    $commandStart = $text.Length
    Send-ServerCommand -Writer $commandWriter -Command 'amctimber selftest'
    $selftestDeadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        if ($process.HasExited) {
            throw "Paper exited during selftest with status $($process.ExitCode)"
        }
        $text = Read-ServerLog -Path $latestLog
        $commandOutput = if ($text.Length -gt $commandStart) { $text.Substring($commandStart) } else { '' }
        if ($commandOutput -match 'FAIL [0-9]+/[0-9]+ AMCTimber') {
            throw 'AMCTimber selftest reported FAIL'
        }
        $selftestMatch = [regex]::Match($commandOutput, 'PASS ([0-9]+)/([0-9]+) AMCTimber selftest ok')
        if ($selftestMatch.Success) {
            break
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $selftestDeadline)
    if (-not $selftestMatch.Success) {
        throw 'AMCTimber selftest did not report PASS within 60 seconds'
    }
    $passed = [int]$selftestMatch.Groups[1].Value
    $total = [int]$selftestMatch.Groups[2].Value
    if ($total -le 0 -or $passed -ne $total) {
        throw "AMCTimber selftest was empty or incomplete: $passed/$total"
    }

    Send-ServerCommand -Writer $commandWriter -Command 'stop'
    if (-not $process.WaitForExit(90000)) {
        throw 'Paper did not stop cleanly within 90 seconds'
    }
    if ($process.ExitCode -ne 0) {
        throw "Paper exited with status $($process.ExitCode) after the stop command"
    }
    $text = Read-ServerLog -Path $latestLog
    if ($text -notmatch 'Stopping server|Closing Server') {
        throw 'Paper exited without a clean shutdown marker'
    }

    $result = 'PASS'
    $reason = 'startup, AMCTimber selftest, and clean shutdown completed'
    $exitCode = 0
} catch {
    $reason = $_.Exception.Message
    throw
} finally {
    if ($null -ne $process -and $processStarted -and -not $process.HasExited) {
        try {
            if ($null -ne $commandWriter) {
                Send-ServerCommand -Writer $commandWriter -Command 'stop'
            }
        } catch {
            # Cleanup continues to the bounded process wait and kill.
        }
        if (-not $process.WaitForExit(30000)) {
            & "$env:SystemRoot\System32\taskkill.exe" /PID $process.Id /T /F *> $null
            $process.WaitForExit()
        }
    }
    if ($null -ne $commandWriter) {
        try {
            $commandWriter.Dispose()
        } catch {
            # The server can close the pipe before local disposal.
        }
    }
    if ($null -ne $commandPipe) {
        $commandPipe.Dispose()
    }
    if ($null -ne $runtime) {
        $latestLog = Join-Path $runtime 'logs\latest.log'
        if (Test-Path -LiteralPath $latestLog) {
            Copy-Item -LiteralPath $latestLog -Destination $consoleLog -Force
        }
        Remove-Item -LiteralPath $runtime -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($null -ne $process) {
        $process.Dispose()
    }
    Write-SmokeSummary
}

Write-Output "Paper $resolvedVersion build $resolvedBuild`: AMCTimber selftest PASS"
