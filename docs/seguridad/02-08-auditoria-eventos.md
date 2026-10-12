# T08 — Auditoría fiable de eventos de seguridad (SEC-14)

Etapa 2, 12 de octubre de 2026, America/Lima. Exclusivamente T08. Base backend
`c7f407cb6d378ee6bb02b70ec378b2e382f46842`; frontend develop de referencia
`b9d6b97877ef64c4cdc90c3e7f432dc0aeb457a5`. Checkout aislado y rama
`security/t08-auditoria-eventos`. Angular no requiere cambios: los contratos son
aditivos. No se ejecutan merge, despliegue, rotación, DDL real ni T09/T10.

Se contrastaron SEC-14/T08, las notas versionadas T01–T07-B y el cierre
`Documentos/Seguridad/02-07b-cierre-integracion.md` con los productores actuales.
Se preservan los documentos anteriores, la auditoría y el frontend original
`feature/validaciones-datos-institucionales`, incluidos sus 16 archivos e2e-selenium.
No se encontraron instrucciones AGENTS.md aplicables a estos repositorios.

## Reproducción previa

Sobre la base integrada, antes de modificar producción, `AuditBaselineDbTest`
ejercitó aplicación/HTTP/JPA/PostgreSQL ficticios: una edición de cuenta revertida
conservó un USER_UPDATED; el cambio propio y logout global no produjeron eventos;
renombrar temporalmente el destino system_logs permitió confirmar otra edición
sin evidencia. Un caso compuesto pasó sus aserciones del comportamiento defectuoso.
Arnés y log permanecen locales, fuera de la PR. Los primeros intentos bloqueados
por loopback y por el arranque del servidor no son evidencia concluyente.

## Diseño de entrega y resultado

`AuditLogWriter.persist` exige una transacción de negocio activa (MANDATORY).
Inserta y hace flush en esa misma transacción. No se oculta el fallo de escritura:
`AuditUnavailableException` impide confirmar el negocio y REST responde
503 AUDIT_UNAVAILABLE con un mensaje fijo, sin SQL, excepción ni secretos.
El flush comprende también cambios de negocio pendientes; se conserva el 409
STORAGE_QUOTA_EXCEEDED de T07-B cuando ese es el fallo detectado.

SUCCESS solo es visible a otros lectores después del commit de negocio; rollback
retira ambos cambios. No hay cola en memoria, outbox, entrega diferida ni callback
que persista después de confirmar. Se eligió este mecanismo porque negocio y
auditoría ya comparten PostgreSQL: evita otro proceso de entrega, reintentos,
duplicados y coordinación de pendientes. PostgreSQL es también el destino durable.
La caída de la JVM después del commit no separa el evento de su operación.
Una pérdida de confirmación de commit por fallo de red puede dejar resultado desconocido; investigar la correlación antes de repetir una acción no idempotente. La atomicidad no garantiza que el cliente reciba el resultado del commit.
La recuperación exige disponibilidad del destino y reintento explícito de una
operación que el servidor rechazó; no se reconstruye un evento perdido.

Los rechazos se construyen fuera de la transacción de negocio. En login el servicio
lanza LoginRejectedException con código interno y el controlador registra después
de que su transacción haya revertido. Conserva exactamente el 401 LOGIN_INVALID
uniforme de T04. Los rechazos REST observan la respuesta final; STOMP observa
denegaciones de protocolo/autorización/entrada y el handler privado de dibujo.
REJECTED distingue esos casos de FAILED para errores operativos/HTTP 5xx.
No se vuelcan mensajes de excepción ni payloads. Un 400/409 es una solicitud
rechazada y no se transforma en éxito ni en autenticación fallida.

