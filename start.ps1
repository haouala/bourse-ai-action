# Launch Bourse AI locally in PowerShell without third-party .env modules.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path '.env')) { throw 'Copy .env.example to .env and choose DB_PASSWORD.' }
Get-Content '.env' | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $pair = $line -split '=', 2
    if ($pair.Length -ne 2 -or $pair[0] -notmatch '^[A-Z][A-Z0-9_]*$') { throw "Invalid environment variable: $line" }
    $value = $pair[1].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))) { $value = $value.Substring(1, $value.Length - 2) }
    [Environment]::SetEnvironmentVariable($pair[0], $value, 'Process')
}
if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD -eq 'CHOOSE_A_STRONG_PASSWORD') {
    throw 'Choose a real DB_PASSWORD in .env.'
}
docker compose --env-file "$PSScriptRoot/.env" -f "$PSScriptRoot/bourse-ai-engine/docker-compose.yml" up -d postgres
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL failed to start.' }
Set-Location "$PSScriptRoot/bourse-ai-engine"
mvn -B -ntp '-Dspring-boot.run.profiles=postgres' spring-boot:run
if ($LASTEXITCODE -ne 0) { throw 'Backend failed to start. Review Flyway and DB logs.' }
