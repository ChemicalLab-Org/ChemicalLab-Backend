# T11-B7 / P02 — Ciclo persistente de identificadores globales de acceso

13 de octubre de 2026, America/Lima. Alcance exclusivo: preparación persistente
P02 y mantenedor interno ficticio. Base esperada y remota comprobada por fetch:
`a29d07bcf7d279a169433cd04bd5c872fc437bdf`. Checkout aislado
`.codex-work/t11b7-backend`, rama `feature/t11b7-identificadores-acceso`.
No AGENTS.md aplicables en backend ni ancestros. Leídos T11-A, T11-C, B5/B6
y los contratos de identidad, sesiones, cuota y auditoría existentes.

## Decisiones humanas confirmadas

1. Docentes y alumnos podrán acceder por username, o por correo verificado
   vinculado a una sola cuenta. El correo es opcional.
2. Cambiar username conserva accountId; el anterior deja de estar activo y
   queda reservado para esa misma cuenta, también frente a un EMAIL ajeno.
3. Reutilizar correo requiere revocar el vínculo anterior, comprobar nuevamente
   su control y revisar los conflictos. Ninguna reutilización es automática.
4. Historia académica, sesiones y resultados permanecen en el accountId original;
   la evidencia del propietario anterior no verifica un vínculo nuevo.
5. Se conserva comparación exacta `LEGACY_EXACT_V1`, sin trim, case fold,
   normalización Unicode ni reglas nuevas de equivalencia en el registro.
   El login legacy conserva su trim/prioridad actuales hasta T12.
6. Estas reglas globales son independientes de F05, que sigue reservando el
   código académico por cuenta/colegio. No se implementa renombrado académico.

**Las reglas nuevas serán efectivas en el acceso al adoptarlas en T12.**
Actualmente AuthService, AuthenticationLimits, CustomUserDetailsService y
AccountSessionService siguen consultando legacy. No se conecta este registro
como autoridad del login ni se cambia REST/STOMP. T14 implementará verificación
externa, recuperación y flujos públicos. No se envían correos en B7.

## Persistencia y operaciones internas

Expansión aditiva única `src/main/resources/db/expansion/t11b7-v1.sql`.
JDBC/records; sin entidades JPA, instalación de arranque, bootstrap, jobs,
controladores ni listeners. Los candidatos, conflictos, bindings PREPARED y
ledgers B5 permanecen intactos. PREPARED nunca es ACTIVE.

| Tabla `t11b7_` | Integridad y evidencia |
| --- | --- |
| identifiers | Cuenta, USERNAME/EMAIL, valor/comparación exacta C, política, origen LEGACY/CHANGE/LINK, candidato B5 opcional comprobado, operación UUID, evidencia FIXTURE, actor, estado, versión y fechas DB. Identidad/procedencia inmutables |
| claims | Clave global única entre tipos, dueño derivado de los identificadores ACTIVE/RESERVED. SQL ordinario no puede escribirla. Username reservado mantiene su claim; revocar el último email libera su claim si no existe username activo/reservado del mismo valor |
| conflicts | Observación exacta del inventario ajeno al preparar cada candidato: legacy, B5 y B7 con IDs/tipos/estados/versiones. Append-only; no selecciona una cuenta |
| reviews | Aprobación explícita del mantenedor, ligada a identificador/versión y fingerprint del inventario actual, con snapshot y evidencia FIXTURE. No permite superar un dueño activo/reservado ni un origen legacy vigente ajeno. Revisión anterior queda conservada al cambiar el inventario |
| proofs | Prueba ligada a identifierId/accountId/operationId/versión, proveedor ficticio, digest SHA-256 de constancia, evidencia, emisión/vencimiento y estados ISSUED/VERIFIED/CONSUMED/REVOKED. No guarda código/token crudo |
| attestations | Constancia inmutable que debe coincidir con el digest emitido. Trigger verifica frescura y cambia la prueba a VERIFIED; un UPDATE directo o booleano libre no verifica |
| events | Snapshots de identificadores, pruebas y revisiones generados por triggers; incluye cambios de propietario como vínculos distintos, nunca transferencia de una fila. UPDATE/DELETE/TRUNCATE e inserción ordinaria rechazados |
| usage / schema_version | Contadores técnicos transaccionales y ledger B7 ligado al fingerprint B6. Ningún ledger histórico se reescribe |

