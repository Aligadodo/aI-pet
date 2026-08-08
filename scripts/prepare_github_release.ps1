param(
    [Parameter(Mandatory = $true)]
    [string]$ProductRoot,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),

    [string]$ReleaseTag = "snapshot-2026-08-08"
)

$ErrorActionPreference = "Stop"

$product = (Resolve-Path -LiteralPath $ProductRoot).Path
$repository = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
$allowedOutputParent = (Resolve-Path -LiteralPath (Split-Path -Parent $repository)).Path
$outputParent = [System.IO.Path]::GetFullPath((Split-Path -Parent $output)).TrimEnd('\', '/')
$stagingMarkerName = '.sweetpet-release-staging'
$stagingMarkerValue = 'SweetPet release staging v1'

if ($output -eq $product -or $output.StartsWith($product + [System.IO.Path]::DirectorySeparatorChar)) {
    throw "OutputDirectory must not be inside ProductRoot."
}
if ($outputParent -ne $allowedOutputParent -or (Split-Path -Leaf $output) -ne 'aI-pet-release-assets') {
    throw "OutputDirectory must be the aI-pet-release-assets sibling of RepositoryRoot."
}

if (Test-Path -LiteralPath $output) {
    $resolvedOutput = (Resolve-Path -LiteralPath $output).Path
    $marker = Join-Path $resolvedOutput $stagingMarkerName
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf) -or (Get-Content -LiteralPath $marker -Raw).Trim() -ne $stagingMarkerValue) {
        throw "Refusing to replace an unowned output directory: $resolvedOutput"
    }
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
}
New-Item -ItemType Directory -Path $output | Out-Null
Set-Content -LiteralPath (Join-Path $output $stagingMarkerName) -Value $stagingMarkerValue -Encoding ascii

$candidates = [System.Collections.Generic.List[object]]::new()
$desktopDeliveryName = [string]::Concat([char[]]@(0x5B89, 0x88C5, 0x5305, 0x4E0E, 0x6E90, 0x7801))
$androidRootName = 'Android' + [char]0x7248
$deliveryName = [string]::Concat([char[]]@(0x4EA4, 0x4ED8))
$petResourceRootName = [string]::Concat([char[]]@(0x684C, 0x5BA0, 0x8D44, 0x6E90, 0x5305))

function Add-Candidate {
    param(
        [string]$Path,
        [string]$Kind,
        [string]$Version,
        [string]$Status,
        [string]$Note,
        [string]$SourceRelative = ''
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing release artifact: $Path"
    }
    $candidates.Add([pscustomobject]@{
        Path = (Resolve-Path -LiteralPath $Path).Path
        Kind = $Kind
        Version = $Version
        Status = $Status
        Note = $Note
        SourceRelative = $SourceRelative
    })
}

function Get-PortableRelativePath {
    param(
        [string]$BasePath,
        [string]$TargetPath
    )
    $baseWithSeparator = $BasePath.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $baseUri = [Uri]::new($baseWithSeparator)
    $targetUri = [Uri]::new($TargetPath)
    return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString()).Replace('/', [System.IO.Path]::DirectorySeparatorChar)
}

