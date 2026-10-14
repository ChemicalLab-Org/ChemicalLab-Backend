# T11-B11 / P06 — Lotes, checkpoints y recuperación del ensayo

14 de octubre de 2026, America/Lima. Base `develop`
`f22fb04bd62fa635f8f2b096b33bf76306f7a30e`, verificada por fetch. Checkout
aislado `.codex-work/t11b11-backend`, rama `feature/t11b11-lotes-recuperacion`.
Leídos T11-A, T11-C, B10, su cierre local y los contratos SQL/servicios/pruebas
aplicables. Sin AGENTS.md aplicables ni delegación. Los resultados definitivos,
su SHA y los hashes de conservación se registran al final de esta nota.

Esta entrega prepara exclusivamente P06 con datos ficticios. Los mantenedores
carecen de endpoints, jobs, listeners, activación al arrancar o autoridad
REST/STOMP. Se exige la guarda B5: loopback `127.0.0.1`, DB
`chemicallab_t01_test`, schema propio `t03_<32 hex>`. El servicio rechaza un
contexto autenticado; las aprobaciones siguen entrando por el mantenedor F08
B10, con dos cuentas distintas y privilegios vigentes. El rol DB de ensayo es
la frontera de confianza del SQL; un propietario/DBA capaz de alterar triggers
o configuración no queda protegido por un hash de catálogo.

## Protocolo persistente

`P06_PLAN_V1` ordena propuestas B10 aprobadas por `root::text`, versión e ID.
El orden se congela; la lista del llamador no decide un orden diferente. Cada
unidad es **un agregado íntegro**, nunca una selección de sus hijos. Incluye
proposalId/versión, `P05_ROW_V1`, fuente/PK, manifiesto/checksums, mapa, referencias,
antecedentes, checksum de medición y ambas fotografías F08. Una propuesta solo
puede pertenecer a una unidad; el mismo recurso puede corregirse posteriormente
mediante otra propuesta/versionado B10. No se mutan sus originales.

La identidad estable de la unidad es `(plan UUID, ordinal)` y su operación UUID
se deriva de ambos. `units.checksum` liga toda su evidencia; el checksum MD5
detecta deriva de JSONB, no autentica personas. El plan guarda especificación,
límites, checksum y versión. Las unidades y operaciones son append-only.

`CREATE` publica plan/unidades y SUCCESS en una transacción. `STEP` procesa una
unidad: aplicación B10 → versiones/medición/cuotas/historia → SUCCESS T08 →
checkpoint → progreso. El checkpoint incluye operación, checksum de unidad,
comando B10, estado APPLIED o STALE, firma de versiones, medición, auditId y
`pg_current_xact_id()` completo. La fila de operación guarda el mismo XID, la
petición exacta y el resultado. **Su visibilidad confirmada prueba el commit**
de todo ese trabajo; no se guarda un LSN previo como si fuera el del commit.
Los commits usan `synchronous_commit=on`; la recuperación incluye sus registros
de commit en WAL hasta el punto de recuperación verificado.

`PAUSE` y `RESUME` son operaciones durables; incrementan un epoch bajo la misma
coordinación. Pausar espera al lote en curso; produce una frontera entre commits.
Otra ejecución consulta `observe(plan)`, conserva las unidades ya confirmadas y
continúa con `next_ordinal`/epoch vigentes. Un trabajador con epoch anterior falla
antes de aplicar. RESUME cambia coordinación; cada STEP revalida fuente,
antecedentes, referencias, medición y ambas facultades F08 mediante B10 antes de
escribir negocio. No actualiza aprobaciones antiguas para adaptarlas a cambios.
Un cambio produce STALE durable, conserva las pruebas anteriores y pausa el plan.
Una corrección requiere propuesta nueva y sus propias aprobaciones.

