param(
    [Parameter(Mandatory)][string]$PostgresBin,
    [Parameter(Mandatory)][int]$Port,
    [Parameter(Mandatory)][string]$RunDirectory,
    [ValidatePattern('^[a-z0-9_]{1,16}$')][string]$Case = 'legacy'
)
$ErrorActionPreference = 'Stop'
$run = (Resolve-Path -LiteralPath $RunDirectory).Path
$ready = Get-Content -Raw "$run/browser/ready.json" | ConvertFrom-Json
if (([Uri]$ready.origin).Host -ne '127.0.0.1' -or $ready.mode -ne 'runtime' -or (Test-Path "$run/upgraded.pid")) {
    throw 'Use a disposable J installation before the content upgrade.'
}
$pg = @('-h','127.0.0.1','-p',"$Port",'-U','t12_runtime_local')
$source = 'chemicallab_t12_runtime_test'
$database = "chemicallab_t12_content_${Case}_test"
$psql = "$PostgresBin/psql.exe"
$seed = @"
with content as (
  insert into concept_contents(title,category,explanation,active,status,created_by_teacher_id,created_at,updated_at)
  select 'Contenido historico ficticio','Quimica','Texto conservado',true,'DRAFT',id,now(),now()
  from teacher_profiles order by id limit 1 returning id
)
insert into concept_materials(concept_content_id,type,active,title,file_data,file_size,content_type,original_file_name)
select id,'FILE',true,'Dato historico',decode('414243','hex'),3,'application/pdf','ficticio.pdf' from content
"@
& "$PostgresBin/pg_dump.exe" @pg -d $source -Fc -f "$run/historical-fence.dump"
if ($LASTEXITCODE -ne 0) { throw 'Dump of disposable J database failed.' }
& "$PostgresBin/createdb.exe" @pg $database
if ($LASTEXITCODE -ne 0) { throw 'Historic disposable database creation failed.' }
& "$PostgresBin/pg_restore.exe" @pg -d $database --exit-on-error "$run/historical-fence.dump"
if ($LASTEXITCODE -ne 0) { throw 'Historic disposable restore failed.' }
& $psql @pg -d $database -X -v ON_ERROR_STOP=1 -c $seed
if ($LASTEXITCODE -ne 0) { throw 'Historic disposable seed failed.' }
$signature = "select c.id,c.title,m.id,encode(sha256(m.file_data),'hex') from concept_contents c join concept_materials m on m.concept_content_id=c.id"
$before = & $psql @pg -d $database -XAtq -v ON_ERROR_STOP=1 -c $signature
$sql = Join-Path (Split-Path $PSScriptRoot -Parent) 'src/main/resources/db/runtime/content-v1.sql'
& $psql @pg -d $database -X -v ON_ERROR_STOP=1 -f $sql *> "$run/historical-fence.log"
if ($LASTEXITCODE -eq 0 -or !(Select-String "$run/historical-fence.log" -Pattern 'Historical content requires approved F08 mapping' -Quiet)) {
    throw 'Expected an explicit F08 rejection.'
}
$after = & $psql @pg -d $database -XAtq -v ON_ERROR_STOP=1 -c $signature
$absent = (& $psql @pg -d $database -XAtq -v ON_ERROR_STOP=1 -c "select to_regnamespace('content_runtime') is null") -eq 't'
if (!$before -or $before -cne $after -or !$absent) { throw 'The rejected transition did not preserve its source.' }
@{source=$database; before=$before; after=$after; contentSchemaAbsent=$absent} | ConvertTo-Json |
    Set-Content "$run/historical-fence.json"