La evidencia de rechazo está limitada y puede suprimirse. Si su escritura falla,
se preserva el rechazo público y se emite una señal independiente. La finalización
del filtro puede escribir después de que el cuerpo HTTP haya sido entregado:
no se garantiza evidencia exhaustiva de cada rechazo ni cobertura de una caída
del proceso en ese intervalo. No se promete recuperación diferida de esa muestra.
La evidencia obligatoria de operaciones confirmadas sí tiene atomicidad durable.

## Catálogo y productores

| Familia / evento obligatorio | Productor actual | Omisión anterior y comportamiento T08 |
| --- | --- | --- |
| LOGIN_SUCCESS | AuthService.login | Existía; ahora confirma junto con migración de hash y sesión; una sola fila alimenta indicadores de login |
| LOGIN_FAILED | AuthController después de AuthService | Existía con actorUsername declarado y best effort; ahora actor vacío, huella declarada separada, motivo permitido y muestra limitada |
| PASSWORD_CHANGED | AuthService.changeTemporaryPassword | Ausente; cambio normal/temporal, actor y objetivo propios, commit junto al hash y sesión de reemplazo |
| PASSWORD_RESET | AdminService y tres resets heredados de UserManagementService | Existía; mismas rutas/permisos, éxito junto al nuevo hash temporal, sin contraseña ni JWT en auditoría |
| SESSION_REVOKED / SESSIONS_REVOKED | AccountSessionService.logout; AuditLogService para invalidaciones | Logout individual/global ausentes; objetivo cuenta, UUID de sesión nunca almacenado en el log; reset/cambio/estado/ámbito dejan revocación global asociada |
| USER_CREATED / USER_UPDATED | AdminService, UserManagementService; AdminBootstrap y DemoAccountSeeder opt-in | APIs existentes; bootstrap/demos ahora también dejan evidencia conjunta, actor humano null y source fijo BOOTSTRAP/DEMO; no incluye nombres personales, valores cambiados ni contraseña temporal |
| USER_DEACTIVATED / USER_REACTIVATED | Familias admin y heredadas; updateStudent con cambio de active | Rutas explícitas existían; edición combinada solo emitía USER_UPDATED; ahora registra también el estado |
| PERMISSIONS_CHANGED | Edición de ámbito de cuenta en AdminService y UserManagementService | Ausente; cambios de grupo/identidad/docente que invalidan credenciales; no añade una API de cambio de rol |
| WHITEBOARD_INTERACTION_UPDATED / WHITEBOARD_PARTICIPANT_INTERACTION_UPDATED | WhiteboardSessionService | Existían; global boolean e individual override + ID de cuenta del participante, sin lienzo |
| WHITEBOARD_SESSION_CREATED/PAUSED/RESUMED/CLOSED | WhiteboardSessionService | Existían; control y captura confirman con su evidencia, sin bytes ni contenido |
| CONCEPT_CREATED/UPDATED/PUBLISHED/ARCHIVED/ASSIGNED | ConceptContentService | Existían; objetivo ID, commit conjunto, sin título/cuerpo/URL |
| CONCEPT_MATERIAL_ADDED/REPLACED/REMOVED; CONCEPT_LINK_ADDED/REMOVED | ConceptMaterialService | Existían; cambio durable junto a cuotas/archivo, únicamente tipo/tamaño permitido |
| EVALUATION_CREATED/CONFIG_UPDATED/PUBLISHED/ASSIGNED | EvaluationService | Existían; preserva política T05 y operaciones, sin preguntas, claves o respuestas |
| EVALUATION_UPDATED (preguntas), EVALUATION_ARCHIVED / EVALUATION_ATTEMPT_STARTED | EvaluationService | Enums sin productor de system_logs; T08 añade configuración de alternativas/desactivación de pregunta sin clave/respuesta, archivo e inicio; conserva el evento ATTEMPT_STARTED académico existente |
| EVALUATION_OPEN_QUESTION_SAVED, ATTEMPT_SUBMITTED/PENDING_REVIEW, ANSWER_REVIEWED, REVIEW_COMPLETED, ADJUSTMENT_ADDED/REMOVED, FEEDBACK_UPDATED, GRADE_CLOSED | EvaluationService | Existían; IDs de intento/respuesta/ajuste, sin texto, nota exacta, feedback ni respuestas |
| REST_DENIED | AuditRequestFilter, respuesta final /api | Ausente; 400/401/403/409/413/429 relevantes, sesión verificada cuando exista y objetivo solicitado numérico cuando se reconoce |
| STOMP_DENIED | Interceptor inbound y handler privado | Ausente; rechazo CONNECT/SEND/SUBSCRIBE/permiso/entrada; no registra cada mensaje válido, latido ni entrega por destinatario |
| OPERATION_FAILED | Filtro REST para 5xx y fallos STOMP operativos | Ausente; resultado FAILED, código fijo, nunca un éxito de transacción revertida |
| TECHNICAL_RETENTION_EXECUTED | TechnicalRetentionService.batch | Lote real de borrado de usage_events antes sin evidencia administrativa; ahora evidencia SUCCESS en la transacción de borrado, estado y liberación de cuotas, con actor verificado del contexto HTTP |

