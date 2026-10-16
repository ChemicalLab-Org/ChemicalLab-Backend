# T12-E — Revisión y calificación docente institucional LEGACY

15–16 de octubre de 2026, America/Lima. Base local y remota `develop` comprobada:
`6d3de4c67257a72dfab8bc46b714aaedd61a55b2`, integración T12-D/#83.
Checkout aislado `.codex-work/t12e-backend`, rama
`feature/t12e-revision-calificacion`. No hay AGENTS.md aplicables al backend
ni instrucciones de delegación; no se delegó trabajo. Se conservan el checkout
principal, el frontend y los demás worktrees.

Se contrastaron T12-A/B/C/D, T11-D y B6/B10/B11, EvaluationService,
EvaluationDeliveryAccessService, EvaluationAccessPolicy/Lock, T02/T05/T10,
el transporte T07-A, cuotas T07-B/B9 y auditoría obligatoria T08.

Este bloque adopta operaciones docentes unitarias exclusivamente en fixtures.
T12 integral y etapa 4 permanecen abiertos. No adopta el motor NEW, creación o
publicación de evaluaciones, Angular, STOMP, registro, bandejas, listados ni
reportes masivos. No autoriza merge, despliegue, datos escolares, DDL/backfill
real ni rotaciones. La entrega se detiene para revisión.

## Contrato HTTP

Prefijo docente:

```text
/api/institutions/{institutionId}/classes/{classId}/evaluation-writes/{contract}/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}/attempts/{attemptId}/review
```

`contract` debe ser LEGACY. Los identificadores NEW, incluso numéricamente
iguales, no se intercambian. Todas las escrituras son POST con `operationId`
UUID obligatorio, incluidas la anulación lógica y el cierre.

| Método/sufijo | Acción institucional | Campos adicionales del cuerpo |
| --- | --- | --- |
| GET al prefijo | REVIEW_ATTEMPT | Sin cuerpo |
| POST `/answers/{answerId}` | GRADE_ATTEMPT | `score` entero >= 0; `feedback` opcional, máximo 2000 caracteres |
| POST `/complete` | GRADE_ATTEMPT | Ninguno |
| POST `/adjustments` | GRADE_ATTEMPT | `amount` en [-20,20]; `reason` no vacío, máximo 500 caracteres |
| POST `/adjustments/{adjustmentId}/void` | GRADE_ATTEMPT | Ninguno |
| POST `/feedback` | GRADE_ATTEMPT | `overallFeedback` opcional, máximo 1500 caracteres |
| POST `/close` | GRADE_ATTEMPT | Ninguno |

El transporte existente acota también estas rutas: máximo 1 MiB o el límite
HTTP configurado si es menor, cuerpos sin Content-Length, tipos de contenido
alternativos y parser estructural T07-A. Se conservan límites de profundidad,
cadenas, propiedades, colecciones y claves duplicadas. MVC valida cuerpos
tipados. La proyección docente admite hasta 200 abiertas y 200 ajustes;
rechaza desbordamientos sin devolver una colección parcial. El alta no permite
superar 200 ajustes históricos del intento, incluidas las anulaciones.

Éxito: 200, `Cache-Control: no-store`. Escrituras devuelven exclusivamente
operationId, attemptId, childId cuando corresponde, estado y gradeClosed.
El GET usa records explícitos: cuenta destinataria comprobada, estado y
calificaciones persistidas, feedback general, fechas, cuenta del docente del
cierre, abiertas con criterio/respuesta/puntos/feedback y cuenta/fecha de revisión,
y ajustes con su autor y estado activo. No serializa entidades, perfiles, nombres,
objetos de cuenta ni el manifiesto B10. El GET no crea respuestas faltantes,
recalcula, repara ni cierra una nota; incluso finalScore es el valor persistido.

