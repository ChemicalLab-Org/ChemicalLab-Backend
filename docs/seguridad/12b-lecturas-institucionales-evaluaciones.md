# T12-B — Lecturas REST institucionales de evaluaciones

15 de octubre de 2026, America/Lima. Base local/remota comprobada por fetch y
`ls-remote`: `fae289519b48c7d3aa814da1d7389ee1f9cde11f`, integración T12-A/#80.
Checkout aislado `.codex-work/t12b-backend`, rama
`feature/t12b-lecturas-institucionales`. Backend principal limpio en develop;
se preserva el trabajo de los demás checkouts. No hay AGENTS.md aplicables en
backend ni ancestros. Sin delegación.

Leídos [T12-A](12a-resolucion-acceso-evaluaciones.md),
[T11-D §7](../arquitectura/11d-cierre-preparacion-modelo.md), contratos vigentes
[T11-A](../arquitectura/11a-modelo-institucional.md),
[T11-C](../arquitectura/11c-balance-cierre-modelo.md),
[T02](02-02-autenticacion-sesiones.md), [T05](02-05-evaluaciones-ventanas.md),
[B3](../arquitectura/11b3-recursos-intentos.md),
[B8](../arquitectura/11b8-revisiones-academicas.md),
[B10](../arquitectura/11b10-conciliacion-contextos.md), el cierre
`Documentos/Seguridad/12a-cierre-resolucion-acceso-evaluaciones.md`,
EvaluationController, DTO, escritores y servicios implicados.

## Rutas y autenticación

Prefijo común:

```text
/api/institutions/{institutionId}/classes/{classId}/evaluation-reads/{contract}/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}
```

`contract` es exactamente `LEGACY` o `NEW`; no son IDs intercambiables.

| Método/sufijo | Acción fijada por servidor | DTO |
| --- | --- | --- |
| GET al prefijo | READ_DELIVERY | Delivery para estudiante propio; Metadata para supervisión |
| GET `/own-attempts/{attemptId}` | READ_OWN_ATTEMPT | Attempt |
| GET `/own-attempts/{attemptId}/correction` | READ_CORRECTION | Correction; 403 mientras esté bloqueada |

Solo GET unitarios. No hay listados, búsquedas, agregados, archivos, revisión
docente, calificación, inicio/respuesta/finalización, frontend o STOMP.

`@AuthenticationPrincipal SessionPrincipal` recibe exclusivamente el principal
producido por el filtro T02 y sus sesiones PostgreSQL. No se reconstruye desde
username, rol, headers propios ni body. Las rutas usan la cadena real de seguridad;
el rol global no selecciona facultades institucionales. Las rutas propias derivan
la cuenta del principal; parámetros de cuenta/acción/proyección no la sustituyen.
El único selector opcional `recipientAccountId`, en GET de entrega, permite
supervisión y pasa por las relaciones institucionales, de clase y destinatario
vigentes de T12-A. No autoriza cargar contenido académico de otra persona.

T12-A conserva su resolver único, decisiones, namespaces, revisión exacta,
vigencias y B10 actual/fresco. GLOBAL no significa público; UNRESOLVED, CONFLICT,
padre STALE, contexto ausente o relación contradictoria no tienen fallback.

Respuestas correctas: 200 con `Cache-Control: no-store`. Activación incompleta:
404. Relaciones/entorno DB no autorizados: 403 genérico. Sesión inválida/revocada:
401; contraseña temporal: 403 PASSWORD_CHANGE_REQUIRED del filtro T02.
Fallo de persistencia, timeout, transacción o proyección: denegación sin datos;
los fallos de almacenamiento/proyección de esta unidad se traducen a 503.

## DTO y matriz de visibilidad

Todos los DTO son records de `InstitutionalEvaluationRead`. No retornan entidades,
JsonNode, mapas de filas SQL ni snapshots completos. `Scope` contiene únicamente
contrato e IDs comprobados de institución/clase/evaluación/entrega/revisión.
`observedAt` es el instante PostgreSQL usado para decidir.

