# T11-B2 — Períodos, clases, matrículas y responsabilidad docente

12 de octubre de 2026, America/Lima. Rama `feature/t11b2-clases-matriculas`,
PR backend en borrador hacia develop. Base actualizada mediante fetch:
`ec0ef0b4b8b093687517dc07a279b02d74d0084c`, merge T11-B1.
Checkout aislado `.codex-work/t11b2-backend`; se conservan checkouts y trabajo ajeno.
No se encontraron AGENTS.md aplicables en ancestros ni backend (ocultos incluidos).
Se leyeron [T11-A](11a-modelo-institucional.md), [T11-B1](11b1-instituciones-membresias.md),
cierre local T11-B1, auditoría original y notas pertinentes T02/T03/T05/T08/T10.

## Decisiones confirmadas y límites

1. Una clase representa un curso y grupo dentro de un período académico: por ejemplo,
   Química de 3.º A en 2026. Su identidad es independiente del docente.
2. Un alumno puede matricularse simultáneamente en varias clases del mismo colegio,
   con matrículas independientes y una sola cuenta.

Estas confirmaciones resuelven solamente esos aspectos de F02/F07. Permanecen:
cuenta en varias instituciones con permisos separados; ADMIN_INSTITUCION gestiona/
supervisa, calificar exige DOCENTE y asignación, sin autocalificación; lectura
individual histórica tras salir bajo T05. No se implementa su autorización aquí.
Co-docencia, padrón/entregas, traslados con intentos, F01, F05–F09 y complementos
restantes siguen pendientes en lo no confirmado. No se inicia T11-B3, T12 ni T13.

## Esquema y expansión independiente

Fuente explícita `src/main/resources/db/expansion/t11b2-v1.sql`, revisión
`t11b2-v1`, ledger propio `t11b2_schema_version`. Records Java y JDBC;
sin @Entity, migrador de arranque, seeder, backfill ni dependencia de Hibernate update.
El archivo T11-B1 y su firma quedan intactos. No se evoluciona ningún objeto B1:
la expansión B2 verifica primero su versión y su catálogo usando la misma definición
de firma. B1 puede volver a validarse después de B2, sin divergencia artificial.

La nueva expansión sí incluye una transición explícita de los CHECK legacy de
system_logs.event_type cuando esa tabla existe en el esquema seleccionado: conserva
su predicado anterior y agrega mediante OR sólo los ocho eventos B2. No modifica
filas ni la guarda append-only, versión o presupuesto T08. Rechaza CHECK no validado
o que mezcle event_type con otra columna, para exigir conciliación explícita.
La firma B2 incluye estos CHECK evolucionados; Hibernate update no los sustituye.
Un esquema de preparación sin system_logs puede expandirse, pero no ejecutar negocio
auditado; añadir después CHECK de eventos produce divergencia y exige transición.

| Tabla | Clave y datos | Integridad |
| --- | --- | --- |
| academic_periods | ID, institución, código local, nombre, ventana UTC, estado, versión/actor/instantes | Unique institución/código; inicio finitos y estrictamente anterior al fin; no año natural ni período global único |
| academic_classes | ID estable, institución, período, código local, nombre/curso, grado/sección | Unique institución/período/código; FK período/institución; grado 1–5/sección A–Z iniciales conforme T11-A |
| teaching_assignments | ID, clase/membresía/institución, RESPONSABLE, ventana, activación/fin, estado/versión/actor | Dos FKs compuestas a clase y membresía; único responsable por intervalo efectivo mediante exclusión GiST |
| enrollments | ID, clase/membresía/institución, número de episodio, ventana/activación/fin, estado/versión/actor | FKs compuestas; unique clase/membresía/episodio; exclusión de intervalos efectivos por pareja |
| *_events | FK entidad/institución, versión, instante efectivo/registro, actor/motivo, snapshot JSONB completo | Unique entidad/versión; append-only generado por trigger; FK tipada a cada tabla, sin historia polimórfica huérfana |