Activación incompleta: 404. Sesión inválida/revocada: 401; contraseña temporal:
403 PASSWORD_CHANGE_REQUIRED del filtro T02. Relación, facultad o regla académica
incumplida: 403 genérico. Clave incompatible: 409 genérico. Entrada inválida: 400;
transporte excedido: 413. Fallo persistente, de auditoría, cuota o coordinación:
503 genérico, sin SQL, motivos, criterios o feedback en la respuesta de error.

## Actor, destinatario y facultades

El actor procede exclusivamente de `@AuthenticationPrincipal SessionPrincipal`
de T02. El destinatario no procede del actor ni de un campo del cliente: se
obtiene de `evaluation_attempts.student_id → student_profiles.user_id` y se
valida en la entrega/revisión/raíz exactas mediante T12-A. Los IDs solo seleccionan.

Se resuelven institución ACTIVE, pertenencia/roles vigentes, clase ACTIVE,
período OPEN, destinatario ESTUDIANTE y matrícula/episodio efectivos. Para el
actor se exigen DOCENTE y asignación vigente RESPONSABLE B2 o CO_DOCENTE B6 de
esa misma institución/clase. Se comprueban cabezas F08, relaciones de entrega,
revisión técnica B3, intento, preguntas, opciones, respuestas y todos los ajustes,
incluidos los anulados. Las relaciones respuesta/pregunta/opción deben ser exactas.

| Actor y situación | Supervisión T12-B | GET docente | Modificación académica |
| --- | --- | --- | --- |
| RESPONSABLE + DOCENTE vigentes | Según T12-A/B | Terminal ajeno | Terminal ajeno y calificación abierta |
| CO_DOCENTE + DOCENTE vigentes | Según T12-A/B | Mismas facultades | Mismas facultades |
| Autor original sin asignación vigente | Sin facultad por autoría | Denegado | Denegado |
| ADMIN_INSTITUCION solo | Metadatos permitidos por T12-A/B | Denegado | Denegado |
| ADMIN_INSTITUCION + DOCENTE + asignación | Según sus relaciones | Permitido en intento ajeno | Permitido si no hay cierre ni autocalificación |
| Grant de biblioteca EDIT_DRAFT/PUBLISH/ASSIGN | No concede supervisión | Denegado por ese grant | Denegado por ese grant |
| Cuenta propietaria, aunque tenga roles docentes/administrativos | Según sus otros contratos | Denegado | Denegado |
| Docencia revocada, vencida o aún no efectiva | Sin facultad por esa docencia | Denegado | Denegado |

No se utiliza `requireOwnedAttemptForTeacher` como autoridad institucional. Las
rutas legacy conservan esa comprobación y sus contratos. El actor necesita un
TeacherProfile existente para la atribución tipada del modelo; no se crea al
calificar ni se toma el perfil del autor. Evaluation.createdByTeacher y
Attempt.student permanecen históricos. reviewedBy, createdBy del ajuste,
gradeClosedBy y auditoría reflejan al docente que actúa. La anulación conserva
el ajuste y su autor original; su actor de anulación queda en recibo y auditoría.

## Núcleo académico y ocultación T05

Se extraen mutaciones privadas de las operaciones legacy de EvaluationService.
Ambas entradas comparten validación de respuesta abierta, rango de puntos,
revisión pendiente, cálculo de score/maxScore, nota vigesimal, ajustes activos,
redondeo HALF_UP a dos decimales y acotación final [0,20]. No existe otro motor.
La entrada institucional usa el reloj PostgreSQL en America/Lima; legacy
mantiene su reloj existente. Se valida también el límite inferior de la
puntuación dentro del núcleo, además de MVC.

Una abierta revisada registra feedback normalizado, fecha y docente reales.
Revisar la última abierta puede producir GRADED y EVALUATION_REVIEW_COMPLETED;
no cierra definitivamente la nota. Completar exige que no queden abiertas
pendientes y tampoco cierra la nota. Cerrar exige la misma condición, recompone
la calificación y fija cierre/fecha/docente. Ninguna operación nueva puede
cambiar puntuaciones, ajustes o feedback después. No hay reapertura.

