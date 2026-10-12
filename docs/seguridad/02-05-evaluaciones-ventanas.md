# T05 — Exposición de resultados y ventanas de evaluaciones

Implementación del 11 de octubre de 2026, America/Lima. Para revisión, sin merge,
despliegue, datos escolares, migración productiva ni rotaciones. Alcance SEC-11 y
SEC-12; conserva T01–T04. No implementa instituciones, invitaciones, privacidad o T06.

Bases `origin/develop`, verificadas mediante fetch: backend
`ec9531b2c7ccaab86d0e9d444f83f6c9261dcb72`; frontend
`f613e90b4580b392fbbf9fe2cf3f7453f0704c24`. Checkouts aislados, rama
`security/t05-evaluaciones-ventanas` en ambos repositorios. El frontend previo sigue
en `feature/validaciones-datos-institucionales`, con `e2e-selenium/` intacto. La
auditoría original no se modificó: SHA-256
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.

## Inventario previo y matriz de exposición

Se recorrieron los mappings actuales, DTO, servicios, repositorios, entidades,
configuración de seguridad y consumidores Angular. Las líneas de la auditoría
eran anteriores. Todas las representaciones REST accesibles al estudiante son:

| Método/ruta bajo `/api/evaluations` | DTO | Programada | Ventana abierta | Ventana cerrada / resultado |
|---|---|---|---|---|
| GET `/student` | `StudentEvaluationResponse[]` | Programación, configuración pública, cantidad de preguntas; `canStart=false` | Estado, intentos usados, `canStart`, ID del intento en progreso | Vencida y progreso; archivo/desactivación excluyen la tarjeta |
| GET `/student/{id}` | `StudentEvaluationDetailResponse` | Metadatos; `questions=[]`, `questionsVisible=false` | Preguntas y opciones sin clave, explicación ni respuesta esperada | `questions=[]`; recurso archivado/desasignado rechaza acceso directo |
| POST `/student/{id}/attempts` | `AttemptResponse` | Rechaza sin crear intento/evento | Propietario, asignación y máximo de intentos; solo datos operativos y respuestas propias | Rechaza sin crear intento |
| GET `/student/attempts/{id}` | `AttemptResponse` | Intentos heredados no entregan respuestas ni orden de preguntas antes del inicio | Respuestas propias sin corrección | Reconcilia IN_PROGRESS cerrándolo con lo guardado; terminal conserva historial propio, aplica política de corrección |
| POST `/student/attempts/{id}/answers` | `AttemptResponse` | Rechaza | Guarda respuesta; ONE_BY_ONE valida pregunta actual y avanza bajo la misma política | Rechaza sin cambiar respuestas, índice, nota ni estado |
| POST `/student/attempts/{id}/submit` | `AttemptResponse` | No incorpora respuestas nuevas | Envío normal: admite respuestas en ALL_AT_ONCE y valida abiertas obligatorias | Puede finalizar, ignora respuestas del cuerpo, conserva guardadas; no exige completar abiertas faltantes |
| POST `/student/attempts/{id}/exit` | `AttemptResponse` | No incorpora respuestas | Finaliza y consume intento usando solo lo guardado | Sigue permitiendo finalizar; mismo filtro de corrección |
| POST `/student/attempts/{id}/events` | `AttemptEventSummaryResponse` | No contiene preguntas/corrección | Trazabilidad propia; conserva allowlist y throttling | No permite modificar respuestas; terminal rechazado por controles existentes |
| GET `/student/results` | `StudentResultSummaryResponse[]` | Historial propio, sin notas restringidas | Estado, bloqueo y contadores agregados; no notas restringidas | Puntaje, porcentaje y nota ajustada solo con oportunidades cerradas y nota cerrada |
| GET `/student/attempts/{id}/result` | `StudentAttemptResultDetailResponse` y `StudentAnswerResultResponse[]` | Sin preguntas antes del inicio | Sin corrección mientras otra estudiante pueda responder o reintentar | Con grupo cerrado y nota abierta: respuestas propias/puntos posibles, sin corrección; con nota cerrada/GRADED: corrección autorizada |

