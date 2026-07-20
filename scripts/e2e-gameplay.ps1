# SPDX-License-Identifier: GPL-3.0-only

[CmdletBinding()]
param(
    [ValidateSet('paper', 'purpur', 'pufferfish')][string]$Runtime = 'paper',
    [string]$MinecraftVersion = '1.21.11',
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [Parameter(Mandatory = $true)][string]$QaBotJar,
    [string[]]$AdditionalPluginJar = @(),
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$JavaExecutable = 'java.exe',
    [int]$ConcurrencyCycles = 3,
    [switch]$EventPolicyFixture,
    [switch]$KeepRuntime
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$headers = @{ 'User-Agent' = 'AMCTimber-gameplay-e2e/1.0 (+https://github.com/asketmc/AMCTimber)' }
$evidence = [IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$consoleLog = Join-Path $evidence 'latest.log'
$receiptPath = Join-Path $evidence 'receipt.json'
$runtimeDir = Join-Path ([IO.Path]::GetTempPath()) ('amctimber-e2e-' + [guid]::NewGuid().ToString('N'))
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$runtimeDir = [IO.Path]::GetFullPath($runtimeDir)
if (-not $runtimeDir.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
    -not ([IO.Path]::GetFileName($runtimeDir)).StartsWith('amctimber-e2e-', [StringComparison]::Ordinal)) {
    throw "Refusing to use unsafe runtime directory: $runtimeDir"
}
$cacheDir = Join-Path ([IO.Path]::GetTempPath()) 'amctimber-e2e-cache'
New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

$script:serverProcess = $null
$script:commandPipe = $null
$script:commandWriter = $null
$script:processStarted = $false
$script:latestLog = Join-Path $runtimeDir 'logs\latest.log'
$script:results = [Collections.Generic.List[object]]::new()
$script:performance = $null
$script:restartRecovery = $null
$script:concurrencyTimeBudgetRetries = 0

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Resolve-ServerArtifact {
    if ($Runtime -eq 'paper') {
        $api = "https://fill.papermc.io/v3/projects/paper/versions/$MinecraftVersion/builds"
        $builds = Invoke-RestMethod -Headers $headers -Uri $api
        $build = $builds |
            Where-Object { $_.channel -eq 'STABLE' -and $null -ne $_.downloads.'server:default' } |
            Sort-Object id -Descending |
            Select-Object -First 1
        if ($null -eq $build) { throw "No stable Paper build for $MinecraftVersion" }
        $download = $build.downloads.'server:default'
        $url = [string]$download.url
        $sha256 = ([string]$download.checksums.sha256).ToLowerInvariant()
        if (-not $url.StartsWith('https://fill-data.papermc.io/')) { throw "Untrusted Paper URL: $url" }
        if ($sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Paper API returned an invalid SHA-256' }
        return [pscustomobject]@{
            Runtime = 'paper'; Version = $MinecraftVersion; Build = [string]$build.id
            Url = $url; ExpectedAlgorithm = 'sha256'; ExpectedHash = $sha256
            ChecksumSource = 'vendor_sha256'
        }
    }

    if ($Runtime -eq 'purpur') {
        $versionApi = "https://api.purpurmc.org/v2/purpur/$MinecraftVersion"
        $version = Invoke-RestMethod -Headers $headers -Uri $versionApi
        $buildId = [string]$version.builds.latest
        if ($buildId -notmatch '^\d+$') { throw "No successful Purpur build for $MinecraftVersion" }
        $build = Invoke-RestMethod -Headers $headers -Uri "$versionApi/$buildId"
        if ($build.result -ne 'SUCCESS' -or ([string]$build.md5) -notmatch '^[0-9a-fA-F]{32}$') {
            throw "Purpur build $buildId has no successful checksum receipt"
        }
        return [pscustomobject]@{
            Runtime = 'purpur'; Version = $MinecraftVersion; Build = $buildId
            Url = "$versionApi/$buildId/download"; ExpectedAlgorithm = 'md5'
            ExpectedHash = ([string]$build.md5).ToLowerInvariant()
            ChecksumSource = 'vendor_md5'
        }
    }

    if ($MinecraftVersion -notmatch '^(1[.]\d+)(?:[.]\d+)?$') {
        throw "Unsupported Pufferfish Minecraft version format: $MinecraftVersion"
    }
    $jobName = "Pufferfish-$($Matches[1])"
    $jobApi = "https://ci.pufferfish.host/job/$jobName/api/json?tree=builds[number,result,timestamp,artifacts[*]]"
    $job = Invoke-RestMethod -Headers $headers -Uri $jobApi
    $escapedVersion = [regex]::Escape($MinecraftVersion)
    $build = $job.builds |
        Where-Object {
            $_.result -eq 'SUCCESS' -and
            @($_.artifacts | Where-Object { $_.relativePath -match "pufferfish-paperclip-$escapedVersion-R0[.]1-SNAPSHOT-mojmap[.]jar$" }).Count -gt 0
        } |
        Sort-Object number -Descending |
        Select-Object -First 1
    if ($null -eq $build) { throw "No successful Pufferfish build for $MinecraftVersion" }
    $relativePath = [string](@($build.artifacts | Where-Object {
        $_.relativePath -match "pufferfish-paperclip-$escapedVersion-R0[.]1-SNAPSHOT-mojmap[.]jar$"
    })[0].relativePath)
    $encodedPath = (($relativePath -split '/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
    $url = "https://ci.pufferfish.host/job/$jobName/$($build.number)/artifact/$encodedPath"
    return [pscustomobject]@{
        Runtime = 'pufferfish'; Version = $MinecraftVersion; Build = [string]$build.number
        Url = $url; ExpectedAlgorithm = 'none'; ExpectedHash = $null
        ChecksumSource = 'not_published_recorded_locally'
    }
}

function Get-ServerJar {
    param([Parameter(Mandatory = $true)]$Artifact)
    $cached = Join-Path $cacheDir ("{0}-{1}-{2}.jar" -f $Artifact.Runtime, $Artifact.Version, $Artifact.Build)
    $valid = $false
    if (Test-Path -LiteralPath $cached) {
        $actual = if ($Artifact.ExpectedAlgorithm -eq 'sha256') {
            Get-FileSha256 -Path $cached
        } elseif ($Artifact.ExpectedAlgorithm -eq 'md5') {
            (Get-FileHash -Algorithm MD5 -LiteralPath $cached).Hash.ToLowerInvariant()
        } else {
            $null
        }
        $valid = $Artifact.ExpectedAlgorithm -eq 'none' -or $actual -eq $Artifact.ExpectedHash
    }
    if (-not $valid) { Invoke-WebRequest -Headers $headers -Uri $Artifact.Url -OutFile $cached }
    $verified = if ($Artifact.ExpectedAlgorithm -eq 'sha256') {
        Get-FileSha256 -Path $cached
    } elseif ($Artifact.ExpectedAlgorithm -eq 'md5') {
        (Get-FileHash -Algorithm MD5 -LiteralPath $cached).Hash.ToLowerInvariant()
    } else {
        $null
    }
    if ($Artifact.ExpectedAlgorithm -ne 'none' -and $verified -ne $Artifact.ExpectedHash) {
        throw 'Downloaded server JAR checksum mismatch'
    }
    return [pscustomobject]@{ Path = $cached; Sha256 = (Get-FileSha256 -Path $cached) }
}

function Read-LatestLog {
    if (-not (Test-Path -LiteralPath $script:latestLog)) { return '' }
    return [string](Get-Content -Raw -LiteralPath $script:latestLog -ErrorAction SilentlyContinue)
}

function Get-JavaVersionLine {
    param([Parameter(Mandatory = $true)][string]$JavaPath)
    $processInfo = [Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $JavaPath
    $processInfo.Arguments = '-version'
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($processInfo)
    try {
        $stderr = $process.StandardError.ReadToEnd()
        $stdout = $process.StandardOutput.ReadToEnd()
        if (-not $process.WaitForExit(10000)) { throw 'Timed out reading Java version' }
        $text = if ([string]::IsNullOrWhiteSpace($stderr)) { $stdout } else { $stderr }
        return (($text -split "`r?`n" | Select-Object -First 1) -join '')
    } finally {
        $process.Dispose()
    }
}

function Send-ServerCommand {
    param([Parameter(Mandatory = $true)][string]$Command)
    if ($null -eq $script:commandWriter) { throw 'Server command pipe is not available' }
    $script:commandWriter.WriteLine($Command)
    $script:commandWriter.Flush()
}

function Wait-LogPattern {
    param(
        [Parameter(Mandatory = $true)][string]$Pattern,
        [int]$TimeoutSeconds = 60,
        [int]$StartOffset = 0
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($null -ne $script:serverProcess -and $script:serverProcess.HasExited) {
            throw "Server exited unexpectedly with status $($script:serverProcess.ExitCode)"
        }
        $text = [string](Read-LatestLog)
        $tail = if ($text.Length -gt $StartOffset) { $text.Substring($StartOffset) } else { '' }
        if ($tail -match $Pattern) { return $tail }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for log pattern: $Pattern"
}

function Invoke-CommandBatch {
    param([Parameter(Mandatory = $true)][string[]]$Commands, [int]$TimeoutSeconds = 30)
    $marker = [guid]::NewGuid().ToString('N')
    $start = ([string](Read-LatestLog)).Length
    Send-ServerCommand -Command "say AMCT_E2E_BEGIN_$marker"
    foreach ($command in $Commands) { Send-ServerCommand -Command $command }
    Send-ServerCommand -Command "say AMCT_E2E_END_$marker"
    return Wait-LogPattern -Pattern "AMCT_E2E_END_$marker" -TimeoutSeconds $TimeoutSeconds -StartOffset $start
}

function Invoke-CapturedCommand {
    param([Parameter(Mandatory = $true)][string]$Command, [int]$TimeoutSeconds = 30)
    return Invoke-CommandBatch -Commands @($Command) -TimeoutSeconds $TimeoutSeconds
}

function Assert-Text {
    param([string]$Text, [string]$Pattern, [string]$Message)
    if ($Text -notmatch $Pattern) { throw "$Message (missing /$Pattern/)" }
}

function Assert-Blocks {
    param([Parameter(Mandatory = $true)][object[]]$Blocks)
    $commands = [Collections.Generic.List[string]]::new()
    $markers = [Collections.Generic.List[string]]::new()
    $index = 0
    foreach ($block in $Blocks) {
        $marker = "AMCT_BLOCK_PASS_$index"
        $commands.Add("execute if block $($block.X) $($block.Y) $($block.Z) minecraft:$($block.Material) run say $marker")
        $markers.Add($marker)
        $index++
    }
    $output = Invoke-CommandBatch -Commands $commands.ToArray()
    for ($i = 0; $i -lt $markers.Count; $i++) {
        if ($output -notmatch [regex]::Escape($markers[$i])) {
            $block = $Blocks[$i]
            throw "Expected minecraft:$($block.Material) at $($block.X),$($block.Y),$($block.Z)"
        }
    }
}

function Wait-BotOnline {
    param([string]$Name, [int]$TimeoutSeconds = 60)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $output = Invoke-CapturedCommand -Command 'bot list'
        if ($output -match ("(?s)" + [regex]::Escape($Name) + ".*?online.*?TAB")) { return $output }
        if ($output -match ("(?s)" + [regex]::Escape($Name) + ".*?(error|kicked).*")) {
            throw "Bot $Name failed to join: $output"
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Bot $Name did not reach online/TAB within $TimeoutSeconds seconds"
}

function Wait-TrunkCount {
    param([int]$Expected, [int]$TimeoutSeconds = 30)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $output = Invoke-CapturedCommand -Command 'amctimber info'
        if ($output -match "active fells: 0, live trunks: $Expected,") { return $output }
        Start-Sleep -Milliseconds 350
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "AMCTimber did not reach active fells=0/live trunks=$Expected"
}

function Give-Axe {
    param([string]$Bot)
    Invoke-CommandBatch -Commands @(
        "minecraft:clear $Bot",
        "minecraft:give $Bot minecraft:diamond_axe 1",
        "bot hold $Bot diamond_axe",
        "minecraft:effect give $Bot minecraft:saturation infinite 0 true"
    ) | Out-Null
}

function New-OakFixture {
    param([int]$X, [int]$Z, [switch]$PlayerBuild)
    $commands = @(
        "minecraft:forceload add $($X-24) $($Z-24) $($X+24) $($Z+24)",
        "minecraft:fill $($X-5) 98 $($Z-5) $($X+5) 109 $($Z+5) minecraft:air",
        "minecraft:fill $($X-5) 99 $($Z-5) $($X+5) 99 $($Z+5) minecraft:stone",
        "minecraft:fill $X 100 $Z $X 104 $Z minecraft:oak_log",
        "minecraft:fill $($X-2) 103 $($Z-2) $($X+2) 106 $($Z+2) minecraft:oak_leaves[persistent=false,distance=1]",
        "minecraft:fill $X 100 $Z $X 104 $Z minecraft:oak_log"
    )
    if ($PlayerBuild) { $commands += "minecraft:setblock $($X+1) 101 $Z minecraft:oak_planks" }
    Invoke-CommandBatch -Commands $commands | Out-Null
}

function New-JungleFixture {
    param([int]$X, [int]$Z)
    Invoke-CommandBatch -Commands @(
        "minecraft:forceload add $($X-24) $($Z-24) $($X+24) $($Z+24)",
        "minecraft:fill $($X-6) 98 $($Z-6) $($X+7) 112 $($Z+7) minecraft:air",
        "minecraft:fill $($X-6) 99 $($Z-6) $($X+7) 99 $($Z+7) minecraft:stone",
        "minecraft:fill $X 100 $Z $($X+1) 105 $($Z+1) minecraft:jungle_log",
        "minecraft:fill $($X-2) 104 $($Z-2) $($X+3) 108 $($Z+3) minecraft:jungle_leaves[persistent=false,distance=1]",
        "minecraft:fill $X 100 $Z $($X+1) 105 $($Z+1) minecraft:jungle_log",
        "minecraft:fill $($X-1) 101 $Z $($X-1) 104 $($Z+1) minecraft:vine[east=true]"
    ) | Out-Null
}

function Remove-GroundItems {
    Invoke-CommandBatch -Commands @(
        'minecraft:forceload remove all',
        'minecraft:kill @e[type=minecraft:item]'
    ) | Out-Null
}

function Complete-Trunk {
    param([string]$Bot, [int]$ExpectedRemaining = 0, [int]$MaxHits = 32)
    for ($hit = 1; $hit -le $MaxHits; $hit++) {
        $info = Invoke-CapturedCommand -Command 'amctimber info'
        if ($info -match "active fells: 0, live trunks: $ExpectedRemaining,") { return }
        $attack = Invoke-CapturedCommand -Command "bot attack $Bot nearest interaction 16"
        if ($attack -match 'no target') { throw "Live trunk has no attackable Interaction hitbox: $attack" }
        Start-Sleep -Milliseconds 450
    }
    throw "Trunk remained live after $MaxHits bot attacks"
}

function Count-GroundMaterial {
    param([string]$Output, [string]$Material)
    $count = 0
    foreach ($match in [regex]::Matches($Output, "ground\s+$Material\s+x(\d+)", 'IgnoreCase')) {
        $count += [int]$match.Groups[1].Value
    }
    return $count
}

function Count-InventoryMaterial {
    param([string]$Output, [string]$Material)
    $count = 0
    foreach ($match in [regex]::Matches($Output, "slot=\d+\s+$Material\s+x(\d+)", 'IgnoreCase')) {
        $count += [int]$match.Groups[1].Value
    }
    return $count
}

function Count-ObservableMaterial {
    param([string]$Bot, [string]$Material, [int]$Radius = 16)
    $ground = Invoke-CapturedCommand -Command "bot ground $Bot $Radius"
    $inventory = Invoke-CapturedCommand -Command "bot inv $Bot"
    return (Count-GroundMaterial -Output $ground -Material $Material) +
        (Count-InventoryMaterial -Output $inventory -Material $Material)
}

function Assert-TpsHealth {
    param([Parameter(Mandatory = $true)][string]$Output)
    $match = [regex]::Match($Output,
        'tps\s+([0-9]+(?:[.][0-9]+)?)\s*/\s*([0-9]+(?:[.][0-9]+)?)\s*/\s*([0-9]+(?:[.][0-9]+)?)\s+mspt\s+([0-9]+(?:[.][0-9]+)?)',
        [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) { throw 'Could not parse TPS/MSPT health sample' }
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $tpsValues = @(1..3 | ForEach-Object { [double]::Parse($match.Groups[$_].Value, $culture) })
    $mspt = [double]::Parse($match.Groups[4].Value, $culture)
    $minimumTps = ($tpsValues | Measure-Object -Minimum).Minimum
    $script:performance = [ordered]@{
        tps_1m = $tpsValues[0]
        tps_5m = $tpsValues[1]
        tps_15m = $tpsValues[2]
        mspt = $mspt
        acceptance = [ordered]@{ minimum_tps = 19.0; maximum_mspt = 50.0 }
    }
    if ($minimumTps -lt 19.0) { throw "TPS health sample fell below 19.0: $minimumTps" }
    if ($mspt -gt 50.0) { throw "MSPT health sample exceeded 50.0: $mspt" }
}

function Enable-FullDiagnostics {
    $configPath = Join-Path $runtimeDir 'plugins\AMCTimber\config.yml'
    if (-not (Test-Path -LiteralPath $configPath)) { throw 'AMCTimber runtime config was not created' }
    $configText = [string](Get-Content -Raw -LiteralPath $configPath)
    $updated = [regex]::Replace($configText, '(?m)^debug:[^\r\n]*(?=\r?$)', 'debug: full')
    if ($updated -eq $configText -and $configText -notmatch '(?m)^debug:\s*full\s*(?=\r?$)') {
        throw 'Could not enable AMCTimber full diagnostics in disposable runtime'
    }
    [IO.File]::WriteAllText($configPath, $updated, [Text.UTF8Encoding]::new($false))
    $reload = Invoke-CapturedCommand -Command 'amctimber reload'
    Assert-Text -Text $reload -Pattern 'config \+ messages reloaded' -Message 'AMCTimber diagnostic reload failed'
}

function Invoke-Scenario {
    param([string]$Id, [scriptblock]$Body)
    $started = [DateTimeOffset]::UtcNow
    try {
        & $Body
        $script:results.Add([pscustomobject]@{
            id = $Id; status = 'passed'; duration_ms = [int]([DateTimeOffset]::UtcNow - $started).TotalMilliseconds
            error = $null
        })
        Write-Output "PASS $Id"
    } catch {
        $script:results.Add([pscustomobject]@{
            id = $Id; status = 'failed'; duration_ms = [int]([DateTimeOffset]::UtcNow - $started).TotalMilliseconds
            error = $_.Exception.Message
        })
        Write-Warning "FAIL $Id`: $($_.Exception.Message)"
    }
}

function Start-Server {
    param([string]$JavaPath)
    $pipeName = 'amctimber-e2e-' + [guid]::NewGuid().ToString('N')
    $script:commandPipe = [IO.Pipes.NamedPipeServerStream]::new(
        $pipeName, [IO.Pipes.PipeDirection]::Out, 1,
        [IO.Pipes.PipeTransmissionMode]::Byte, [IO.Pipes.PipeOptions]::Asynchronous
    )
    $processInfo = [Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $env:ComSpec
    $processInfo.Arguments = "/d /s /c `"`"$JavaPath`" -Xms512M -Xmx2G -Dterminal.jline=false -Dterminal.ansi=false -jar server.jar --nogui < `"\\.\pipe\$pipeName`"`""
    $processInfo.WorkingDirectory = $runtimeDir
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $script:serverProcess = [Diagnostics.Process]::new()
    $script:serverProcess.StartInfo = $processInfo
    if (-not $script:serverProcess.Start()) { throw 'Server process did not start' }
    $script:processStarted = $true
    $connect = $script:commandPipe.WaitForConnectionAsync()
    if (-not $connect.Wait(30000)) { throw 'Server did not connect to its command pipe' }
    $script:commandWriter = [IO.StreamWriter]::new($script:commandPipe, [Text.UTF8Encoding]::new($false), 1024, $true)
    $script:commandWriter.NewLine = "`n"
    $script:commandWriter.AutoFlush = $true
    Wait-LogPattern -Pattern 'Done \([^)]*\)! For help, type "help"' -TimeoutSeconds 300 | Out-Null
}

function Stop-Server {
    if ($null -ne $script:serverProcess -and $script:processStarted -and -not $script:serverProcess.HasExited) {
        try { Send-ServerCommand -Command 'stop' } catch { }
        if (-not $script:serverProcess.WaitForExit(90000)) {
            & "$env:SystemRoot\System32\taskkill.exe" /PID $script:serverProcess.Id /T /F *> $null
            $script:serverProcess.WaitForExit()
        }
    }
    if ($null -ne $script:commandWriter) { try { $script:commandWriter.Dispose() } catch { } }
    if ($null -ne $script:commandPipe) { try { $script:commandPipe.Dispose() } catch { } }
    if ($null -ne $script:serverProcess) { try { $script:serverProcess.Dispose() } catch { } }
    $script:commandWriter = $null
    $script:commandPipe = $null
    $script:serverProcess = $null
    $script:processStarted = $false
}

$startedAt = [DateTimeOffset]::UtcNow
$fatalError = $null
$artifact = $null
$serverJar = $null
$plugin = $null
$qabot = $null
$additionalPlugins = @()
$java = $null
$javaVersion = $null
$port = $null

try {
    $plugin = (Resolve-Path -LiteralPath $PluginJar).Path
    $qabot = (Resolve-Path -LiteralPath $QaBotJar).Path
    $additionalPlugins = @($AdditionalPluginJar | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
    $javaCommand = Get-Command $JavaExecutable -ErrorAction Stop
    $java = $javaCommand.Source
    $javaVersion = Get-JavaVersionLine -JavaPath $java
    $artifact = Resolve-ServerArtifact
    $serverJar = Get-ServerJar -Artifact $artifact
    $port = Get-FreeTcpPort

    New-Item -ItemType Directory -Force -Path (Join-Path $runtimeDir 'plugins') | Out-Null
    Copy-Item -LiteralPath $serverJar.Path -Destination (Join-Path $runtimeDir 'server.jar')
    Copy-Item -LiteralPath $plugin -Destination (Join-Path $runtimeDir 'plugins\AMCTimber.jar')
    Copy-Item -LiteralPath $qabot -Destination (Join-Path $runtimeDir 'plugins\VCraftQABot.jar')
    foreach ($additionalPlugin in $additionalPlugins) {
        Copy-Item -LiteralPath $additionalPlugin -Destination (Join-Path $runtimeDir ('plugins\' + (Split-Path -Leaf $additionalPlugin)))
    }
    [IO.File]::WriteAllText((Join-Path $runtimeDir 'eula.txt'), "eula=true`n")
    $properties = @"
allow-flight=true
difficulty=peaceful
enable-command-block=false
enable-query=false
enable-rcon=false
generate-structures=false
level-name=world
level-seed=1
level-type=minecraft:flat
max-players=8
motd=AMCTimber gameplay E2E
online-mode=false
server-ip=127.0.0.1
server-port=$port
simulation-distance=3
spawn-protection=0
view-distance=4
"@
    [IO.File]::WriteAllText((Join-Path $runtimeDir 'server.properties'), $properties)

    Start-Server -JavaPath $java

    Invoke-Scenario -Id 'runtime-selftest' -Body {
        $output = Invoke-CapturedCommand -Command 'amctimber selftest'
        Assert-Text -Text $output -Pattern 'PASS ([0-9]+)/\1 AMCTimber selftest ok' -Message 'AMCTimber runtime selftest did not pass'
    }

    Invoke-Scenario -Id 'qa-hooks-default-deny' -Body {
        $output = Invoke-CapturedCommand -Command 'amctimber test break 0 100 0 AMCT_QA'
        Assert-Text -Text $output -Pattern 'QA commands are disabled in config' -Message 'Destructive QA hooks were not disabled by default'
    }

    Enable-FullDiagnostics

    Invoke-CommandBatch -Commands @('bot add AMCT_QA') | Out-Null
    Wait-BotOnline -Name 'AMCT_QA' | Out-Null
    Give-Axe -Bot 'AMCT_QA'

    Invoke-Scenario -Id 'sneak-bypass-vanilla-path' -Body {
        Remove-GroundItems
        New-OakFixture -X 0 -Z 0
        Invoke-CommandBatch -Commands @('bot sneak AMCT_QA on', 'bot mine AMCT_QA 0 100 0') | Out-Null
        Start-Sleep -Milliseconds 900
        Assert-Blocks -Blocks @(
            [pscustomobject]@{ X = 0; Y = 100; Z = 0; Material = 'air' },
            [pscustomobject]@{ X = 0; Y = 101; Z = 0; Material = 'oak_log' }
        )
        Wait-TrunkCount -Expected 0 -TimeoutSeconds 5 | Out-Null
        Invoke-CommandBatch -Commands @('bot sneak AMCT_QA off') | Out-Null
    }

    Invoke-Scenario -Id 'player-build-rejected' -Body {
        Remove-GroundItems
        New-OakFixture -X 20 -Z 0 -PlayerBuild
        $rejectionOffset = ([string](Read-LatestLog)).Length
        Invoke-CapturedCommand -Command 'bot mine AMCT_QA 20 100 0' | Out-Null
        Wait-LogPattern -Pattern 'fell rejected: player-build at 20,100,0' -TimeoutSeconds 10 -StartOffset $rejectionOffset | Out-Null
        Assert-Blocks -Blocks @(
            [pscustomobject]@{ X = 20; Y = 100; Z = 0; Material = 'air' },
            [pscustomobject]@{ X = 20; Y = 101; Z = 0; Material = 'oak_log' },
            [pscustomobject]@{ X = 21; Y = 101; Z = 0; Material = 'oak_planks' }
        )
        Wait-TrunkCount -Expected 0 -TimeoutSeconds 5 | Out-Null
    }

    if ($EventPolicyFixture) {
        Invoke-Scenario -Id 'late-cancel-no-secondary-effects' -Body {
            Remove-GroundItems
            Give-Axe -Bot 'AMCT_QA'
            New-OakFixture -X -20 -Z 0
            $offset = ([string](Read-LatestLog)).Length
            Invoke-CapturedCommand -Command 'bot mine AMCT_QA -20 100 0' | Out-Null
            Wait-LogPattern -Pattern 'policy fixture cancelled break at -20,100,0' -TimeoutSeconds 10 -StartOffset $offset | Out-Null
            Assert-Blocks -Blocks @(
                [pscustomobject]@{ X = -20; Y = 100; Z = 0; Material = 'oak_log' },
                [pscustomobject]@{ X = -20; Y = 101; Z = 0; Material = 'oak_log' }
            )
            Wait-TrunkCount -Expected 0 -TimeoutSeconds 5 | Out-Null
            $logs = Count-ObservableMaterial -Bot 'AMCT_QA' -Material 'oak_log'
            if ($logs -ne 0) { throw "Cancelled break produced $logs observable oak logs" }
        }

        Invoke-Scenario -Id 'late-no-drop-no-secondary-effects' -Body {
            Remove-GroundItems
            Give-Axe -Bot 'AMCT_QA'
            New-OakFixture -X -40 -Z 0
            $offset = ([string](Read-LatestLog)).Length
            Invoke-CapturedCommand -Command 'bot mine AMCT_QA -40 100 0' | Out-Null
            Wait-LogPattern -Pattern 'policy fixture disabled drops at -40,100,0' -TimeoutSeconds 10 -StartOffset $offset | Out-Null
            Assert-Blocks -Blocks @(
                [pscustomobject]@{ X = -40; Y = 100; Z = 0; Material = 'air' },
                [pscustomobject]@{ X = -40; Y = 101; Z = 0; Material = 'oak_log' }
            )
            Wait-TrunkCount -Expected 0 -TimeoutSeconds 5 | Out-Null
            $logs = Count-ObservableMaterial -Bot 'AMCT_QA' -Material 'oak_log'
            if ($logs -ne 0) { throw "No-drop break produced $logs observable oak logs" }
        }
    }

    Invoke-Scenario -Id 'oak-fell-chop-yield-cleanup' -Body {
        Remove-GroundItems
        Give-Axe -Bot 'AMCT_QA'
        New-OakFixture -X 40 -Z 0
        Invoke-CapturedCommand -Command 'bot mine AMCT_QA 40 100 0' | Out-Null
        Wait-TrunkCount -Expected 1 | Out-Null
        Assert-Blocks -Blocks @(
            [pscustomobject]@{ X = 40; Y = 100; Z = 0; Material = 'oak_log' },
            [pscustomobject]@{ X = 40; Y = 101; Z = 0; Material = 'air' },
            [pscustomobject]@{ X = 40; Y = 104; Z = 0; Material = 'air' }
        )
        $entities = Invoke-CapturedCommand -Command 'bot entities AMCT_QA interaction'
        Assert-Text -Text $entities -Pattern '(?i)interaction' -Message 'Bot did not observe an Interaction hitbox'
        Complete-Trunk -Bot 'AMCT_QA'
        Wait-TrunkCount -Expected 0 | Out-Null
        Start-Sleep -Milliseconds 500
        $logs = Count-ObservableMaterial -Bot 'AMCT_QA' -Material 'oak_log'
        if ($logs -ne 3) { throw "Expected exactly 3 oak logs from four toppled logs, got $logs" }
        $after = Invoke-CapturedCommand -Command 'bot entities AMCT_QA interaction'
        Assert-Text -Text $after -Pattern '(?i)none matching INTERACTION' -Message 'Interaction hitbox remained after trunk completion'
    }

    Invoke-Scenario -Id 'jungle-2x2-vines-stump-yield-cleanup' -Body {
        Remove-GroundItems
        Give-Axe -Bot 'AMCT_QA'
        New-JungleFixture -X 70 -Z 0
        Invoke-CapturedCommand -Command 'bot mine AMCT_QA 70 100 0' | Out-Null
        Wait-TrunkCount -Expected 1 | Out-Null
        $checks = [Collections.Generic.List[object]]::new()
        foreach ($dx in 0..1) {
            foreach ($dz in 0..1) {
                $checks.Add([pscustomobject]@{ X = 70 + $dx; Y = 100; Z = $dz; Material = 'jungle_log' })
                foreach ($y in 101..105) {
                    $checks.Add([pscustomobject]@{ X = 70 + $dx; Y = $y; Z = $dz; Material = 'air' })
                }
            }
        }
        foreach ($z in 0..1) {
            foreach ($y in 101..104) {
                $checks.Add([pscustomobject]@{ X = 69; Y = $y; Z = $z; Material = 'air' })
            }
        }
        Assert-Blocks -Blocks $checks.ToArray()
        Complete-Trunk -Bot 'AMCT_QA'
        Wait-TrunkCount -Expected 0 | Out-Null
        Start-Sleep -Milliseconds 500
        $logs = Count-ObservableMaterial -Bot 'AMCT_QA' -Material 'jungle_log'
        if ($logs -ne 16) { throw "Expected exactly 16 jungle logs from twenty toppled logs, got $logs" }
        foreach ($type in @('interaction', 'block_display')) {
            $after = Invoke-CapturedCommand -Command "bot entities AMCT_QA $type"
            Assert-Text -Text $after -Pattern ("(?i)none matching " + $type.ToUpperInvariant()) -Message "$type remained after jungle trunk completion"
        }
    }

    Invoke-Scenario -Id 'planned-restart-yield-recovery' -Body {
        Remove-GroundItems
        Give-Axe -Bot 'AMCT_QA'
        New-OakFixture -X 90 -Z 0
        Invoke-CapturedCommand -Command 'bot mine AMCT_QA 90 100 0' | Out-Null
        Wait-TrunkCount -Expected 1 | Out-Null

        Stop-Server
        $recoveryFile = Join-Path $runtimeDir 'plugins\AMCTimber\pending-yields.properties'
        if (-not (Test-Path -LiteralPath $recoveryFile)) {
            throw 'Planned shutdown did not create the pending-yield journal'
        }
        $journal = [string](Get-Content -Raw -LiteralPath $recoveryFile)
        Assert-Text -Text $journal -Pattern '(?m)^schema=amctimber[.]pending-yield[.]v2$' -Message 'Recovery journal schema is not v2'
        Assert-Text -Text $journal -Pattern '(?m)^count=1$' -Message 'Recovery journal did not contain exactly one trunk yield'
        Assert-Text -Text $journal -Pattern '(?m)^entry[.]0[.]material=OAK_LOG$' -Message 'Recovery journal material was not oak'
        Assert-Text -Text $journal -Pattern '(?m)^entry[.]0[.]amount=3$' -Message 'Recovery journal did not retain exact reduced yield'
        if ($journal -match '(?m)^entry[.]0[.]owner=none$') {
            throw 'Recovery journal lost the felling actor identity'
        }
        $journalSha = Get-FileSha256 -Path $recoveryFile

        $preRestartLog = Join-Path $evidence 'pre-restart.log'
        if (Test-Path -LiteralPath $script:latestLog) {
            Move-Item -LiteralPath $script:latestLog -Destination $preRestartLog -Force
        }
        Start-Server -JavaPath $java
        Wait-LogPattern -Pattern 'loaded 1 pending timber yield record[(]s[)]' -TimeoutSeconds 30 | Out-Null
        Invoke-CommandBatch -Commands @('bot add AMCT_QA') | Out-Null
        Wait-BotOnline -Name 'AMCT_QA' | Out-Null
        Invoke-CommandBatch -Commands @('minecraft:tp AMCT_QA 90 100 0') | Out-Null
        Start-Sleep -Milliseconds 750
        $logs = Count-ObservableMaterial -Bot 'AMCT_QA' -Material 'oak_log'
        if ($logs -ne 3) { throw "Expected exactly 3 recovered oak logs after restart, got $logs" }
        $info = Invoke-CapturedCommand -Command 'amctimber info'
        Assert-Text -Text $info -Pattern 'pending yield: 0, recovery budget: 0/[0-9]+' -Message 'Recovered yield was not acknowledged'
        Assert-Text -Text $info -Pattern 'entity budget: 0/[0-9]+ sessions, 0/[0-9]+ entities' -Message 'Restart recovery leaked live entities'
        Give-Axe -Bot 'AMCT_QA'
        $script:restartRecovery = [ordered]@{
            journal_schema = 'amctimber.pending-yield.v2'
            journal_records = 1
            recovered_material = 'OAK_LOG'
            recovered_amount = 3
            actor_retained = $true
            journal_sha256_before_restart = $journalSha
            pre_restart_log_sha256 = if (Test-Path -LiteralPath $preRestartLog) { Get-FileSha256 -Path $preRestartLog } else { $null }
        }
    }

    Invoke-Scenario -Id 'three-bot-concurrency-soak' -Body {
        Invoke-CommandBatch -Commands @('bot add AMCT_QA2 AMCT_QA3') | Out-Null
        Wait-BotOnline -Name 'AMCT_QA2' | Out-Null
        Wait-BotOnline -Name 'AMCT_QA3' | Out-Null
        foreach ($bot in @('AMCT_QA', 'AMCT_QA2', 'AMCT_QA3')) { Give-Axe -Bot $bot }
        for ($cycle = 1; $cycle -le [Math]::Max(1, $ConcurrencyCycles); $cycle++) {
            Remove-GroundItems
            $baseX = 100 + ($cycle * 50)
            New-OakFixture -X $baseX -Z 0
            New-OakFixture -X ($baseX + 15) -Z 0
            New-OakFixture -X ($baseX + 30) -Z 0
            $cycleOffset = ([string](Read-LatestLog)).Length
            Invoke-CommandBatch -Commands @(
                "bot mine AMCT_QA $baseX 100 0",
                "bot mine AMCT_QA2 $($baseX+15) 100 0",
                "bot mine AMCT_QA3 $($baseX+30) 100 0"
            ) | Out-Null
            try {
                Wait-TrunkCount -Expected 3 -TimeoutSeconds 12 | Out-Null
            } catch {
                $logText = [string](Read-LatestLog)
                $tail = if ($logText.Length -gt $cycleOffset) { $logText.Substring($cycleOffset) } else { '' }
                $retryTargets = @(
                    [pscustomobject]@{ Bot = 'AMCT_QA'; X = $baseX },
                    [pscustomobject]@{ Bot = 'AMCT_QA2'; X = $baseX + 15 },
                    [pscustomobject]@{ Bot = 'AMCT_QA3'; X = $baseX + 30 }
                ) | Where-Object { $tail -match "fell rejected: attempt-time-budget at $($_.X),100,0" }
                if ($retryTargets.Count -eq 0) { throw }
                foreach ($target in $retryTargets) {
                    New-OakFixture -X $target.X -Z 0
                    Invoke-CapturedCommand -Command "bot mine $($target.Bot) $($target.X) 100 0" | Out-Null
                    $script:concurrencyTimeBudgetRetries++
                    Start-Sleep -Milliseconds 150
                }
                Wait-TrunkCount -Expected 3 -TimeoutSeconds 15 | Out-Null
            }
            Complete-Trunk -Bot 'AMCT_QA' -ExpectedRemaining 2
            Complete-Trunk -Bot 'AMCT_QA2' -ExpectedRemaining 1
            Complete-Trunk -Bot 'AMCT_QA3' -ExpectedRemaining 0
            Wait-TrunkCount -Expected 0 | Out-Null
        }
        $info = Invoke-CapturedCommand -Command 'amctimber info'
        Assert-Text -Text $info -Pattern 'entity budget: 0/[0-9]+ sessions, 0/[0-9]+ entities' -Message 'Entity budget leaked after concurrency cycles'
        $tps = Invoke-CapturedCommand -Command 'bot tps'
        Assert-TpsHealth -Output $tps
    }

    Invoke-Scenario -Id 'plugin-log-health' -Body {
        $logText = [string](Read-LatestLog)
        Assert-Text -Text $logText -Pattern 'AMCTimber v[0-9.]+ enabled - felling on[.]' -Message 'AMCTimber enable receipt missing'
        Assert-Text -Text $logText -Pattern 'VCraftQABot.*ready' -Message 'VCraftQABot ready receipt missing'
        if ($EventPolicyFixture) {
            Assert-Text -Text $logText -Pattern 'AMCTimberE2EPolicy.*event policy fixture ready' -Message 'Policy fixture ready receipt missing'
        }
        if ($logText -match '(?m)^.*Error occurred while enabling (?:AMCTimber|VCraftQABot).*$') {
            throw 'A tested plugin reported an enable failure'
        }
        if ($logText -match '(?m)^.*\[(?:AMCTimber|VCraftQABot)\].*(?:NoClassDefFoundError|ClassNotFoundException|LinkageError).*$') {
            throw 'A tested plugin reported a runtime linkage failure'
        }
        if ($additionalPlugins.Count -gt 0 -and $logText -match '(?m)^.*Error occurred while enabling .*$') {
            throw 'An additional compatibility plugin reported an enable failure'
        }
    }
} catch {
    $fatalError = $_.Exception.Message
    Write-Warning "FATAL: $fatalError"
} finally {
    try { Stop-Server } catch { if ($null -eq $fatalError) { $fatalError = $_.Exception.Message } }
    if (Test-Path -LiteralPath $script:latestLog) {
        Copy-Item -LiteralPath $script:latestLog -Destination $consoleLog -Force
    }

    $endedAt = [DateTimeOffset]::UtcNow
    $passed = @($script:results | Where-Object status -eq 'passed').Count
    $failed = @($script:results | Where-Object status -eq 'failed').Count
    $receipt = [ordered]@{
        schema = 'amctimber-gameplay-e2e-v1'
        result = if ($null -eq $fatalError -and $failed -eq 0 -and $passed -gt 0) { 'passed' } else { 'failed' }
        started_at = $startedAt.ToString('o')
        ended_at = $endedAt.ToString('o')
        duration_seconds = [Math]::Round(($endedAt - $startedAt).TotalSeconds, 3)
        fatal_error = $fatalError
        environment = [ordered]@{
            os = [Environment]::OSVersion.VersionString
            java = $javaVersion
            runtime = $Runtime
            minecraft = $MinecraftVersion
            server_build = if ($null -ne $artifact) { $artifact.Build } else { $null }
            server_url = if ($null -ne $artifact) { $artifact.Url } else { $null }
            server_sha256 = if ($null -ne $serverJar) { $serverJar.Sha256 } else { $null }
            server_checksum_source = if ($null -ne $artifact) { $artifact.ChecksumSource } else { $null }
            loopback_port = $port
            online_mode = $false
            instrumentation = @('VCraftQABot') + @($additionalPlugins | ForEach-Object { Split-Path -Leaf $_ })
            test_overrides = @('debug=full') + $(if ($EventPolicyFixture) { @('event-policy-fixture') } else { @() })
        }
        artifacts = [ordered]@{
            amctimber_file = if ($null -ne $plugin) { Split-Path -Leaf $plugin } else { $null }
            amctimber_sha256 = if ($null -ne $plugin) { Get-FileSha256 -Path $plugin } else { $null }
            qabot_file = if ($null -ne $qabot) { Split-Path -Leaf $qabot } else { $null }
            qabot_sha256 = if ($null -ne $qabot) { Get-FileSha256 -Path $qabot } else { $null }
            additional_plugins = @($additionalPlugins | ForEach-Object {
                [ordered]@{ file = Split-Path -Leaf $_; sha256 = Get-FileSha256 -Path $_ }
            })
            harness_sha256 = Get-FileSha256 -Path $PSCommandPath
            latest_log_sha256 = if (Test-Path -LiteralPath $consoleLog) { Get-FileSha256 -Path $consoleLog } else { $null }
        }
        counters = [ordered]@{ discovered = $script:results.Count; passed = $passed; failed = $failed; skipped = 0 }
        performance = $script:performance
        restart_recovery = $script:restartRecovery
        resilience = [ordered]@{ concurrency_time_budget_retries = $script:concurrencyTimeBudgetRetries }
        scenarios = @($script:results)
    }
    [IO.File]::WriteAllText($receiptPath, ($receipt | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    if (-not $KeepRuntime) {
        Remove-Item -LiteralPath $runtimeDir -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Write-Output "Runtime retained: $runtimeDir"
    }
}

$final = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json
Write-Output ("{0} {1} {2}: {3}/{4} scenarios passed; receipt={5}" -f $final.result.ToUpperInvariant(), $Runtime, $MinecraftVersion, $final.counters.passed, $final.counters.discovered, $receiptPath)
if ($final.result -ne 'passed') { exit 1 }
