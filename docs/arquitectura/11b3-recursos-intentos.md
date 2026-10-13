# T11-B3 — Recursos institucionales y conservación del presupuesto legacy

Fecha: 13 de octubre de 2026, America/Lima. Rama `feature/t11b3-recursos-intentos`.
Base `develop`: `97d6fe5da8fb2b22ffda8c81d4f0b9761b619cf3`, confirmada por fetch.
Preparación interna y ensayo ficticio; no completa T11-B ni la etapa 3.

Se contrastaron [T11-A, §2.6 y A13–A17](11a-modelo-institucional.md),
[B1](11b1-instituciones-membresias.md), [B2](11b2-clases-matriculas.md),
el cierre local `Documentos/Seguridad/11b2-cierre-clases-matriculas.md` y las
notas [T03](../seguridad/02-03-seguridad-pizarras.md),
[T05](../seguridad/02-05-evaluaciones-ventanas.md),
[T06](../seguridad/02-06-materiales-archivos.md),
[T07-B](../seguridad/02-07b-consultas-cuotas-retencion.md),
[T08](../seguridad/02-08-auditoria-eventos.md) y
[T10](../seguridad/02-10-concurrencia.md). No se encontraron AGENTS.md aplicables
en el repositorio ni sus ancestros; el proyecto de vídeo está fuera de alcance.

## Modelo persistente

La expansión explícita `src/main/resources/db/expansion/t11b3-v1.sql` conserva
ConceptContent, Evaluation, perfiles, assignments, intentos, respuestas y ajustes
en sus tablas originales y con sus IDs. No añade anotaciones JPA ni instalador
de arranque. Los nuevos records JDBC son observaciones internas.

| Tabla (prefijo `t11b3_`) | Contrato |
| --- | --- |
| concept_resources / evaluation_resources | PK del recurso original; institución y autor de mapa aprobado. FK original, autor global y membresía opcional de la misma institución/cuenta. Procedencia inmutable |
| concept_revisions / evaluation_revisions | Contenido congelado, número técnico, checksum y procedencia. FKs compuestas recurso/institución; actualización/borrado/truncate rechazados |
| concept_deliveries / evaluation_deliveries | Clase y revisión de la misma institución, FK y snapshot íntegro del assignment original. Unique revisión/clase y assignment legacy |
| concept_recipients / evaluation_recipients | Unique entrega/cuenta; episodio opcional demostrado. La FK y guarda comprueban clase, institución y cuenta; ausencia de episodio no significa asistencia |
| attempt_contexts | Intento original, entrega/revisión/cuenta y episodio demostrado al startedAt original. Comprueba asignación, cuenta, clase y ventana efectiva del episodio. Inmutable |
| opportunity_groups | EvaluationOpportunityGroup: unique origen/evaluationId, máximo, configuración, todas las ventanas originales y cierre T05 al ensayo |
| account_attempt_budgets | AccountAttemptBudget: unique raíz/cuenta, consumo, siguiente número y activo originales; conflictos conservan consumo con número/activo pendientes |
| attempt_consumptions | PK attemptId original, presupuesto/cuenta/raíz, número/estado originales y snapshot. Nunca se duplica consumo |
| reconciliations / reconciliation_events | Origen, PK, versión `jsonb-v1`, snapshot/checksum, destino exacto/checksum, aprobación ficticia, estado e historia append-only |
| budget_observations | Añade vigencia calculada desde el origen y mapa actuales; admisión y exposición nuevas permanecen siempre deshabilitadas |

Las revisiones de conceptos contienen explicación, listas con su orden, materiales
con bytes y metadatos y referencias originales. Las de evaluación contienen
configuración, preguntas, opciones y sus IDs, autor y assignments originales.
No siguen una FK a contenido mutable para reconstruir silenciosamente el pasado.
El ledger además conserva íntegramente intentos, respuestas, ajustes y eventos.
Una copia técnica repite el payload congelado: no crea una evaluación académica
nueva, cambia máximo, publicación, ventana ni raíz. F09 permanece pendiente.

No se implementan gestores de biblioteca, publicación/edición institucional externa
ni capacidades pendientes de co-docencia. Un episodio demuestra correspondencia
temporal con la matrícula; no se presenta como prueba de asistencia a una sesión.
La institución nunca se obtiene de docente, grupo, username o membresía actual.

## Mapa y conciliación exclusivamente ficticios

