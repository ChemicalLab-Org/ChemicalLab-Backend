# T11-B5 — Identidad global, roles de plataforma y códigos institucionales

13 de octubre de 2026, America/Lima. Base `develop` actualizada por fetch:
`4e0454562e0046fd90e9c981439961afacfef5f6`. Checkout aislado
`.codex-work/t11b5-backend`, rama `feature/t11b5-identidad-codigos`.
No AGENTS.md aplicables en repositorio ni ancestros, incluidos ocultos.
Leídos T11-A, notas B1–B4 y sus cierres locales en `Documentos/Seguridad`,
T02, T04 y T08. Fuentes y documentos anteriores se conservan, excepto la
actualización solicitada de F05 y matriz en T11-A. Frontend institucional,
auditoría original y 16 archivos E2E quedan fuera del cambio.

**F05 confirmada expresamente por el usuario:** el código académico queda
reservado para la misma cuenta dentro del colegio, incluso tras retirarse;
no se asigna a otra persona. Puede coincidir en otro colegio y es independiente
del usuario global de acceso. La readmisión conserva identidad y reserva.
La confirmación no resuelve renombrados académicos, reutilización de correos
o aliases, nueva normalización, F01/F06/F08/F09 ni los pendientes complementarios.

Esta preparación no cierra T11-B ni etapa 3. Autorización efectiva y principal
institucional corresponden a T12; continuidad y flujos de incorporación a
T13/T14 y tareas posteriores. Sin endpoints, paneles, registro, selector,
activación multiinstitucional, merge, despliegue ni DDL/backfill reales.

## Inventario del acceso vigente antes de diseñar el registro

| Productor/resolución | Contrato conservado |
| --- | --- |
| `UserAccount`, `UserAccountRepository` | Username único y email único por separado; igualdad exacta, sin IgnoreCase. El esquema legacy no impide username de A = email de B. `findByUsernameOrEmail` existe pero no tiene consumidores actuales |
| `AuthService.login` | Java `String.trim()` sobre entrada; busca username exacto. Si contiene `@`, consulta email exacto y conserva prioridad de username. No exige correo verificado. Tras lock T02 relee cuenta, hash/estado e identidad y emite sesión |
| `AuthenticationLimits.login` T04 | Mismo trim, comparación y prioridad para presupuesto por identificador/cuenta; no busca códigos académicos nuevos |
| `CustomUserDetailsService` | Username exacto; autoridades desde rol legacy de cuenta |
| `AccountSessionService` T02 | Cuenta/sesión/versiones persistidas; autoridades actuales legacy. Ningún registro B5 sustituye estas consultas |
| Aprovisionamiento/edición legacy | `UserManagementService` y `AdminService` aún generan/modifican studentCode y username relacionados; unique global de student_profiles se conserva. No incorporar estos writers al registro nuevo sin corte posterior explícito |

El inventario SQL `t11b5_identifier_inventory` reúne todos los username/email
legacy y los candidatos preparados, conservando cuenta, tipo, valor y origen.
Expone colisión exacta entre cuentas, coincidencia entre tipos, whitespace y
una **vista previa**, denominada `REVIEW_ASCII`, de trim ASCII + case fold ASCII.
La comparación del registro es `LEGACY_EXACT_V1`, con collation C y valor raw
intacto. La vista previa bloquea conservadoramente la conciliación; no cambia
la comparación del login ni el valor registrado. No se afirma normalización
Unicode/NFKC/IDNA, política universal de correo o inventario de una base real.
El contrato legacy incluye confirmación Java exacta de la identidad; futuras
collations/comparaciones requieren revisión de colisiones y decisión explícita.
La igualdad de consultas SQL depende de la collation de las columnas legacy;
no se certifica la de una base real. El ensayo usa locale C y la comprobación
Java final sigue siendo exacta. Se conserva ese contrato, sin imponer C a legacy.

## Persistencia y procedencia

JDBC y records, sin nuevas entidades JPA ni instalador de arranque. Fuente única
ejecutable: `src/main/resources/db/expansion/t11b5-v1.sql`.