| Proyección | Campos permitidos | Exclusiones |
| --- | --- | --- |
| Metadata | scope, projection=SUPERVISION_METADATA, observedAt | Sin título libre, preguntas, respuestas, notas ni payload |
| Delivery | scope, título/descripción/instrucciones/tema, duración, herramientas, detección de salida, modo/aleatoriedad, preguntas activas, observedAt | Sin expectedAnswer, explicaciones, criterios privados, correct ni feedback |
| Question/Option | id, questionText, questionType, points posibles, orderIndex, required, options; opción: id, optionText, orderIndex | Sin clave ni objetos adicionales/anidados del JSON |
| Attempt | scope, attemptId, status, attemptNumber, questionOrder, currentQuestionIndex, answers, observedAt | Sin score/maxScore/finalScore, corrección, feedback, oportunidades o permiso de avance/escritura |
| Answer | questionId, selectedOptionId, answerText propio | Sin campos desconocidos de objetos de respuesta ni corrección |
| Correction | scope, attemptId, score/maxScore/finalScore persistidos disponibles, overallFeedback, answers, observedAt | Nunca expectedAnswer/criterios privados ni ajustes/historia crudos |
| AnswerCorrection | pregunta/tipo/texto, selección/texto propio, correct, pointsAwarded, teacherFeedback de abierta revisada, correctOptionText de alternativa, explanation | Sin revisión docente, autor privado, expectedAnswer o objetos adicionales |

En NEW, solo se proyecta el payload de la revisión PUBLISHED exacta de la entrega
validada. El ancla Evaluation DRAFT y la revisión sucesora no aportan contenido.
Se recorren explícitamente resource/questions/options y los campos públicos de
las respuestas; nunca se copia un objeto anidado desconocido. Se validan tipos
escalares. OPEN_TEXT admite texto escalar B8 o `answer_text` explícito; alternativas
admiten ID escalar o `selected_option_id`, de una opción de esa pregunta. No se
serializa el JSON original de respuestas. B8 conserva solo nota global: no se
inventan puntos, corrección automática ni feedback por pregunta; esos campos van
null. `currentQuestionIndex` es null porque la simulación no tiene cursor de avance.

LEGACY conserva la autoridad del motor original: publicación, preguntas/opciones
activas y corrección proceden de tablas originales; la revisión B3 se identifica
como snapshot técnico, sin transformarla en publicación académica. No se llama
a EvaluationService.getAttempt/getStudentAttemptResult: pueden finalizar,
reconstruir órdenes o completar notas al consultar. Esta lectura no lo hace.
Un orden legacy ausente obtiene solo un fallback determinista en memoria,
sin persistencia ni aleatorización. Se conserva el orden guardado y el índice;
no se añaden permisos para responder/avanzar. Preguntas/opciones de entrega y
preguntas de corrección mantienen visibilidad activa y orden por orderIndex.
Se rechazan relaciones de respuesta/opción de otro recurso/pregunta.

## Unidad de consistencia y escritores

`EvaluationDeliveryAccessService.readCoherently` es una composición interna
package-private. No recibe ni reutiliza un ALLOW anterior. En una transacción
REQUIRES_NEW, READ_COMMITTED, obtiene bloqueos SHARE de **todas las tablas del
schema ficticio** y después revalida sesión, ejecuta el mismo resolver T12-A,
lee datos y materializa íntegramente los records. Revalida sesión nuevamente
antes de terminar. El controlador recibe solo DTO materializados, sin lecturas
perezosas posteriores al commit. `decide` conserva su contrato independiente.

SHARE bloquea INSERT/UPDATE/DELETE/TRUNCATE concurrentes: incluye revocaciones
T02, membresías/grants/períodos/clases/matrículas/asignaciones B1/B2/B6,
fuentes y versiones/antecedentes B10, publicaciones/entregas/intentos B8 y
escritores académicos originales T05/T10. Cubre también inserciones fantasma
de otras asignaciones, clases o revisiones de la raíz y las dependencias de
frescura B10. No se requieren cambios a estos escritores ni al SQL histórico.
Identificadores de tabla/schema se obtienen entrecomillados desde PostgreSQL,
sin concatenar selectores del cliente en SQL. DDL concurrente queda fuera del
protocolo: se ensaya una cadena de schema congelada ya instalada.

El punto de consistencia es la observación SQL después de adquirir todos los
bloqueos. Una misma sentencia observa reloj DB y cierre global B8; vigencias,
publicación efectiva y T05 Java usan ese mismo instante. READ_COMMITTED evita
retener una fotografía anterior a un escritor que confirmó mientras se esperaba
el lock. El intervalo bloqueado mantiene juntos autorización y datos. No hay
mutaciones de negocio pese a que PostgreSQL exige una transacción no read-only
para LOCK SHARE. Timeout de unidad: 15 s; espera de lock: 5 s, fallo cerrado.

Resultados permitidos de carreras:

1. Si la lectura adquiere los locks primero, devuelve una proyección completa
   autorizada del estado anterior, o la denegación anterior. El escritor confirma
   después; la siguiente petición vuelve a resolver. Un cambio de fuente puede
   exigir conciliación B10 explícita antes de volver a autorizar.