`GlobalAccessIdentifierMaintenanceService` prepara, revisa, emite/atestigua/revoca
prueba ficticia, activa, revoca correo y cambia username. Operaciones bloquean
primero las tablas fuente/B5/B7 y releen versiones. La preparación es idempotente
por UUID y mapa exacto: mismo UUID con otro mapa se rechaza. Revisiones, emisión
de prueba y constancia repiten solo su procedencia idéntica; activación,
revocación y cambio repiten la transición/versiones exactas sin otra historia,
cuota o SUCCESS. No se presume éxito ante un commit desconocido sin investigar
IDs/versiones/procedencia/correlación.

Username: PREPARED → ACTIVE → RESERVED. Índice parcial impide dos principales
activos; constraint trigger diferido exige exactamente uno al commit desde la
primera activación. El sucesor registra ID/versión exactos del username reemplazado;
un reintento no puede atribuirse a otro renombrado posterior. Cambio reserva el anterior y activa el preparado en una
sola transacción; un fallo del segundo paso revierte el primero. No se cambia
`user_accounts.username`: hacerlo ahora conectaría la nueva política al login
legacy antes del corte T12. La cuenta puede preparar de nuevo un valor reservado
propio, conservando el episodio histórico; otra cuenta nunca adquiere ese valor.

Correo: PREPARED → ACTIVE → REVOKED, o PREPARED → REVOKED para retirar una
vinculación preparada explícita. Un vínculo revocado no se restaura: incluso el
mismo propietario prepara otra operación y necesita prueba nueva. La activación
consume exclusivamente una prueba VERIFIED, vigente según reloj DB, del mismo
identificador/propietario/operación/versión. Falta, digest inválido, expiración,
revocación o consumo anterior bloquean. Una constancia ficticia prueba el contrato
del modelo, no control real de un buzón ni autorización humana operativa.

El inventario sigue mostrando el correo legacy después de revocarlo. Solo una
revocación explícita B7 con origen LEGACY comprobado permite revisar ese vínculo
como retirado; no borra la columna ni evidencia anterior. Los candidatos B5 y los
PREPARED ajenos se conservan y requieren decisión explícita antes de activar,
sin constituir reserva perpetua de correo. Si legacy cambia después de preparar,
el origen LEGACY se considera stale y no puede activarse. La comparación nueva
es exacta; las advertencias REVIEW_ASCII de B5 se conservan como evidencia histórica
y no introducen nuevas equivalencias.

## Contexto, atomicidad y concurrencia

Actor de mutaciones de cuenta: SessionPrincipal autenticado de la **misma cuenta**.
Ningún rol institucional ni ADMINISTRADOR aporta por sí solo poder sobre otra
cuenta. Contexto presente inválido se rechaza. Sin contexto solo se admite el
mantenedor interno, protegido además por `t11b5_fixture()` en SQL: DB ficticia
`chemicallab_t01_test`, loopback 127.0.0.1, schema propio `t03_<32 hex>`.
Revisar/emitir/atestiguar prueba exige exclusivamente ese mantenedor sin contexto
público. No existe consumidor externo que pueda invocarlo. SQL valida identidad
del actor aportado y relaciones, pero no autentica una sesión: el rol DB de ensayo
es la frontera de confianza del mantenedor. Privilegios productivos son posteriores.

Mutaciones, claims, prueba consumida, snapshots, contador y SUCCESS T08 confirman
o revierten juntos con el transaction manager existente. ADMIN_ACTION usa targets
seguros GlobalAccessIdentifier, IdentifierVerificationProof o IdentifierConflictReview,
ID numérico y correlación; no valores, pruebas libres, digests, passwords ni códigos
crudos en system_logs. Solo se amplía AuditSafety, sin tocar enum/CHECK/guards T08.