`ResourceReconciliationService` carece de controladores, listeners y jobs.
`propose` toma tipo/PK y un mapa explícito; `approve` exige el checksum compuesto
exacto y evidencia cerrada `FIXTURE-*`; `apply` ejecuta el ensayo aprobado.
Actor procede de SessionPrincipal o null para mantenimiento interno sin sesión.
Los identificadores de evidencia son convenciones del fixture: **no confirman
F08 ni identifican responsables reales**.

Ejemplo ilustrativo, únicamente IDs ficticios:

```json
{
  "institutionId": 101,
  "authorAccountId": 102,
  "authorMembershipId": 103,
  "deliveries": [{
    "assignmentId": 104,
    "classId": 105,
    "recipients": [{"accountId": 106, "enrollmentId": 107, "evidence": "FIXTURE-ROSTER"}]
  }],
  "contexts": [{
    "attemptId": 108,
    "accountId": 106,
    "enrollmentId": 107,
    "sourceTimezone": "America/Lima",
    "evidence": "FIXTURE-START"
  }]
}
```

Los arrays pueden estar vacíos; una ausencia no crea un destinatario ni contexto.
Cada recipient necesita evidencia explícita; enrollmentId puede faltar. Los
contextos requieren episodio y assignment históricos demostrados. Los timestamps
legacy se conservan locales; America/Lima es una afirmación explícita del fixture,
comprobada para fijar su episodio. No es una conversión masiva de históricos.

El servicio y la función de aplicación rechazan bases fuera de
`chemicallab_t01_test`, servidor `127.0.0.1` y esquema propio `t03_<32 hex>`.
Estos límites evitan usar el mantenedor para backfill real; no son autorización
ni un sustituto de permisos operativos. Crear el esquema en sí no aplica datos.

Desde B3 v2, aplicar PROPOSED siempre exige aprobación antes de comprobar
frescura: tanto fuente intacta como modificada provocan rechazo sin mutaciones,
historia nueva ni SUCCESS de transición. APPROVED con deriva persiste STALE;
el trigger registra historia/cuota y el servicio exige SUCCESS T08 en la misma
transacción. Si falla auditoría, todo vuelve a APPROVED y el reintento puede
confirmar una sola transición. STALE es terminal y sus reintentos no duplican
historia, cuota ni auditoría. El servicio relee el estado bajo el lock original
y rechaza cualquier discrepancia con el retorno SQL antes de auditar.

Estados: PROPOSED → APPROVED → APPLIED/CONFLICT/STALE. APPLIED/CONFLICT puede
registrar STALE al detectar deriva; snapshots, mapas y firmas no se sobrescriben.
El checksum MD5 es un detector de diferencias del JSONB canónico, no una firma
criptográfica de aprobación. La aprobación liga fuente y mapa; los JSON son
privados al ensayo y no pasan a auditoría ni DTOs. T08 recibe tipo/ID numérico,
SUCCESS y correlación, sin nombres, respuestas, archivos o evidencia libre.

Se toma una fotografía coherente por sentencia; al aplicar se bloquean tablas
fuente y referencias del mapa antes de la fila del ledger, se relee el conjunto
y se compara el checksum. Los mapas incluyen snapshots de clases, membresías,
episodios y perfiles involucrados para detectar también cambios de referencias.
La repetición conserva la entrada; aplicación/consumo/SUCCESS son idempotentes.
Contradicciones de autor/institución/clase/episodio, varios perfiles por cuenta,
numeración no consecutiva/duplicada o dos activos conservan origen y evidencia
en conflicto. No se eliminan, fusionan ni renumeran filas para resolverlo.

El ensayo reserva consumo incluso si el mapa académico resulta conflictivo.
Una subtransacción retira asociación/revisiones/entregas/contextos inválidos,
incluyendo su cuota, mientras la raíz/consumo reservado y estado CONFLICT se
confirman con su SUCCESS T08. Un fallo de cuota, negocio transaccional o auditoría
revierte la operación entera. No se deja un APPLIED parcial ni SUCCESS huérfano.

## Presupuesto, T05 y writers legacy

Se incluyen todas las filas evaluation/student: terminales, activas, inactive y
sin assignmentId. used es el conteo completo por cuenta de los perfiles originales;
next es used+1 sólo si la secuencia 1..used y único activo son consistentes.
Las cuentas de destinatarios explícitos sin intentos reciben used=0, sin inferir
otros alumnos del grupo. Más de un perfil por cuenta impide adoptar el presupuesto.
El puntero activo conserva el attemptId original incluso si active=false: coincide
con la búsqueda legacy por IN_PROGRESS sin filtro active.