No existe otra ruta de avance o historial de intentos: el avance secuencial usa
`/answers`; historial y agregados estudiantiles usan `/results`. Se verificaron
también `/api/usage-metrics/events`, conceptos, herramientas químicas y pizarras:
no retornan preguntas, notas o corrección de evaluaciones. Estadísticas de docente,
supervisión y registro individual de uso son rutas por rol docente/administrador;
no son accesibles al estudiante. Autenticación, sesiones, revocación y propiedad
se mantienen; ID de intento o asignación enviado por cliente no concede acceso.

## Política de corrección

`EvaluationAccessPolicy` concentra ventana, admisión, escritura y exposición;
`EvaluationService.computeReviewAvailability` resuelve el cierre definitivo de **todas**
las asignaciones activas, incluso vacías o futuras. Una sección
cerrada no libera respuestas si otra sección todavía tiene oportunidad de rendir.
Un inicio futuro cuenta como oportunidad pendiente. Con múltiples intentos,
terminar el primero no agota las oportunidades del alumno.

Se puede revisar cuando ninguna oportunidad vigente queda abierta: todas las
asignaciones activas vencieron, o la evaluación/asignaciones se cerraron por
archivo/desactivación. Las fechas opcionales no crean cierres ficticios; una
asignación sin dueAt sigue abierta hasta un cierre administrativo explícito.
Los contadores finished/pending conservan su sentido de intentos agotados, aunque
la ventana haya cerrado para alguien que aún no terminó. El motivo histórico
`ALL_ASSIGNED_STUDENTS_FINISHED` se conserva como nombre histórico para cierre
administrativo; no implica que todas tengan una respuesta enviada. Agotar los
intentos del padrón actual **no libera corrección**, ni siquiera con pending=0.

La corrección requiere además `GRADED` y `gradeClosed=true`. Se aplica al DTO general
de inicio/consulta/guardado/envío/salida y al detalle/listado de resultados. Antes
del desbloqueo, correct, pointsAwarded, score, porcentaje, nota final, explicaciones,
clave y feedback son null o no se envían. Un envío automático no evade el filtro.
La nota parcial de una abierta y los ajustes permanecen restringidos. Los puntos
posibles de preguntas son configuración pública; no significan puntos obtenidos.
`canViewDetailedFeedback` exige ambas condiciones, no solo cierre grupal.

Para impedir reabrir oportunidades después de exponer resultados: con intentos
existentes no se aumenta maxAttempts ni cambia duración/modo; con resultados
terminales no se crean nuevas asignaciones. Para una nueva convocatoria se crea
otra evaluación. Las asignaciones deben completarse antes del primer envío/salida.

## Semántica temporal y finalización

- Ventana `[startAt, dueAt)`: inicio inclusivo, cierre exclusivo. Fechas opcionales:
  startAt ausente elimina espera inicial, dueAt ausente elimina cierre por fecha.
  Con ambas presentes, dueAt debe ser estrictamente posterior; igualdad/inversión
  rechazan antes de persistir. Intervalos heredados inválidos fallan cerrados.
- Plazo para responder: menor de dueAt y startedAt + duración, sin gracia. La
  antigua gracia de 60 s permitía modificar respuestas tras desbloquear revisión
  por dueAt; se elimina. El contador del navegador presenta el plazo del servidor.
- Archivo, evaluación inactiva o asignación desactivada cierran nuevas admisiones
  y escritura incluso en intentos en progreso. Cambio de grupo tampoco conserva
  permiso para escribir con la asignación antigua. T02 sigue revocando esa sesión.
- Guardar y avanzar rechazan al cerrar sin mutación. Enviar/salir siguen disponibles
  para el propietario: califican las respuestas ya guardadas y crean filas vacías
  para la revisión de abiertas que falten. Ignoran el cuerpo tardío y omiten el
  requisito de abiertas obligatorias; no dejan un intento imposible de finalizar.
- GET del intento reconcilia un IN_PROGRESS que ya no puede escribir; esto es una
  finalización idempotente por recuperación, no una aceptación de respuestas nuevas.
  No hay un job que cierre todas las filas inmediatamente; una fila aún IN_PROGRESS
  vencida no habilita escritura ni bloquea revisión por sí sola. El historial propio
  sigue disponible después del archivo/desactivación. Un intento heredado sin
  asignación usa la asignación activa actual de su sección, si existe; en otro caso
  permite finalizar con lo guardado, sin nuevas respuestas.