Techo técnico B7: 100000 filas / 64 MiB lógicos. Incluye historial y constancias;
claims derivados liberados descuentan su fila/bytes, nunca la historia. No es
política institucional P04 ni dimensionamiento. Cuotas T07-B y B1–B6 intactas.

Locks de tabla de ensayo serializan el inventario y arbitran entre procesos,
incluyendo escritores legacy. Unique global y guardas SQL protegen también al
omitir Java. Escritores internos adquieren `t11b7_lock()` antes de tocar filas;
SQL arbitrario que invierta este orden puede causar deadlock y se revierte.
No se promete rendimiento ni ausencia universal de deadlocks. La carrera ensayada
exige un commit y un 23505 por dueño incompatible, nunca timeout/deadlock como éxito.
La carrera SQL verifica integridad; las pruebas del servicio verifican T08.

## Instalación, certificación, repetición y recuperación

Cadena: **B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2 → B4 v1 → B4 v2 → B5 v1 → B6 v1 → B7 v1**.
Se conservan los **nueve SQL históricos**, ledgers y fingerprints. B7 exige B6
instalado, incluye su certificador congelado sin cambios y certifica la cadena
antes de crear sus objetos. No añade triggers a tablas anteriores.

**B7 v1 es el certificador sucesor actual**; B6 puede seguir certificando su
catálogo, porque B7 no lo modifica. Certificadores B3/B4/B5 anteriores pueden
rechazar correctamente el catálogo B2 extendido por B6; no repetir toda la cadena
vieja sobre el sucesor. Fingerprint B7 cubre columnas/defaults, CHECK/FK/unique,
índices y estado, funciones/triggers y enablement, vistas y configuración de
secuencias. No firma datos, OIDs o consumo de secuencia ni impide alteraciones DBA.

Procedimiento futuro, solo tras autorización operativa: verificar destino,
search_path y fuente; detener escritores; probar backup/restauración; revisar
PostgreSQL/extensión/privilegios. pgpass para credenciales. Sin ejecución escolar
en esta entrega.

```powershell
# Esquema B6 previamente certificado; conexión explícita al destino autorizado.
psql --set ON_ERROR_STOP=1 --command 'select current_database(),current_schema();'
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b7-v1.sql
# Repetir B7 certifica cadena y catálogo propio sin escribir negocio o ledgers.
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b7-v1.sql
```

El DO es atómico, con advisory transaction lock común. Objetos sin ledger,
predecesor desconocido/divergente, versión desconocida o guard deshabilitado
se rechazan sin reparación. Usar transacción por archivo completo. No recalcular
fingerprints ni reparar con Hibernate update. El arnés heredado crea únicamente
el schema legacy nuevo/desocupado con Hibernate; la cadena explícita instala y
certifica las expansiones. Nunca se arranca Hibernate para reparar/restaurar.

Recuperar dump completo en otra DB aislada con pg_dump -Fc y
pg_restore --single-transaction --exit-on-error; certificar B7 y comparar firmas
de todas las tablas, IDs, credenciales, sesiones, reservas, pruebas, historia,
cuotas y ledgers antes de permitir escritores. Restauración instala triggers
después de cargar datos; no deshabilitar guardas sobre datos vivos. Fallo de una
operación exige comprobar rollback y corregir la causa; revertir binario conserva
tablas inertes. DROP/CASCADE no es recuperación. Restore estático no cierra
P06, checkpoints o recuperación de escrituras posteriores. Rol runtime futuro
sin ALTER/DROP/TRUNCATE ni escritura de ledgers/contadores fuera de guards.

## Verificación y cierre acotado

Pruebas `GlobalAccessIdentifierDbTest`: ausencia de correo, preparación sin acceso,
renombrado atómico/reserva, colisiones entre tipos, ambigüedad preservada,
revisión explícita y stale, reutilización con nueva prueba, evidencia de otro
propietario/operación, rechazo de pruebas inválidas/vencidas/revocadas/consumidas,
historia/sesiones conservadas, actor propio, reintentos, rollback auditoría/cuota,
SQL directo, expansión/repetición/divergencia, dos procesos y dump/restore.
La evidencia definitiva y el SHA probado se registran a continuación.