| Tabla/vista con prefijo `t11b5_` | Preparación e integridad |
| --- | --- |
| admin_proofs | Cuenta + evidencia explícita FIXTURE-* + checksum de la fila legacy observada + actor/instante. Guarda SQL comprueba rol ADMINISTRADOR y checksum actual bajo lock de cuenta; prueba y procedencia inmutables |
| platform_role_grants | `PlatformRoleGrant`: cuenta, solo ADMINISTRADOR, prueba comprobada, otorgante, ventana UTC `[desde,hasta)`, ACTIVE/REVOKED, revocante/instante/motivo. Exclusión GiST conserva incluso intervalo efectivo revocado; no restauración, transferencia, borrado o truncado |
| login_identifiers | `AccountLoginIdentifier`: cuenta/tipo/valor raw/comparación/evidencia/actor. Mapas explícitos deben coincidir con columna legacy actual. Verificación siempre false, también EMAIL; no nuevas credenciales ni inferencia de persona |
| identifier_conflicts | Copia inmutable generada de contrapartes de otras cuentas, tipo/valor y motivo EXACT/REVIEW_ASCII al preparar candidato. Conserva el conflicto aunque derive el origen; exige revisión posterior separada |
| login_bindings | Clave de comparación global única entre tipos y dueño demostrado; solo PREPARED. Insert requiere candidato fresco sin conflicto persistente ni actual. Misma cuenta puede tener username/email idénticos; otra cuenta nunca se elige por orden |
| identifier_observations / binding_observations | PREPARED/CONFLICT/STALE y frescura calculadas desde candidato, conflictos y origen; authentication_enabled siempre false. Alias histórico conservado si cambia legacy; no permiso efectivo por binding almacenado |
| academic_codes | `AcademicCodeReservation`: institución/membresía/cuenta/código/evidencia/actor. Unique colegio+código permanente y una reserva por membresía/cuenta/colegio. FK compuesta y guarda de cuenta de la membresía; UPDATE/DELETE/TRUNCATE bloqueados |
| identity_events | Snapshot de cada INSERT y revocación UPDATE en las tablas de identidad, generado por triggers; insert manual, update, delete y truncate rechazados |
| membership_timeline | Consulta de los snapshots B1 existentes, ordenados por access_version, con estado previo, índice de episodio y transición. No nueva copia ni invento de intervalos |
| usage / schema_version | Contadores transaccionales de filas/bytes y ledger de versión/revisión/fingerprint/predecesor; sin sobrescribir ledgers B1–B4 |

La importación de privilegios no parte de autores, recursos, pertenencias ni
actividad: exige que **esa misma cuenta ya tenga ADMINISTRADOR legacy**, mapa
explícito con checksum comprobado y evidencia de fixture. No hay método genérico
que convierta ADMIN_INSTITUCION en privilegio global. El checksum MD5 detecta
deriva del JSONB de la fila (no se copia hash/password al historial B5); no firma
autorización humana ni acredita quién aprobaría un mapa real. FIXTURE-* tampoco
confirma F08. Concesiones nuevas reales y autorización de revocación son posteriores.
Las fechas de preparación se suministran explícitamente, con precisión de
microsegundos UTC y comienzo igual o posterior a observed_at de la prueba;
el rol presente no demuestra una vigencia histórica anterior. La guarda fija
observed_at/recorded_at con reloj DB, sin aceptar fechas observadas inventadas.

Los códigos se comparan exactamente, sin cambiar mayúsculas, padding o studentCode
legacy. No hay servicio de renombrado, sustitución ni liberación, tampoco para
la misma cuenta. PENDING/ACTIVE/SUSPENDED/ENDED no libera la reserva. Asociar
historia no exige reconstruir permiso pasado desde un grant actual. El mapa
explicita colegio, membershipId, accountId y código; DB comprueba los vínculos.
No se toma un prefijo EST, el nombre o correo como prueba de persona.