`EvaluationAccessLock` serializa decisiones/mutaciones sobre la fila de evaluación
y luego el intento, refrescando los estados tras adquirir el bloqueo. Inicio,
guardar/avanzar, envío/salida, lectura de resultados, revisión docente, archivo,
edición y asignación coordinan ese bloqueo PostgreSQL. Los listados bloquean las
evaluaciones en orden ascendente de ID. Así guardar/enviar/archivar no dejan una
respuesta modificada después de la calificación ni una revisión basada en un
estado anterior al bloqueo. No se amplía a la campaña general de concurrencia T10.

## Reloj, fechas existentes y compatibilidad

Bean inyectable `evaluationClock`: reloj del módulo, explícitamente America/Lima.
Las decisiones usan su Instant convertido a LocalDateTime académico. No se toca
el reloj de sesiones/JWT/T04. Pruebas reemplazan solo la política mediante una
configuración de test; no hay endpoint productivo para manipular el reloj.

Se conservan LocalDateTime y columnas timestamp sin zona, sin migración de esquema.
Los valores existentes se interpretan como horas académicas de Lima **tal como
están guardados**; no se convierten ni corrigen en silencio. Si un despliegue antiguo
generó horas de eventos con otra zona, T05 no puede inferir esa zona: cualquier
reconciliación histórica requiere inventario y decisión separados. Las fechas de
programación mantienen exactamente el horario introducido por el docente.

Angular envía datetime-local ISO sin offset (`2030-06-03T10:00` o con segundos),
que significa 10:00 en Lima. Jackson serializa LocalDateTime sin offset; su ajuste
America/Lima no controlaba LocalDateTime.now(). Ahora el servicio fija startedAt,
assignedAt, answeredAt, submittedAt, gradedAt, cierres, revisión, eventos, ajustes
y fechas de evaluación con el reloj inyectado. Callbacks JPA respetan timestamps
ya fijados y su fallback para escrituras directas usa Lima explícitamente; una
calificación ya no sobrescribe answeredAt con la hora del equipo.

`academic-time.util.ts` interpreta fechas sin offset con `-05:00`; Intl presenta
America/Lima explícitamente. El navegador usa serverNow para presentar la cuenta
atrás y answerDeadlineAt para incluir el cierre de asignación; canStart y estados
vienen del servidor. Campos adicionales son compatibles con consumidores antiguos;
el frontend nuevo necesita este backend. No hay despliegue incluido en la entrega.

Angular presenta Programada/Disponible/Vencida y revisión bloqueada/disponible.
Actualizar estado consulta otra vez detalle/listado. La consulta incluye el ID del
intento en progreso para reanudar sin depender del almacenamiento de otro equipo;
`/evaluations?attempt=<id>` soporta recarga y acceso directo con autorización HTTP.
Un rechazo temporal recupera y finaliza el intento, libera el modo examen y elimina
preguntas del estado local. Valores ausentes conservan «—» y no generan cero, una
marca de incorrecto o una nota inventada. La pantalla de rendición se carga de forma
diferida para mantener el presupuesto original del bundle inicial.

## Reproducciones y verificación

Antes de cambiar EvaluationService se ejecutaron dos pruebas sobre la base actual.
SEC-11 devolvió score=3 donde se exigía null; SEC-12 devolvió una pregunta antes del
inicio. Dos fallos de aserción, sin errores; log local `t05-reproduction.log`. Las
pruebas ahora pasan. La primera reproducción fue de servicio con repositorios
simulados; las verificaciones siguientes incluyen el stack HTTP completo y SQL real.