Se cierra únicamente el alcance implementado **P02 persistente** con esa evidencia.
P01 sigue preparado en B6; **P03–P06 y etapa 3 permanecen abiertos**. T12/T13/T14
conservan sus gates. SEC-13/VER-01, Linux/SCA/operación siguen pendientes.
Documentos históricos, auditoría original, frontend institucional y 16 E2E
conservados; solo se actualizan decisiones/matrices vigentes T11-A/T11-C.
PR borrador hacia develop, sin merge, despliegue, DDL/backfill real ni activación
multiinstitucional. No se inicia T12 ni P03–P06.

## Evidencia definitiva atribuida al commit

**SHA probado:** `edc8bc3a9adf8665f2881952f1215a3eb614d884`.
`clean package` terminó el **13 de octubre de 2026, 11:14:16 America/Lima**,
en **14 min 06 s**: **61 suites, 795 pruebas, 0 fallos, 0 errores, 0 omitidas;
BUILD SUCCESS y JAR generado**. Incluye los 30 casos P02 y las regresiones
T02/T03/T04/T05/T06/T07/T08/T10 y B1–B6 ejecutadas en esa misma suite.
Selecciones de desarrollo, carrera y restore no se suman nuevamente al total.
El commit de cierre posterior agrega exclusivamente documentación/evidencia;
código, SQL, pruebas y POM coinciden con este SHA.

Entorno: Windows, JDK 17, Maven 3.9.15 offline, PostgreSQL 18 desechable propio
en 127.0.0.1:55437, DB `chemicallab_t01_test`, rol ficticio `t11b7_fixture`,
perfil test, locale C y 300 conexiones. DB TimeZone America/Bogota; entrega en
America/Lima, sin reinterpretar timestamps legacy. No certificación Linux,
capacidad, SCA nueva, TLS/permisos, control real de correo ni operación escolar.

JAR SHA-256: `6f38a8e86b48d19a307da8905f44789bc5b3ff9bb8e26c9637ec6d3209ae37cc`.
Los **diez SQL** del JAR coinciden con sus fuentes, normalizando solo CRLF/LF.
SQL B7 UTF-8/LF SHA-256:
`9c76ee9c27b8f7517980fd36f76a87a7b63658b8d9c923e05812d501937f3ccb`.

| Verificación sobre el SHA probado | Resultado / evidencia |
| --- | --- |
| Cuenta sin correo y estados | USERNAME activo con correo null; EMAIL preparado sin prueba no activa; UPDATE libre de VERIFIED rechazado |
| Cambio de username | Una transacción reserva anterior/activa sucesor, un principal al commit; SQL rechaza dos activos o dejar cero. ID/versión del reemplazado persistidos. Reintento de otra pareja rechazado; dueño original puede preparar de nuevo su reserva |
| Clave global y conflictos | Colisiones USERNAME/EMAIL entre cuentas bloqueadas; misma cuenta con dos tipos iguales válida. Candidatos ambiguos no producen dueño automático. Conflictos B5/B7 originales conservados incluso después de resolución explícita |
| Reutilización de correo | Antes de revocar se rechaza nuevo dueño; después exige prueba nueva y revisión explícita. Evidencia del dueño/operación anteriores rechazada; link anterior permanece REVOKED con su prueba/historia originales |
| Evidencia y revisiones | Digest inválido, prueba ausente/ISSUED/vencida/revocada/de otro identificador o consumida no activa; constancia vinculada y consumo único. Cambio del inventario exige otra revisión y conserva la anterior; origen legacy stale no activa |
| Comparación | Case, whitespace y Unicode compuesto/descompuesto permanecen distintos; sin cambio de equivalencias ni login legacy |
| Cuenta, sesiones e historia | Firmas de cuentas, credenciales, sesiones, perfiles, matrículas, reservas académicas, intentos, evaluaciones y participantes intactas durante renombrado/reutilización. Login legacy continúa por el username original |
| Atomicidad/reintentos | Auditoría ausente temprana/tardía y cuota revierten estados, claims, pruebas/consumo, revisión, historia y SUCCESS. Reintentos exactos no duplican evidencia/cuota/logs. Revocación fallida conserva propietario |
| Actor/logs | SessionPrincipal propio capturado en estado/historia/SUCCESS; admin institucional ajeno y contexto público para emitir/revisar evidencia rechazados. SQL también rechaza actor de otra cuenta; logs solo con target/ID/correlación seguros |
| Cadena | Instalación con rollback, expansión sobre B6 poblado y repetición sin cambio de ledgers anteriores. Guard B7 o trigger sucesor B6 deshabilitados producen divergencia sin reparación |
| Carrera relevante | Dos JVM OS **33788/32780**, dos PID PostgreSQL **3740/31424** observados bloqueados. Activaciones EMAIL de propietarios distintos: **REJECTED:23505 / COMMITTED**, un claim/identificador activo y una prueba consumida; timeout/deadlock no aceptados |
| Restauración | pg_dump -Fc / pg_restore transaccional conservó firmas completas de **94 tablas**. B7 certificó toda la cadena; logs/historia inmutables y mantenimiento fuera de la DB ficticia bloqueados; deriva de guard detectada y recertificada al recuperarlo |