Los cuatro tipos de evento tienen tablas separadas: academic_period_events,
academic_class_events, teaching_assignment_events y enrollment_events. Identidades,
padres, códigos, atributos descriptivos y ventanas originales quedan fijos en este
mantenedor mínimo. No hay borrado/cascada ni edición cosmética general. Evolucionar
esos datos requiere un mantenedor y transición explícitos posteriores; no recrear IDs.
Los códigos técnicos son etiquetas internas, nunca códigos de acceso/incorporación.

## Estados, intervalos y conservación

Período: PLANNED → OPEN → CLOSED → ARCHIVED.
Clase: PLANNED → ACTIVE → CLOSED → ARCHIVED.
No se reabren cerrados/archivados ni se borra su historia. Abrir período exige
institución ACTIVE e instante dentro de su ventana; activar clase exige institución
ACTIVE y período OPEN, ventana vigente y estado del período ya efectivo.

Asignación: PLANNED → ACTIVE → ENDED; PLANNED → CANCELLED.
Sólo función RESPONSABLE. CO_DOCENTE se rechaza en DB: no se habilita ni se definen
facultades pendientes. Una clase puede quedar sin responsable durante una baja,
sin reconstruirla ni conceder acceso académico.

Matrícula: PENDING → ACTIVE ↔ SUSPENDED; ACTIVE/SUSPENDED →
COMPLETED/WITHDRAWN/TRANSFERRED; PENDING → CANCELLED.
TRANSFERRED conserva solamente el motivo de terminación local del episodio;
no crea matrícula destino, traslada intentos ni implementa traslado T13.
Readmisión crea otro ID/episodeNumber de matrícula para la misma clase/membresía,
conservando los anteriores. La readmisión de la membresía sigue el contrato B1.

Todas las ventanas son finitas UTC `[valid_from, valid_until)`, contenidas en el
período. No se obliga a años naturales ni a períodos disjuntos. Activación exige
`valid_from <= state_since < valid_until` y fija `activated_at` una sola vez.
El intervalo efectivo/reservado de un episodio es
`[activated_at, min(valid_until, ended_at))`; fin ausente significa valid_until.
PLANNED/PENDING y CANCELLED no tienen intervalo efectivo. Los terminales conservan
su intervalo histórico; una nueva relación puede empezar exactamente en su fin,
pero no superponerlo retroactivamente.

SUSPENDED conserva el intervalo reservado del episodio, impidiendo otro episodio
incompatible por pareja; no representa permiso de participación durante la suspensión.
Reactivar conserva activated_at y vuelve a validar pertenencia/grant/estado/ventana.
Las matrículas en otras clases tienen exclusiones independientes.

Transiciones son estrictamente crecientes, salvo primera activación desde pendiente/
planificada que puede coincidir con su creación. Esto permite un reemplazo adyacente
en el mismo instante, sin introducir hueco artificial. Una baja exige instante
posterior a activated_at; puede registrar un cierre tardío tras vencimiento natural,
sin ampliar el intervalo efectivo. Estado ACTIVE puede permanecer almacenado después
del fin de ventana. Cerrar institución/período/clase o suspender membresía no reescribe
automáticamente relaciones ni eventos. **T12 deberá combinar todos los estados,
vigencias y tiempo del servidor para autorizar; ACTIVE almacenado jamás basta.**
Los instantes efectivos del mantenimiento son parámetros del llamador interno
confiable, separados de recorded_at/updated_at PostgreSQL; no son un reloj de acceso.

## Activación, concurrencia y auditoría

Activar o reactivar comprueba en PostgreSQL: institución ACTIVE; período OPEN y
clase ACTIVE efectivos; membresía de misma institución ACTIVE en su ventana y
state_since; grant institucional DOCENTE o ESTUDIANTE ACTIVE y vigente en ese instante.
Un rol global legacy, ADMIN_INSTITUCION o grant ausente/futuro/vencido/revocado
no sustituye el grant requerido. Bajas/reactivaciones posteriores no fabrican permisos.

