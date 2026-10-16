param([Parameter(Mandatory=$true)][string]$GameDirectory)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($GameDirectory).TrimEnd('\','/')
if (-not (Test-Path -LiteralPath (Join-Path $root 'mods') -PathType Container)) {
    throw 'Select the Minecraft instance folder containing mods.'
}

function Test-OwnedArgument([string]$Value) {
    $value = $Value.Trim('"').ToLowerInvariant()
    return ($value.StartsWith('-javaagent:') -and $value.Contains('lightspeed-bootstrap-agent')) -or
        $value.StartsWith('-dlightspeed.agent.owner=') -or
        $value.StartsWith('-dlightspeed.bootstrapcachedir=') -or
        $value.StartsWith('-dlightspeed.workers=') -or
        $value.StartsWith('-dlightspeed.managedcicompilercount=')
}

function Clear-Arguments([string]$Text) {
    $tokens = [regex]::Matches($Text, '(?:[^\s"]+|"[^"]*")+') | ForEach-Object {$_.Value}
    $retained = [Collections.Generic.List[string]]::new()
    $managedCount = $null
    $removed = $false
    foreach ($token in $tokens) {
        $value = $token.Trim('"')
        if ($null -ne $managedCount -and $value -ieq "-XX:CICompilerCount=$managedCount") {
            $managedCount = $null
            $removed = $true
            continue
        }
        $managedCount = $null
        if ($value -imatch '^-Dlightspeed\.managedCICompilerCount=(\d+)$') {$managedCount=$Matches[1]}
        if (Test-OwnedArgument $token) {$removed=$true} else {$retained.Add($token)}
    }
    if (-not $removed) {return $Text}
    return [string]::Join(' ', $retained)
}

function Test-OwnedCommand([string]$Text) {
    $value = $Text.Replace('\','/').Trim()
    return $value -imatch '^cmd\.exe /d /s /c call "[^"]*/\.lightspeed/bootstrap/pack-compiler\.cmd"$'
}

function Write-Atomic([string]$Path, [string]$Text) {
    $parent = [IO.Path]::GetDirectoryName($Path)
    $temporary = Join-Path $parent ([IO.Path]::GetRandomFileName())
    try {
        [IO.File]::WriteAllText($temporary, $Text, [Text.UTF8Encoding]::new($false))
        [IO.File]::Replace($temporary, $Path, [NullString]::Value)
    } finally {
        if (Test-Path -LiteralPath $temporary) {Remove-Item -LiteralPath $temporary}
    }
}

$changed = 0
foreach ($file in @(Get-ChildItem -LiteralPath $root -File -Filter '*.json')) {
    $document = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    if ($null -eq $document.arguments -or $null -eq $document.arguments.jvm) {continue}
    $current = @($document.arguments.jvm)
    $filtered = [Collections.Generic.List[object]]::new()
    $managedCount = $null
    foreach ($item in $current) {
        if ($item -is [string]) {
            if ($null -ne $managedCount -and $item -ieq "-XX:CICompilerCount=$managedCount") {
                $managedCount = $null
                continue
            }
            $managedCount = $null
            if ($item -imatch '^-Dlightspeed\.managedCICompilerCount=(\d+)$') {$managedCount=$Matches[1]}
            if (Test-OwnedArgument $item) {continue}
        } else {
            $managedCount = $null
        }
        $filtered.Add($item)
    }
    if ($filtered.Count -eq $current.Count) {continue}
    $document.arguments.jvm = @($filtered.ToArray())
    Write-Atomic $file.FullName (($document | ConvertTo-Json -Depth 100) + [Environment]::NewLine)
    $changed++
    Write-Host "Cleaned $($file.Name)"
}

foreach ($relative in @('PCL\Setup.ini','instance.cfg')) {
    $file = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {continue}
    $content = [IO.File]::ReadAllText($file)
    $newline = if ($content.Contains("`r`n")) {"`r`n"} else {"`n"}
    $lines = [Collections.Generic.List[string]]::new()
    $updated = $false
    foreach ($line in [regex]::Split($content, '\r?\n')) {
        $replacement = $line
        foreach ($prefix in @('VersionAdvanceJvm:','JvmArgs=')) {
            if ($line.StartsWith($prefix)) {
                $arguments = $line.Substring($prefix.Length)
                $clean = Clear-Arguments $arguments
                if ($clean -ne $arguments) {$replacement=$prefix+$clean}
            }
        }
        foreach ($prefix in @('VersionAdvanceRun:','PreLaunchCommand=')) {
            if ($line.StartsWith($prefix) -and (Test-OwnedCommand $line.Substring($prefix.Length))) {
                $replacement = $prefix
            }
        }
        if ($replacement -ne $line) {$updated=$true}
        $lines.Add($replacement)
    }
    if ($updated) {
        Write-Atomic $file ([string]::Join($newline,[string[]]$lines.ToArray()))
        $changed++
        Write-Host "Cleaned $relative"
    }
}
Write-Host "Changed $changed file(s). Restart the launcher before launching Minecraft."