Carrera SQL y restore forman parte de las 30 pruebas B7. La carrera comprueba
integridad de persistencia; el servicio ensaya además atomicidad obligatoria T08.
Pruebas de desarrollo anteriores fueron selecciones sobre worktree sin commit:
se corrigieron declaración de excepción, aliases PL/pgSQL y expectativas de
rechazo/reintento. Sus logs son diagnóstico; no se atribuyen a este SHA ni se
suman. Solo una suite completa final, sin repetirla por el cierre documental.

Reproducción sobre un clúster **nuevo, ficticio, loopback y puerto libre**:

```powershell
initdb -D ../t11b7-repro-postgres -U t11b7_fixture -A trust --encoding=UTF8 --locale=C
pg_ctl -D ../t11b7-repro-postgres -l ../t11b7-repro-postgres.log -o '-h 127.0.0.1 -p 55437 -c max_connections=300' -w start
createdb -h 127.0.0.1 -p 55437 -U t11b7_fixture chemicallab_t01_test
# JDK17 en JAVA_HOME; PostgreSQL18 en PATH. Se usó Maven 3.9.15 cacheado por wrapper.
mvn -o '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55437/chemicallab_t01_test' '-Dspring.datasource.username=t11b7_fixture' '-Dspring.datasource.password=fictitious' clean package
# Selección durante desarrollo: mismos argumentos + '-Dtest=GlobalAccessIdentifierDbTest' test.
dropdb -h 127.0.0.1 -p 55437 -U t11b7_fixture chemicallab_t01_test
pg_ctl -D ../t11b7-repro-postgres -m fast -w stop
```

Trust exclusivamente en el clúster propio de ensayo. Evidencia local fuera de PR:
`.codex-work/t11b7-evidence/tested-sha.txt`, `full-package.log`,
`verified-results.json`, `verified-suites.json`, `verified-reports.zip`,
`verified-backend.jar`, `verified-packaged-sql.json`,
`verified/t11b7-processes` (barrera/PID/resultados), `verified/t11b7-native`
(dump y manifiesto de 94 tablas), `preservation.json` y scripts de colección/limpieza.

Conservación: **42/42 hashes protegidos** sin discrepancias, incluidos auditoría,
cierres anteriores y **16/16 archivos E2E**. Nueve SQL históricos byte a byte
intactos; de 60 documentos backend previos, 58 intactos y solo matrices T11-A/T11-C
actualizadas. Auditoría original SHA-256:
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Frontend permanece en `feature/validaciones-datos-institucionales`,
`e88589635a8269c50ee0f53fccf157f0d0985034`, con su `?? e2e-selenium/` previo.
Los E2E se preservaron; no se atribuye una ejecución frontend a esta entrega backend.