Los índices únicos parciales por presupuesto/número y presupuesto/IN_PROGRESS se
aplican sólo a consumos conciliados; los conflictos originales se conservan aparte
con verified=false. Toda entrada conserva el ID y snapshot del intento y tiene
vínculo único, incluso si falta contexto. Readmisión no crea un segundo recipient,
raíz o saldo. El contexto de un intento puede mencionar un episodio anterior al
recipient actual; se demuestra su ventana al inicio, nunca se mueve el resultado.

El cierre conservado usa **todas** las asignaciones originales: las futuras, vacías
y sin dueAt no se omiten. `t11b3_review_closed` reproduce cierre por vencimiento de
todas las activas, archivo/no publicación o desactivación de todas las asignaciones
existentes. Una evaluación publicada sin asignaciones no cierra por padrón vacío.
El instante local del ensayo queda registrado; un cierre observado es inmutable.
Duración sigue en la configuración original y startedAt en el intento original.
No hay gracia, restitución de intentos ni exposición de correcciones por entrega.

**Legacy evaluación/student y su lock siguen siendo la única autoridad de inicio,
finalización y exposición.** No se cambia startAttempt, REST, STOMP o T05.
El modelo preparado nunca admite intentos ni expone corrección, incluso con snapshot
vigente. La lectura histórica individual F03 conserva sus vínculos; su superficie
externa y autorización quedan para T12/T13 bajo T05, sin activar membresías.

`source_current` recalcula fuente y mapa; una respuesta con false no es una
conciliación vigente. Un inicio/finalización legacy, edición de respuestas,
preguntas/materiales, cambio de perfil o referencia invalida la observación.
El caché reservado **no se decrementa ni actualiza por writers legacy**: presentarlo
como saldo actual sería incorrecto. Nueva propuesta conserva otra fotografía;
si ya existe raíz, queda en conflicto y no reemplaza sus datos. Sincronizar/cortar
writers exige otra evolución explícita, cero diferencias y ventana autorizada;
no se implementa una segunda autoridad ni se afirma reconciliación continua.

## Cuotas y atomicidad

Evolución explícita de T07-B: nuevas categorías por cuenta autora
REVISION_BYTES=268435456 y REVISION_COUNT=1000 para el ensayo. No cambian máximos,
uso ni guards originales de materiales/pizarras. No se transfieren autores.
Se cobra cada copia del ledger, historia, revisión y filas auxiliares por el tamaño
del JSONB serializado; la representación hex de bytea se cobra completa, sin
descuento por compresión. Las actualizaciones permitidas del ledger cobran su
delta de metadatos. Una copia técnica también consume cuota.

Estas son cuotas adicionales explícitas de preparación, no una política definitiva
institucional aprobada ni medición de espacio físico PostgreSQL/índices/WAL/backups.
El crecimiento y límites operativos deben revisarse antes de operación real;
no se autorizan copias reales ni categorías sin cuota. La propiedad sigue en la
cuenta autora conforme T07-B; la distribución institucional final sigue pendiente.
Contadores, filas, historia y AuditLogWriter MANDATORY usan la misma transacción.

## Expansión, certificación y recuperación

Cadena explícita, con writers detenidos en una futura operación autorizada:
legacy T07-B/T08 y CHECK cerrado → B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2.
No ejecutar Hibernate update como reparación ni instalar B3 al arrancar.

```powershell
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b1-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b2-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b2-v2.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b3-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b3-v2.sql
# Sobre B2 v2 certificado: aplicar B3 v1 y después B3 v2.
# Sobre B3 v1 certificado: aplicar sólo B3 v2.
# Tras B3 v2: certificar con B1 v1 y B3 v2.
```

B3 valida catálogo B1 y predecesor B2 v2 antes de evolucionar los CHECKs T08.
Conserva los predicados anteriores y añade exclusivamente seis eventos B3.
Las firmas B1/B2 v1/B2 v2 y fuentes originales permanecen intactas; registra
predecesor y firma sucesora B2 separadas. **B1 v1 + B3 v2 certifican el esquema
actual**. B2 v1/v2 rechazan deliberadamente el CHECK sucesor; no deben usarse
para reparar ni sobrescribir firmas. B3 valida columnas/defaults, constraints,
validación/estado de índices, triggers, funciones, vistas, secuencias y categorías
de cuota; incluye las funciones storage de las que depende.

