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
La evidencia definitiva y el SHA probado se añaden después del empaquetado.

Se cierra únicamente el alcance implementado **P02 persistente** con esa evidencia.
P01 sigue preparado en B6; **P03–P06 y etapa 3 permanecen abiertos**. T12/T13/T14
conservan sus gates. SEC-13/VER-01, Linux/SCA/operación siguen pendientes.
Documentos históricos, auditoría original, frontend institucional y 16 E2E
conservados; solo se actualizan decisiones/matrices vigentes T11-A/T11-C.
PR borrador hacia develop, sin merge, despliegue, DDL/backfill real ni activación
multiinstitucional. No se inicia T12 ni P03–P06.