LOGOUT histórico queda legible; los nuevos tipos distinguen explícitamente
revocación individual/global. Los enums RESULT_VIEWED, SYSTEM_HEALTH_CHECK,
ADMIN_ACTION y otros reservados no implican productores reales. No hay endpoints
de exportación, instituciones o invitaciones que deban inventarse. Las lecturas de
material/captura/resultados, presencia y cada respuesta académica/dibujo no crean
otra escritura ilimitada de auditoría. La telemetría y timeline académico conservan
sus tablas, cuotas y contratos separados; no se duplican LOGIN_SUCCESS ni sus
indicadores. Varias causas de invalidación de una cuenta en una misma transacción
comparten una única evidencia de revocación global.

## Contexto y contratos seguros

Actor de negocio: SessionPrincipal establecido por la autenticación T02, o cuenta
verificada por login exitoso. No se resuelve un actor buscando un nombre declarado.
En rechazos sin autenticar, actorUserId/actorUsername/actorRole son null. CONNECT
rechazado por credenciales tampoco atribuye un actor. Un principal verificado antes
de una denegación de ámbito puede identificar al solicitante, sin afirmar autorización.
Objetivo confirmado: tipo/ID del recurso de negocio; en denegaciones, el ID representa
el recurso solicitado, sin afirmar propiedad ni existencia.
Los LOGIN_FAILED históricos pueden conservar actorUsername con el identificador
declarado anterior: no acredita identidad autenticada. Su resultado/correlación null
identifica evidencia anterior a este contrato; no se reescriben esos históricos.

Servidor genera UUID por request y frame STOMP; ignora cabeceras de correlación
aportadas por cliente. Un contexto de transacción conserva correlación en llamadas
internas. HTTP devuelve X-Correlation-ID y CORS lo expone; el error privado de dibujo
añade auditCorrelationId sin reemplazar clientEventId de T03. El cliente no controla
el UUID de auditoría. AuditLogResponse incorpora outcome, correlationId y
declaredIdentifier; se conserva content/page/size/totalElements y filtros existentes.

createdAt nuevo usa explícitamente hora de America/Lima, compatible con la interpretación académica T05 y los filtros/indicadores existentes; no convierte históricos ni desplaza las sesiones cinco horas.

declaredIdentifier es `sha256:` + huella de la cadena declarada saneada/recortada
(máximo 100 puntos de código). Se evita almacenar una credencial pegada como login.
La huella es seudónimo susceptible de diccionario, no anonimización ni identidad
verificada. No se guarda el identificador declarado en campos de actor.

