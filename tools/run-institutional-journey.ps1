param(
    [Parameter(Mandatory)][string]$PostgresBin,
    [Parameter(Mandatory)][string]$Maven,
    [Parameter(Mandatory)][string]$RunDirectory,
    [int]$Port = 55463,
    [string]$Selection = 'InstitutionalJourneyFixture',
    [switch]$QuietSql
)
$ErrorActionPreference = 'Stop'
$run = [IO.Path]::GetFullPath($RunDirectory)
if (Test-Path $run) { throw 'Choose a new disposable run directory.' }
New-Item -ItemType Directory -Path $run | Out-Null
$data = Join-Path $run 'pgdata'
$database = 'chemicallab_t01_test'
$role = 't12_local'
& "$PostgresBin/initdb.exe" --locale=C --encoding=UTF8 --auth=trust --username=$role -D $data *> "$run/initdb.log"
if ($LASTEXITCODE -ne 0) { Get-Content "$run/initdb.log"; exit $LASTEXITCODE }
$logging = if ($QuietSql) { '' } else { ' -c log_min_duration_statement=0 -c log_parameter_max_length=0' }
$serverArgs = '-D "' + $data + '" -l "' + $run + '/postgres.log" -o "-h 127.0.0.1 -p ' + $Port + ' -c max_connections=40 -c shared_buffers=32MB -c max_locks_per_transaction=512' + $logging + '" -w start'
$server = Start-Process "$PostgresBin/pg_ctl.exe" -ArgumentList $serverArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/start.out" -RedirectStandardError "$run/start.err"
if (!$server.WaitForExit(20000) -or $server.ExitCode -ne 0) { Get-Content "$run/start.err"; exit 1 }
try {
    & "$PostgresBin/createdb.exe" -h 127.0.0.1 -p $Port -U $role $database
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Push-Location (Join-Path $PSScriptRoot '..')
    try {
        git rev-parse HEAD | Set-Content "$run/sha.txt"
        git status --porcelain | Set-Content "$run/status.txt"
        $mavenArgs = @('-o', 'test', "-Dtest=$Selection", '-Dspring.profiles.active=test',
            "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$Port/$database",
            "-Dspring.datasource.username=$role", '-Dspring.datasource.password=fictitious',
            "-Dt12.journey.dir=$run/browser")
        & $Maven @mavenArgs *> "$run/backend.log"
        $code = $LASTEXITCODE
        Get-Content "$run/backend.log" -Tail 24
        exit $code
    } finally { Pop-Location }
} finally {
    & "$PostgresBin/pg_ctl.exe" -D $data -w -m fast stop *> "$run/stop.log"
}