Los registros inmutables de aliases/candidatos y sus conflictos **no establecen
una regla F05 para correo**. No hay flujo de liberar/reasignar/revocar/activar
aliases de acceso en este bloque; resolver conflictos, verificación de correo,
su ciclo ACTIVE/REVOKED y eventuales reglas de reutilización siguen pendientes.
Un binding PREPARED nunca certifica email ni constituye autoridad de autenticación.

## Historia temporal y contexto original

B1 ya guarda PENDING, activación, suspensión, reactivación, fin, readmisión y
ROLE_CHANGED como snapshots append-only con state_since efectivo y recorded_at.
Readmisión reutiliza membresía y cambia ventana actual; eventos anteriores
conservan ventana cerrada. Suspensión/reactivación no abre episodio de admisión.
La vista B5 cuenta episodios solo en PENDING→ACTIVE y ENDED→ACTIVE; el estado
PENDING queda con índice cero. ROLE_CHANGED no cambia episodio. No se rellenan
gaps, no se presume actividad entre snapshots ni se reconstruye permiso pasado.
No modifica enrollmentId, recursos, destinatarios, intentos, participantes,
autorías, presupuestos T05 ni referencias de B2/B3/B4.

## Atomicidad, carreras y auditoría

`IdentityPreparationService` es interno, sin controlador/listener/job/login hook.
Todas sus mutaciones y las guardas SQL exigen DB `chemicallab_t01_test`, loopback
127.0.0.1 y schema propio `t03_<32 hex>`. Es una barrera de ensayo, no autorización
ni validación productiva. Restauración/certificación pueden ejecutarse en otra DB,
pero allí no puede conciliarse información. Los reintentos comparan mapa/evidencia
y fechas explícitas; una diferencia no se ignora. Repetir no duplica reserva,
prueba/grant, binding, historial ni SUCCESS. Grant revocado no vuelve a ACTIVE.

El transaction manager existente une JDBC, guards/historia, contadores y evidencia
obligatoria T08. Preparación usa ADMIN_ACTION/SUCCESS con objetivo numérico tipado
PlatformRoleGrant, AccountLoginIdentifier, AccountLoginBinding o AcademicCodeReservation.
La secuencia INSERT/UPDATE y sus campos se conservan en identity_events; reserva
añade institutionId permitido a metadata. Actor solo de SessionPrincipal autenticado
o null en mantenimiento interno. No códigos, aliases, checksum, evidencia libre,
passwords o tokens en system_logs. Se amplía únicamente la allowlist de targets,
sin editar el enum, CHECK, guardas ni filas de auditoría preexistentes.

Fallos de auditoría, quota, relación o transacción revierten toda la preparación,
incluidos cambios compuestos de B1/B2 en esa transacción. Las tablas B5 cobran
inserciones, snapshots de historia/conflictos y delta lógico de bytes de revocación,
con techo técnico fijo de ensayo 100000 filas / 64 MiB; no es dimensionamiento
ni política de cuotas escolares. T07-B, límites y consumo legacy se conservan.
SQL directo de pruebas comprueba integridad, no constituye flujo auditado de negocio.

Reservas bloquean membresía y unique colegio+código arbitra entre cuentas diferentes
sin mutex de JVM. Identificadores bloquean tablas fuente/candidatos antes de leer
inventario y conciliar, incluidos guards de bindings directos. Grants bloquean cuenta;
exclusión DB impide ventanas solapadas. Un escritor SQL arbitrario puede producir
deadlock si invierte locks; DB revierte, no se promete ausencia universal de deadlocks.
La carrera de reserva exige un commit y un rechazo 23505, nunca timeout/deadlock.

## Instalación, certificación, repetición y recuperación