Descripción y acción nuevas son códigos de evento controlados por servidor;
targetLabel no incorpora texto libre. Texto identificador autenticado queda acotado
y sin controles/formato Unicode/separadores de línea. Metadata acepta como máximo
12 claves, entrada 2048 caracteres; IDs/tamaño/versión numéricos hasta 18 dígitos,
booleans y enums cerrados de rol/tipo/override/motivo. Todo otro texto se descarta,
incluidos los valores de campos editados. No hay IP, User-Agent u origen nuevos:
T04 mantiene su único ClientOriginResolver y la política de proxies explícitos.

## Rechazos, señales y límites

Dos escritores simultáneos por instancia, sin espera en una cola local. Presupuesto
local de 120 intentos de evidencia por 60 segundos, también cuando el destino falla.
Una fila audit_rejection_budget compartida admite como máximo 120 escrituras en un
minuto PostgreSQL, incluidas todas las familias de rechazo/fallo. Reserva y evidencia
confirman en la misma transacción REQUIRES_NEW; la fila persiste tras reinicio.
No crea claves por usuario/IP/destino. El reloj del servidor DB abre el siguiente
minuto. El límite local puede suprimir más que el compartido, especialmente tras
una ráfaga; no se presentan muestras ni contadores como historial exhaustivo.

lock_timeout=250ms, statement_timeout=1500ms y timeout transaccional=2s acotan
esperas/consultas del escritor de rechazo. La adquisición de conexión depende del
pool/driver y no queda certificada como deadline absoluto. La admisión local evita
agotar el pool con más de dos escritores de rechazo de esta instancia. Los límites
de HTTP/STOMP/T04/T07-A siguen vigentes; no es un dimensionamiento productivo.

Micrometer: contador security.audit, etiqueta fija signal:
required_committed, required_rolled_back, required_persist_failed, commit_unknown,
rejection_committed, rejection_persist_failed, rejection_suppressed_local,
rejection_suppressed_shared. Committed/rolled_back cuentan eventos, no transacciones.
Los fallos escriben SECURITY_AUDIT_EVIDENCE_UNAVAILABLE por SLF4J, como máximo una
vez/minuto/instancia, sin causas ni datos de usuario. No usa system_logs para avisar
de su avería. Los contadores en proceso no son una cola durable: recolección externa,
alarmas, conservación de logs de emergencia y reinicios de contadores son controles
operativos pendientes. No se añade un endpoint público de métricas.

## Esquema, históricos y procedimiento operativo pendiente

Fuente única: `src/main/resources/security/t08-audit.sql`, versión 1. Añade columnas
nullable outcome/correlation_id/declared_identifier e índice de correlación, fila de
presupuesto, versión y trigger audit_append_only contra UPDATE/DELETE/TRUNCATE.
Los históricos permanecen íntegros y con resultado/correlación null: no se backfillea
SUCCESS sobre evidencia generada antes de T08. La consulta continúa siendo exclusiva
del administrador, con límite 100, paginación y orden createdAt,id de T07-B. Las APIs
no exponen mutaciones. El trigger protege DML ordinario, sin declarar inmutabilidad
frente a un administrador DB que pueda desactivarlo o alterar tablas/funciones.
No hay retención ni borrado automático de auditoría; T07-B solo selecciona usage_events.

### Lotes administrativos de retención y cuota STOMP

Corrección de la revisión de PR #63 sobre
`8078caeca0f83fda5a221dc0579e4aa7baf4c1d8`: los arneses locales ReviewAudit.java
y ReviewQuotaClassification.java se ejecutaron nuevamente antes de editar el código.
El primero reprodujo HTTP 200, deleted=1 e incremento de system_logs=0 y repitió
cinco controles T08 aprobados. El segundo reprodujo STORAGE_QUOTA_EXCEEDED con
OPERATION_FAILED/FAILED/reason=STOMP_FAILURE. Los arneses, logs y datos ficticios
permanecen fuera de la PR; no se publica evidencia sensible.