| Evidencia | Resultado |
|---|---|
| Backend clean package, incluyendo T01–T04 | 499 pruebas, 0 fallos, 0 errores, 0 omitidas; JAR Spring Boot generado |
| `EvaluationAccessPolicyTest` | Límites ±1 ns, start inclusivo/due exclusivo, opcionales, rangos inválidos, duración mínima, sin gracia, reloj con zona Tokio interpretado en Lima, exposición final |
| `EvaluationWindowHttpDbTest`, 10 escenarios HTTP sobre PostgreSQL 18 desechable | Dos alumnas/secciones, rutas directas, rechazo ajeno/no asignado, intento no creado al rechazar, respuesta/índice intactos, calificación parcial, nota abierta/cerrada, ajustes, intentos múltiples, archivo/desactivación, recuperación, avance secuencial, carrera guardar/enviar/archivar, futuras vacías y padrón mutable |
| HTTP con JVM `-Duser.timezone=Asia/Tokyo` | Los mismos 10 escenarios, 0 fallos, errores u omisiones; límites académicos de Lima independientes del equipo |
| Angular con fixture STOMP real | 129 pruebas, 0 fallos u omisiones; DOM/HTTP de T05 y regresiones T02–T04 |
| Build Angular producción | Bundle inicial 943.56 kB; presupuestos originales conservados y pantalla de evaluación diferida. Persisten avisos SCSS y del entorno de tests previamente presentes |
| Navegador real, localhost con API desechable | Programada sin inicio; disponible exactamente al inicio; respuestas objetiva/abierta guardadas; URL directa y recarga conservan respuestas; selección al dueAt rechazada y recuperación finaliza; revisión bloqueada hasta la otra sección; revisión disponible tras cierre de nota y plazo final |

El navegador mostró, tras el intento de cambio tardío, la alternativa **guardada
antes del cierre** y nota 6/20 (abierta 3/6, objetiva 0/4); la selección tardía no se
incorporó. Capturas locales `t05-review-locked.png` y `t05-review-available.png`.
No hubo errores de consola en esa comprobación. Las evidencias locales no se suben
porque las fixtures/logs pueden contener tokens de pruebas; solo se versionan código
reproducible, tests y esta nota, revisados antes de publicar en repositorios públicos.

Reproducir con PostgreSQL **nuevo y desechable**, ligado a loopback, base ficticia
creada `chemicallab_t01_test`, usuario ficticio t05_test (PowerShell):

```powershell
.\mvnw.cmd clean package '-Dspring.profiles.active=test' `
  '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55475/chemicallab_t01_test' `
  '-Dspring.datasource.username=t05_test' '-Dspring.datasource.password=fictitious'
```

La guarda de integración exige loopback y ese nombre test, crea un esquema aleatorio
y lo elimina. `EvaluationBrowserFixture` y `WhiteboardBrowserFixture` son opt-in,
no tests productivos: escribir archivos señal termina sus procesos y elimina sus
esquemas; tienen límite automático de 30 minutos. Para T03 Angular usar su JSON
local como `T03_LIVE_FIXTURE`, luego `npm test -- --watch=false` y `npm run build`.

## Límites y pendientes

### Corrección del hallazgo de padrón mutable antes del merge

