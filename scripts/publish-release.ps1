param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$versionNumber = $Version.Trim()
if ($versionNumber.StartsWith('v', [System.StringComparison]::OrdinalIgnoreCase)) {
    $versionNumber = $versionNumber.Substring(1)
}

if ($versionNumber -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid version '$Version'. Use three numbers, for example 1.2.3."
}

$tagName = "v$versionNumber"
$snapshotVersion = "$versionNumber-SNAPSHOT"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Assert-LastCommand([string]$description) {
    if ($LASTEXITCODE -ne 0) {
        throw "$description failed with exit code $LASTEXITCODE."
    }
}

function Update-ProjectVersion([string]$pomPath, [string]$newVersion) {
    $content = [System.IO.File]::ReadAllText($pomPath)
    $pattern = '(<artifactId>employee-scheduling</artifactId>\s*<version>)[^<]+(</version>)'
    if ([regex]::Matches($content, $pattern).Count -ne 1) {
        throw 'Unable to identify the project version in pom.xml.'
    }
    $updated = [regex]::Replace($content, $pattern, "`${1}$newVersion`${2}")
    [System.IO.File]::WriteAllText($pomPath, $updated, $utf8NoBom)
}

Push-Location $projectRoot
try {
    $branch = (& git branch --show-current).Trim()
    Assert-LastCommand 'Reading the current Git branch'
    if ($branch -ne 'main') {
        throw "Releases must be published from main. The current branch is '$branch'."
    }

    $changes = @(& git status --porcelain --untracked-files=all)
    Assert-LastCommand 'Checking the Git working tree'
    if ($changes.Count -gt 0) {
        throw 'The working tree contains changes. Commit or discard them before publishing a release.'
    }

    Write-Host "Fetching main and existing tags..." -ForegroundColor Cyan
    & git fetch origin main --tags
    Assert-LastCommand 'Fetching from GitHub'

    $behindCount = [int]((& git rev-list --count HEAD..origin/main).Trim())
    Assert-LastCommand 'Checking the remote main branch'
    if ($behindCount -gt 0) {
        throw 'The local main branch is behind GitHub. Pull the latest changes before publishing.'
    }

    if ((& git tag --list $tagName).Count -gt 0) {
        throw "Tag $tagName already exists locally. Choose a newer version."
    }
    & git ls-remote --exit-code --tags origin "refs/tags/$tagName" | Out-Null
    if ($LASTEXITCODE -eq 0) {
        throw "Tag $tagName already exists on GitHub. Choose a newer version."
    }
    if ($LASTEXITCODE -ne 2) {
        throw 'Unable to check the release tag on GitHub.'
    }

    Write-Host "Preparing Employee Scheduling $tagName..." -ForegroundColor Cyan
    Update-ProjectVersion (Join-Path $projectRoot 'pom.xml') $snapshotVersion

    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        & npm version $snapshotVersion --no-git-tag-version
        Assert-LastCommand 'Updating the frontend version'
    } finally {
        Pop-Location
    }

    & git diff --check
    Assert-LastCommand 'Validating the version changes'

    Write-Host ''
    Write-Host "Version: $tagName" -ForegroundColor Yellow
    Write-Host 'GitHub Actions will build the Windows MSI and both Raspberry/Linux packages.'
    $confirmation = Read-Host 'Publish this release? Type YES to continue'
    if ($confirmation -cne 'YES') {
        throw 'Release cancelled. Version files remain modified so you can review them.'
    }

    & git add -- pom.xml frontend/package.json frontend/package-lock.json
    Assert-LastCommand 'Staging the version files'
    & git commit -m "chore: prepare release $tagName"
    Assert-LastCommand 'Creating the release commit'
    & git push origin main
    Assert-LastCommand 'Pushing main to GitHub'
    & git tag -a $tagName -m "Employee Scheduling $tagName"
    Assert-LastCommand 'Creating the release tag'
    & git push origin $tagName
    Assert-LastCommand 'Pushing the release tag'

    Write-Host ''
    Write-Host "$tagName published. GitHub Actions is now building Windows and Raspberry packages." -ForegroundColor Green
    Write-Host 'https://github.com/MirkoUgoliniDev/employee-scheduling/actions'
} finally {
    Pop-Location
}