AcademicMaintenanceService es infraestructura interna confiable, sin autorización
externa ni conexión con controlador/login/job. Usa el transaction manager existente:
negocio JDBC, versiones/eventos por triggers y AuditLogWriter T08 confirman o
revierten juntos. Reemplazar responsable termina el anterior, crea y activa el
siguiente atómicamente; cualquier error de relación o evidencia restaura el anterior.
Se conservan classId, cuentas y matrículas. No se prueba acceso del reemplazo.

Orden interno: membresías por ID ascendente → institución → período → clase →
relación. Primero se bloquean padres y se releen estados; no mutex de JVM.
La asignación de episodeNumber se hace bajo esos locks. B1 grant/revoke/terminación
bloquea la membresía, serializando su estado con la activación. La exclusión GiST
y unique son defensas finales también ante otras conexiones/SQL directo.
Los nuevos triggers de inserción siguen ese orden. UPDATE SQL directo adquiere
su fila antes del trigger: no se promete ausencia universal de deadlocks para
escritores arbitrarios que omitan el protocolo; PostgreSQL revierte al perdedor
y conserva integridad. Futuros mantenedores compuestos deben tomar previamente
todas las membresías ordenadas y los padres para evitar inversión de locks.
No se certifica capacidad productiva de estos locks de mantenimiento.

Nuevos eventos T08: ACADEMIC_PERIOD_CREATED/STATE_CHANGED,
ACADEMIC_CLASS_CREATED/STATE_CHANGED, TEACHING_ASSIGNMENT_CREATED/STATE_CHANGED,
ENROLLMENT_CREATED/STATE_CHANGED. Actor procede sólo de SessionPrincipal autenticado,
o null en llamada interna sin contexto. Motivo cerrado CREATED/estado destino;
snapshots conservan actor y cronología. Auditoría contiene objetivo/ID numérico,
institutionId permitido, SUCCESS y correlación, sin nombres/códigos ni texto libre.
Se extiende sólo vocabulario de targets/eventos, sin relajar filtro de secretos.
No hay cola, éxito posterior al commit ni historia recuperada por reconstrucción.

## Aplicación, repetición, divergencia y recuperación

El DO completo es atómico y comparte advisory transaction lock con B1. Exige B1
en el esquema seleccionado; objetos B2 sin ledger, versión desconocida o divergencia
B1/B2 se rechazan sin reparación. Firma de catálogo cubre columnas/defaults/identity,
constraints/validación, índices/estado, triggers/enablement, funciones y secuencias;
excluye datos/OIDs/consumo de secuencia. Ledger B2 también forma parte del catálogo.
No se actualizan firmas para esconder cambios; toda evolución necesita otra versión.
Firma no autentica criptográficamente fuente ni protege de un DBA que altera guardas/ledger.

Procedimiento futuro sólo tras autorización operativa específica: identificar DB/
schema, detener escritores, revisar checksum de fuente, backup/restauración y
permisos/extensión, comprobar search_path. Credenciales fuera de argumentos/logs:

```powershell
$env:PGOPTIONS='-c search_path=public'
psql --set ON_ERROR_STOP=1 --command "select current_database(),current_schema(),current_setting('search_path');"
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b1-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b2-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b2-v2.sql
# En instalaciones B2 v1 existentes: ejecutar sólo v2 después de verificar B1.
# Después de v2: repetir B1 v1 y B2 v2 para certificar el esquema actual.
# B2 v1 conserva la firma del predecesor: rechaza el catálogo evolucionado v2.
```

