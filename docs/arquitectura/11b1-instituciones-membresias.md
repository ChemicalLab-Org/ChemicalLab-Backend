# T11-B1 — Base persistente de instituciones, membresías y roles

12 de octubre de 2026, America/Lima. Bloque limitado para revisión en
`feature/t11b1-instituciones-membresias`, PR backend en borrador hacia `develop`.
Base actualizada: `57c6e5862566bd90c74cbeda1dc2bb487b9fa00a` (T11-A integrada).
El checkout backend estaba limpio; se creó la rama sobre `origin/develop` después
de fetch. No se encontraron AGENTS.md aplicables en ancestros ni en el backend,
incluidos archivos ocultos. No hubo cambios ajenos que exigieran otro checkout.

Se leyeron [T11-A](11a-modelo-institucional.md), la auditoría original y su cierre
local T11-A, además de las notas pertinentes T02 (cuentas/sesiones), T03 (STOMP),
T08 (auditoría) y T10 (concurrencia). Se conservan los documentos anteriores,
frontend institucional y los 16 archivos E2E. SEC-13 y VER-01 siguen abiertos.
Esta entrega no completa T11-B ni inicia T12, no habilita registro ni acceso
multiinstitucional, y no autoriza merge, despliegue o DDL en bases reales.

## Esquema y persistencia

La fuente explícita es `src/main/resources/db/expansion/t11b1-v1.sql`, versión 1,
revisión `t11b1-v1`. Las entidades Java son records persistidos mediante JDBC;
no llevan `@Entity` y Hibernate no crea ni actualiza estas tablas. La configuración
legacy de Hibernate se conserva; `update` no sustituye esta expansión. No hay
instalador de arranque, seeder, backfill ni endpoint nuevo.

| Tabla | Identidad y datos | Integridad |
| --- | --- | --- |
| `institutions` | bigint identity, código técnico de plataforma, nombre, zona horaria, estado, versión de acceso, timestamps UTC | Código único ASCII mayúsculo de 1–64 caracteres; nombre no vacío; zona reconocida por PostgreSQL; identidad/código/creación inmutables |
| `institution_memberships` | bigint identity, institución/cuenta existentes, estado, ventana, comienzo del estado, versión, actor y motivo del último cambio | Unique institución/cuenta; FK a cuenta sin modificarla; unique id/institución para referencias compuestas; identidad inmutable |
| `membership_events` | Snapshot por membresía/versión de estado, ventana, instante efectivo, actor, motivo e instante de registro | FK compuesta, unique membresía/versión; generado por trigger; inserción manual, update, delete y truncate rechazados |
| `membership_role_grants` | bigint identity, membresía/institución, rol, ventana original, estado, otorgante, revocante, instante y motivo de revocación | FK compuesta; roles cerrados; fechas finitas y válidas; exclusión GiST por membresía/rol/intervalo efectivo; identidad y ventana original inmutables |
| `t11b1_schema_version` | versión/revisión, firma de catálogo e instante de instalación | Una versión conocida; repetición valida catálogo y no modifica datos ni reinstala objetos |

F01 sigue pendiente: esta institución es una frontera técnica, sin jerarquía de
sedes ni identificador oficial obligatorio. La captura/verificación de identificadores
oficiales se incorporará cuando se defina F01; no se elige tipo, granularidad ni
unicidad oficial por conveniencia. Tampoco se añaden códigos académicos, aliases
o datos escolares de presentación a la membresía en este bloque.

## Estados, vigencia e historia

`Institution`: `DRAFT → ACTIVE ↔ SUSPENDED → ARCHIVED`. Solo se archiva desde
SUSPENDED; ARCHIVED no admite cambios ni reactivación rutinaria. Cada actualización
válida incrementa su versión en PostgreSQL. El servicio rechaza cambiar al mismo
estado. No se borra ni trunca una institución.

`InstitutionMembership`: alta PENDING; activación ACTIVE; ACTIVE ↔ SUSPENDED;
ACTIVE/SUSPENDED → ENDED. Readmisión ENDED → ACTIVE reutiliza el ID y abre una
nueva ventana. Los instantes de transición son estrictamente crecientes. Este
mantenedor mínimo usa ventanas abiertas hasta la baja efectiva; ENDED exige
`valid_until = state_since > valid_from`. La readmisión establece un nuevo
`valid_from = state_since` y elimina solo el fin del snapshot actual; los snapshots
del episodio anterior permanecen en `membership_events`. No hay API de admisión
programada ni interpretación de estas fechas como autoridad actual.