Cada lote coordinado, no simulado, que elimina al menos una fila emite exactamente
un TECHNICAL_RETENTION_EXECUTED de categoría ADMIN, resultado SUCCESS y objetivo
TechnicalRetention/1 (la fila singleton de coordinación, no un ID eliminado).
Actor y correlación se obtienen del contexto autenticado del administrador y del
request del servidor. El acceso HTTP continúa reservado al administrador.
Metadata se limita a cutoff ISO local validado, candidates, deleted y batchSize
(cantidades entre 0 y 1000); identifica el alcance exclusivamente sobre usage_events.
No contiene listas de usuarios, IDs eliminados, descripciones ni contenidos.
La fecha cutoff conserva la semántica LocalDateTime del contrato T07-B.

El evento confirma junto con todos los DELETE, technical_retention_state y las
liberaciones USAGE_COUNT de los triggers T07-B. Si falla la auditoría obligatoria,
HTTP responde 503 AUDIT_UNAVAILABLE y se revierten las tres clases de cambio.
Recuperar el destino permite un reintento explícito, sin evidencia de éxito duplicada
de la operación fallida. Si otros escritores dejan cero borrados efectivos tras
seleccionar candidatos, no se avanza el estado ni se emite un evento de borrado.

Simulation=true, coordinated=false y ausencia de candidatos conservan sus resultados
distintos en la respuesta, sin mutación durable de datos/estado/cuotas ni evento
TECHNICAL_RETENTION_EXECUTED. El bloqueo de coordinación de la simulación es temporal.
Los rechazos HTTP por retención deshabilitada o permisos insuficientes y los fallos
pueden generar la muestra REST_DENIED/OPERATION_FAILED ya documentada; no significan
un borrado realizado. delete-enabled=false sigue siendo el valor por defecto, sin
scheduler nuevo. System_logs y registros académicos siguen protegidos de la retención.

El handler privado STOMP reconoce P7501 mediante StorageErrors.quota antes de
clasificar la evidencia: STOMP_DENIED/REJECTED/reason=STORAGE_QUOTA_EXCEEDED.
Una avería operativa distinta conserva OPERATION_FAILED/FAILED/reason=STOMP_FAILURE.
No extrae texto SQL ni mensajes de excepción. Preserva code/error privados,
clientEventId, auditCorrelationId, accepted=false y resync=true; el presupuesto de
rechazos anterior sigue aplicando, sin promesa de historial exhaustivo.

El primer pase de corrección ejecutó 41 casos: un fallo en la aserción nueva de
históricos, cero errores/omisiones. La aserción excluía por tipo también los lotes
de pruebas previas; se corrigió para excluir solo la fila recién insertada y
comprobar que todos los históricos permanecen completos. No se contabiliza ese
pase como validación aprobada. Los demás casos, incluidos rollback íntegro por
auditoría averiada y las dos clasificaciones STOMP, aprobaron sus aserciones.

Antes de una futura publicación, con autorización operativa separada:

1. Identificar esquema y DB, preparar backup/restauración, revisar permisos mínimos
   y acordar ventana. Detener escritores de todas las instancias y procesos externos.
2. Con search_path verificado y credenciales fuera de comandos/logs, aplicar **una
   vez**, mediante `psql --single-transaction --set ON_ERROR_STOP=1 --file
   src/main/resources/security/t08-audit.sql`. No incluye BEGIN; el runner de ensayo
   también lo envuelve en una transacción. Preparar T07-B si aún no existe.
3. Verificar versión 1, columnas/índice, trigger habilitado y presupuesto id=1;
   comparar históricos completos. Ensayar operación confirmada/rollback/denegación
   con cuentas ficticias y comprobar señales fuera de DB antes de publicar.
4. Publicar instancias coordinadas y recolectar/alertar señales de fallos/supresión;
   medir crecimiento/latencia y definir supervisión externa. Mantener retención
   de auditoría intacta. No mezclar con escritores anteriores que oculten sus fallos.