B3 v2 valida de nuevo el catálogo completo B1, el sucesor B2 registrado por B3
y el catálogo íntegro B3 v1 antes de sustituir exclusivamente `t11b3_apply`.
Conserva sin cambios el archivo B3 v1 y su fila `t11b3_schema_version`; registra
predecesor y fingerprint actual en una fila separada `t11b3_apply_version`
(version=2). Incluye esa tabla en la firma sucesora, además de todos los objetos
y cuotas previamente certificados. No cambia CHECKs T08, guardas ni límites.
La repetición de v2 verifica el sucesor y no vuelve a instalar ni reparar.
Después de v2, B3 v1 también rechaza deliberadamente el catálogo sucesor.
No usar certificadores de predecesores para reparar el esquema actual.

Una restauración de B3 v1 debe certificarse con B1 v1/B3 v1 antes de instalar
v2. Una restauración de B3 v2 se certifica con B1 v1/B3 v2; no necesita volver
a aplicar v1. La instalación fallida de v2 revierte función y fila de versión,
dejando B3 v1 certificable; el arnés prueba rollback, reintento y restauración.

Un fallo del DO o de transacción revierte su trabajo. Interrupción/commit desconocido
se investiga por ledger/correlación y se reanuda por ID; no recrear raíz ni consumo.
Ante deriva de catálogo, detener y comparar con fuente/backup; otra transición
versionada debe resolverla. Restaurar dump completo a DB separada, mismo nombre
de esquema, y revalidar B1 v1/B3 v2, IDs/checksums, contadores, historia y guards T08.
Restore carga datos antes de triggers; no deshabilitar guards sobre datos vivos.
Revertir aplicación conserva tablas inertes: DROP/CASCADE perdería historia.
Las cuentas runtime productivas, permisos reales y capacidad de locks no se certifican.
Los locks de tabla de apply están limitados a fixtures; no son protocolo productivo.

## Verificación y límites de la entrega

`ResourceReconciliationDbTest` usa PostgreSQL desechable y arnés propio. Incluye
A13–A17, recursos/clases de dos instituciones, FKs cruzadas, snapshots estables,
varios assignments y raíz compartida, activo/unassigned/inactive, readmisión,
numeración/perfiles/activos conflictivos, fuentes cambiantes, cuota/auditoría/rollback,
repetición, aprobación exacta y cadena desde CHECK anterior a B1 sin Hibernate
posterior. Prueba veinte tipos B1/B2/B3 y rechazo de UNKNOWN.

Ensayos entre dos JVM independientes aplican el mismo lote y compiten por la última
oportunidad con el writer legacy mientras existen las entregas preparadas.
El dump/restore nativo compara todas las tablas del esquema por firmas de datos,
incluyendo IDs, checksums y contadores; vuelve a certificar y prueba guards T08
y divergencia sin reparación. Resultados definitivos y SHA se añaden después
de la ejecución completa; las selecciones de desarrollo no se suman a esa suite.

No se modifican cuentas, credenciales, sesiones, pizarras, tombstones, logs históricos,
frontend institucional ni documentos previos. Los 27 archivos protegidos, incluidos
los 16 E2E, se comparan por hash con la evidencia anterior. Sólo la nota B3 es nueva.
Sin merge, despliegue, DDL/backfill reales, activación multiinstitucional ni registro
público. SEC-13 y VER-01 siguen abiertos. No se inicia T11-B4, T12 o T13.

## Inventario pendiente de T11-B

- De B1: PlatformRoleGrant independiente de ADMINISTRADOR, aliases/login global,
  códigos académicos/aliases/reservas y episodios de vigencia de membresía completos;
  no importar privilegios ni inferir identidades desde roles/membresías.
- De B2: funciones/facultades de CO_DOCENTE y edición general de atributos/ventanas,
  por evolución y decisiones pendientes; sus relaciones actuales preparan almacenamiento,
  no autorizan acceso. FKs/roles/vigencias deben combinarse en T12.
- Biblioteca: delegación ResourceManagerGrant, flujo de borrador/publicación de nuevas
  revisiones académicas, permisos y políticas funcionales pendientes. La copia técnica
  de este bloque no implementa esos flujos ni decide F09.
- Conciliación real: F08/responsables y mapas aprobados reales, inventario completo,
  lotes/reanudación productivos, resolución humana de conflictos y adaptación/corte
  de todos los writers. Este servicio sólo ensaya fixtures y deja snapshots desactualizados
  detectables; no importa ni recertifica automáticamente una fuente cambiante.