El GET docente sigue permitido tras el cierre, bajo acceso vigente. El alumno
continúa usando los GET T12-B: intento propio sin corrección y corrección propia
solo cuando T12-A/T05 la permiten. Cerrar una nota individual no libera claves,
puntos o feedback mientras haya asignaciones que bloqueen el cierre global,
incluidas futuras, vacías y sin vencimiento. Se conserva la política T05 y su
comprobación positiva una vez alcanzadas todas las condiciones. Los metadatos
de administración no constituyen una proyección alternativa del detalle docente.

## Sucesor y compatibilidad

`t12e-v1.sql` añade exclusivamente `t12e_<mismo hex>`, con el certificador D
completo como prefijo, normalizado a LF. Los dieciséis SQL anteriores, sus
aprobaciones, fingerprints y evidencia no se modifican. Repetir certifica, no
repara; la firma E cubre columnas, relaciones, constraints, funciones, índices
y estados, triggers/enablement y configuración de secuencias, ligada a D.
No se instala DDL al arrancar ni se ejecuta backfill automático.

E continúa el journal START/SAVE/SUBMIT de D y añade GRADE/COMPLETE/ADD/REMOVE/
FEEDBACK/CLOSE. Sus BEGIN/FINISH conservan fotografía previa/sucesora,
propuesta/versión raíz aprobadas, cabezas, referencias, request exacto, derivación,
resultado, medición, auditoría y XID. El scanner congelado B10 contrasta las
filas reales y admite solo los campos de esa operación y sus recálculos
académicos; rechaza eliminaciones, cambios de identidad/autoría, texto del alumno
y cambios laterales. Un docente no puede crear una respuesta faltante.

Para operaciones docentes, la derivación procede del destinatario/episodio de
la entrega, nunca del actor. Los ajustes nuevos reciben ese contexto académico;
su autor sigue siendo el docente. Se conserva la procedencia de intentos y
respuestas C/D sin inventar versiones B10 históricas para PK nuevas.

T12-A/B y escritores C/D prefieren E si está instalado; en ausencia de E
conservan sus rutas anteriores. E hereda frescura, contextos y medición D/C.
Las búsquedas de recibos reconocen sus predecesores sin reescribirlos. El replay
C sigue exigiendo las precondiciones C existentes, incluida ventana de respuesta;
un recibo de guardado no autoriza editar un intento terminal. Los recibos D
pueden recuperarse tras operaciones docentes si el acceso/contexto actual lo
permite. Las operaciones posteriores sobre otro intento autorizado usan E.

La última cabeza explícita F08 prevalece siempre. UNRESOLVED, CONFLICT, STALE,
contexto contradictorio o fotografía alterada fuera del protocolo deniegan
lecturas/escrituras/replays. Un recibo previo nunca restaura la autoridad de una
raíz corregida. El propietario/DBA capaz de falsificar funciones y evidencia
permanece fuera de la frontera certificada del fixture.

## Atomicidad, auditoría, cuotas y concurrencia

Unidad REQUIRES_NEW/READ_COMMITTED, timeout 30 s y lock_timeout 5 s. Conserva
coordinación T12-B/C/D con T05/T10: `evaluations` primero, EXCLUSIVE para
escritura o SHARE para lectura; después las demás tablas del schema en orden
estable SHARE ROW EXCLUSIVE/SHARE, antes de locks de fila evaluación → intento.
Incluye relaciones, sesiones, revocaciones, contextos y fantasmas. READ_COMMITTED
observa el commit anterior después de esperar. No hay mutex solo de JVM.

Negocio, calificación, atribución, contexto, recibo/historia, auditoría y cuotas
confirman o revierten juntos con synchronous_commit=on. Las mutaciones
institucionales no emiten la auditoría best effort de los wrappers legacy.
AuditLogWriter MANDATORY persiste los eventos académicos existentes con IDs
seguros, actor, acción fija y correlación UUID; no textos, puntuaciones, feedback,
motivos, criterios, nombres ni contenido del alumno. Una transición a GRADED
durante GRADE añade el evento obligatorio de revisión completada en la misma
transacción. Un trigger diferido impide confirmar un recibo incompleto.
La evidencia confirmada rechaza UPDATE/DELETE/TRUNCATE.