Los eventos son snapshots append-only: se reconstruyen transiciones y episodios
por membresía/versión, distinguiendo `state_since` (instante efectivo UTC aportado
por el llamador interno) de `recorded_at` (registro PostgreSQL). ROLE_CHANGED
conserva el estado y la ventana y registra otra versión; no representa readmisión.
Suspender/reactivar no cambia el episodio de admisión. No se deduce su historia
del último estado ni se reescriben eventos anteriores.

Los roles institucionales son exclusivamente ADMIN_INSTITUCION, DOCENTE y
ESTUDIANTE. ADMINISTRADOR permanece en el enum legacy de cuenta; no existe en
el enum ni CHECK de grants institucionales. Se permite estructuralmente combinar
ADMIN_INSTITUCION + DOCENTE y pertenecer a varias instituciones. No se decide
co-docencia, múltiples clases u otras políticas complementarias de F02/F04.

Los grants nuevos requieren membresía ACTIVE y `valid_from >= state_since`.
Sus ventanas son `[desde,hasta)`, con fin opcional. ACTIVE/REVOKED distingue el
registro; un grant vencido puede seguir etiquetado ACTIVE, pero su intervalo ya
terminó. La exclusión considera también la historia efectiva de REVOKED:
`[valid_from, min(valid_until, revoked_at))`. Si se revoca antes de su inicio,
su intervalo efectivo es vacío. Una revocación conserva la ventana original y
solo añade actor, instante y motivo cerrado REMOVED/MEMBERSHIP_ENDED. No se
permite restaurar ni editar un grant revocado. El nuevo grant puede empezar
exactamente en el fin efectivo del anterior, pero no solaparse con él.

Antes de ENDED se revocan todos los grants ACTIVE dentro de la transacción;
PostgreSQL rechaza cerrar una membresía con grants abiertos o historia efectiva
que exceda su fin. Readmisión no restaura grants: requiere concesiones nuevas.
Suspensión no revoca automáticamente grants, preservando su historia; T12 deberá
componer vigencia/estado institucional y de membresía con los grants para cualquier
autoridad futura. Este bloque no concede facultades ni derecho de calificación.

