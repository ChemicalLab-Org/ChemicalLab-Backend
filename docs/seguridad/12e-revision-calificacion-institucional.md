# T12-E — Revisión y calificación docente institucional LEGACY

15 de octubre de 2026, America/Lima. Base local y remota `develop` comprobada:
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

Resultados definitivos, JAR, conservación y SHA publicado se incorporan al
terminar las ejecuciones y antes de entregar el PR en borrador.

## Pendientes

T12 integral y etapa 4; consumidores restantes y recuperación histórica F03,
T13, mapas/aprobaciones reales y P08/P12, inventario/capacidad operativos,
SEC-13/VER-01, Linux/SCA y activación productiva. No se altera frontend,
auditoría histórica, documentos/evidencias anteriores ni los 16 E2E. La entrega
no inicia ningún bloque posterior.