La medición sucesora reutiliza las fuentes, atribución F08 y límites B9 y los
triggers legacy ACCOUNT/SYSTEM. Preserva atribución institucional de filas nuevas
de todas las raíces C/D/E. No crea otro saldo ni suma dimensiones. Los journals
mantienen su techo técnico independiente de 10000 recibos/64 MiB JSON cada uno;
no se recortan históricos para permitir una operación.

Antes del recibo se adquiere coordinación y se revalida REVIEW_ATTEMPT. Una
clave vincula actor, operación, contexto y contenido exacto. Un replay idéntico
devuelve el resultado durable sin otro ajuste, cierre, evento o medición.
Reutilización incompatible: 409. Los replays siguen sujetos al acceso vigente.
Para devolver CLOSE no se exige de nuevo nota abierta: REVIEW permite leer el
terminal cerrado; únicamente una operación nueva pasa por GRADE_ATTEMPT y
requireNotClosed. Tras mutar se resuelven otra vez permisos/contextos y sesión;
un fallo final revierte también el journal ya terminado.

Las carreras serializan docentes, cierre/edición, revocación y aplicación F08.
Un contendiente puede recibir 503 por lock_timeout; repetir consulta el recibo
confirmado sin duplicación. Una propuesta F08 aprobada antes de una mutación
no incorpora automáticamente su nueva fotografía: puede quedar STALE al aplicar.
El bloqueo global y escaneo completo corresponden al sobre de fixtures; no
certifican capacidad productiva, ausencia universal de deadlocks o DDL concurrente.

## Activación, certificación y recuperación

Por defecto permanecen false `app.evaluations.institutional.enabled`,
answer-save-enabled, attempt-lifecycle-enabled y review-enabled. Se requieren
perfil único test, schema explícitamente aprobado `t03_<32 hex>` igual al
current_schema, DB exactamente chemicallab_t01_test y loopback 127.0.0.1.
La guarda PostgreSQL t11b5_fixture es independiente del flag Java. Las
escrituras E requieren su sucesor instalado explícitamente. No se declaran
aisladas las rutas legacy restantes.

Aplicar el archivo sucesor completo transaccionalmente solo sobre fixture
certificado; repetir tras commit desconocido. Ante deriva se investiga y no se
recalculan ledgers para aceptarla. Restauración incluye t03, t06, t12c, t12d y
t12e juntos, negocio, aprobaciones, cuotas, auditoría y dependencias completas.
El journal no reemplaza WAL ni respalda archivos externos o escritores ajenos.

El ensayo reutiliza pg_basebackup/streaming WAL y pg_verifybackup del arnés
existente; arranca copia en puerto propio, certifica E, compara todas las tablas
de los cinco schemas y comprueba lectura docente/propia y replays D/E
aplicables por HTTP. La compatibilidad de un recibo C original se verifica en
otro caso HTTP. Es recuperación del estado nuevo; no reejecución ni
reatribución del PITR/WAL histórico B11.

## Evidencia de ejecución

Logs, comandos, SHA/parche, XML, backups y hashes se conservan localmente en
`.codex-work/t12e-evidence/`, fuera del PR. Cada ejecución se atribuye a las
fuentes compiladas, sin sumar selecciones a suites completas. Los intentos
fallidos permanecen registrados; compilación no equivale a pruebas aprobadas.

Entorno: Windows, Java 17.0.12+8-LTS-286, Maven 3.9.15 offline, PostgreSQL 18.4,
zona America/Lima y perfil único test. Clúster nuevo y desechable propio:
127.0.0.1:55448/chemicallab_t01_test, rol ficticio t12e_fixture,
max_connections=100, shared_buffers=32MB y max_locks_per_transaction=512.
Los clientes se conectan por HTTP al servidor real y por JDBC a PostgreSQL;
no se utiliza H2 ni se simulan persistencia, calificación o autorización.