La unicidad y exclusión se sostienen en PostgreSQL, también frente a SQL directo
y otras conexiones. Los triggers de grant bloquean la fila de membresía; el
servicio toma esa fila antes de revocar/conceder/cerrar. Los triggers incrementan
versiones y generan eventos en la misma transacción. La exclusión utiliza
`btree_gist`, conforme a la [documentación PostgreSQL de rangos y exclusión](https://www.postgresql.org/docs/18/rangetypes.html).

## Operaciones internas y auditoría T08

`InstitutionMaintenanceService` ofrece crear institución, cambiar estado, crear
membresía PENDING, transicionar, otorgar rol y revocarlo. `InstitutionRepository`
mapea lecturas de las tres entidades. Los métodos son infraestructura interna
confiable: no se conectan a controladores, bootstrap, login, jobs o importadores.
Antes de exponer futuros llamadores, T12 deberá añadir su autorización por ámbito.

Cada operación usa el transaction manager existente: JDBC y `AuditLogWriter`
participan en la misma transacción. Un error de evidencia obligatoria revierte
tablas nuevas, versiones y eventos. Terminar una membresía registra cada
revocación y la transición, juntas. Los eventos nuevos son INSTITUTION_CREATED,
INSTITUTION_STATE_CHANGED, MEMBERSHIP_CREATED, MEMBERSHIP_STATE_CHANGED,
MEMBERSHIP_ROLE_GRANTED y MEMBERSHIP_ROLE_REVOKED.

El actor se obtiene exclusivamente de SessionPrincipal autenticado; una llamada
interna sin contexto deja actor null, sin inventar identidad humana. Grants y
eventos guardan el mismo actor cuando existe. La auditoría incluye tipo/ID del
objetivo, resultado SUCCESS, correlación T08 y un institutionId numérico permitido
en metadata; no incluye nombres, códigos, credenciales ni texto libre. Se amplió
solo el vocabulario cerrado de objetivos/ID; no se relajó el filtro de secretos.
El enum global Role y la resolución de autoridades no se modificaron.

SQL directo de ensayo verifica integridad pero no es un flujo de negocio auditado
por el servicio. Un operador real necesitaría procedimiento y evidencia propios;
esta entrega no autoriza ese uso. Triggers y firma detectan cambios ordinarios,
no prometen resistencia frente a un DBA capaz de deshabilitarlos o alterar el
ledger. El rol runtime futuro debe carecer de ALTER, DROP, TRUNCATE y escritura
del ledger; no se certifican aquí permisos de ninguna base real.

## Expansión explícita y repetición

El DO completo es atómico; `--single-transaction` conserva además el procedimiento
de ejecución. Antes de una instalación futura autorizada: comprobar DB/esquema,
detener escritores, revisar fuente/checksum, versión de PostgreSQL/extensión,
backup/restauración y permisos. No usar credenciales en argumentos ni logs.

```powershell
# Conexión del entorno de ensayo ya seleccionada mediante PGHOST/PGPORT/PGDATABASE/PGUSER.
# Credenciales mediante pgpass; comprobar la salida antes de ejecutar la expansión.
$env:PGOPTIONS='-c search_path=public'
psql --set ON_ERROR_STOP=1 --command 'select current_database(), current_schema(), current_setting(''search_path'');'
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b1-v1.sql
psql --set ON_ERROR_STOP=1 --command 'select * from t11b1_schema_version;'
# Repetir el mismo archivo valida la firma y no crea otra versión ni datos.
```

`user_accounts` debe pertenecer al esquema seleccionado, no resolverse desde otro
schema. `btree_gist` se instala si falta; si ya existe, sus operator classes deben
ser visibles en el search_path comprobado. Se usa un advisory transaction lock
para coordinar expansiones de esta versión. Objetos institucionales sin ledger,
versión/revisión desconocida o divergencia de firma causan error, sin reparación
automática. La firma cubre columnas/orden/tipos/defaults/identity, constraints y
validación, índices y estados, triggers/enablement, funciones y configuración de
secuencias; excluye datos, OIDs y consumo de secuencias. No es un checksum del
archivo ni un mecanismo criptográfico de autenticación. El checksum del archivo
debe revisarse por separado; toda evolución posterior necesita otra versión.

## Compatibilidad

No se modifica ni backfillea UserAccount, IDs, username/email, hashes, roles,
credencialesVersion o sesiones. Nuevas membresías/grants no cambian JWT,
SessionPrincipal, `/auth/me`, reglas REST ni STOMP. Una cuenta con grants DOCENTE
y ADMIN_INSTITUCION sigue autenticándose con su rol legacy ESTUDIANTE y recibe
403 en rutas docentes/administrativas y rechazo de un tópico ajeno.

No se asignan usuarios existentes automáticamente ni se infiere pertenencia por
actividad. No se tocan evaluaciones, intentos, respuestas, presupuesto compartido
por evaluación/cuenta, padrón, clases, perfiles, materiales o cuotas. F03 conserva
la decisión de lectura individual histórica bajo T05; su implementación externa
corresponde a T12/T13 y no se afirma entregada por estas tablas.

## Verificación y evidencias

**SHA de código probado:** `34547cfebcb8cf29ba951cb6dc3797f0cb44ee71`.
`clean package` completo finalizó el 2026-10-12 a las 15:58:38 America/Lima:
**54 suites, 611 pruebas, cero fallos, errores u omisiones; JAR generado**.
Las 18 pruebas de `InstitutionPersistenceDbTest` están incluidas en esas 611;
no se suman otra vez. El commit de cierre posterior cambia exclusivamente esta
documentación; sus fuentes, SQL, tests y POM son idénticos al SHA probado.

| Ejecución | Alcance y resultado |
| --- | --- |
| Selección inicial, iteración sin commit (`focused-sixth.log`) | 16 casos institucionales, 0/0/0; anterior al caso de actor y a la corrección de restauración. No es una suite completa ni la validación del código final |
| Primer intento completo, `a4cde60dff4b4ef869ddf8879d311c35b2cdb6ce` | Interrumpido al agotar las 100 conexiones del cluster de ensayo; T10 no pudo arrancar una JVM. No cuenta como suite completa |
| Segundo intento completo, mismo SHA `a4cde60…` (`full-package-final.log`) | 610 casos, 0 fallos, 15 errores, 0 omisiones; faltaba perfil test en las suites legacy. Sin JAR. No se presenta como aprobado |
| Ejecución definitiva, `34547cf…` (`full-verified.log`) | Suite completa y empaquetado: 611/0/0/0; PostgreSQL desechable con 300 conexiones y perfil test explícito |
| Ensayos nativos, `34547cf…` | psql expansión/repetición, rechazo de divergencia, dump/restore/revalidación y dos carreras con dos procesos psql cada una; resultados propios, fuera del conteo JUnit |

Los demás intentos de compilación/fixture quedan en los logs locales de iteración,
sin convertir fallos de preparación en pruebas aprobadas. Los mensajes de guardas
de arranque y `SECURITY_AUDIT_EVIDENCE_UNAVAILABLE` de casos deliberados no se
interpretan como errores de la ejecución JUnit: los XML finales y el resumen
Maven confirman cero fallos/errores. No se acumulan conteos de ejecuciones.

| Invariante verificado | Evidencia del bloque |
| --- | --- |
| Cuenta simultánea en A y B; roles separados/combinados | Dos membresías independientes, ADMIN_INSTITUCION + DOCENTE en A, ESTUDIANTE en B; suspender A conserva estado/versión de B |
| Duplicados y referencias contradictorias | Servicio y SQL rechazan pareja duplicada, cuenta/institución inexistente, grant de otro tenant y cambio de identidad de membresía |
| Roles/fechas/solapamiento | SQL rechaza ADMINISTRADOR/roles desconocidos, ventanas vacías/invertidas/infinitas, grant anterior al estado actual y solapamiento, incluso historia revocada; permite borde adyacente |
| Transiciones e historia | PENDING, ACTIVE, SUSPENDED, reactivación, ENDED y readmisión conservan versiones 0–7 y snapshots previos; readmisión conserva ID sin restaurar grants |
| Historia protegida | Update/delete/truncate ordinarios rechazados; ARCHIVED no se reactiva; grants solo se revocan |
| Concurrencia | Dos conexiones JDBC con transacciones independientes y, además, dos procesos OS psql por carrera: un commit y un rechazo; una membresía y un grant. Rechazos por unique y exclusion, no por timeout |
| Expansión | Aplicación y repetición sobre legacy poblado, sin asignación automática; objetos sin versión y divergencias de columnas/triggers se rechazan sin reparar |
| Compatibilidad/autorización | Mapas completos de user_accounts y account_sessions idénticos, incluidos IDs/hashes/rol/versiones; /auth/me conserva ESTUDIANTE; grants nuevos no habilitan rutas admin/docente ni tópico STOMP ajeno |
| Auditoría | Rollback revierte grant, versión, eventos y SUCCESS; fallo de destino revierte transición; actor autenticado, institutionId y correlación conservados sin token/nombres de negocio |
| Recuperación | Constraints reconstituidos desde pg_get_constraintdef conservan firma; pg_dump/pg_restore real ficticio valida expansión y siete firmas de datos sin discrepancias |

El ensayo nativo usó `chemicallab_t11b1_expansion_test` y
`chemicallab_t11b1_restore_test`, en el mismo cluster ficticio. Un primer restore
reveló una diferencia de representación de arrays varchar en CHECKs equivalentes;
se hicieron explícitos los casts a text, sin ampliar valores permitidos, y se
repitió el ensayo limpio. El restore final preservó firmas de cuentas, sesiones,
instituciones, membresías, eventos, grants y ledger (7/7). La revalidación con
trigger deshabilitado salió con código psql 3; al restaurar su enablement volvió
a validar. No se editó el fingerprint para ocultar diferencias.

Comandos nativos usados, con variables de conexión restringidas al cluster ficticio:

```powershell
pg_dump -Fc --file fictitious-expansion.dump chemicallab_t11b1_expansion_test
createdb chemicallab_t11b1_restore_test
pg_restore --single-transaction --exit-on-error --dbname chemicallab_t11b1_restore_test fictitious-expansion.dump
psql --dbname chemicallab_t11b1_restore_test --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b1-v1.sql
```

Fuente SQL ensayada en UTF-8/LF, SHA-256:
`23eb762d3ac581289e05f09725ab235efea7cf3d50b832e3a98f89e730e59548`.
El checksum de un checkout CRLF puede diferir; contrastar el contenido Git del
SHA probado y la normalización antes de concluir una divergencia de fuente.

Se retiraron las tres bases ficticias, se comprobó cero bases `chemicallab%` en
ese cluster y se detuvo el servidor. Manifiesto final: 26/26 archivos conservados
(10 documentos de seguridad anteriores y 16 E2E), cero discrepancias SHA-256.
Frontend permanece en `feature/validaciones-datos-institucionales`,
`e88589635a8269c50ee0f53fccf157f0d0985034`, con su `?? e2e-selenium/` previo.
Auditoría original conservada:
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
El diff backend contiene diez archivos exclusivamente de este bloque y conserva
todos sus documentos anteriores. Fetch de entrega reconfirmó develop en la base
57c6e58, sin avances posteriores que incorporar.

El comando reproducible usa JDK 17 y exclusivamente el PostgreSQL desechable
`127.0.0.1:55431/chemicallab_t01_test`, rol ficticio `t11b1_fixture`:

```powershell
./mvnw.cmd '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55431/chemicallab_t01_test' '-Dspring.datasource.username=t11b1_fixture' '-Dspring.datasource.password=fictitious' '-Dtest=InstitutionPersistenceDbTest' test
./mvnw.cmd '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55431/chemicallab_t01_test' '-Dspring.datasource.username=t11b1_fixture' '-Dspring.datasource.password=fictitious' clean package
```

Para preparar un cluster nuevo de ensayo, con PostgreSQL 18 en PATH y ese puerto
libre, desde la raíz backend (el directorio elegido debe estar vacío):

```powershell
initdb -D ../.codex-work/t11b1-repro-postgres -U t11b1_fixture -A trust --encoding=UTF8 --locale=C
pg_ctl -D ../.codex-work/t11b1-repro-postgres -l ../.codex-work/t11b1-repro-postgres.log -o '-h 127.0.0.1 -p 55431 -c max_connections=300' -w start
createdb -h 127.0.0.1 -p 55431 -U t11b1_fixture chemicallab_t01_test
# Tras finalizar y retirar exclusivamente los datos ficticios:
pg_ctl -D ../.codex-work/t11b1-repro-postgres -m fast -w stop
```

Trust se limita al ensayo loopback; no es una configuración productiva. La suite
abre varios contextos y JVM simultáneos: 100 conexiones no bastaron, por eso se
usaron 300. El perfil test es imprescindible para las suites legacy SpringBootTest;
sus defaults públicos de ensayo no habilitan bootstrap, demos ni instalación real.

El fixture existente rechaza destinos que no coincidan con la DB loopback ficticia,
crea schemas aleatorios y los elimina al terminar. Logs/manifiestos quedan en
`.codex-work/t11b1-evidence` fuera de la PR, sin datos reales. Las selecciones,
intentos fallidos y suite completa se reportan por ejecución, sin sumarlos como
pruebas únicas.

## Recuperación y siguientes bloques

Un fallo del DO revierte toda la expansión. Un fallo de negocio/evidencia revierte
su transacción; tras recuperar el destino de auditoría se reintenta explícitamente,
sin cola ni eventos reconstruidos. Si el resultado del commit es desconocido,
investigar IDs/correlación antes de repetir altas no idempotentes.

Ante divergencia, detener mantenimiento y comparar contra fuente y backup;
no actualizar el fingerprint para ocultarla ni borrar la historia. Para una
restauración futura, restaurar dump de esquema/datos a un PostgreSQL separado,
con el mismo nombre de esquema, y verificar ledger/firma, conteos/IDs y snapshots
antes de habilitar escritores. Los triggers append-only se crean después de
cargar datos en un restore completo; no se propone desactivarlos sobre datos
vivos. No eliminar una extensión compartida ni aplicar DROP CASCADE en una DB
real como reversión. Con datos nuevos, revertir la aplicación conservando estas
tablas inertes exige revisión; borrar tablas perdería episodios y grants.

Pendiente de T11-B: PlatformRoleGrant independiente para ADMINISTRADOR global,
sin inferir privilegios desde membresías; importar exclusivamente privilegio
legacy confirmado mediante expansión/versiones y conciliación auditadas. No se
implementa ni migra anticipadamente. También siguen pendientes aliases/login,
códigos académicos, períodos/clases, asignaciones docentes, matrículas, recursos/
revisiones/entregas, padrón histórico, contexto de hechos, ledger de conciliación,
backfill y presupuestos compartidos legacy descritos por T11-A.

F01, F05–F09 y complementos de F02/F03/F04 continúan pendientes. Ninguna decisión
pendiente fue indispensable para el almacenamiento técnico de este bloque. T12
deberá autorizar bilateralmente REST/STOMP y comprobar versiones/contexto;
T13 deberá aplicar continuidad/históricos/calificación con DOCENTE + asignación,
sin autocalificación ni bypass T05. La entrega se detiene en PR borrador para revisión.