Cadena explícita: **B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2 → B4 v1 → B4 v2 → B5 v1**.
B5 requiere sucesores B3/B4 instalados y lleva copia congelada del certificador
B4 v2, que valida también B1/B2/B3 antes de crear o certificar B5. Sus ramas
de instalación previas no pueden ejecutarse con los predecesores requeridos.
No modifica fuentes, tablas, CHECKs o fingerprints anteriores. Se pueden repetir
**B1 v1, B3 v2, B4 v2 y B5 v1** para certificar; B2/B3/B4 v1 aislados no son
certificadores del catálogo sucesor y pueden rechazarlo deliberadamente.

Antes de una instalación futura autorizada: comprobar DB/schema/search_path,
fuentes/checksums, PostgreSQL/extensión btree_gist, detener writers, probar backup
y restore, revisar permisos. Credenciales mediante pgpass, fuera de argumentos/logs.

```powershell
# Conexión explícita al entorno autorizado, jamás defaults de una base escolar.
$env:PGOPTIONS='-c search_path=public'
psql --set ON_ERROR_STOP=1 --command "select current_database(),current_schema(),current_setting('search_path');"
# Aplicar cadena precedente, si corresponde, con transacción por archivo.
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b5-v1.sql
psql --set ON_ERROR_STOP=1 --command 'select * from t11b5_schema_version;'
# Repetición del mismo archivo: valida catálogos; no instala datos ni reescribe ledger.
```

La firma cubre estructura/constraints/índices y su estado, funciones/triggers y
enablement, vistas y configuración de secuencias; no datos, OIDs o consumo de
secuencias. Objetos sin ledger, versión desconocida y divergencia se rechazan.
No recalcular fingerprint ni usar Hibernate update como reparación. El DO B5
es atómico; usar además single-transaction para el archivo completo.

Si falla evidencia, corregir el destino y reintentar explícitamente. Commit
desconocido requiere comprobar IDs/evidencia/correlación antes de repetir.
Restaurar dump completo en DB separada, misma identidad de schema, con
`pg_dump -Fc` y `pg_restore --single-transaction --exit-on-error`; certificar
cadena y comparar firmas de todas las tablas, cuentas/sesiones/IDs/history/quota.
No borrar tablas ni deshabilitar guards de datos vivos. Rollback de aplicación
conserva preparación inerte. Rol runtime futuro sin ALTER/DROP/TRUNCATE ni
escritura de ledgers, pruebas y contadores fuera de guards; DBA capaz de alterar
catálogo no queda cubierto. Privilegios, TLS, capacidad/reloj y operación pendientes.

## Verificación y atribución

**SHA probado:** `f3f633766ece835e0bd37cc018505f7dc89e3e50`.
`clean package` terminó el 13 de octubre de 2026 a las **06:47:54 America/Lima**,
en 12 min 31 s: **59 suites, 739 pruebas, 0 fallos, 0 errores, 0 omitidas;
BUILD SUCCESS y JAR generado**. Incluye los 25 casos B5 y las 714 regresiones
preexistentes recién ejecutadas, incluidas T02/T04/T08 y B1–B4. Las selecciones
no se suman a ese total. El commit posterior añade exclusivamente evidencia
a esta nota; código, SQL, pruebas y POM coinciden con el SHA probado.

Entorno de ejecución: Windows, JDK 17.0.12, Maven 3.9.15 offline, PostgreSQL 18.4
propio desechable en 127.0.0.1:55435, DB `chemicallab_t01_test`, rol ficticio
`t11b5_fixture`, perfil test, 300 conexiones, locale C, DB TimeZone America/Bogota.
El tiempo de entrega se expresa en America/Lima; no se reinterpretan timestamps
legacy ni se certifica restauración entre zonas distintas.

JAR SHA-256: `94ebc80918e39545d9f7a8a9cb7a98f17ef2ba9efc1307e3ee3ff9c3a17308fa`.
Los **ocho SQL de expansión** dentro del JAR coinciden con las fuentes probadas,
normalizando solo CRLF/LF. SQL B5 UTF-8/LF SHA-256:
`44eaf40b695846f295c5d643e0beafb53095386d66d1d8ea9b992ff5ec51e2df`.