La transacción conserva el advisory lock de B5/B10 y locks de tablas en orden
estable. No hay lease expirable ni una conexión que aplique fuera del lock.
Una muerte real de JVM cierra la conexión: PostgreSQL revierte negocio/checkpoint
y libera coordinación. Un trabajador no puede guardar un checkpoint en otra
transacción ni escribir después de perder ese lock. Dos ejecutores sobre la
misma unidad reciben el mismo resultado persistido y un solo SUCCESS.

Ante commit desconocido, consultar `lookup(operation)` y comprobar acción,
petición, XID, resultado, checkpoint y auditoría. Una ausencia observada mientras
otro backend trabaja todavía no acredita rollback. `t11p06_execute` vuelve a
consultar la operación **después de adquirir coordinación** antes de insertar;
un UUID con otra petición falla. Repetir la misma petición no duplica versiones,
historia, mediciones, cuotas, checkpoint ni SUCCESS. Todos los accesos SQL de
inserción de comandos atraviesan las mismas guardas y chequeos de cuota.

## Límites efectivos y alcance del lote

| Límite | Contrato efectivo |
| --- | --- |
| Plan | 1–100 propuestas distintas; orden congelado |
| Transacción de trabajo | Exactamente un agregado; 1–256 PK y hasta 2 MiB UTF-8 de manifiesto, reducibles por plan |
| Fixture completo | Hasta 20 000 filas y 64 MiB de JSON UTF-8 entre todas las tablas de ambos schemas, reducibles por plan |
| Tamaño físico | Hasta 256 MiB sumando `pg_total_relation_size` de todas esas tablas, incluidos TOAST/índices; la hinchazón también puede rechazar el ensayo |
| Almacenamiento P06 | CONTROL `P06_ROW_JSON_UTF8_V1`, hasta 100 000 filas / 32 MiB lógicos; techo independiente sin autoridad de negocio |
| Tiempo | Servicio: transacción 60 s, statement 55 s, adquisición de lock 3 s. Contendientes de prueba: statement 55 s, lock 20 s para su barrera |
| Bloqueo | Schema de negocio completo en SHARE ROW EXCLUSIVE y tablas técnicas; serializa escritores de ensayo, no promete concurrencia productiva |

El preflight comprueba tamaño físico y cuenta filas con `LIMIT restante+1`
antes de llamar funciones B10 de datos. Solo entonces mide JSON; comprueba el
mismo sobre después de escribir, con rollback si el resultado excede límites.
El agregado demasiado grande se rechaza completo; no publica una corrección
parcial. Los límites mayores que los máximos del contrato se rechazan.

**B10 sigue escaneando completamente el fixture** para manifiesto, frescura y
medición. P06 no lo convierte en un censo paginado ni en un algoritmo de coste
constante respecto del número de filas. La propiedad demostrada es un sobre
global finito, bloqueado y comprobado antes/después de cada unidad; los ensayos
rechazan un fixture o agregado fuera de ese sobre. La preparación de propuestas
e inventario B10 conserva sus límites históricos. No se afirma rendimiento,
memoria máxima de 64 MiB, ausencia universal de deadlocks ni backfill de tamaño
arbitrario. Medir JSON de una fila puede requerir memoria de deserialización;
el techo físico y el timeout son límites adicionales, no una certificación de
capacidad. Ventana/capacidad/escritores reales pertenecen a T12/operación.

`t11p06_control_usage()` mide las filas reales de las cinco tablas técnicas,
incluidos fotografías, comandos, checkpoints, ledger y plan mutable. No guarda
otra fotografía de su propia medición ni genera una serie recursiva. Las filas
SUCCESS ContextResolution ya consumen CONTROL P05 y no se cobran otra vez en
P06. No se suman estas dimensiones a los contadores B9 o ACCOUNT/SYSTEM legacy.
Los incrementos de atribución B10 siguen sujetos a límites institucionales B9.
Todo confirma o revierte junto con el checkpoint.

## Evolución y certificación

