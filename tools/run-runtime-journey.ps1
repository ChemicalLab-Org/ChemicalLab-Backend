param(
    [Parameter(Mandatory)][string]$PostgresBin,
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$Jar,
    [Parameter(Mandatory)][string]$RunDirectory,
    [switch]$Content,
    [string]$PredecessorJar,
    [int]$DatabasePort = 55472,
    [int]$HttpPort = 18092
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jarFile = (Resolve-Path -LiteralPath $Jar).Path
if ($Content -and $PredecessorJar) { throw 'Choose a fresh content install or an upgrade.' }
$applicationJar = if ($PredecessorJar) { (Resolve-Path -LiteralPath $PredecessorJar).Path } else { $jarFile }
$run = [IO.Path]::GetFullPath($RunDirectory)
if (Test-Path -LiteralPath $run) { throw 'Choose a new directory for the disposable cluster.' }
New-Item -ItemType Directory -Path $run | Out-Null
$browser = New-Item -ItemType Directory -Path (Join-Path $run 'browser')
$cluster = Join-Path $run 'pgdata'
$db = 'chemicallab_t12_runtime_test'
$role = 't12_runtime_local'
$secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
& "$PostgresBin/initdb.exe" --locale=C --encoding=UTF8 --auth=trust --username=$role -D $cluster *> "$run/initdb.log"
if ($LASTEXITCODE -ne 0) { throw 'initdb failed.' }
$pgArgs = '-D "'+$cluster+'" -l "'+$run+'/postgres.log" -o "-h 127.0.0.1 -p '+$DatabasePort+
    ' -c max_connections=40 -c shared_buffers=32MB -c log_min_duration_statement=0 -c log_parameter_max_length=0" -w start'
$pg = Start-Process "$PostgresBin/pg_ctl.exe" -ArgumentList $pgArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/pg-start.log" -RedirectStandardError "$run/pg-start.err"
if (!$pg.WaitForExit(30000) -or $pg.ExitCode -ne 0) { throw 'PostgreSQL startup failed.' }

function Start-Application([string]$Stage, [string]$Ddl, [switch]$PrepareEmptySchema) {
    $arguments = @('-jar', ('"'+$applicationJar+'"'), '--spring.profiles.active=dev', '--server.address=127.0.0.1',
        "--server.port=$HttpPort", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:$DatabasePort/$db",
        "--spring.datasource.username=$role", '--spring.datasource.password=fictitious', "--app.jwt.secret=$secret",
        "--spring.jpa.hibernate.ddl-auto=$Ddl", '--app.demo.enabled=false', '--app.bootstrap.admin.enabled=false')
    $app = Start-Process -FilePath $Java -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/$Stage.log" -RedirectStandardError "$run/$Stage.err"
    try {
        for ($poll=0; $poll -lt 100; $poll++) {
            if ($app.HasExited) {
                if ($PrepareEmptySchema -and (Select-String -Path "$run/$Stage.log" -Pattern 'relation "(audit_schema_version|storage_schema_version)" does not exist' -Quiet)) {
                    return
                }
                throw "Application exited during $Stage."
            }
            if (Select-String -Path "$run/$Stage.log" -Pattern 'Started ChemicalLabBackendApplication' -Quiet) { return $app }
            Start-Sleep -Seconds 1
        }
        throw "Application startup timed out during $Stage."
    } catch {
        if (!$app.HasExited) { Stop-Process -Id $app.Id; $app.WaitForExit() }
        throw
    }
}

$app = $null
try {
    & "$PostgresBin/createdb.exe" -h 127.0.0.1 -p $DatabasePort -U $role $db
    if ($LASTEXITCODE -ne 0) { throw 'createdb failed.' }
    git -C $repo rev-parse HEAD | Set-Content "$run/backend-sha.txt"
    git -C $repo status --porcelain | Set-Content "$run/backend-status.txt"
    Get-FileHash -LiteralPath $jarFile | ConvertTo-Json | Set-Content "$run/artifact.json"
    $app = Start-Application 'bootstrap' 'update' -PrepareEmptySchema
    if ($null -ne $app) { throw 'Empty-schema preparation must stop at the existing T07B/T08 prerequisite.' }
    $sql = @('-h','127.0.0.1','-p',"$DatabasePort",'-U',$role,'-d',$db,'-X','-v','ON_ERROR_STOP=1')
    & "$PostgresBin/psql.exe" @sql --single-transaction -c 'set search_path to public' -f "$repo/src/main/resources/security/t07b-storage.sql" -f "$repo/src/main/resources/security/t08-audit.sql" *> "$run/preparation-prerequisites.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$run/preparation-prerequisites.log" -Tail 15; throw 'T07B or T08 preparation failed.' }
    & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/evaluation-v1.sql" -f "$PSScriptRoot/runtime-demo.sql" *> "$run/preparation.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$run/preparation.log" -Tail 15; throw 'Schema or dataset preparation failed.' }
    if ($Content) {
        & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/content-v1.sql" *> "$run/content-install.log"
        if ($LASTEXITCODE -ne 0) { throw 'Content installation failed.' }
    }
    $app = Start-Application 'runtime' 'validate'
    $ready = @{origin="http://127.0.0.1:$HttpPort";mode='runtime';teacherA='runtime-teacher-a';teacherB='runtime-teacher-b';
        student='runtime-student';password='FictitiousRuntime-2026!';a=1;b=2;pid=$app.Id}
    $ready | ConvertTo-Json | Set-Content (Join-Path $browser.FullName 'ready.json') -Encoding utf8NoBOM
    Write-Output "Ready: $($browser.FullName)"
    $restart = Join-Path $browser.FullName 'restart'
    while (!$app.HasExited -and !(Test-Path -LiteralPath (Join-Path $browser.FullName 'stop'))) {
        if ($PredecessorJar -and (Test-Path "$browser/upgrade") -and !(Test-Path "$run/upgraded.pid")) {
            Stop-Process -Id $app.Id
            $app.WaitForExit()
            & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/content-v1.sql" *> "$run/content-install.log"
            if ($LASTEXITCODE -ne 0) { throw 'Content upgrade failed.' }
            $applicationJar = $jarFile
            $app = Start-Application 'upgrade' 'validate'
            $app.Id | Set-Content "$run/upgraded.pid"
        }
        if (($Content -or $PredecessorJar) -and (Test-Path "$browser/recover") -and !(Test-Path "$run/recovered.pid")) {
            Stop-Process -Id $app.Id
            $app.WaitForExit()
            & "$PostgresBin/pg_dump.exe" -h 127.0.0.1 -p $DatabasePort -U $role -d $db --format=custom --file="$run/recovery.dump" *> "$run/dump.log"
            if ($LASTEXITCODE -ne 0) { throw 'Content recovery dump failed.' }
            $db = 'chemicallab_t12_content_recovered_test'
            & "$PostgresBin/createdb.exe" -h 127.0.0.1 -p $DatabasePort -U $role $db
            if ($LASTEXITCODE -ne 0) { throw 'Content recovery database creation failed.' }
            & "$PostgresBin/pg_restore.exe" -h 127.0.0.1 -p $DatabasePort -U $role -d $db --exit-on-error "$run/recovery.dump" *> "$run/restore.log"
            if ($LASTEXITCODE -ne 0) { throw 'Content restore failed.' }
            $app = Start-Application 'recovery' 'validate'
            $app.Id | Set-Content "$run/recovered.pid"
        }
        if ((Test-Path -LiteralPath $restart) -and !(Test-Path "$run/restarted.pid")) {
            Stop-Process -Id $app.Id
            $app.WaitForExit()
            $app = Start-Application 'restart' 'validate'
            $app.Id | Set-Content "$run/restarted.pid"
        }
        Start-Sleep -Seconds 1
    }
    if ($app.HasExited) { throw 'Application exited during the run.' }
} finally {
    if ($null -ne $app -and !$app.HasExited) { Stop-Process -Id $app.Id; $app.WaitForExit() }
    & "$PostgresBin/pg_ctl.exe" -D $cluster -m fast -w stop *> "$run/stop.log"
}