# Canonical desktop archives are rebuilt without caches/shortcuts and with third-party notices.
$python = (Get-Command python -ErrorAction Stop).Source
$zipSanitizer = Join-Path $repository 'scripts\sanitize_zip.py'
$desktopNoticeRoot = Join-Path $repository 'desktop\SweetGirlfriendDesktopPet'
Get-ChildItem -LiteralPath $product -Directory |
    Where-Object { $_.Name -match '^v\d+\.\d+\.\d+$' } |
    Sort-Object Name |
    ForEach-Object {
        $version = $_.Name
        $delivery = Join-Path $_.FullName $desktopDeliveryName
        if (-not (Test-Path -LiteralPath $delivery)) { return }
        $status = if ($version -eq 'v1.2.4') { 'current' } else { 'historical' }
        $cleaned = @()
        foreach ($kind in @('source', 'windows')) {
            $sourceArchive = Get-ChildItem -LiteralPath $delivery -File -Filter "SweetGirlfriendDesktopPet-$version-$kind.zip" | Select-Object -First 1
            if (-not $sourceArchive) { throw "Missing desktop $kind archive for $version" }
            $cleanArchive = Join-Path $output $sourceArchive.Name
            & $python $zipSanitizer $sourceArchive.FullName $cleanArchive --notices $desktopNoticeRoot
            if ($LASTEXITCODE -ne 0) { throw "ZIP sanitizer failed for $($sourceArchive.FullName)" }
            $sourceRelative = (Get-PortableRelativePath -BasePath $product -TargetPath $sourceArchive.FullName).Replace('\', '/')
            $artifactKind = if ($kind -eq 'windows') { 'windows-runtime' } else { 'desktop-source-archive' }
            Add-Candidate -Path $cleanArchive -Kind $artifactKind -Version $version.TrimStart('v') -Status $status -Note 'Sanitized desktop archive with third-party notices' -SourceRelative $sourceRelative
            $cleaned += Get-Item -LiteralPath $cleanArchive
        }
        $sidecarName = "SweetGirlfriendDesktopPet-$version-SHA256.txt"
        $sidecarPath = Join-Path $output $sidecarName
        $sidecarLines = $cleaned | Sort-Object { if ($_.Name -like '*-windows.zip') { 0 } else { 1 } } | ForEach-Object {
            $archiveHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            "$archiveHash  $($_.Name)"
        }
        $sidecarLines | Set-Content -LiteralPath $sidecarPath -Encoding ascii
        Add-Candidate -Path $sidecarPath -Kind 'checksum' -Version $version.TrimStart('v') -Status $status -Note 'Regenerated checksums for sanitized desktop archives' -SourceRelative "generated/$version/$sidecarName"
    }

# Android delivery directories. Nested trial snapshots are included; exact-byte duplicates are collapsed below.
$androidRoot = Join-Path $product $androidRootName
Get-ChildItem -LiteralPath $androidRoot -Directory |
    Where-Object { $_.Name -match '^v\d+\.\d+\.\d+$' } |
    Sort-Object Name |
    ForEach-Object {
        $version = $_.Name
        $delivery = Join-Path $_.FullName $deliveryName
        if (-not (Test-Path -LiteralPath $delivery)) { return }
        Get-ChildItem -LiteralPath $delivery -Recurse -File |
            Where-Object { $_.Extension -in '.apk', '.zip', '.petpack', '.sha256' } |
            Sort-Object FullName |
            ForEach-Object {
                $kind = switch ($_.Extension) {
                    '.apk' { 'android-apk' }
                    '.zip' { 'android-trial-bundle' }
                    '.petpack' { 'petpack-snapshot' }
                    default { 'checksum' }
                }
                $status = if ($version -eq 'v0.5.6') { 'current-qa' } else { 'historical-qa' }
                $note = if ($_.Extension -eq '.apk') { 'Debug-signed QA APK; not a production/store signature' } else { 'Android iteration delivery artifact' }
                Add-Candidate -Path $_.FullName -Kind $kind -Version $version.TrimStart('v') -Status $status -Note $note
            }
    }

# Canonical PetPack outputs. Reproduction-only packages are deliberately excluded.
$packDist = Join-Path (Join-Path $product $petResourceRootName) 'PetPack-v2\dist'
Get-ChildItem -LiteralPath $packDist -File |
    Where-Object {
        ($_.Extension -eq '.petpack' -or $_.Extension -eq '.sha256') -and
        $_.Name -notmatch '^_repro_' -and
        $_.Name -notmatch '\.repro\.petpack$'
    } |
    Sort-Object Name |
    ForEach-Object {
        $version = if ($_.BaseName -match '(\d+\.\d+\.\d+)') { $Matches[1] } else { '' }
        $status = if ($_.Name -like 'jk-beach-summer-*') { 'validated' } else { 'historical-or-candidate' }
        $note = if ($_.Extension -eq '.petpack') { 'PetPack v2 installable resource package' } else { 'SHA-256 sidecar' }
        Add-Candidate -Path $_.FullName -Kind $(if ($_.Extension -eq '.petpack') { 'petpack' } else { 'checksum' }) -Version $version -Status $status -Note $note
    }

# Publish the sanitized QA evidence for the latest independently released pack.
$qaSource = Join-Path $repository 'petpack\PetPack-v2\reports\jk-beach-summer-1.0.0'
$qaArchive = Join-Path $output 'jk-beach-summer-1.0.0-qa-evidence.zip'
Compress-Archive -Path (Join-Path $qaSource '*') -DestinationPath $qaArchive -CompressionLevel Optimal
Add-Candidate -Path $qaArchive -Kind 'qa-evidence' -Version '1.0.0' -Status 'validated' -Note 'Sanitized deterministic QA and Android install-gate evidence'

$entries = [System.Collections.Generic.List[object]]::new()
$byHash = @{}
$usedNames = @{}
$orderedCandidates = $candidates | Sort-Object @{ Expression = {
    if ($_.Kind -eq 'petpack') { 0 }
    elseif ($_.Kind -eq 'checksum') { 1 }
    elseif ($_.Kind -eq 'petpack-snapshot') { 3 }
    else { 2 }
} }, Path

foreach ($candidate in $orderedCandidates) {
    $file = Get-Item -LiteralPath $candidate.Path
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $relative = if ($candidate.SourceRelative) {
        $candidate.SourceRelative
    } else {
        (Get-PortableRelativePath -BasePath $product -TargetPath $file.FullName).Replace('\', '/')
    }

    if ($byHash.ContainsKey($hash)) {
        $byHash[$hash].aliases += $relative
        continue
    }

    $assetName = $file.Name
    if ($usedNames.ContainsKey($assetName)) {
        $safeVersion = if ($candidate.Version) { $candidate.Version } else { 'unversioned' }
        $assetName = "$($candidate.Kind)-$safeVersion-$assetName"
    }
    if ($usedNames.ContainsKey($assetName)) {
        throw "Unable to produce a unique release asset name for $($file.FullName)"
    }

    $destination = Join-Path $output $assetName
    if ($file.FullName -ne $destination) {
        Copy-Item -LiteralPath $file.FullName -Destination $destination
    }
    $copied = Get-Item -LiteralPath $destination
    $copiedHash = (Get-FileHash -LiteralPath $copied.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($copied.Length -ne $file.Length -or $copiedHash -ne $hash) {
        throw "Release staging verification failed for $assetName"
    }

    $entry = [pscustomobject]@{
        assetName = $assetName
        kind = $candidate.Kind
        version = $candidate.Version
        status = $candidate.Status
        bytes = $file.Length
        sha256 = $hash
        source = $relative
        aliases = @()
        note = $candidate.Note
    }
    $entries.Add($entry)
    $byHash[$hash] = $entry
    $usedNames[$assetName] = $true
}

$manifest = [ordered]@{
    schemaVersion = 1
    releaseTag = $ReleaseTag
    generatedAtUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    repository = 'https://github.com/Aligadodo/aI-pet'
    assetCount = $entries.Count
    totalBytes = ($entries | Measure-Object -Property bytes -Sum).Sum
    artifacts = $entries
}

$manifestPath = Join-Path $output 'release-manifest.json'
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
$repositoryManifestPath = Join-Path $repository 'docs\release-manifest.json'
Copy-Item -LiteralPath $manifestPath -Destination $repositoryManifestPath -Force

$markdown = [System.Collections.Generic.List[string]]::new()
$markdown.Add('# Release artifact index')
$markdown.Add('')
$markdown.Add("Release: [$ReleaseTag](https://github.com/Aligadodo/aI-pet/releases/tag/$ReleaseTag)")
$markdown.Add('')
$markdown.Add('Binary deliverables are stored in the GitHub Release instead of Git history. SHA-256 values are recorded here and in the attached `release-manifest.json`. Byte-identical files are deduplicated; their original paths remain in the manifest `aliases` field.')
$markdown.Add('')
$markdown.Add('| Artifact | Kind | Version | Status | Bytes | SHA-256 |')
$markdown.Add('|---|---|---:|---|---:|---|')
foreach ($entry in $entries | Sort-Object kind, version, assetName) {
    $markdown.Add("| ``$($entry.assetName)`` | $($entry.kind) | $($entry.version) | $($entry.status) | $($entry.bytes) | ``$($entry.sha256)`` |")
}
$markdown.Add('')
$markdown.Add('> Android APK files are debug-signed QA builds, not production/store-signed releases.')

$artifactIndex = Join-Path $repository 'docs\ARTIFACTS.md'
$markdown | Set-Content -LiteralPath $artifactIndex -Encoding utf8

Write-Output "Prepared $($entries.Count) unique assets ($($manifest.totalBytes) bytes)."
Write-Output "Assets: $output"
Write-Output "Manifest: $manifestPath"
Write-Output "Repository manifest: $repositoryManifestPath"
Write-Output "Index: $artifactIndex"