Los **trece SQL B1–B10** y todos sus ledgers/fingerprints permanecen intactos.
`t11b11-v1.sql` incorpora íntegro el certificador B10 congelado y añade el
sucesor B11. Las funciones nuevas usan `t11p06_*`: `t11b11_*` colisionaría con
el patrón histórico `t11b1_%`. No se excluyen objetos de un certificador viejo
ni se recalculan firmas para ocultar deriva.

El control reside en `t06_<el mismo hex del schema t03>`: `schema_version`,
`plans`, `units`, `commands`, `checkpoints`. Mantenerlo separado conserva el
censo cerrado B10, sus once exclusiones originales y su firma de fuentes. No
es otra institución, mapa o autoridad. Sus FKs apuntan a propuestas, comandos,
mediciones y auditoría del schema de negocio; respaldo y recuperación incluyen
**ambos schemas**. B11 certifica columnas/defaults, constraints/validación,
índices/estado, triggers/enablement, funciones y catálogo técnico junto al B10
congelado. Objetos desconocidos, huérfanos de ledger o divergentes se rechazan.
El ensayo vuelve a certificar B11/B10 tras recuperación sin Hibernate reparador.

Cadena para schema nuevo: B1 v1 → B2 v1/v2 → B3 v1/v2 → B4 v1/v2 → B5 v1 →
B6 v1 → B7 v1 → B8 v1 → B9 v1 → B10 v1 → **B11 v1**. En B10 certificado
se aplica solo el sucesor faltante. Cada archivo completo requiere transacción.

```powershell
# Exclusivamente fixture propio: configurar PGHOST/PGPORT/PGUSER/PGDATABASE y pgpass.
psql --set ON_ERROR_STOP=1 --command 'select current_database(),current_schema();'
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b11-v1.sql
# La repetición certifica; no repara ni reejecuta unidades.
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b11-v1.sql
```

Los métodos internos `create`, `pause`, `resume`, `observe`, `lookup` y `step`
están en `TransitionBatchMaintenanceService`. El llamador debe usar un servicio
Spring transaccional y una llamada por lote; no envolver el plan entero en una
transacción externa. SQL reproducible, sobre propuestas ficticias F08 ya aprobadas:

```sql
-- UUIDs/proposalIds de ejemplo deben ser los del ensayo propio.
BEGIN;
SET LOCAL lock_timeout='3s'; SET LOCAL statement_timeout='55s';
SELECT t11p06_execute(:'operation'::uuid, 'CREATE',
 jsonb_build_object('plan', :'plan'::uuid, 'proposals', jsonb_build_array(:proposal_id),
 'limits', jsonb_build_object('rows',20000,'bytes',67108864,'aggregateRows',256,'aggregateBytes',2097152)));
COMMIT;
SELECT t11p06_observe(:'plan'::uuid); -- usar operation_id de la unidad, ordinal y epoch
BEGIN;
SET LOCAL lock_timeout='3s'; SET LOCAL statement_timeout='55s';
SELECT t11p06_execute(:'unit_operation'::uuid, 'STEP',
 jsonb_build_object('plan', :'plan'::uuid, 'ordinal',1,'epoch',1));
COMMIT;
SELECT t11p06_lookup(:'unit_operation'::uuid);
```

## Recuperación: contrato mínimo con WAL existente

Se elige **respaldo físico + WAL archivado** de PostgreSQL. Un journal limitado
a los comandos P06 omitiría las escrituras legacy, los efectos de triggers y
operaciones SQL posteriores; no justificaría recuperación de todas las relaciones,
cuotas y auditoría. WAL conserva las páginas y commits reales de todas las tablas
persistentes del cluster sin modificar escritores ni reejecutar su lógica.

