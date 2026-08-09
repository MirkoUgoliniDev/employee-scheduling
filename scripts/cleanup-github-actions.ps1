param(
    [Parameter(Mandatory = $true)]
    [int]$KeepPerWorkflow
)

$ErrorActionPreference = 'Stop'

if ($KeepPerWorkflow -lt 1 -or $KeepPerWorkflow -gt 50) {
    throw 'KeepPerWorkflow must be between 1 and 50.'
}

$ghCommand = Get-Command gh.exe -ErrorAction SilentlyContinue
if ($ghCommand) {
    $gh = $ghCommand.Source
} else {
    $gh = Join-Path $env:LOCALAPPDATA 'Programs\GitHubCLI\gh.exe'
}
if (-not (Test-Path -LiteralPath $gh)) {
    throw 'GitHub CLI is not installed. Install it from https://cli.github.com/ and try again.'
}

& $gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'GitHub authentication is required once.' -ForegroundColor Yellow
    & $gh auth login --web --git-protocol https
    if ($LASTEXITCODE -ne 0) {
        throw 'GitHub authentication was not completed.'
    }
}

$repository = (& $gh repo view --json nameWithOwner --jq '.nameWithOwner').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repository)) {
    throw 'Unable to identify the GitHub repository.'
}

Write-Host "Reading completed workflow runs from $repository..." -ForegroundColor Cyan
$json = & $gh run list --repo $repository --limit 1000 `
    --json databaseId,workflowName,status,conclusion,createdAt,displayTitle,url
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read GitHub Actions workflow runs.'
}

$runs = @($json | ConvertFrom-Json)
$completedRuns = @($runs | Where-Object { $_.status -eq 'completed' })
$toDelete = @()
foreach ($workflowGroup in ($completedRuns | Group-Object workflowName)) {
    $oldRuns = @($workflowGroup.Group |
        Sort-Object { [DateTimeOffset]$_.createdAt } -Descending |
        Select-Object -Skip $KeepPerWorkflow)
    $toDelete += $oldRuns
}
$toDelete = @($toDelete | Sort-Object { [DateTimeOffset]$_.createdAt })

if ($toDelete.Count -eq 0) {
    Write-Host "Nothing to delete. The newest $KeepPerWorkflow completed run(s) per workflow are protected." -ForegroundColor Green
    exit 0
}

Write-Host ''
Write-Host 'The following completed workflow runs will be permanently deleted:' -ForegroundColor Yellow
$toDelete |
    Select-Object @{Name='Date';Expression={([DateTimeOffset]$_.createdAt).ToLocalTime().ToString('yyyy-MM-dd HH:mm')}},
        workflowName, conclusion, displayTitle, databaseId |
    Format-Table -AutoSize

Write-Host "Protected: the newest $KeepPerWorkflow completed run(s) of every workflow." -ForegroundColor Green
Write-Host 'Active runs, Git tags, GitHub Releases, and release assets are not deleted.' -ForegroundColor Green
$expectedConfirmation = "DELETE $($toDelete.Count)"
$confirmation = Read-Host "Type '$expectedConfirmation' to continue"
if ($confirmation -cne $expectedConfirmation) {
    Write-Host 'Cleanup cancelled. Nothing was deleted.' -ForegroundColor Yellow
    exit 0
}

$deleted = 0
foreach ($run in $toDelete) {
    Write-Host "Deleting run $($run.databaseId): $($run.displayTitle)"
    & $gh run delete $run.databaseId --repo $repository
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to delete workflow run $($run.databaseId). $deleted run(s) were already deleted."
    }
    $deleted++
}

Write-Host ''
Write-Host "$deleted old workflow run(s) deleted successfully." -ForegroundColor Green