Un fallo de expansión revierte el DO; un fallo de evidencia revierte todo el negocio.
Después de recuperar auditoría se reintenta explícitamente; commit desconocido exige
investigar IDs/correlación antes de repetir altas. Ante divergencia, detener mantenimiento,
comparar fuente/backup y corregir mediante transición autorizada, sin sobrescribir firma.
Restaurar dump completo a DB separada con mismo schema, revalidar B1 v1/B2 v2 y hashes/IDs/
historia antes de habilitar escritores. Restore carga datos antes de crear triggers,
sin deshabilitarlos sobre datos vivos. Reversión de aplicación conserva tablas inertes;
DROP/CASCADE destruiría historia y no es procedimiento productivo de recuperación.
Rol runtime futuro sin ALTER/DROP/TRUNCATE ni escritura del ledger; permisos reales
siguen sin certificar.

## Compatibilidad y pendientes

Sin cambios en UserAccount, perfiles legacy, credenciales/sesiones, evaluación/intentos,
materiales/pizarras/cuotas, login, JWT, principal ni autoridades REST/STOMP.
No cuentas nuevas para matrículas, inferencia desde docente/grado/sección legacy,
backfill ni traslados de intentos. No endpoints, paneles, selector ni códigos de ingreso.
Historia individual y calificación conservan T05; implementación externa T12/T13.
Se preservan auditoría, cierres anteriores, frontend institucional y los 16 archivos E2E.

SEC-13 y VER-01 siguen abiertos. No merge, despliegue, DDL real, registro público
ni activación multiinstitucional. Este bloque no completa T11-B ni la etapa 3.
La entrega termina con PR borrador para revisión.

## Verificación histórica de la entrega inicial

Los resultados de esta sección corresponden exclusivamente al SHA inicial indicado.
La corrección de revisión tiene su propia evidencia y SHA al final de la nota.

**SHA de código probado:** `9d4c573d457d8f3efd6f03d51b503290514146bd`.
Entorno: Windows, JDK 17.0.12, PostgreSQL 18.4 desechable,
`127.0.0.1:55432/chemicallab_t01_test`, perfil test, 300 conexiones,
rol ficticio t11b2_fixture. No DB real, instalación global ni medición productiva.

`clean package` definitivo finalizó el 2026-10-12 a las 22:49:43 America/Lima:
**55 suites, 640 pruebas, cero fallos, errores u omisiones; JAR generado**.
Incluye las 29 pruebas académicas; no se suman nuevamente. El commit posterior
de cierre cambia exclusivamente esta nota; fuentes, SQL, tests y POM conservan
el contenido del SHA probado. SHA-256 del JAR conservado:
`061924edbceea5279ceb4db0ffdd17ef6d2151745cfde988f6d46642911c096d`.

| Ejecución | Alcance y resultado |
| --- | --- |
| Selección inicial en sandbox | Falló al construir el arnés por bloqueo de loopback: 1 error de inicialización, sin aceptación funcional |
| Selección fuera de sandbox antes de transición CHECK explícita | 28 casos, 0 fallos/errores/omisiones; iteración previa, sin atribuirle el código final |
| Selección actualizada previa al commit | 29 casos, 0 fallos/errores/omisiones; incluye transición audit enum sin Hibernate y round-trip de constraint |
| Suite completa / empaquetado sobre SHA probado | 640/0/0/0; 55 suites, JAR generado, regresiones T01–T10 y B1 incluidas |
| Ensayo nativo definitivo sobre SHA probado | Expansión/repetición sobre B1 poblada y legacy; B1 revalida; dos carreras con dos procesos psql; dump/restore y 19 firmas idénticas; divergencia/revalidación |

La suite incluye las selecciones; no se suman conteos entre ejecuciones. Se registran
las iteraciones y ensayos separados de JUnit. El primer ensayo nativo se detuvo
por esperar exit code 3 para psql -c: el SQLSTATE real ya era 23P01 con exit 1.
Se conservó y repitió íntegro en bases ficticias nuevas con expectativa correcta,
sin cambiar código ni esquema para ocultar ese resultado. El rechazo de expansión
por psql -f sí devolvió 3. La preparación inicial de initdb dentro del sandbox no
fue válida; el cluster definitivo se creó fuera de él y únicamente en loopback.

