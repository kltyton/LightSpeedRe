param([Parameter(Mandatory=$true)][string]$GameDirectory)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($GameDirectory).TrimEnd('\','/')
$prismParent = Split-Path -Parent $root
$profiles = @((Join-Path $root 'PCL/Setup.ini'), (Join-Path $root 'instance.cfg'))
if ($prismParent -and (Split-Path -Leaf $root) -in @('.minecraft', 'minecraft')) {
    $profiles += Join-Path $prismParent 'instance.cfg'
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'mods') -PathType Container) -and
    -not ($profiles | Where-Object {Test-Path -LiteralPath $_ -PathType Leaf})) {
    throw 'Select the Minecraft instance folder containing mods.'
}

function Test-OwnedArgument([string]$Value) {
    $value = $Value.Trim('"').ToLowerInvariant()
    return ($value.StartsWith('-javaagent:') -and $value.Contains('lightspeed-bootstrap-agent')) -or
        $value.StartsWith('-dlightspeed.agent.owner=') -or
        $value.StartsWith('-dlightspeed.bootstrapcachedir=') -or
        $value.StartsWith('-dlightspeed.workers=') -or
        $value -eq '-dlightspeed.managedgcthresholds=true' -or
        $value.StartsWith('-dlightspeed.managedcicompilercount=')
}

function Remove-OwnedArguments([object[]]$Arguments) {
    $managedCount = $null
    $managedGcRemaining = 0
    foreach ($token in $Arguments) {
        if ($token -isnot [string]) {
            $managedCount = $null
            $managedGcRemaining = 0
            ,$token
            continue
        }
        $value = $token.Trim('"')
        if ($value -ieq '-Dlightspeed.managedGcThresholds=true') {
            $managedGcRemaining = 3
            $managedCount = $null
            continue
        }
        if ($managedGcRemaining -gt 0 -and $value -imatch '^-XX:(MetaspaceSize=512m|InitialCodeCacheSize=128m|ReservedCodeCacheSize=512m)$') {
            $managedGcRemaining--
            continue
        }
        $managedGcRemaining = 0
        if ($null -ne $managedCount -and $value -ieq "-XX:CICompilerCount=$managedCount") {
            $managedCount = $null
            continue
        }
        $managedCount = $null
        if ($value -imatch '^-Dlightspeed\.managedCICompilerCount=(\d+)$') {$managedCount=$Matches[1]}
        if (-not (Test-OwnedArgument $token)) {Write-Output $token}
    }
}

function Clear-Arguments([string]$Text) {
    $tokens = @([regex]::Matches($Text, '(?:[^\s"]+|"(?:\\"|[^"])*")+') | ForEach-Object {$_.Value})
    $retained = @(Remove-OwnedArguments $tokens)
    if ($retained.Count -eq $tokens.Count) {return $Text}
    return [string]::Join(' ', [string[]]$retained)
}

function Test-OwnedCommand([string]$Text) {
    $value = $Text.Replace('\"','"').Replace('\\','\').Replace('\','/').Trim()
    if ($value.StartsWith('"') -and $value.EndsWith('"')) {$value = $value.Substring(1, $value.Length - 2)}
    return $value -imatch '^cmd\.exe /d /s /c call "[^"]*/\.lightspeed/bootstrap/pack-compiler\.cmd"$' -or
        $value -imatch "^(?:/bin/)?sh '[^']*/\.lightspeed/bootstrap/pack-compiler\.sh'$"
}

function Clear-PrismArguments([string]$Text, [bool]$QtFormat) {
    # QSettings wraps the INI string and escapes quotes and backslashes again.
    $encoded = $QtFormat -or $Text.Contains('\"') -or $Text.Contains('\\')
    $wrapped = $encoded -and $Text.StartsWith('"') -and $Text.EndsWith('"')
    $value = if ($wrapped) {$Text.Substring(1, $Text.Length - 2)} else {$Text}
    if ($encoded) {$value = $value.Replace('\"','"').Replace('\\','\')}
    $clean = Clear-Arguments $value
    if ($clean -eq $value) {return $Text}
    if ($encoded) {$clean = $clean.Replace('\','\\').Replace('"','\"')}
    if ($wrapped) {return '"' + $clean + '"'}
    return $clean
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
    $filtered = @(Remove-OwnedArguments $current)
    if ($filtered.Count -eq $current.Count) {continue}
    $document.arguments.jvm = $filtered
    Write-Atomic $file.FullName (($document | ConvertTo-Json -Depth 100) + [Environment]::NewLine)
    $changed++
    Write-Host "Cleaned $($file.Name)"
}

foreach ($file in $profiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {continue}
    $content = [IO.File]::ReadAllText($file)
    $newline = if ($content.Contains("`r`n")) {"`r`n"} else {"`n"}
    $lines = [Collections.Generic.List[string]]::new()
    $updated = $false
    $qtFormat = $content -match '(?m)^ConfigVersion=1\.[23]\r?$'
    foreach ($line in [regex]::Split($content, '\r?\n')) {
        $replacement = $line
        foreach ($prefix in @('VersionAdvanceJvm:','JvmArgs=')) {
            if ($line.StartsWith($prefix)) {
                $arguments = $line.Substring($prefix.Length)
                $clean = if ($prefix -eq 'JvmArgs=') {Clear-PrismArguments $arguments $qtFormat} else {Clear-Arguments $arguments}
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
        Write-Host "Cleaned $file"
    }
}
Write-Host "Changed $changed file(s). Restart the launcher before launching Minecraft."