AuditSchema comprueba versión/guard/presupuesto antes de abrir HTTP. Si falta o se
deshabilita el guard, falla el arranque. app.audit.install-schema=false es el valor
normal. La instalación automática solo se habilita en perfil test y DB ficticia
loopback con puerto explícito y nombre chemicallab_t01_test; activarla en prod se
rechaza. Los ensayos crean y eliminan exclusivamente esquemas ficticios aleatorios.
La reversión requiere revisar conjuntamente código/esquema con escritores detenidos;
no retirar guards ni restaurar un backend best effort para eludir un incidente.
Tiempo de lock/índice con históricos reales, privilegios productivos y recuperación
de backups quedan pendientes, sin extrapolar el ensayo local.

## Verificación y cierre

AuditReliabilityDbTest ejecuta transacciones reales, HTTP, STOMP, indisponibilidad
por renombrado del destino ficticio, recuperación, dos contextos Spring independientes,
reinicio y cuota compartida. AuditSafetyTest comprueba allowlist, secretos/control,
identidad declarada, contexto y 250 rechazos contra escritor averiado: 120 intentos
DB y 130 suprimidos, con señales verificadas. Son contextos en una JVM, no hosts reales
ni una prueba de caída abrupta del sistema operativo. La atomicidad durable no depende
de una entrega pendiente al reiniciar.

La verificación focalizada final pasó **49 pruebas, cero fallos/errores/omisiones**:
12 AuditReliabilityDbTest, 5 AuditSafetyTest, 13 LoginProtectionDbTest,
17 AccountProvisioningTest y 2 StartupPersistenceDbTest. Incluye instalación de la
fuente SQL sobre una tabla anterior ficticia con una fila histórica conservada,
estado+grupo simultáneos con una sola revocación, UUID cliente ignorado e indicador
de sesión exactamente uno. El ensayo compartido partió de presupuesto 110: veinte
escritores entre dos contextos confirmaron exactamente diez y dejaron 120; el
reinicio conserva ese contador y una ventana vencida vuelve a admitir uno.

La primera ejecución global recorrió 560 casos, con un fallo de comparación literal
del UUID nuevo y dos errores de conexión del clúster ficticio inicial de 20 conexiones.
Se valida UUID v4 en cada respuesta y se compara el resto completo de cabeceras/cuerpo
entre cuenta activa, inactiva y ausente. El clúster **solo de pruebas** se reinició con
100 conexiones y 32 MiB de shared_buffers; no se cambiaron pools ni límites de la app.
Otros intentos parciales detectaron el constructor nuevo, una expectativa antigua
de descripción y carreras de observación HTTP/rollover de minuto; no se contabilizan
como comprobaciones aprobadas ni como suite acumulada.

Entorno: Windows, Java 17, Maven 3.9.15, PostgreSQL 18.4 desechable ligado únicamente
a 127.0.0.1:55479, base ficticia chemicallab_t01_test. Cada fixture guarda/elimina su
propio schema; ninguna conexión se dirigió a una base escolar. El esquema original
se prueba en otro schema ficticio y se elimina al terminar. La instalación/guardas
productivas no se aplicaron sobre datos reales.

Comando reproducible para suite y JAR, con credenciales externas exclusivamente de
esa instancia ficticia:

```text
mvn -o clean package -Dspring.profiles.active=test -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:<PUERTO>/chemicallab_t01_test -Dspring.datasource.username=<USUARIO_FICTICIO> -Dspring.datasource.password=<CLAVE_FICTICIA>
```

La ejecución global de la entrega inicial de `clean package` sobre el commit de implementación
`e32450bbc8a1f38f8199bcd2ae38d17d29293e31` terminó con **565 pruebas, cero
fallos, errores u omisiones**, BUILD SUCCESS y JAR Spring Boot generado. Duró
6 min 9 s y terminó el 12 de octubre de 2026 a las 04:15:10 America/Lima.
Incluye las pruebas T08 y las regresiones existentes T01–T07-B, REST/STOMP,
indicadores, aprovisionamiento, consultas, cuotas y validación de archivos.
Los errores de arranque deliberados de las pruebas de guardas son esperados;
no son errores del resultado final de Surefire.