| Invariante | Evidencia |
| --- | --- |
| Ámbitos y FKs | Códigos iguales A/B y otro período permitidos; duplicados locales y padres ajenos por SQL rechazados |
| Cuenta compartida | Dos clases A y una B con la misma cuenta; terminar A1 conserva A2/B |
| Activación | Membresía pendiente/suspendida/terminada/ausente, grant ausente/futuro/vencido/revocado/equivocado y rol global sin grant rechazados |
| Estados/fechas | Intervalos vacíos/invertidos/infinitos/fuera del período y transiciones inválidas rechazadas; expiración natural no amplía acceso |
| Episodios | Suspensión/reanudación conserva activación; readmisión crea episodio siguiente; terminales históricos bloquean solapamiento; adyacencia permitida |
| Reemplazo | Nuevo responsable en el mismo instante del fin previo, con classId/cuentas/matrículas idénticos; CO_DOCENTE rechazado |
| Historia y atomicidad | Identidad/ventanas/episodios/eventos protegidos; rollback, sucesor inválido y fallo de evidencia tras terminar predecesor revierten versiones/historia/SUCCESS |
| Auditoría | Actor autenticado, objetivo/ID, institutionId y correlación seguros; transición de CHECK legacy explícita y repetible sin alterar filas anteriores |
| Concurrencia | Dos conexiones JDBC y dos procesos OS psql por carrera; un commit/un 23P01 y una fila efectiva |
| Compatibilidad | Mapas completos de cuentas/sesiones idénticos; /auth/me conserva ESTUDIANTE, rutas admin/docente 403 y tópico STOMP ajeno rechazado |
| Recuperación | pg_dump -Fc/pg_restore transaccional; B1 y B2 revalidan; 19 firmas de datos idénticas; trigger deshabilitado detectado sin reparación |

En las carreras nativas se retuvo la fila de clase desde una conexión control,
se observaron dos PID PostgreSQL bloqueados antes de liberar la barrera y se
exigió SQLSTATE 23P01, sin aceptar timeout/deadlock como éxito:
responsables PID OS 35916/36700; matrículas 33876/26468; resultados 0/1.
Los procesos son psql independientes, no dos tareas sobre una conexión compartida.
SQL directo verifica integridad, no sustituye la evidencia de servicio T08.
No se afirma ausencia universal de carreras ni capacidad productiva.

Bases definitivas nativas: chemicallab_t11b2_expansion_verified y
chemicallab_t11b2_restore_verified. La expansión dejó intactas las ocho firmas
B1/legacy iniciales; restore conservó diecinueve tablas incluyendo eventos,
ledgers B1/B2 y guarda/presupuesto T08. Datos, dumps y logs son exclusivamente
ficticios, conservados localmente en `.codex-work/t11b2-evidence`, fuera de la PR.
Checksum SHA-256 del SQL UTF-8/LF:
`85c9a06bc9fa7a974d5ea2534d3040c2e9bcbaa38134ff67d106a3eeeb7e6e4f`.
Un checkout CRLF puede tener otro checksum físico: contrastar normalización
y contenido Git antes de concluir divergencia de fuente.

Comando reproducible desde backend, con JDK 17 y PostgreSQL 18 en PATH:

```powershell
# Inicializar un directorio nuevo de ensayo y comprobar el puerto libre:
initdb -D ../t11b2-repro-postgres -U t11b2_fixture -A trust --encoding=UTF8 --locale=C
pg_ctl -D ../t11b2-repro-postgres -l ../t11b2-repro-postgres.log -o '-h 127.0.0.1 -p 55432 -c max_connections=300' -w start
createdb -h 127.0.0.1 -p 55432 -U t11b2_fixture chemicallab_t01_test
./mvnw.cmd '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55432/chemicallab_t01_test' '-Dspring.datasource.username=t11b2_fixture' '-Dspring.datasource.password=fictitious' clean package
# Selección parcial: añadir '-Dtest=AcademicPersistenceDbTest' y usar test.
# Tras terminar y retirar exclusivamente fixtures:
pg_ctl -D ../t11b2-repro-postgres -m fast -w stop
```