Se reprodujo sobre backend PR #59 HEAD `6cd9e49a58fef1a99c332dc83da98d5c3cc4f244`
(frontend #81 HEAD `4163d881dd1cb6925868ed24d64f6ef067b6ebed`): A agotó su único
intento, B futura no tenía alumnas activas y el DTO general exponía score=7 y
correctionVisible=true. También ocurría con una asignación disponible cuyo padrón
había terminado. Activar/incorporar una alumna ocultaba otra vez la revisión, después
de haberla revelado. Antes de corregir se ejecutaron dos pruebas de servicio y dos
HTTP: 2 fallos de aserción en cada ejecución, 0 errores/omisiones. Logs locales
`t05-roster-reproduction.log` y `t05-roster-http-reproduction.log`, no versionados.

Se elige **esperar al cierre definitivo de todas las asignaciones**, eliminando la
liberación anticipada por finalización grupal. No se introduce estado persistente,
tabla, migración, gancho en altas/activaciones/cambios de sección ni modelo institucional.
Los contadores de alumnas activas permanecen solo como datos informativos. Las
asignaciones y límites del examen gobiernan admisión/escritura y revisión bajo los
bloqueos ya existentes. Los resultados terminales siguen impidiendo crear nuevas
asignaciones; no existe una operación de reactivación ni extensión de fechas de
asignaciones cerradas. No es necesario añadir una carrera de cierre persistente/inicio:
no se añadió ese mecanismo; se conserva la prueba de guardar/enviar/archivar.

| Caso | Antes del cierre definitivo | Después del cierre definitivo |
|---|---|---|
| B futura y vacía, A con intentos agotados | Corrección bloqueada; B sin preguntas ni inicio antes de startAt | Solo se libera al vencer B o cerrarse administrativamente |
| Grupo disponible con todos sus alumnos actuales terminados | Corrección bloqueada aunque finished=assigned y pending=0 | Nota/corrección requiere además gradeClosed y GRADED |
| Alta/reactivación/cambio de sección | Puede rendir si ventana y reintentos lo permiten; ninguna corrección previa expuesta | Preguntas ocultas, inicio rechazado sin crear intento; revisión permanece disponible |
| Asignación sin dueAt, padrón terminado/vacío | Permanece bloqueada indefinidamente | El docente debe desactivar las asignaciones restantes o archivar |
| Intento IN_PROGRESS que cruza el cierre | Puede guardar dentro de su plazo | Rechaza guardar/avanzar; envío/GET conservan respuestas y finalizan; nota abierta permanece oculta |

Tres escenarios nuevos en `EvaluationWindowHttpDbTest` comprueban esas transiciones
con SQL de fixture para padrón ficticio y autenticación/consultas/mutaciones de
evaluaciones por HTTP real: futura vacía con reactivación/alta; disponible terminada
con altas, reactivación y cambio de sección; sin dueAt con cierre por desactivación.
Verifican intento general, detalle/listado de resultados, detalle/listado de
evaluaciones, inicio, guardado y recuperación, y preservación de respuestas/notas.
Las pruebas anteriores mantienen fechas exactas, duración sin gracia, reintentos,
abiertas, ajustes, cierre de nota, propiedad y carrera concreta. Angular conserva
los estados del servidor y deja de prometer revisión por finalización del padrón.
Su prueba DOM comprueba reviewLocked con 100% de intentos agotados, sin inventar
nota ni registrar revisión efectiva, y el mensaje de cierre explícito sin dueAt.

La suite completa de 499 pruebas se ejecutó con la regla de cierre definitivo. En
la revisión final se sincronizó además `canViewDetailedFeedback` del resumen con
la misma condición de nota cerrada/GRADED del detalle. Después de ese ajuste se
ejecutó `package -Dtest=EvaluationServiceTest,EvaluationAccessPolicyTest,EvaluationWindowHttpDbTest`:
102 pruebas de evaluaciones y empaquetado del JAR final, sin omisiones. La repetición
Tokio incluye los casos individuales de reactivación y cambio de sección. Logs locales
`t05-roster-backend-full.log`, `t05-roster-final-package.log`,
`t05-roster-tokyo-http.log` y `t05-roster-frontend-default.log`.

Una ejecución Angular paralela al backend abortó por salida inesperada de un worker;
otra con workers limitados falló en el mock existente de T03. La ejecución final,
con configuración habitual y backend ya terminado, pasó las 129 pruebas y STOMP
real. No se deshabilitaron tests ni se alteró el código de T02–T04 para forzar ese
resultado. Permanecen los avisos previos de mock hoisted/SCSS/polyfills.

La regla evita exposiciones nuevas por padrón mutable; no puede deshacer una
corrección que alguien hubiese leído antes. Esta corrección sigue en etapa 2,
seguridad actual, dentro de los PR existentes: no implementa T06 ni campaña T10.

T05 está acotada a la instalación actual de un colegio. No certifica producción,
infraestructura ni zona histórica de todos los timestamps; no ejecutó carga sostenida,
campaña T10, migraciones/rotaciones, ni operaciones en bases reales. Los bloqueos por
evaluación priorizan coherencia y pueden serializar peticiones de un examen concurrido;
medir capacidad queda para la etapa acordada de concurrencia/operación. Una solicitud
que ya pasó la autenticación puede terminar durante una revocación concurrente,
como el contrato T02 existente. No quedan pruebas de aceptación de T05 diferidas a
datos escolares o a un despliegue manual. PRs de backend/frontend hacia develop
se enlazan mutuamente en sus descripciones; no se hizo merge.
