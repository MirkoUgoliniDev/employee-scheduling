param(
    [Parameter(Mandatory=$true)]
    [int]$Port
)

Write-Host "Searching for processes on port $Port..."

$connections = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue

if (-not $connections) {
    Write-Host "Port $Port is free. No process to terminate."
    exit 0
}

$pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique

foreach ($pid in $pids) {
    $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "Process found: $($process.ProcessName) (PID: $pid)"
        Stop-Process -Id $pid -Force
        Write-Host "Process $pid terminated successfully."
    }
}

Write-Host "Port $Port is free."
