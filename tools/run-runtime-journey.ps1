param(
    [Parameter(Mandatory)][string]$PostgresBin,
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$Jar,
    [Parameter(Mandatory)][string]$RunDirectory,
    [switch]$Content,
    [switch]$Boards,
    [switch]$Administration,
    [switch]$Agreements,
    [switch]$Subscriptions,
    [int]$SubscriptionMockPort = 18094,
    [string]$PredecessorJar,
    [int]$DatabasePort = 55472,
    [int]$HttpPort = 18092
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jarFile = (Resolve-Path -LiteralPath $Jar).Path
if ($Content -and ($PredecessorJar -or $Boards -or $Administration)) { throw 'Choose one runtime install or upgrade.' }
if ($Agreements -and (!$Administration -or $PredecessorJar)) { throw 'Agreements require a fresh disposable administration runtime.' }
if ($Subscriptions -and (!$Administration -or $Agreements -or $PredecessorJar)) { throw 'Subscription verification requires fresh administration with agreements disabled.' }
$applicationJar = if ($PredecessorJar) { (Resolve-Path -LiteralPath $PredecessorJar).Path } else { $jarFile }
$run = [IO.Path]::GetFullPath($RunDirectory)
if (Test-Path -LiteralPath $run) { throw 'Choose a new directory for the disposable cluster.' }
New-Item -ItemType Directory -Path $run | Out-Null
$browser = New-Item -ItemType Directory -Path (Join-Path $run 'browser')
$cluster = Join-Path $run 'pgdata'
$db = 'chemicallab_t12_runtime_test'
$role = 't12_runtime_local'
$secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$platformUser = 'PilotPlatform'
$platformTemporary = 'Fictitious-Platform-Initial-2026!'
& "$PostgresBin/initdb.exe" --locale=C --encoding=UTF8 --auth=trust --username=$role -D $cluster *> "$run/initdb.log"
if ($LASTEXITCODE -ne 0) { throw 'initdb failed.' }
$pgArgs = '-D "'+$cluster+'" -l "'+$run+'/postgres.log" -o "-h 127.0.0.1 -p '+$DatabasePort+
    ' -c max_connections=40 -c shared_buffers=32MB -c log_min_duration_statement=0 -c log_parameter_max_length=0" -w start'
$pg = Start-Process "$PostgresBin/pg_ctl.exe" -ArgumentList $pgArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/pg-start.log" -RedirectStandardError "$run/pg-start.err"
if (!$pg.WaitForExit(30000) -or $pg.ExitCode -ne 0) { throw 'PostgreSQL startup failed.' }

function Start-Application([string]$Stage, [string]$Ddl, [switch]$PrepareEmptySchema, [switch]$ProvisionPlatform) {
    $arguments = @('-jar', ('"'+$applicationJar+'"'), '--spring.profiles.active=dev', '--server.address=127.0.0.1',
        "--server.port=$HttpPort", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:$DatabasePort/$db",
        "--spring.datasource.username=$role", '--spring.datasource.password=fictitious', "--app.jwt.secret=$secret",
        '--app.cors.allowed-origins=http://127.0.0.1:[*],http://localhost:[*]',
        "--spring.jpa.hibernate.ddl-auto=$Ddl", '--app.demo.enabled=false',
        "--app.bootstrap.admin.enabled=$($ProvisionPlatform.ToString().ToLowerInvariant())",
        "--app.agreements.enabled=$((!$PrepareEmptySchema -and $Agreements).ToString().ToLowerInvariant())")
    $processEnvironment = @{}
    if ($ProvisionPlatform) {
        $processEnvironment = @{ APP_BOOTSTRAP_ADMIN_USERNAME=$platformUser; APP_BOOTSTRAP_ADMIN_PASSWORD=$platformTemporary }
    }
    if ($Subscriptions -and !$PrepareEmptySchema) {
        $processEnvironment += @{
            APP_SUBSCRIPTIONS_MODE = 'LOCAL_TEST'
            APP_SUBSCRIPTIONS_LOCAL_API = "http://127.0.0.1:$SubscriptionMockPort"
            APP_SUBSCRIPTIONS_RETURN_URL = "http://127.0.0.1:4200/suscripcion-institucional"
            APP_SUBSCRIPTIONS_SELLER_ID = '1101'
            APP_SUBSCRIPTIONS_APPLICATION_ID = '1102'
            APP_SUBSCRIPTIONS_BUYER_ID = '1103'
            APP_SUBSCRIPTIONS_BUYER_EMAIL = 'subscription-buyer@example.invalid'
            APP_SUBSCRIPTIONS_WEBHOOK_SECRET = 'fictitious-local-signature-2026'
            APP_SUBSCRIPTIONS_WORK_DELAY_MS = '1000'
        }
    }
    $app = Start-Process -FilePath $Java -ArgumentList $arguments -Environment $processEnvironment -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/$Stage.log" -RedirectStandardError "$run/$Stage.err"
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
    $evaluationFiles = @('-f', "$repo/src/main/resources/db/runtime/evaluation-v1.sql")
    if (!$Administration -or $PredecessorJar -or $Agreements -or $Subscriptions) { $evaluationFiles += @('-f', "$PSScriptRoot/runtime-demo.sql") }
    & "$PostgresBin/psql.exe" @sql @evaluationFiles *> "$run/preparation.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$run/preparation.log" -Tail 15; throw 'Schema or dataset preparation failed.' }
    if ($Content -or $Boards -or $Administration) {
        & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/content-v1.sql" *> "$run/content-install.log"
        if ($LASTEXITCODE -ne 0) { throw 'Content installation failed.' }
    }
    if ($Administration -or ($Boards -and !$PredecessorJar)) {
        & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/board-v1.sql" *> "$run/board-install.log"
        if ($LASTEXITCODE -ne 0) { throw 'Board installation failed.' }
    }
    if ($Administration -and !$PredecessorJar) {
        & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/administration-v1.sql" *> "$run/administration-install.log"
        if ($LASTEXITCODE -ne 0) { throw 'Administration installation failed.' }
    }
    if ($Agreements) {
        $snapshot = @('-h','127.0.0.1','-p',"$DatabasePort",'-U',$role,'-d',$db,'--data-only',
            '--exclude-schema=agreement_runtime','--restrict-key=AgreementVerification2026')
        & "$PostgresBin/pg_dump.exe" @snapshot --file="$run/agreements-before.sql"
        if ($LASTEXITCODE -ne 0) { throw 'Pre-install snapshot failed.' }
        & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/agreements-v1.sql" -f "$PSScriptRoot/agreements-demo.sql" *> "$run/agreements-install.log"
        if ($LASTEXITCODE -ne 0) { throw 'Agreement installation or seed failed.' }
        & "$PostgresBin/pg_dump.exe" @snapshot --file="$run/agreements-after.sql"
        if ($LASTEXITCODE -ne 0) { throw 'Post-install snapshot failed.' }
        $before = (Get-FileHash -LiteralPath "$run/agreements-before.sql").Hash
        $after = (Get-FileHash -LiteralPath "$run/agreements-after.sql").Hash
        if ($before -ne $after) { throw 'An existing table or sequence changed during agreement installation.' }
        $before | Set-Content "$run/agreements-preservation.sha256"
    }
    if ($Subscriptions) {
        & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/agreements-v1.sql" -f "$repo/src/main/resources/db/runtime/subscriptions-v1.sql" *> "$run/subscriptions-install.log"
        if ($LASTEXITCODE -ne 0) { Get-Content "$run/subscriptions-install.log" -Tail 15; throw 'Subscription installation failed.' }
    }
    $app = Start-Application 'runtime' 'validate' -ProvisionPlatform:$Administration
    if ($Administration) {
        # Startup logging may precede ApplicationRunner. Wait for the audited bootstrap commit.
        for ($poll=0; $poll -lt 40; $poll++) {
            $platformId = & "$PostgresBin/psql.exe" @sql -At -c "select id from user_accounts where username='PilotPlatform' and role='ADMINISTRADOR'"
            if ($LASTEXITCODE -eq 0 -and $platformId) { break }
            if ($app.HasExited) { throw 'Platform bootstrap failed.' }
            Start-Sleep -Seconds 1
        }
        if (!$platformId) { throw 'Platform bootstrap did not commit.' }
        if (!$PredecessorJar) {
            & "$PostgresBin/psql.exe" @sql -c "insert into administration_runtime.platform_authority(account_id) select id from user_accounts where username='PilotPlatform' and role='ADMINISTRADOR'" *> "$run/platform-authority.log"
            if ($LASTEXITCODE -ne 0) { throw 'Platform authority setup failed.' }
        }
    }
    $ready = @{origin="http://127.0.0.1:$HttpPort";mode='runtime';teacherA='runtime-teacher-a';teacherB='runtime-teacher-b';
        student='runtime-student';password='FictitiousRuntime-2026!';a=1;b=2;pid=$app.Id}
    if ($Administration) { $ready.platform = $platformUser; $ready.platformTemporary = $platformTemporary }
    if ($Agreements) {
        $ready.familyMembership = [long](& "$PostgresBin/psql.exe" @sql -At -c "select m.id from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id where u.username='runtime-student' and m.institution_id=1")
        if ($LASTEXITCODE -ne 0 -or !$ready.familyMembership) { throw 'Agreement fixture membership lookup failed.' }
    }
    $ready | ConvertTo-Json | Set-Content (Join-Path $browser.FullName 'ready.json') -Encoding utf8NoBOM
    Write-Output "Ready: $($browser.FullName)"
    $restart = Join-Path $browser.FullName 'restart'
    while (!$app.HasExited -and !(Test-Path -LiteralPath (Join-Path $browser.FullName 'stop'))) {
        if ($PredecessorJar -and (Test-Path "$browser/upgrade") -and !(Test-Path "$run/upgraded.pid")) {
            Stop-Process -Id $app.Id
            $app.WaitForExit()
            $contract = if ($Administration) { 'administration' } elseif ($Boards) { 'board' } else { 'content' }
            & "$PostgresBin/psql.exe" @sql -f "$repo/src/main/resources/db/runtime/$contract-v1.sql" *> "$run/$contract-install.log"
            if ($LASTEXITCODE -ne 0) { throw "$contract upgrade failed." }
            if ($Administration) {
                & "$PostgresBin/psql.exe" @sql -c "insert into administration_runtime.platform_authority(account_id) select id from user_accounts where username='PilotPlatform' and role='ADMINISTRADOR'" *> "$run/platform-authority.log"
                if ($LASTEXITCODE -ne 0) { throw 'Platform authority setup after upgrade failed.' }
            }
            $applicationJar = $jarFile
            $app = Start-Application 'upgrade' 'validate'
            $app.Id | Set-Content "$run/upgraded.pid"
        }
        if (($Content -or $Boards -or $Administration -or $PredecessorJar) -and (Test-Path "$browser/recover") -and !(Test-Path "$run/recovered.pid")) {
            Stop-Process -Id $app.Id
            $app.WaitForExit()
            & "$PostgresBin/pg_dump.exe" -h 127.0.0.1 -p $DatabasePort -U $role -d $db --format=custom --file="$run/recovery.dump" *> "$run/dump.log"
            if ($LASTEXITCODE -ne 0) { throw 'Runtime recovery dump failed.' }
            $db = if ($Administration) { 'chemicallab_t12_administration_recovered_test' } elseif ($Boards) { 'chemicallab_t12_board_recovered_test' } else { 'chemicallab_t12_content_recovered_test' }
            & "$PostgresBin/createdb.exe" -h 127.0.0.1 -p $DatabasePort -U $role $db
            if ($LASTEXITCODE -ne 0) { throw 'Runtime recovery database creation failed.' }
            & "$PostgresBin/pg_restore.exe" -h 127.0.0.1 -p $DatabasePort -U $role -d $db --exit-on-error "$run/recovery.dump" *> "$run/restore.log"
            if ($LASTEXITCODE -ne 0) { throw 'Runtime restore failed.' }
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