Este cierre se añade mediante un commit exclusivamente documental posterior.
No se atribuye una suite global a ese HEAD posterior: su código, tests, recursos
y pom.xml son idénticos al SHA probado, verificados con git diff. No se suman
ejecuciones parciales. Los logs/arneses locales t08-baseline-confirmed.log,
t08-focused-final.log, t08-full-first.log y t08-full-final.log no se publican;
los tests reproducibles sí se versionan.

### Verificación final de las correcciones de revisión

`clean package` se ejecutó sobre el commit
`cca0fe595d8a4b0dfcd2b5f71fd4e6802796b731`: **574 pruebas, cero fallos,
errores u omisiones**, BUILD SUCCESS y JAR Spring Boot generado. Duración 6 min
29 s; fin el 12 de octubre de 2026 a las 04:48:09 America/Lima. No es la suma de
ejecuciones parciales ni una extrapolación de la suite de 565 casos anterior.

Incluye 6 RetentionAuditDbTest: borrado confirmado con administrador/cutoff/
cantidades/correlación e históricos conservados; destino de auditoría averiado con
rollback de filas, estado y cuotas, seguido de recuperación; simulación sin mutación;
ausencia de candidatos; coordinación ocupada; retención deshabilitada y acceso de
estudiante rechazado. Las pruebas HTTP usan datos ficticios y un lote máximo de dos.
Incluye 2 StompAuditClassificationTest: P7501 esperado frente a una avería SQLSTATE
08006, con actor/correlación, respuesta privada, clientEventId, resync y ausencia de
texto SQL/excepción/cuerpo. StorageQueriesDbTest (15 casos) comprueba además una cuota
STOMP real contra PostgreSQL, evento REJECTED y misma correlación que el error privado,
estado del lienzo intacto y reconciliación/restauración previa. AuditSafetyTest (6)
valida también fechas reales, límites de cantidades y descarte de listas/control;
AuditReliabilityDbTest (12) conserva los controles generales T08. Todos están incluidos
en los 574; no son casos adicionales a ese total.

El commit posterior de cierre solo cambia este documento. No se declara ejecución
global de su HEAD: src y pom.xml permanecen idénticos al commit probado, comprobados
con git diff. Los logs baseline-retention-correction.log, correction-focused.log y
correction-global.log y el resumen correction-results.json quedan locales en
.codex-work/t08-review, fuera de la PR. Se conservan también los arneses de revisión.
No se requiere un DDL adicional por estas dos correcciones; no se alteró la fuente
SQL T08 ni el procedimiento/guardas operativos pendientes. PostgreSQL utilizado fue
exclusivamente el clúster ficticio ya indicado; no se midieron límites productivos.

Conservación: 22 archivos del inventario local (seis Documentos/Seguridad y los
16 e2e), cero diferencias SHA256. Backend original limpio en la base indicada.
Frontend original conserva rama y HEAD e88589635a8269c50ee0f53fccf157f0d0985034;
los documentos versionados T01–T07-B no tienen cambios en esta rama.

SEC-14 puede cerrar su implementación de código tras las verificaciones de entrega.
La efectividad desplegada permanece pendiente de instalación autorizada, permisos
DB reales, recolección/alertas externas, crecimiento, disponibilidad/backup y reloj
sincronizado. Este documento y la PR no certifican almacenamiento inmutable,
historial exhaustivo de rechazos ni controles productivos. Detener para revisión.

Referencias del mecanismo: [transacciones Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
y [triggers PostgreSQL 18](https://www.postgresql.org/docs/18/sql-createtrigger.html).