Trust sólo para cluster desechable loopback. El arnés exige DB loopback ficticia,
crea esquemas aleatorios y los elimina. Perfil test imprescindible para suites
legacy. No se modifican configuración, cuotas, guardas ni POM para hacerlas pasar.
Conservación comprobada: 27/27 archivos previos (11 documentos de seguridad y
16 E2E), cero discrepancias SHA-256; auditoría original conserva
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Frontend permanece en feature/validaciones-datos-institucionales,
`e88589635a8269c50ee0f53fccf157f0d0985034`, con su e2e-selenium previo.
Diff revisado: once archivos exclusivos del bloque, sin secretos/datos reales;
fuente B1, documentos previos y superficies legacy protegidas sin cambios.

Limpieza final: cinco bases ficticias retiradas (incluido el ensayo incompleto),
cero bases chemicallab% en el cluster propio y servidor detenido. Dump y evidencia
se conservan fuera de la PR. La recuperación confirmó también audit_append_only
habilitado, versión T08=1 y fila de presupuesto intacta. Los mensajes deliberados
de SECURITY_AUDIT_EVIDENCE_UNAVAILABLE no son fallos JUnit; XML y resumen Maven
confirman los resultados anteriores. No se ejecutó suite Linux, nueva SCA ni
medición sostenida/operativa; SEC-13 y VER-01 continúan abiertos.

## Corrección del hallazgo de revisión T11-B2 / PR #68

El HEAD revisado `e789aac6371629d085ef722823e3217f0d503309` expandía
el CHECK de auditoría sólo con ocho eventos B2. Sobre un CHECK anterior a B1,
las seis operaciones B1 seguían rechazadas y su evidencia obligatoria T08
provocaba rollback. La reproducción original y sus logs se conservan en
`.codex-work/t11b2-review`; la reproducción de esta corrección se conserva
en `.codex-work/t11b2-correction/reproduced-before.log`: seis rechazos B1,
ENROLLMENT_CREATED aceptado y fila LOGIN_SUCCESS intacta.

`t11b2-v2.sql` es una evolución explícita, atómica y acumulativa. Conserva
sin cambios las fuentes B1 v1 y B2 v1 y sus filas de versión/fingerprint.
Antes de evolucionar valida ambos catálogos firmados; requiere system_logs
y al menos un CHECK cerrado de event_type, validado y limitado a esa columna.
No instala auditoría ni admite un esquema staged sin CHECK; prepararlo exige
reconciliación explícita previa. Añade por OR al predicado anterior exactamente:

- INSTITUTION_CREATED, INSTITUTION_STATE_CHANGED, MEMBERSHIP_CREATED,
  MEMBERSHIP_STATE_CHANGED, MEMBERSHIP_ROLE_GRANTED, MEMBERSHIP_ROLE_REVOKED.
- ACADEMIC_PERIOD_CREATED, ACADEMIC_PERIOD_STATE_CHANGED, ACADEMIC_CLASS_CREATED,
  ACADEMIC_CLASS_STATE_CHANGED, TEACHING_ASSIGNMENT_CREATED,
  TEACHING_ASSIGNMENT_STATE_CHANGED, ENROLLMENT_CREATED, ENROLLMENT_STATE_CHANGED.

El vocabulario legacy permanece admitido por su propio predicado y los eventos
desconocidos continúan rechazados. No modifica filas de logs, guardas, presupuesto
ni versión T08. La nueva tabla t11b2_audit_version registra versión 2,
revision t11b2-v2, predecessor_fingerprint idéntico al B2 v1 y la nueva firma
del catálogo (incluidos CHECK y estructura del nuevo ledger). No sobrescribe
ninguna firma. Repetir v2 comprueba el predecesor y el catálogo actual; detecta
alteraciones de CHECK, triggers, B1, estructura y firma del predecesor sin reparar.
Repetir B2 v1 después de v2 rechaza deliberadamente el catálogo sucesor:
para validación y restore actuales se usan B1 v1 y B2 v2.