- Contexto de otros hechos: pizarras/participantes/ownership/capturas, uso/métricas y
  ámbito institucional de auditoría; procedencia histórica sin inferencia. No rellenar
  institución en logs históricos ni fabricar asistencia.
- Cuotas finales institucionales, retención/crecimiento, sincronización de presupuestos
  durante el corte y certificación operativa de runtime/permisos/backup/restauración.
- F01, F05–F09 y complementos pendientes de F02/F03/F04 siguen separados. REST/STOMP,
  principal/sesión/contexto, lectura F03, revisión DOCENTE + asignación y veto de
  autocalificación corresponden a T12/T13; sustituciones y traslados no están implementados.

La entrega termina en PR backend borrador hacia develop para revisión.


## Corrección del hallazgo de revisión de PR #69

Se reprodujo primero el defecto sobre las clases compiladas del SHA anterior
`fca56471250538cb1adc770baee90aa8cd664bbe`, correspondientes al código de HEAD
`483b517dc0a7bd3fb4279c5126bb8ca8cdbccb1f`: dos apply sin aprobación devolvían
STALE, la fila conservaba PROPOSED y aparecían dos SUCCESS ficticios.
Registro separado: `.codex-work/t11b3-correction/reproduced-old-head.log` y
`reproduced-old-head.json`; no cuenta como validación de la corrección.

Las regresiones nuevas verifican ambos PROPOSED por servicio y SQL directo,
dos rechazos sin cambios en tablas B3, cuotas e historial/auditoría; comprueban
APPROVED→STALE persistido con un historial y un SUCCESS; comparan todas esas
tablas antes/después de un fallo T08 y prueban reintento sin eventos repetidos.
Una función ficticia incompatible comprueba que el servicio rechaza retorno
distinto del estado persistido sin emitir SUCCESS. La cadena desde CHECK pre-B1,
validación de deriva, interrupción/reanudación, carreras JVM y dump/restore siguen
habilitados. La selección de desarrollo se registra separada de `clean package`.

SHA probado de la corrección: `e02c7acfe5dd796f93d33825492a2208169adc03`. `clean package` completo:
**56 suites / 675 pruebas / 0 fallos / 0 errores / 0 omitidas; JAR generado**.
Incluye 31 pruebas B3 (cuatro regresiones nuevas); la selección focalizada de 31
se mantiene aparte y no se suma. El commit posterior sólo incorpora esta evidencia
documental; no cambia código, SQL, pruebas ni POM respecto al SHA probado.

Entorno: Windows, JDK 17.0.12, PostgreSQL 18.4 desechable, Maven offline,
perfil test, `127.0.0.1:55433`. Comando: `mvnw.cmd -o
-Dspring.profiles.active=test` con propiedades del datasource ficticio y
`clean package`. Contraseña sintética no vacía para las pruebas heredadas que
arrancan con perfil prod; el servidor propio usa trust en loopback. Cada
ejecución definitiva comienza con la base ficticia recién creada.

SHA256 JAR: `4a4360143251db72c3a75fc6bb0daa515dc8269fbf8b9aed25b3ef847afcde67`.
SHA256 B3 v2 UTF-8/LF: `f9801eee77dec461da3ee463b7f4e147d1888cadc2ab30c11de0d7e997ee04a9`.
El JAR contiene exactamente los cinco SQL de la cadena; las fuentes B1/B2/B3 v1
son idénticas a las anteriores. Sus filas/fingerprints también se conservan;
el test rechaza deriva de triggers y de la función, sin reparación silenciosa.

Restauración nueva: firmas idénticas de **61 tablas** (incluida la versión v2),
recertificación B1 v1/B3 v2 y guards T08 UPDATE/DELETE/TRUNCATE comprobados.
Carreras nuevas: APPLIED/APPLIED con un consumo y un SUCCESS; STARTED:3/REJECTED
con tres intentos originales y un activo. T05 y la autoridad legacy permanecen.
Los 27 archivos protegidos, incluidos 16 E2E, conservan sus hashes anteriores.

