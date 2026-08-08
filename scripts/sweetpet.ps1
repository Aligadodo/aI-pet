[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ForwardedArguments
)

$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$scriptPath = Join-Path $PSScriptRoot 'sweetpet.py'
$repoVenv = Join-Path (Split-Path $PSScriptRoot -Parent) '.venv\Scripts\python.exe'

if ($env:SWEETPET_PYTHON -and (Test-Path -LiteralPath $env:SWEETPET_PYTHON)) {
    & $env:SWEETPET_PYTHON $scriptPath @ForwardedArguments
} elseif (Test-Path -LiteralPath $repoVenv) {
    & $repoVenv $scriptPath @ForwardedArguments
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    & python $scriptPath @ForwardedArguments
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3.11 $scriptPath @ForwardedArguments
} else {
    Write-Error 'Python 3.11+ was not found. Set SWEETPET_PYTHON or install Python.'
    exit 2
}

exit $LASTEXITCODE
