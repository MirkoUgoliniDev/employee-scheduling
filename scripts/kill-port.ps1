param(
    [Parameter(Mandatory=$true)]
    [int]$Port
)

# NOTE ON THE LOOP VARIABLE: it is deliberately NOT called $pid.
# $PID is a read-only PowerShell automatic variable holding the PID of the current process.
# Using it as a foreach variable fails with VariableNotWritable on every iteration, so the
# target was never terminated, and in the worst case $pid kept its original value — the PID of
# the shell itself — pointing Stop-Process at its own host process. Keep this named $procId.

Write-Host "Searching for processes on port $Port..."

$connections = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue

if (-not $connections) {
    Write-Host "Port $Port is free. No process to terminate."
    exit 0
}

$procIds = $connections | Select-Object -ExpandProperty OwningProcess -Unique

foreach ($procId in $procIds) {
    if ($procId -eq 0) { continue }

    # Refuse to terminate this script's own shell: the port would stay held anyway, and the
    # operator would lose the session that reported the problem.
    if ($procId -eq $PID) {
        Write-Warning "Port $Port is held by this very shell (PID $procId). Not terminating it."
        continue
    }

    $process = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "PID $procId no longer exists; the port entry is stale."
        continue
    }

    Write-Host "Process found: $($process.ProcessName) (PID: $procId)"
    try {
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Host "Process $procId terminated."
    } catch {
        Write-Warning "Could not terminate PID $procId : $($_.Exception.Message)"
    }
}

# Re-check instead of asserting. The previous version printed "Port is free." unconditionally,
# so a failed kill produced a success message and the caller went looking elsewhere.
# Termination is asynchronous: the socket can outlive the process by a moment.
$deadline = (Get-Date).AddSeconds(5)
do {
    Start-Sleep -Milliseconds 200
    $remaining = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
} while ($remaining -and (Get-Date) -lt $deadline)

if ($remaining) {
    $stillThere = ($remaining | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
    Write-Error "Port $Port is STILL in use by PID(s): $stillThere."
    exit 1
}

Write-Host "Port $Port is free."
exit 0