Cobertura demostrada: cluster PostgreSQL desechable propio, tablas persistentes,
secuencias, bytea internos, historia/triggers, controles B1–B11 y commits hasta
un punto nombrado exacto. Incluye escrituras SQL sintéticas fuera de los comandos
P06, además de sus agregados. No es adopción REST/STOMP o de escritores vivos.
No cubre archivos externos, otros clusters, sistemas remotos, tablas UNLOGGED/
temporales ni un archivo WAL faltante. Fsync/full_page_writes/synchronous_commit
deben estar encendidos, wal_level=replica y archive_mode=on en el primario.
El ensayo comprueba esos ajustes y rechaza expresamente una tabla UNLOGGED.
La recuperación no se acepta mientras no alcanza el punto nombrado y coinciden
firmas/certificación. Sin WAL continuo o sin registro de commits posteriores,
**no hay declaración de recuperación completa ni promoción manual para eludir
el objetivo**. Para un corte real debe inventariarse además almacenamiento externo,
privilegios, escritores/configuración y política de archivo; permanece abierto.

`TransitionWalRecoveryFixture` clona físicamente el cluster ficticio del test
para crear un primario adicional en puerto propio; verifica su base con
`pg_verifybackup`. Toma un segundo `pg_basebackup --wal-method=stream
--checkpoint=fast` antes de las escrituras que se deben recuperar. Después
confirma STEP y altas/actualizaciones/eliminaciones representativas, historia,
cuotas y auditoría; obtiene `pg_create_restore_point` y `pg_switch_wal`, espera
el segmento requerido archivado y restaura la segunda base en otro puerto con
`recovery.signal`, `restore_command`, `recovery_target_name` y promoción al
objetivo. Una escritura deliberada **después del objetivo** queda fuera, probando
que la base no se copió desde el estado final del primario.

El primario permanece independiente durante el replay. WAL reproduce efectos
confirmados sin disparar otra vez comandos o triggers, devolver oportunidades
o insertar SUCCESS. Se comparan todas las tablas y el avance seguro/estado de secuencias
antes/después/recuperado; se certifica B11/B10 y se repite la operación recuperada.
WAL puede adelantar una secuencia por su registro anticipado de `nextval`;
se permiten esos huecos, conservando is_called y prohibiendo retroceso/reutilización.
Los IDs de filas, números/consumo académicos y todas las firmas de tablas son
exactamente iguales. No se usa setval para ocultar diferencias ni se consume un
ID al reintentar. Luego se reinicia la copia recuperada y se repite
certificación/comparación del estado recuperado completo.
No se deshabilitan guardas ni se sobreescribe una base activa con un dump viejo.
Los procesos creados se detienen al terminar; respaldos/WAL quedan fuera del PR.

Comandos nativos del arnés (parámetros exclusivamente de ese ensayo propio):

```powershell
pg_basebackup -h 127.0.0.1 -p <PUERTO_PRIMARIO_FICTICIO> -U <ROL_FICTICIO> -D <DIRECTORIO_NUEVO> --wal-method=stream --checkpoint=fast
pg_verifybackup <DIRECTORIO_NUEVO>
# recovery.signal y postgresql.auto.conf en la copia aislada; restore_command lee su archivo WAL.
pg_ctl -D <COPIA_AISLADA> -l <LOG_LOCAL> -w -t 45 start
# Esperar fin de recovery y objetivo; conectar a su puerto y certificar B11/comparar.
pg_ctl -D <COPIA_AISLADA> -m fast -w -t 45 stop
```

El arnés incluye copiado/contraste binario de segmentos en Windows y cp/cmp en
Unix. Las ejecuciones de esta entrega no certifican Linux. Un intento inicial
de preparación mediante dump selectivo fue rechazado por el certificador B5;
se conserva como fallido y se usa preparación física, sin tocar su fingerprint.
Otra iteración falló por separadores del restore_command Windows; el objetivo
no alcanzado no se publicó como recuperación exitosa.

## Matriz antes / después / recuperado y pruebas