AcademicPersistenceDbTest instala un CHECK pre-B1 después del arranque inicial
de Hibernate y aplica B1 v1/B2 v1. Comprueba que una creación institucional
auditada falla y revierte antes de v2, y sólo entonces aplica la transición.
No reinicia la aplicación ni permite un update posterior de Hibernate.
Las regresiones académicas operan sobre el CHECK preparado y verifican evidencia
real de los catorce tipos, operaciones de ambos servicios, CHECK idéntico después
del negocio y rollback de B1/B2 ante indisponibilidad de auditoría, con historia
y SUCCESS intactos. Los ensayos nativos verifican datos legacy/B1 poblados,
repetición, rechazo desconocido, guardas append-only, dos carreras independientes
y pg_dump/pg_restore con revalidación v2 y detección de divergencia.

La primera selección de esta corrección tuvo 33 pruebas, 0 fallos y 1 error:
un nuevo fixture SQL usaba nombres de columnas incorrectos. Se conservó el log,
se corrigió el fixture y se repitió; ese intento no se presenta como aceptación.
Los resultados definitivos se registran a continuación tras las verificaciones.

### Resultados definitivos de la corrección

**SHA probado:** `827bb8c9554718efb799ac6edd4811a93cad73f1`. Se repitió `clean package` completo:
**55 suites, 644 pruebas, 0 fallos, 0 errores, 0 omitidas; JAR generado**.
Incluye las 33 pruebas académicas (29 previas y cuatro nuevas); las selecciones
no se suman al total. Las 640 pruebas anteriores se conservan como evidencia
histórica de su propio SHA, no se atribuyen a éste sin ejecución.
Entorno: Windows, JDK 17.0.12, PostgreSQL 18.4 desechable, perfil test,
loopback 127.0.0.1:55432 y datos exclusivamente ficticios.

SHA-256 JAR: `6ba16f3b8e61d7151124b18795860cabdb63383d259bc9fcb48c492fafa68fcf`.
Se comprobó que incluye el SQL v2 idéntico al probado.
SHA-256 de t11b2-v2.sql UTF-8/LF: `997eded3ed9488a14c1cc8c1a3e643b8b1a3e5687b7b02d35c9cbb8685ab3358`.
El posterior commit de evidencia modifica sólo esta nota; la PR y la evidencia
local identifican su HEAD final por separado del SHA probado.

Ensayo nativo sobre el mismo SHA: catorce eventos aceptados y UNKNOWN rechazado,
datos legacy/B1 y fingerprint original intactos, repetición v2, divergencia del
predecesor rechazada antes de evolucionar y divergencia del sucesor rechazada
después de restore. pg_dump -Fc/pg_restore conserva veinte firmas de datos,
incluidos los dos ledgers B2 y presupuesto/versión T08. En restauración se
comprobaron audit_append_only habilitado y UPDATE/DELETE/TRUNCATE rechazados
con P7508, sin cambios en presupuesto o versión. Dos procesos psql por carrera
observados bloqueados: responsables 31452/36800 y matrículas 34504/34340,
un commit y un 23P01 en cada caso. No se suman estos ensayos a JUnit.

La base de reproducción anterior recibió v2 y admitió los catorce eventos,
preservando ambas filas iniciales y el ledger B2 v1 completo. Los logs, dump,
firmas y scripts se conservan en `.codex-work/t11b2-correction`, separados de
los documentos y evidencia anteriores. Se verificaron 32/32 hashes protegidos
sin discrepancias, incluidos los 16 E2E y artefactos previos de revisión;
frontend conserva su branch, HEAD y e2e-selenium anteriores. B1 v1, B2 v1 y
T08 originales tienen diff vacío frente al HEAD revisado.

No merge, despliegue, DDL real ni avance a T11-B3/T12/T13. PR #68 permanece
en borrador y el trabajo se detiene para revisión.