| Verificación nueva sobre el SHA probado | Resultado |
| --- | --- |
| DEMO-A/DEMO-B y F05 | EST0001 para cuentas diferentes en dos colegios; cuenta común con códigos independientes; reasignación rechazada por servicio y SQL también tras ENDED; readmisión conserva ID/reserva |
| Conservación | Firmas completas de cuentas/sesiones/perfiles/participantes/intentos y cuota legacy iguales al registrar códigos; referencias originales no se mueven |
| Privilegio global | ADMIN_INSTITUCION y autor docente no pueden preparar ADMINISTRADOR; SQL rechaza rol escolar/proof ausente/derivado. Checksum/evidencia explícitos, fuente actual y vigencia no retroactiva; solapamiento rechazado, revocación inmutable y grants adyacentes |
| Identificadores | Colisión username/email y vista previa ASCII entre cuentas conservadas, sin fusión/renombre ni email verificado; SQL y servicio bloquean binding. Misma cuenta con dos tipos iguales válida. Alias/candidato desactualizado se conserva y bloquea; prioridad T02/T04 legacy intacta |
| Historia/atomicidad | Episodios, ROLE_CHANGED, suspensión/reactivación y readmisión sobre eventos B1 originales. Fallo de evidencia temprano/tardío revierte privilegio/prueba, alias, reserva, pertenencia, historia, cuotas y SUCCESS; reintentos idempotentes |
| Carrera concreta de reserva | Dos JVM OS 30656/31176 y dos PID PostgreSQL 21140/21276 observados esperando locks; resultados COMMITTED / REJECTED:23505. Una reserva y dueño permanente; SQL directo, no flujo auditado de negocio |
| Expansión/certificación | Instalación rollback/repetición, ledgers previos intactos; deriva de trigger rechazada sin reparación; CHECK y vistas reconstituidos por restore conservan catálogo |
| Restauración | pg_dump/pg_restore transaccional preservó firmas completas de **78 tablas del schema de ensayo**, incluidos IDs, credenciales/sesiones, relaciones, historia, ledgers y cuotas. B5 valida cadena; UPDATE/DELETE/TRUNCATE de logs siguen rechazados; conciliación fuera de DB fixture bloqueada |

Comando definitivo desde el checkout, con JAVA_HOME de JDK17 y PostgreSQL18 en PATH:

```powershell
# Se usó el mvn.cmd 3.9.15 cacheado por el wrapper; estos argumentos son los ejecutados.
mvn -o '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55435/chemicallab_t01_test' '-Dspring.datasource.username=t11b5_fixture' '-Dspring.datasource.password=fictitious' clean package
# Selección B5: los mismos argumentos y '-Dtest=IdentityPreparationDbTest' test.
# El test reproduce pg_dump -Fc / pg_restore --single-transaction --exit-on-error,
# certificación SQL y disputa de reserva en dos procesos independientes.
```

Preparación reproducible, únicamente en un directorio nuevo y puerto libre:

```powershell
initdb -D ../t11b5-repro-postgres -U t11b5_fixture -A trust --encoding=UTF8 --locale=C
pg_ctl -D ../t11b5-repro-postgres -l ../t11b5-repro-postgres.log -o '-h 127.0.0.1 -p 55435 -c max_connections=300' -w start
createdb -h 127.0.0.1 -p 55435 -U t11b5_fixture chemicallab_t01_test
# Ejecutar la suite, verificar evidencia y retirar únicamente la base ficticia propia.
dropdb -h 127.0.0.1 -p 55435 -U t11b5_fixture chemicallab_t01_test
pg_ctl -D ../t11b5-repro-postgres -m fast -w stop
```

Trust solo en el clúster loopback de ensayo; no configuración operativa.
El arnés inicial crea el esquema legacy mediante Hibernate antes de aplicar
la cadena explícita; después ninguna divergencia se repara con Hibernate.
Restore y procesos B5 no arrancan instaladores JPA. Perfil test obligatorio;
las guardas de los arneses rechazan destinos ajenos. Los errores deliberados
SECURITY_AUDIT_EVIDENCE_UNAVAILABLE/guardas de arranque no son fallos Surefire.