Evidencia nueva separada en `.codex-work/t11b3-correction/`: `full-package.log`,
`verified-results.json`, `native-signatures.json`, `process-results.json` y
`preservation-after.json`. `focused.log` pertenece a desarrollo previo al commit;
`reproduced-old-head.*` confirma el fallo anterior y no certifica la corrección.
El primer intento completo omitió el perfil global test y usó contraseña ficticia
vacía: 675 pruebas, 17 errores de arranque heredados, sin JAR.
Se conserva como `full-package-missing-profile.log`
y `missing-profile-reports.zip`; no se suma ni se presenta como ejecución válida.
La repetición con perfil correcto aún conservaba la contraseña vacía: se detuvo
tras el rechazo de los dos escenarios prod de StartupPersistenceDbTest y quedó
como `partial-package-empty-fixture-password.log` / `empty-password-partial-reports.zip`.
Se repitió desde base ficticia nueva con perfil y contraseña sintética correctos
sobre el mismo SHA, sin modificar pruebas ni comprobaciones.
La base y el clúster ficticios se retiran tras verificar resultados; su limpieza
queda registrada en `cleanup.log`. No se certifican CI remoto, Linux, operación
productiva ni aislamiento institucional REST/STOMP. La evidencia suplementaria
A/B anterior conserva exclusivamente su SHA anterior; no se volvió a ejecutar.

## Evidencia anterior a la corrección (no atribuible al nuevo SHA)


SHA probado: `fca56471250538cb1adc770baee90aa8cd664bbe`. `clean package` completo: **56 suites, 671 pruebas,
0 fallos, 0 errores, 0 omitidas; JAR generado**. Incluye 27 pruebas nuevas B3;
no se acumulan las selecciones ni la ejecución parcial interrumpida de `73e7b0a`.
Los 644 resultados históricos de B2 conservan su atribución original; esta cifra
de 671 corresponde a la ejecución nueva del SHA indicado. El commit posterior
de evidencia modifica únicamente esta nota, sin cambiar código/SQL/pruebas/POM.

Entorno: Windows, JDK 17.0.12, PostgreSQL 18.4 desechable,
Maven offline, perfil test, loopback `127.0.0.1:55433`. Todas las cuentas,
instituciones, mapas, materiales y respuestas son ficticios.

SHA-256 JAR: `20b82d94ff80a46ad07c9658908741e1b3a86201e6e51e971ddc2ff3dd4a888b`.
SHA-256 SQL UTF-8/LF: `9fb63b81d399c1cf12d3f824cbacc6d8706f01680d792b4b43956de2728215f7`.
Se comprobó que el JAR contiene exactamente el SQL probado.

Restauración nativa: **60 tablas del esquema** conservan firmas
idénticas de datos, incluyendo IDs, snapshots/checksums, historia y contadores.
`pg_dump -Fc`/`pg_restore --exit-on-error` en DB separada; revalidación B1 v1/B3 v1,
UPDATE/DELETE/TRUNCATE de T08 rechazados y divergencia de trigger detectada sin
reparar ni sobrescribir firmas. La DB de restauración fue retirada por el arnés.

Dos procesos JVM por carrera, `ddl-auto=none` y sin instaladores T07-B/T08:
aplicación concurrente devuelve APPLIED/APPLIED, **un consumo y un SUCCESS**;
última oportunidad legacy devuelve **STARTED:3/REJECTED**, tres intentos originales
totales y un activo. La observación preparada detecta la escritura como
`source_current=false`, sin convertirse en una segunda autoridad.

Las 27/27 firmas de archivos protegidos, incluidos los 16 E2E, son idénticas.
Auditoría original: `1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Frontend conserva rama `feature/validaciones-datos-institucionales`, HEAD
`e88589635a8269c50ee0f53fccf157f0d0985034` y estado previo `?? e2e-selenium/`.

Evidencias locales fuera de la PR: `.codex-work/t11b3-evidence/full-package.log`,
`verified-results.json`, `native-signatures.json`, `process-results.json` y
`preservation-before.json`/`preservation-after.json`; los artefactos nativos y
logs de procesos están también en `target/t11b3-native`/`target/t11b3-processes`.
Las iteraciones fallidas/parcial permanecen separadas. No se certifican Linux,
capacidad/carga productiva, datos/mapas reales ni aislamiento institucional REST/STOMP.

Ensayo suplementario sobre las clases compiladas del mismo SHA, separado del
conteo de 671: recursos y entregas asociados efectivamente a A y B, con la misma
cuenta global y membresías explícitas en ambas instituciones. Raíces distintas,
consumo reservado A=1/B=0, referencias SQL cruzadas rechazadas y ningún tenant
inferido del perfil legacy. Evidencia: `two-institutions.json` y
`two-institutions.log`, con arnés local `VerifyTwoInstitutions.java`.

La base ficticia de la suite fue retirada y el cluster propio quedó detenido
después de comprobar su data_directory exacto; `cleanup.log` conserva el resultado.
El JAR, los dumps ficticios y las evidencias permanecen locales para revisión.