| Ejecución de desarrollo | SHA compilado | Resultado y límite |
| --- | --- | --- |
| compile-1 | Base `6d3de4c` + implementación luego conservada en `298d3d8` | BUILD SUCCESS; solo compilación, sin JUnit/JAR verificado |
| selection-1 | `298d3d8` | 1 prueba / 0 fallos / 1 error / 0 omitidas; loopback denegado por sandbox, sin resultado de negocio |
| selection-2 | `298d3d8` | 1/0/0/0; recorrido docente inicial, 3 min 32 s; selección, no suite completa |
| candidate-e | `afdf195` | Interrumpida con exit 1 tras detectar defectos de preparación del arnés; no hay un resultado de suite completa |
| critical-e | `d7ca41f` | 4/1/0/0; 8 min 40 s, BUILD FAILURE; tres casos pasan, falla GET del co-docente administrador por fotografía previa no vigente |
| co-teacher-retry | `4a70d91` | 1/0/0/0; 2 min 30 s, BUILD SUCCESS; la fotografía previa devuelve fresh=false y se prepara una nueva conciliación F08 explícita |
| final-session-retry | `731f1fb` | 1/0/0/0; 3 min 37 s, BUILD SUCCESS; autenticación inicial válida, journal finalizado antes de expirar y rollback completo en la revalidación final; selección, no suite completa |

La preparación inicial de PostgreSQL informó primero conexión denegada por
sandbox y después conexión rechazada, antes de iniciar el clúster fuera de
esa restricción. Son intentos de preparación separados de JUnit. La revisión
automática rechazó inicialmente la publicación por destino/autorización no
verificados. Las comprobaciones de solo lectura acreditaron la petición expresa
del adjunto, el remoto público exacto y permiso ADMIN; el reintento fue aprobado
y se publicó el PR #84 en borrador. No queda bloqueo de publicación.
El arnés original intentaba crear un perfil
docente ya existente y usaba instantes distintos en una misma planificación;
se corrigió antes de las verificaciones siguientes. También se separan las
aserciones sobre SUCCESS de los eventos REJECTED/FAILED que T08 conserva tras
rollback, y se prueba expiración real del plazo de sesión al finalizar la unidad.
La revisión de cuotas distingue AUXILIARY para ajustes y ORIGINAL para el cierre.
Las correcciones del arnés no cambian las reglas de negocio ni los SQL anteriores.

Se conservan logs y artefactos parciales, incluida la recuperación física
alcanzada durante candidate-e; no se presenta ese candidato como aprobado ni
se atribuyen sus resultados parciales al código posterior. Los ERROR deliberados
de auditoría, cuota o certificación se valoran por las aserciones y XML JUnit,
no por su mera presencia en PostgreSQL/logs.

La validación abarca estas doce clases completas:
EvaluationDeliveryAccessDbTest, InstitutionalEvaluationReadDbTest,
InstitutionalAnswerSaveDbTest, InstitutionalAttemptLifecycleDbTest,
InstitutionalAttemptReviewDbTest, EvaluationWindowHttpDbTest, ConcurrencyDbTest,
EvaluationServiceTest, EvaluationOpenQuestionPersistenceDbTest,
EvaluationAccessPolicyTest, TransportInputFilterTest e InputBudgetsTest.
No equivale a ejecutar toda la suite del repositorio. Las ejecuciones aceptadas
y la generación separada del JAR se atribuyen a continuación.