2. Si el escritor confirma antes de adquirir los locks, la lectura observa ese
   cambio y autoriza/deniega según el estado nuevo. Una revocación implica 401;
   permiso retirado/contexto STALE implica 403.
3. Ante bloqueo prolongado, deadlock o error, no se publica un DTO parcial.

Una respuesta ya materializada puede transmitirse después de que confirme una
revocación posterior a su punto de consistencia. No promete cancelar bytes de
una solicitud ya autorizada. **Bloqueos amplios exclusivos del ensayo ficticio:**
pueden demorar escritores, producir contención/deadlock y limitar capacidad;
no certifican concurrencia, inventario de escritores ni rendimiento productivos.

## Activación y conservación

Desactivadas por defecto en application.properties. Para un ensayo autorizado
se requieren simultáneamente:

- `app.evaluations.institutional.enabled=true`.
- Único perfil activo `test`.
- `app.evaluations.institutional.fixture-schema=t03_<32 hex>` aprobado explícitamente
  y exactamente igual al current_schema de la conexión.
- Comprobación SQL independiente de DB `chemicallab_t01_test`, dirección servidor
  `127.0.0.1` y forma del schema; además se conserva `t11b5_fixture()`.

Una bandera por sí sola nunca habilita datos reales. No hay instalador SQL,
DDL/backfill productivo, datos escolares ni activación de despliegue. El rol/DB
de ensayo sigue siendo la frontera de confianza; no se certifica defensa contra
un DBA que altere funciones, datos o catálogo. No se proporciona modo productivo.

T05 conserva el cierre global de todas las asignaciones legacy y de todas las
entregas/clases/revisiones NEW de la raíz, incluso futuras, vacías y sin plazo.
Cerrar una entrega o agotar intentos no libera corrección. Se requiere además
intento propio iniciado y GRADED/FINAL con nota individual cerrada. No se crean
intentos, consumos, presupuestos, revisiones, entregas, notas, cuotas, historia
ni SUCCESS por leer. B3 no pasa a ser un saldo vivo; F09 y la autoridad legacy
se conservan. F03 histórico tras baja sigue bajo T05 y corresponde a T13;
una denegación de contexto vigente aquí no redefine ese derecho.

**Las rutas legacy `/api/evaluations/**` conservan su contrato actual y todavía
no están cubiertas por el aislamiento institucional de T12-B.** Esto incluye
sus GET con reconciliación histórica; ninguna ruta nueva deriva hacia ellos.

## Verificación y evidencia

Pruebas `InstitutionalEvaluationReadDbTest`: HTTP real/T02 sobre PostgreSQL
desechable y SQL congelado B1–B11. Incluyen ambas direcciones A/B, cuenta común,
roles/grants distintos, selectores manipulados, namespaces e intentos ajenos,
sesiones/temporal, vigencias, B10 ausente/incierto/STALE y revisión sucesora;
JSON completo y campos anidados con marcadores ficticios únicos; T05 bloqueado
y desbloqueado legítimamente; activación; ausencia de cambios en firmas de negocio,
cuotas, historia, SUCCESS y secuencias; regresión HTTP de rutas legacy.

Las barreras de carrera detienen una lectura HTTP después de ALLOW y de cargar
contenido NEW, manteniendo sus locks mientras otra conexión intenta cambiar
contexto, grant o sesión. PostgreSQL pg_stat_activity acredita la espera real.
Otra barrera prueba cierre académico durante una corrección bloqueada; una
conexión escritora que confirma primero prueba también la observación nueva.
Los spies solo colocan barreras, sin simular permisos, datos, locks ni escritores.

Resultados definitivos y SHA de suite completa/empaquetado: pendientes de la
ejecución final. Las selecciones y fallos diagnósticos se conservan separados
en `.codex-work/t12b-evidence/`; no se suman a una suite completa ni se atribuyen
al SHA final. El directorio local de evidencia y la DB no forman parte de la PR.

## Superficies pendientes y entrega

T12 integral/etapa 4, adopción de los demás consumidores/escritores, rutas legacy,
listados/agregados, archivos, revisión/calificación, mutaciones de intentos,
frontend/STOMP, T13/F03, P08, SEC-13, VER-01, Linux/SCA, privacidad y operación
siguen pendientes. No se modifican esquemas, SQL históricos, auditoría original,
documentos previos, frontend institucional ni los 16 E2E.

Entrega con commits y PR backend **en borrador hacia develop**, adjunta al chat,
detenida para revisión. Sin merge, despliegues, datos reales, rotaciones o
activación productiva.