| Intentos/selecciones anteriores, sin sumarlos a la suite definitiva | Resultado y atribución |
| --- | --- |
| Maven inicial dentro de sandbox | Interrumpido sin suite; bloqueo de procesos/runtime. Un intento psql de lectura en sandbox fue bloqueado y se repitió fuera de sandbox sobre el mismo clúster ficticio |
| focused-01 / focused-02 | Errores de compilación del arnés (API HTTP/principal y throws); ninguna aceptación funcional |
| focused-03 | 1 error de inicialización por CASE PL/pgSQL sin paréntesis; corregido antes del commit |
| focused-04 | 22 errores de fixture por membresía B ya creada en B4; se reutilizó su ID, sin cambiar unicidad |
| focused-05 / restore-diagnosis | 22 casos con 1 error de restore, y diagnóstico 1 caso fallido. Se detectó representación de BETWEEN/AND y aliases de UNION; definición explícita corregida sin sobrescribir firmas ni debilitar guardas |
| focused-06 | 22/0/0/0, iteración anterior a control de procedencia temporal y tres controles finales |
| focused-07 | 25/0/0/0 antes del commit, mismos código/SQL/tests que el SHA probado; selección, no suite completa |
| full-package.log | Única suite completa de entrega: 739/0/0/0 sobre SHA f3f6337 |

Las **714 pruebas históricas** de B4 pertenecen exclusivamente a
`9c60faaa4dd50aa9c34e65a7c44da1f5b5cd0f58`; sus notas/cierres/evidencias no se
reescriben ni se atribuyen a B5. El total nuevo se obtiene de XML Surefire de
una ejecución completa, no de sumar selectores o evidencia nativa adicional.
Carrera y restauración B5 forman parte de sus 25 pruebas, no casos extra.

Evidencia local conservada en `.codex-work/t11b5-evidence`, fuera de la PR:
`tested-sha.txt`, `full-package.log`, `verified-results.json`, `verified-suites.json`,
`verified-reports.zip`, `verified-backend.jar`, `verified-packaged-sql.json`,
`verified/t11b5-native` y `verified/t11b5-processes`, dumps/catálogos/firmas,
logs focused-* y restore-diagnosis separados, manifiestos de conservación y
`collect.ps1`, `cleanup.ps1`, `cleanup.log`. No incluye datos escolares reales.

Conservación: **30/30 archivos protegidos**, incluidos catorce documentos locales
anteriores y los **16 E2E**, sin discrepancias; siete SQL anteriores intactos y
56 documentos backend anteriores (excluida la actualización solicitada T11-A)
sin cambios. Auditoría original SHA-256
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Frontend conserva `feature/validaciones-datos-institucionales`,
`e88589635a8269c50ee0f53fccf157f0d0985034`, con `?? e2e-selenium/` previo.
Checkout backend original limpio en develop/base 4e04545. Fetch final reconfirmó
el mismo develop. La base ficticia se retiró y el servidor propio se detuvo,
verificando primero ruta/instancia, cero schemas de ensayo y cero conexiones.

No suite Linux, nueva SCA, dimensionamiento sostenido o validación operativa.
No se certifican permisos reales, recuperación entre zonas distintas ni consumo
de secuencias. SEC-13 y VER-01, ciclos operativos de aliases y todos los gates
T12/T13 siguen pendientes. Esta entrega termina en PR borrador hacia develop.

## Matriz T11-A vigente

La matriz A01–A17 actualizada reside en [T11-A, §7.3](11a-modelo-institucional.md).
Cumplido significa preparación del modelo/ensayo cubierto; no gate externo T12/T13
ni certificación de operación. Los pendientes de B2 (co-docencia), B3 (biblioteca,
ciclo académico de revisiones, sincronización de writers), B4 (reemplazo aditivo
de vínculos históricos erróneos), SEC-13/VER-01 y restantes decisiones siguen abiertos.