La primera ejecución conjunta, `final-package`, compila el SHA
`1e75120c50623a7cc05de0606e91a982c8d6b1d7`, sin parche en `src` ni `pom.xml`.
Termina con BUILD FAILURE, 253 pruebas / 2 fallos / 1 error / 0 omitidas,
5 h 01 min; nueve suites completas suman 181 casos aprobados. C, D y E no
se consideran suites aprobadas de esa ejecución. Maven se detiene en tests,
antes de generar el JAR.
Los eventos System de Windows registran dos suspensiones del equipo el 15 de
octubre, 19:13:52–21:49:10 y 21:56:31–22:24:16, America/Lima. Coinciden con
el fallo de barrera de C (`contextChangeWaitsForSaveAndMakesSuccessorStale`,
9370 s) y el error HTTP de D
(`historicalInactiveAndUnassignedRowsConsumeTheSameLastOpportunity`, 1706 s,
conexión cerrada por el temporizador de respuesta). Sus resultados se conservan
como fallidos: C 21/1/0/0 y D 27/0/1/0. La correspondencia temporal está en
`power-pause-events.json`, `power-events-d.json` y los dumps de hilos; no se
alteran aplicación, aserciones ni plazos para ocultar esos resultados.

La primera suite E completa termina 24/1/0/0, 2689 s. Falla únicamente
`finalSessionRevalidationRollsBackAlreadyFinishedJournalAndGrade`: recibe el
401 esperado, pero no alcanza `finish_operation`. El fixture cambiaba la
caducidad persistida sin emitir otro JWT. T02 compara exactamente ambas fechas,
por lo que rechazaba el token al entrar, antes de probar la revalidación final.
La corrección de prueba `731f1fb1ea9ac27260b1bfc118056947b83924e0` emite con el
JwtService real un token concordante con la sesión persistida, truncada a
segundos, y comprueba primero autenticación positiva. Exige terminar realmente
el journal mientras la sesión sigue vigente, espera la caducidad efectiva y
mantiene las aserciones de 401, rollback completo, sesión intacta y nueva
operación válida tras otro login. No cambia aplicación, pom ni plazos de HTTP
o transacción; `src/main` y `pom.xml` son idénticos a `1e75120`.

Durante la continuación se utiliza únicamente una solicitud temporal de
actividad del sistema, ligada al proceso de pruebas y con caducidad; no cambia
el plan ni la configuración de energía y no impide una suspensión explícita.
El usuario confirma que mantendrá el equipo encendido para la continuación y
la repetición completa de C/D. La evidencia de esas ejecuciones se conserva
separada, con SHA, comandos, XML y códigos de salida propios.

La repetición `cde-retry`, sobre `731f1fb`, conserva otros dos fallos de las
suites completas C y D: C 21/1/0/0 (1251 s) y D 27/1/0/0 (6507 s).
La ejecución conjunta termina 72/2/0/0, BUILD FAILURE, 2 h 53 min;
solo E es aceptada de esa ejecución.
C recibe 500 en `windowExpiringAfterAuthorizationRollsBackAnswerReceiptCursorAndSuccess`
en vez de 403. El caso iniciaba la ventana de veinte segundos antes de preparar
la instrumentación, y exigía que todavía quedara plazo después de `finish_save`.
El log original no identifica directamente la causa del 500; se conserva como
fallo, sin atribuirlo a una suspensión. La repetición comprueba que se
alcanzó el journal después de autorizar y que la ventana expiró antes de la
revalidación final, incluyendo la expiración natural durante `finish_save`.

En D falla únicamente `institutionalQuotaFailureRollsBackWholeStart` (4591 s):
esperaba 503 y recibe 403. `power-events-cde.json` registra suspensión por
botón/tapa desde 00:21:26 del 16 de octubre y una resincronización del reloj
hasta 01:37:05, unos 4537 s; supera la ventana académica de una hora del fixture.
Una segunda suspensión por botón/tapa dura unos segundos a las 01:37.
La correspondencia temporal explica el rechazo académico antes de alcanzar
la cuota, pero no acredita ese caso de cuota: la suite D completa se repite
y aprueba en `cd-final`.
El usuario confirma después que mantendrá la tapa abierta y el equipo activo.