| Hecho | Antes del respaldo | Después confirmado | Recuperado exigido |
| --- | --- | --- | --- |
| Unidad P06 | Pendiente, sin versiones/checkpoint/SUCCESS de STEP | Agregado completo APPLIED, medición y checkpoint mismo XID | Mismos IDs/firmas/XID/auditoría; repetir no agrega efectos |
| Material retenido | Ausente | Alta, actualización de bytes/título y misma relación/autor | Bytes actualizados, relación y consumo T07-B exactos |
| Material eliminado | Ausente | Alta y eliminación confirmadas con cuota neta liberada real | Ausente; ningún resucitado desde un dump anterior |
| Período | Ausente | Alta y cambio a OPEN, dos eventos históricos | Misma fila/relaciones y ambos eventos |
| Objeto de pizarra | Ausente | Alta con autor original y tombstone `deleted=true` | Misma identidad/autor/tombstone y cuotas |
| Legacy T05 | Agotado 2/2, activo sin assignment, nota cerrada/respuesta/feedback | STEP no modifica raíz, consumo, máximo, ventana o resultado | Firmas originales conservadas; sin devolución ni autoridad nueva |
| F09 nuevo | Un presupuesto, intento respondido de raíz académica explícita | STEP no crea raíz, presupuesto o intento sustituto | Misma raíz/presupuesto/consumo/respuesta |
| Auditoría/cuotas | Histórico íntegro | Efectos de altas/cambios/bajas + SUCCESS confirmados | Mismas filas/contadores; append-only y guardas siguen activas |
| Escritura posterior al objetivo | Ausente | Presente solo en primario tras el punto nombrado | Ausente en recuperación dirigida a ese punto |

Casos PostgreSQL en `TransitionBatchDbTest`: pausa/reanudación desde otra llamada,
replay exacto, fuentes entre lotes, F08 revocado, antecedente cambiado, unidad
incompatible entre planes, UUID reutilizado, rechazo por sobres/tamaño de agregado,
rollback por cuota/auditoría, sucesor/repetición/deriva y guardas. Dos JVM/PID
distintos compiten con barrera PostgreSQL y confirman una operación/SUCCESS.
Otra JVM muere realmente antes del commit; otra confirma y muere sin escribir
respuesta al ejecutor. `lookup` y replay contrastan estado completo. El caso
WAL realiza respaldo, escrituras posteriores, recuperación y reintento aislados.

```powershell
# Base ficticia propia ya preparada; JDK17 y PostgreSQL nativo en PATH/pgBin del arnés.
./mvnw.cmd -o test '-Dtest=TransitionBatchDbTest' '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55441/chemicallab_t01_test' '-Dspring.datasource.username=t11b11_fixture' '-Dspring.datasource.password=<CLAVE_FICTICIA>'
./mvnw.cmd -o clean package '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55441/chemicallab_t01_test' '-Dspring.datasource.username=t11b11_fixture' '-Dspring.datasource.password=<CLAVE_FICTICIA>'
```

## Balance para revisión

P06 se entrega como protocolo/ensayo restringido y verificable, con el sobre y
cobertura anteriores. P01–P06 preparados no cierran automáticamente T11-B
agregado ni etapa 3: queda revisión final de criterios. Mapas/responsables reales
P08, adopción/autorización/escritores T12, SEC-13, VER-01, Linux, RPO/RTO,
capacidad, permisos y demás pendientes operativos permanecen abiertos.
Sin merge, despliegue, DDL/backfill escolar, rotaciones o activación institucional.
Se conservan auditoría, documentos históricos, frontend institucional y 16 E2E.

## Evidencia definitiva

La ejecución completa final y su SHA exacto se incorporan aquí después de su
terminación. Las selecciones/iteraciones fallidas se mantienen separadas y no se
suman a la suite definitiva. Los artefactos voluminosos y credenciales sintéticas
permanecen locales en `.codex-work/t11b11-evidence` y `target/t11b11-*`.