Ante la demora se revisan hilos Java, actividad y locks de PostgreSQL, memoria
y disco. Las muestras de 02:45–02:46 muestran consultas sucesivas de menos de
dos segundos, cero locks pendientes y cero deadlocks, unos 70 GiB libres y
aproximadamente 2,9 GiB de memoria disponible. El hilo principal prepara los
fixtures SQL; cada `start` hereda los anteriores hasta B1–B11 y C/D/E. Los
promedios activos observados por caso son aproximadamente 33 s (A), 38 s (B),
60 s (C), 73 s (D, descontando la suspensión) y 112 s (primera E). Son medidas
del arnés en este entorno; no son una medida de latencia de las rutas del producto.
Los dumps y muestras se conservan como `diagnostic-user-request-*`.

La suite E completa aceptada de `cde-retry` compila
`731f1fb1ea9ac27260b1bfc118056947b83924e0` y termina el 16 de octubre a las
02:48:32 con 24/0/0/0, 2590,811 s. No convierte en aprobadas las suites C/D
de la misma ejecución ni suma la selección previa de sesión. Su recuperación
física conserva 134 tablas con firmas iguales antes/después y
`pg_verifybackup` correcto; SHA-256 de `signatures.json`:
`03b4343af75db2367da5d8b277ac228e762e32cda46edd1a3b2069c84b09c1f9`.

La corrección exclusivamente de prueba C queda en
`e8a145529837971420a8a40777eb0f2f60f5c009`.
Prepara la instrumentación antes de la misma ventana de veinte segundos,
elimina la precondición artificial de seguir vigente después del journal y
exige fase final alcanzada, reloj de base vencido, 403 y los mismos rollback
de respuesta, recibo, cursor y auditoría. Registra solo fase/plazo/estado HTTP,
sin contenido académico; no cambia reglas, plazos productivos ni aplicación.
El 500 anterior permanece sin una causa interna directamente registrada.

## Verificación aceptada y artefacto

Se cuentan una sola vez doce suites completas: **253 pruebas, cero fallos,
cero errores y cero omitidas**. Las selecciones de diagnóstico no se suman.
Cada fila identifica su ejecución completa aceptada; los intentos fallidos
anteriores permanecen preservados y no se presentan como aprobados.

| Suite completa | Ejecución | SHA compilado | Pruebas / fallos / errores / omitidas |
| --- | --- | --- | --- |
| EvaluationDeliveryAccessDbTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 22/0/0/0 |
| InstitutionalEvaluationReadDbTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 27/0/0/0 |
| InstitutionalAnswerSaveDbTest | cd-final | `e8a145529837971420a8a40777eb0f2f60f5c009` | 21/0/0/0 |
| InstitutionalAttemptLifecycleDbTest | cd-final | `e8a145529837971420a8a40777eb0f2f60f5c009` | 27/0/0/0 |
| InstitutionalAttemptReviewDbTest | cde-retry | `731f1fb1ea9ac27260b1bfc118056947b83924e0` | 24/0/0/0 |
| EvaluationWindowHttpDbTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 10/0/0/0 |
| ConcurrencyDbTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 17/0/0/0 |
| EvaluationServiceTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 88/0/0/0 |
| EvaluationOpenQuestionPersistenceDbTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 5/0/0/0 |
| EvaluationAccessPolicyTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 4/0/0/0 |
| TransportInputFilterTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 3/0/0/0 |
| InputBudgetsTest | final-package | `1e75120c50623a7cc05de0606e91a982c8d6b1d7` | 5/0/0/0 |

Las nueve suites aceptadas de `final-package` suman 181 casos; E aporta 24
de `cde-retry` y las suites C/D aportan 48 de `cd-final` (BUILD SUCCESS,
48 min 25 s). Estas últimas se
ejecutan mediante `clean test`, sin parche pendiente en `src` o `pom.xml`.
El colector comprueba identidad de `src/main` y `pom.xml` entre los tres SHA
y el HEAD de verificación, además del blob exacto de la clase de cada suite.
Las únicas correcciones posteriores del árbol de tests afectan los casos de
sesión E y vencimiento C descritos arriba; no se reatribuyen resultados.

HEAD de verificación: `e8a145529837971420a8a40777eb0f2f60f5c009`.
Árbol `src/main`: `9c0d27e1892ae87e1c68da2d8fe8abff7f14efce`.
Árbol `src/test`: `9d0aa9f4a0ab8f2b6129c6dee3a69ba65bf2dd8c`.

El caso focalizado `window-fixture-retry` termina 1/0/0/0 en 1 min 57 s:
`finished=true`, `remainingMs=5820`, `expiredBeforeRevalidation=true`,
`status=403`. Demuestra expiración posterior a la autorización y rollback,
pero no cuenta como otra suite ni como otro caso de los 253.

Después de aceptar las doce suites se genera el JAR mediante Maven offline
`clean package -DskipTests` (`jar-package-command.txt` conserva argumentos
y `jar-package-sha.txt` el SHA compilado). Este empaquetado pasa en 41,584 s; omite tests
porque las suites completas se verificaron separadamente. No se afirma un
único `clean package` con las 253 pruebas aprobadas.

El JAR se comprueba por CRC de todas sus entradas, manifest de Spring Boot,
clases nuevas, cuatro flags institucionales `false`, los diecisiete SQL
empaquetados iguales a las fuentes y prefijo D completo en E. No se utiliza
este examen de archivo como una prueba de despliegue ni de base real.

JAR SHA-256: `069b5a408a1a75d3586b7b926e489aa1b09d22a838752aa08ff4da116e726a70`.
Archivo de XML aceptados SHA-256: `3afb08670006371da80adc27371bad19f8339f96b29cda64c75d86eed684d81e`.

Artefactos locales: `verified-backend.jar`, `verified-reports.zip`,
`verified-results.json` y `native-summary.json`, bajo el directorio de evidencia.
El manifiesto registra SHA, blob de prueba y hash de cada XML.

| Recuperación nativa | Ejecución | Tablas antes/restauradas idénticas | SHA-256 de firmas |
| --- | --- | --- | --- |
| t12c | cd-final | 130 | `b0b5b6fe8360552c535be161d5581862d609e297f9dfc649003ebc89ea8fff86` |
| t12d | cd-final | 132 | `e48d1adb7bd82e01585018efc86e0504b95dedd6511097aede823f33446ed303` |
| t12e | cde-retry | 134 | `03b4343af75db2367da5d8b277ac228e762e32cda46edd1a3b2069c84b09c1f9` |

Las tres recuperaciones conservan `pg_verifybackup` correcto. Los XML
aceptados cubren repetición/certificación del sucesor y las comprobaciones
HTTP sobre el estado restaurado; la evidencia histórica anterior permanece
sin reejecución o reatribución.

La comparación final conserva 192 archivos protegidos, los 16 E2E, los
dieciséis SQL anteriores, frontend/checkout principal y los HEAD de los otros
29 checkouts (`preservation.json`, sin diferencias). Los E2E se conservan;
no se declaran ejecutados. Los límites de activación y pendientes siguen vigentes.

La publicación es el PR #84 **en borrador**, hacia `develop`:
[PR #84](https://github.com/ChemicalLab-Org/ChemicalLab-Backend/pull/84).
El commit de entrega posterior solo actualiza esta documentación. El SHA
completo realmente publicado se registra después del push en
`published-head.txt`, se contrasta con `headRefOid` del PR y se entrega en el
chat; no se confunde con los SHA de ejecución de la tabla.

Al terminar se detiene únicamente el clúster desechable propio del puerto
55448 y se comprueba que no quedan solicitudes temporales de actividad del
sistema propias de estas ejecuciones. Se conservan datos, backups y evidencia;
no se cambia el plan de energía (`cleanup.json`, `fixture-shutdown.log`).

## Pendientes

T12 integral y etapa 4; consumidores restantes y recuperación histórica F03,
T13, mapas/aprobaciones reales y P08/P12, inventario/capacidad operativos,
SEC-13/VER-01, Linux/SCA y activación productiva. No se altera frontend,
auditoría histórica, documentos/evidencias anteriores ni los 16 E2E. La entrega
no inicia ningún bloque posterior.
