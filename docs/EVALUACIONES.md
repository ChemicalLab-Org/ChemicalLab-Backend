# Evaluaciones

Módulo del backend que permite a los docentes crear evaluaciones sobre temas de química
con preguntas de **alternativa única** y de **respuesta abierta**, agregarles preguntas
(y alternativas cuando corresponde), publicarlas y asignarlas a los grados y secciones
que tienen a cargo. Los estudiantes resuelven únicamente las evaluaciones publicadas que
corresponden a su grado y sección.

> Estado actual: además de crear, publicar y rendir evaluaciones, el módulo ya
> **califica automáticamente** las preguntas de alternativa única al enviarse y soporta
> **preguntas abiertas con calificación manual** del docente. Un intento con preguntas
> abiertas sin calificar queda en estado `PENDING_MANUAL_REVIEW` (puntaje parcial, nota
> no definitiva) hasta que el docente revise cada respuesta y asigne puntaje; entonces
> pasa a `GRADED`. Expone los **resultados/calificaciones** para el docente (resultados
> de su evaluación, detalle de cada intento y bandeja de revisión manual) y para el
> estudiante (sus calificaciones y el estado de revisión). Quedan fuera de alcance la
> calificación automática de texto, las rúbricas complejas, los archivos como respuesta,
> la detección de plagio/similitud, la exportación a Excel/PDF y las estadísticas avanzadas.

## Propósito del módulo

- Que el docente arme evaluaciones y las mantenga como borrador hasta estar listas.
- Que el docente publique y asigne una misma evaluación a una o varias secciones.
- Que el estudiante vea y resuelva solo lo publicado y asignado a su sección, sin
  conocer las respuestas correctas antes de enviar.
- Que el administrador pueda consultar todas las evaluaciones si se requiere.

## Entidades principales

### Evaluation
Evaluación creada por un docente.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `title` | Título (obligatorio) |
| `description` | Descripción (opcional, TEXT) |
| `instructions` | Instrucciones (opcional, TEXT) |
| `topic` | Tema (opcional) |
| `status` | Estado (`EvaluationStatus`: `DRAFT`, `PUBLISHED`, `ARCHIVED`) |
| `maxAttempts` | Intentos permitidos por estudiante (1 a 10) |
| `timeLimitMinutes` | Límite de tiempo en minutos (opcional, 1 a 240) |
| `allowChemicalCalculator` | Si el estudiante puede usar la herramienta de apoyo químico durante el intento (por defecto `false`) |
| `allowPeriodicTable` | Si el estudiante puede consultar la tabla periódica durante el intento (por defecto `false`) |
| `trackTabExit` | Si se detectan y registran salidas de pestaña/pérdida de foco durante el intento (por defecto `false`) |
| `questionDisplayMode` | Modo de presentación de preguntas (`QuestionDisplayMode`: `ALL_AT_ONCE`, `ONE_BY_ONE`; por defecto `ALL_AT_ONCE`) |
| `randomizeQuestions` | Si el orden de preguntas se aleatoriza por intento (por defecto `false`) |
| `createdByTeacher` | Docente autor (`TeacherProfile`) |
| `active` | Marca de actividad |
| `createdAt` / `updatedAt` | Auditoría de fechas |

### EvaluationQuestion
Pregunta de una evaluación. Puede ser de alternativa única (`MULTIPLE_CHOICE`, con sus
alternativas) o de respuesta abierta (`OPEN_TEXT`, sin alternativas y con calificación
manual).

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `evaluation` | Evaluación a la que pertenece |
| `questionText` | Enunciado (obligatorio, TEXT) |
| `questionType` | Tipo (`QuestionType`: `MULTIPLE_CHOICE`, `OPEN_TEXT`) |
| `points` | Puntaje de la pregunta (mínimo 1) |
| `orderIndex` | Orden de presentación |
| `explanation` | Explicación opcional (TEXT) |
| `expectedAnswer` | Solo `OPEN_TEXT`: respuesta esperada o criterio de corrección, **visible solo para el docente** (TEXT, opcional, máx. 3000) |
| `required` | Si la pregunta es obligatoria (por defecto `true`); en `OPEN_TEXT` impide enviar el intento con la respuesta en blanco |
| `active` | Marca de actividad (borrado lógico) |
| `createdAt` / `updatedAt` | Auditoría de fechas |

### EvaluationOption
Alternativa de una pregunta. El campo `correct` **nunca** se expone al estudiante
antes de enviar su intento.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `question` | Pregunta a la que pertenece |
| `optionText` | Texto de la alternativa (obligatorio, TEXT) |
| `correct` | Indica si es la alternativa correcta |
| `orderIndex` | Orden de presentación |
| `active` | Marca de actividad |

### EvaluationAssignment
Asignación de una evaluación a un grado/sección, con ventana de fechas opcional.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `evaluation` | Evaluación asignada |
| `teacher` | Docente que asigna |
| `grade` / `section` | Grado y sección destino |
| `startAt` / `dueAt` | Ventana de disponibilidad (opcional) |
| `active` | Marca de actividad |
| `assignedAt` | Fecha de asignación |

### EvaluationAttempt
Intento de un estudiante sobre una evaluación.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `evaluation` | Evaluación |
| `assignment` | Asignación bajo la que se inició |
| `student` | Estudiante (`StudentProfile`) |
| `attemptNumber` | Número de intento |
| `status` | Estado (`AttemptStatus`: `IN_PROGRESS`, `SUBMITTED`, `PENDING_MANUAL_REVIEW`, `GRADED`) |
| `startedAt` / `submittedAt` | Fechas de inicio y envío |
| `gradedAt` | Fecha de calificación final (coincide con el envío en alternativa única; en intentos con preguntas abiertas se fija al completar la revisión manual; `null` mientras está `PENDING_MANUAL_REVIEW`) |
| `score` / `maxScore` | Puntaje obtenido y máximo **en puntos** (al enviar/calificar) |
| `finalScore` | Nota final en **escala 0–20** = nota base (`score/maxScore*20`) + suma de ajustes manuales activos, acotada a `[0, 20]`. Se recalcula tras cada revisión, ajuste o cierre |
| `overallFeedback` | Retroalimentación general del docente para el estudiante (TEXT, máx. 1500); visible al estudiante solo con la calificación cerrada |
| `gradeClosed` | Si la calificación ya fue cerrada. Mientras es `false`, el docente puede ajustar y el estudiante ve "pendiente de revisión"; al cerrarse, la nota final queda visible y se bloquea la edición. Por defecto `true` en intentos antiguos (columna con default) |
| `gradeClosedAt` / `gradeClosedBy` | Momento y docente del cierre. `gradeClosedBy` es `null` en cierres automáticos (solo alternativa única) |
| `questionOrder` | Orden de preguntas fijado para el intento (IDs separados por comas) |
| `currentQuestionIndex` | Solo `ONE_BY_ONE`: índice (0-based) de la pregunta actual; las anteriores quedan bloqueadas |
| `active` | Marca de actividad |

### EvaluationAttemptAdjustment
Ajuste manual de puntaje que el docente aplica a un intento completo, por encima del
puntaje por pregunta. Permite sumar o restar puntos a la nota final con una justificación
obligatoria. Vive en su propia tabla (`evaluation_attempt_adjustments`); **no sobrescribe**
la nota: la nota final se recompone a partir de los ajustes activos.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `attempt` | Intento al que pertenece |
| `amount` | Monto en escala 0–20 (`numeric(5,2)`), con signo: positivo bonifica, negativo penaliza; nunca cero |
| `type` | `AdjustmentType` (`BONUS`/`PENALTY`), derivado del signo del monto |
| `reason` | Motivo **obligatorio** del ajuste (TEXT) |
| `createdBy` / `createdAt` | Docente responsable y momento del registro |
| `active` | Anulación lógica: un ajuste anulado deja de afectar la nota pero permanece registrado |

### EvaluationAttemptEvent
Evento de trazabilidad registrado durante un intento. Es trazabilidad **a nivel de
intento**, no un log global de auditoría: vive en su propia tabla
(`evaluation_attempt_events`) para no saturar el visor de logs administrativos.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `attempt` | Intento al que pertenece |
| `eventType` | Tipo (`AttemptEventType`, ver abajo) |
| `description` | Descripción breve y no sensible (opcional, máx. 200) |
| `metadata` | Metadata segura y acotada (opcional, máx. 255): `tool=PERIODIC_TABLE`, `source=VISIBILITY_CHANGE`, `reason=USER_CONFIRMED_EXIT`… |
| `occurredAt` | Momento del evento |

Tipos de evento (`AttemptEventType`):

| Grupo | Valores | Quién lo registra |
|-------|---------|-------------------|
| Incidencias de foco (solo si `trackTabExit`) | `TAB_HIDDEN`, `TAB_VISIBLE`, `WINDOW_BLUR`, `WINDOW_FOCUS` | Frontend → endpoint de eventos |
| Trazabilidad del intento | `NAVIGATION_BLOCKED`, `TOOL_OPENED`, `TOOL_RETURNED`, `EXIT_ATTEMPTED` | Frontend → endpoint de eventos |
| Hitos del ciclo de vida | `ATTEMPT_STARTED`, `ATTEMPT_SUBMITTED`, `TIME_EXPIRED`, `ATTEMPT_EXITED` | Backend (al iniciar/enviar/salir) |

Se considera una **"salida" de pestaña** un evento `TAB_HIDDEN` o `WINDOW_BLUR`, y un
**"regreso"** un `TAB_VISIBLE` o `WINDOW_FOCUS`. Los hitos del ciclo de vida los registra
**solo el backend**: el cliente no puede falsificarlos (se rechazan si llegan por el
endpoint de eventos). Nunca se almacena contenido de otras pestañas, capturas de pantalla,
historial del navegador, respuestas, claves, tokens, IP ni datos sensibles.

### EvaluationAnswer
Respuesta de un estudiante a una pregunta dentro de un intento.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `attempt` | Intento |
| `question` | Pregunta |
| `selectedOption` | Alternativa elegida (alternativa única) |
| `answerText` | Texto escrito por el estudiante (preguntas abiertas, máx. 3000) |
| `correct` | Resultado de la corrección de alternativa única (al enviar); `null` en preguntas abiertas |
| `pointsAwarded` | Puntos otorgados: automáticos en alternativa única, asignados por el docente en preguntas abiertas |
| `reviewed` | Si la respuesta ya fue calificada; `true` siempre en alternativa única, `false` en una abierta hasta que el docente la revise |
| `teacherFeedback` | Retroalimentación opcional del docente para una respuesta abierta (TEXT, máx. 2000) |
| `reviewedAt` / `reviewedBy` | Momento y docente de la revisión manual (`null` mientras no se revisa) |
| `answeredAt` | Fecha de la respuesta |

## Flujo del docente

1. Crear evaluación (queda en `DRAFT`).
2. Editar evaluación.
3. Agregar preguntas:
   - de **alternativa única**: con sus alternativas (se exige exactamente una correcta);
   - de **respuesta abierta**: con enunciado, puntaje y, opcionalmente, un criterio de
     corrección (`expectedAnswer`) visible solo para el docente; sin alternativas.
4. Editar o desactivar preguntas.
5. Publicar la evaluación (validaciones de publicación, ver abajo).
6. Asignar la evaluación a uno o varios grados/secciones.
7. Revisar manualmente los intentos con preguntas abiertas: asignar puntaje y
   retroalimentación por respuesta, lo que recalcula la nota y, al completar todas, deja
   el intento en `GRADED` (revisado pero aún **no cerrado**).
8. Ajustar la nota final del intento (opcional): agregar ajustes manuales positivos o
   negativos con motivo, y escribir una retroalimentación general para el estudiante.
9. **Cerrar la calificación**: fija la nota final, la deja visible al estudiante y bloquea
   la edición posterior (ver «Ajuste manual de puntajes…»).
10. Archivar la evaluación o desactivar una asignación.

## Flujo del estudiante

1. Listar las evaluaciones publicadas asignadas a su grado/sección.
2. Ver el detalle de una evaluación asignada (sin las respuestas correctas ni los
   criterios de corrección de las preguntas abiertas).
3. Iniciar un intento.
4. Guardar respuestas de forma incremental (alternativa elegida o texto en las abiertas).
5. Enviar el intento:
   - si solo tiene alternativa única, se califica automáticamente y queda en `GRADED`;
   - si tiene preguntas abiertas, la parte cerrada se califica y el intento queda en
     `PENDING_MANUAL_REVIEW` hasta la revisión del docente.
6. Consultar sus resultados:
   - mientras la calificación **no esté cerrada**, ve el estado «pendiente de revisión» sin
     nota final;
   - una vez **cerrada**, ve su nota final (0–20), la retroalimentación general, los puntos
     y la retroalimentación por pregunta abierta, sin criterios internos ni claves.

## Endpoints principales

Base: `/api/evaluations`

### Docente (`/teacher`)
| Método | Ruta | Acción |
|--------|------|--------|
| POST | `/teacher` | Crear evaluación |
| GET | `/teacher` | Listar evaluaciones propias |
| GET | `/teacher/{evaluationId}` | Detalle de evaluación propia |
| PUT | `/teacher/{evaluationId}` | Editar evaluación |
| POST | `/teacher/{evaluationId}/questions` | Agregar pregunta (alternativa única con alternativas, o abierta con criterio opcional) |
| PUT | `/teacher/{evaluationId}/questions/{questionId}` | Editar pregunta (incluye cambio de tipo) |
| PATCH | `/teacher/{evaluationId}/questions/{questionId}/deactivate` | Desactivar pregunta |
| PATCH | `/teacher/{evaluationId}/publish` | Publicar evaluación |
| PATCH | `/teacher/{evaluationId}/archive` | Archivar evaluación |
| POST | `/teacher/{evaluationId}/assignments` | Asignar a grado/sección |
| PATCH | `/teacher/{evaluationId}/assignments/{assignmentId}/deactivate` | Desactivar asignación |
| GET | `/teacher/{evaluationId}/results` | Resultados de la evaluación (agregados + intentos) |
| GET | `/teacher/{evaluationId}/results/summary` | Solo los agregados de resultados |
| GET | `/teacher/attempts/{attemptId}/result` | Detalle del resultado de un intento (con alternativa correcta y respuestas abiertas) |
| GET | `/teacher/attempts/{attemptId}/traceability` | Trazabilidad del intento: resumen + línea de tiempo de eventos (sin respuestas ni claves) |
| GET | `/teacher/manual-review` | Bandeja de intentos pendientes de revisión manual |
| GET | `/teacher/attempts/{attemptId}/review` | Detalle de un intento para revisar sus respuestas abiertas (incluye el criterio de corrección) |
| PATCH | `/teacher/attempts/{attemptId}/answers/{answerId}/manual-grade` | Asignar puntaje y retroalimentación a una respuesta abierta |
| PATCH | `/teacher/attempts/{attemptId}/complete-review` | Marcar la revisión como completa y recalcular la nota (no cierra la calificación) |
| POST | `/teacher/attempts/{attemptId}/adjustments` | Agregar un ajuste manual de puntaje (bonificación/penalización) con motivo |
| DELETE | `/teacher/attempts/{attemptId}/adjustments/{adjustmentId}` | Anular un ajuste manual aplicado |
| PATCH | `/teacher/attempts/{attemptId}/feedback` | Guardar la retroalimentación general del intento |
| PATCH | `/teacher/attempts/{attemptId}/close-grade` | Cerrar la calificación: fija la nota final y la deja visible al estudiante |

### Estudiante (`/student`)
| Método | Ruta | Acción |
|--------|------|--------|
| GET | `/student` | Listar evaluaciones disponibles |
| GET | `/student/{evaluationId}` | Ver detalle de evaluación asignada |
| POST | `/student/{evaluationId}/attempts` | Iniciar intento |
| GET | `/student/attempts/{attemptId}` | Ver intento |
| POST | `/student/attempts/{attemptId}/answers` | Guardar/actualizar una respuesta (alternativa o texto en las abiertas) |
| POST | `/student/attempts/{attemptId}/submit` | Enviar intento (queda `GRADED` o `PENDING_MANUAL_REVIEW` si hay abiertas) |
| POST | `/student/attempts/{attemptId}/exit` | Salir del intento: lo finaliza (GRADED) con lo guardado; no retomable |
| POST | `/student/attempts/{attemptId}/events` | Registrar evento del intento: incidencia de foco (solo si `trackTabExit`), uso de herramienta (`TOOL_OPENED`/`TOOL_RETURNED`) o intento de salida (`EXIT_ATTEMPTED`) |
| GET | `/student/results` | Listar sus resultados/calificaciones |
| GET | `/student/attempts/{attemptId}/result` | Detalle del resultado de un intento propio |

### Administrador (`/admin`)
| Método | Ruta | Acción |
|--------|------|--------|
| GET | `/admin` | Listar todas las evaluaciones |
| GET | `/admin/{evaluationId}` | Ver el detalle de cualquier evaluación |

## Restricciones de seguridad

- Todas las rutas `/api/evaluations/**` requieren autenticación (JWT).
- Segmentación por rol en `SecurityConfig`:
  - `/api/evaluations/teacher/**` → `DOCENTE`
  - `/api/evaluations/student/**` → `ESTUDIANTE`
  - `/api/evaluations/admin/**` → `ADMINISTRADOR`
- El docente y el estudiante se resuelven desde el usuario autenticado
  (`Authentication.getName()`), nunca desde identificadores enviados por el cliente.
- Un docente solo puede ver y modificar **sus propias** evaluaciones.
- El estudiante solo ve evaluaciones publicadas asignadas a su grado/sección y
  **no recibe** el campo `correct` de las alternativas ni el `expectedAnswer`/criterio de
  corrección de las preguntas abiertas.
- La revisión manual de un intento solo la puede hacer el docente **dueño** de la
  evaluación; no puede revisar, ajustar ni cerrar intentos de evaluaciones ajenas.
- El estudiante no ve el puntaje ni la retroalimentación de una respuesta abierta hasta
  que el docente la revisa, ni la **nota final** ni la retroalimentación general hasta que
  la calificación está **cerrada**.
- Los ajustes manuales, la retroalimentación y el cierre solo proceden mientras la
  calificación no esté cerrada; una vez cerrada se bloquea la edición (no hay reapertura).
- El estudiante nunca recibe los criterios internos (`expectedAnswer`), las claves de
  alternativas, ni el detalle técnico de los ajustes.

## Validaciones de negocio

**Docente**
- El título es obligatorio; `1 <= maxAttempts <= 10`; `timeLimitMinutes` entre 1 y 240 si se envía.
- La configuración avanzada (`allowChemicalCalculator`, `trackTabExit`,
  `questionDisplayMode`) toma valores por defecto seguros si no se envía.
- Toda pregunta exige enunciado y puntaje mayor a 0.
- Una pregunta de **alternativa única** debe tener al menos dos alternativas y exactamente
  una correcta.
- Una pregunta **abierta** no puede llevar alternativas; su `expectedAnswer` es opcional
  (máx. 3000) y solo lo ve el docente.
- Al publicar, no se exige alternativas a las preguntas abiertas (sí a las de alternativa
  única); no se publica sin preguntas activas.
- No asignar una evaluación archivada ni duplicar una asignación activa en la misma sección.
- En la revisión manual, el puntaje asignado a una respuesta abierta debe estar entre 0 y
  el puntaje máximo de la pregunta; la retroalimentación es opcional (máx. 2000).

**Estudiante**
- No iniciar un intento si la evaluación no está asignada a su sección.
- No superar `maxAttempts` ni tener más de un intento `IN_PROGRESS` a la vez.
- Si la asignación tiene `dueAt` vencido, se bloquea el inicio del intento.
- No responder preguntas ajenas a la evaluación ni elegir alternativas ajenas a la pregunta.
- No enviar un intento ya enviado.
- No enviar a tiempo un intento con una pregunta abierta **obligatoria** en blanco
  (un cierre por tiempo agotado o por salida no bloquea: esas preguntas quedan en 0/pendiente).
- No guardar ni enviar respuestas una vez vencido el tiempo (con su margen de gracia).
- Solo registrar incidencias de salida de pestaña de su **propio** intento y únicamente
  si la evaluación tiene `trackTabExit` activo.
- En `ONE_BY_ONE`, solo responder la pregunta actual del intento: no volver a una
  anterior ya bloqueada ni saltar a una futura (validado en backend).

## Trazabilidad y logs

Esta sesión amplía la trazabilidad de configuración de evaluaciones en el log global de
auditoría (`/api/admin/logs`), con descripciones seguras (solo el nombre de la
evaluación, nunca preguntas, claves ni respuestas):

- `EVALUATION_CREATED` — creación (incluye la configuración avanzada).
- `EVALUATION_CONFIG_UPDATED` — edición de la configuración; al activar por primera vez
  la detección de salida de pestaña se registra además un evento específico
  («Se habilitó la detección de salida de pestaña en una evaluación.»).
- `EVALUATION_PUBLISHED` y `EVALUATION_ASSIGNED` — publicación y asignación (ya existían).
- `EVALUATION_ATTEMPT_SUBMITTED` — envío del intento; los envíos fuera de tiempo añaden
  `outOfTime=true` en su metadato.
- `EVALUATION_OPEN_QUESTION_SAVED` — creación o edición de una pregunta abierta (sin
  enunciado ni criterio en la descripción).
- `EVALUATION_ATTEMPT_PENDING_REVIEW` — el intento quedó pendiente de revisión manual.
- `EVALUATION_ANSWER_REVIEWED` — el docente revisó una respuesta abierta (metadato solo con
  `attemptId`/`answerId`; **nunca** el texto de la respuesta ni la retroalimentación).
- `EVALUATION_REVIEW_COMPLETED` — se completó la revisión manual y se recalculó la nota final.
- `EVALUATION_ADJUSTMENT_ADDED` — se agregó un ajuste manual de puntaje (metadato con
  `attemptId`, `adjustmentId` y `type=BONUS/PENALTY`; **nunca** el monto ni el motivo).
- `EVALUATION_ADJUSTMENT_REMOVED` — se anuló un ajuste manual de puntaje.
- `EVALUATION_FEEDBACK_UPDATED` — se actualizó la retroalimentación general (sin su texto).
- `EVALUATION_GRADE_CLOSED` — se cerró la calificación de un intento.

Estos logs usan descripciones seguras del tipo «El docente revisó una respuesta abierta de
la evaluación "Nombre".», «Se agregó un ajuste de puntaje al intento de una evaluación.» o
«Se cerró la calificación de un intento.». **No** se registran el texto de la respuesta del
estudiante, la retroalimentación completa, los montos, los motivos de ajuste, los criterios
de corrección, las claves ni el payload.

Las **incidencias de salida de pestaña** se registran como eventos del intento
(`evaluation_attempt_events`), **no** como logs globales de auditoría, para no saturar el
visor administrativo.

## Configuración avanzada del intento

El docente define, al crear o editar la evaluación, un conjunto de reglas que el
estudiante debe respetar al rendir. Todas tienen un valor por defecto que **preserva el
comportamiento histórico**, de modo que las evaluaciones existentes no cambian.

| Configuración | Campo | Por defecto | Qué hace |
|---------------|-------|-------------|----------|
| Formación de compuestos | `allowChemicalCalculator` | `false` | Habilita el acceso al módulo existente de Formación de compuestos (`/compounds`) como herramienta de apoyo durante el intento. |
| Tabla periódica | `allowPeriodicTable` | `false` | Habilita el acceso al módulo existente de Tabla periódica (`/periodic-table`) como herramienta de apoyo durante el intento. |
| Detección de salida de pestaña | `trackTabExit` | `false` | Permite registrar incidencias de pérdida de foco/cambio de pestaña asociadas al intento. |
| Modo de preguntas | `questionDisplayMode` | `ALL_AT_ONCE` | `ALL_AT_ONCE` muestra todas las preguntas juntas; `ONE_BY_ONE`, una por pantalla en flujo secuencial sin retroceso. No afecta la calificación. |
| Orden aleatorio | `randomizeQuestions` | `false` | Si está activo, el orden de preguntas se baraja por intento (fijo para ese intento). |
| Límite de intentos | `maxAttempts` | `1` | Intentos permitidos por estudiante (1 a 10). |
| Tiempo máximo | `timeLimitMinutes` | `null` (sin límite) | Minutos para resolver (1 a 240) desde el inicio del intento. |

### Orden de preguntas por intento y flujo secuencial

Al **iniciar** el intento, el backend fija el orden de las preguntas y lo guarda en
`EvaluationAttempt.questionOrder` (IDs separados por comas). Si `randomizeQuestions` está
activo, ese orden se baraja; si no, respeta el orden del docente. El orden **no se
regenera** al consultar el intento ni al recargar: es estable durante todo el intento, y
es el mismo que usa el frontend para presentar las preguntas. La calificación es por
pregunta, así que el orden no afecta el puntaje.

En el modo **`ONE_BY_ONE`** el avance es **secuencial y sin retroceso**, validado en el
backend (no solo en el frontend):

- Solo se puede responder/guardar la **pregunta actual** (`questionOrder[currentQuestionIndex]`).
- Al guardar esa respuesta, el backend **avanza** `currentQuestionIndex`: la pregunta
  anterior queda **bloqueada** y un intento de volver a ella se rechaza
  («No puedes volver a una pregunta anterior…»).
- No se puede **saltar** a una pregunta futura («Debes responder las preguntas en orden»).
- Si el estudiante recarga, `getAttempt` devuelve `currentQuestionIndex` y el frontend
  **continúa desde la pregunta pendiente**, sin perder las respuestas ya registradas.
- Al enviar, las respuestas ya están guardadas (se grabaron al avanzar), por lo que el
  envío **no reprocesa** el cuerpo en este modo.

En el modo **`ALL_AT_ONCE`** se mantiene el comportamiento histórico: todas las preguntas
visibles y respuestas modificables hasta enviar (respetando el orden del intento si
`randomizeQuestions` está activo).

**Combinaciones:** ALL_AT_ONCE + normal (orden del docente); ALL_AT_ONCE + aleatorio
(todas juntas en orden barajado fijo); ONE_BY_ONE + normal (una por una sin retroceso);
ONE_BY_ONE + aleatorio (una por una, orden barajado fijo, sin retroceso).

Los intentos creados antes de esta funcionalidad no tienen orden guardado: se inicializa
de forma perezosa con el orden natural (sin barajar) la primera vez que se consultan,
para no alterar un intento en curso.

### Qué valida el backend y qué maneja el frontend

- **Backend (autoridad):** persiste y respeta la configuración; impide superar
  `maxAttempts`; controla el tiempo al guardar/enviar; solo registra incidencias de
  salida de pestaña si `trackTabExit` está activo y el intento es del propio estudiante;
  nunca expone la alternativa correcta antes de tiempo. El frontend **no** puede evadir
  estas reglas.
- **Frontend (experiencia):** muestra la pantalla previa con las reglas; presenta las
  preguntas según el modo; muestra el contador regresivo y dispara el envío automático
  al agotarse el tiempo; adapta la barra lateral al modo examen mostrando solo las
  herramientas permitidas; detecta la pérdida de foco y la reporta. Es una capa de
  usabilidad, no de seguridad.

### Herramientas durante el intento (formación de compuestos y tabla periódica)

`allowChemicalCalculator` y `allowPeriodicTable` controlan si el estudiante puede usar,
**durante el intento**, los **módulos ya existentes** de Formación de compuestos
(`/compounds`) y Tabla periódica (`/periodic-table`) como herramientas de apoyo. No se
crean ni se duplican herramientas dentro del examen: se reutilizan tal cual los módulos
del sistema. Ninguno de esos módulos accede a la clave de respuestas de la evaluación.

El backend es la fuente de verdad de estos permisos: además de los DTOs del docente,
viajan en los DTOs seguros del estudiante (`StudentEvaluationResponse` y
`StudentEvaluationDetailResponse`) y en el propio intento (`AttemptResponse`), para que
el frontend sepa qué módulos habilitar durante el examen.

**Barra lateral en modo examen:** mientras hay un intento activo, el frontend reemplaza
el menú normal del estudiante por un menú reducido: *Volver al intento*, *Formación de
compuestos* (solo si `allowChemicalCalculator`), *Tabla periódica* (solo si
`allowPeriodicTable`) y *Salir del intento*. No se muestran Inicio, Conceptos, Mis
evaluaciones ni Mis resultados.

**Control de navegación:** es un control **dentro de la aplicación** (no un bloqueo del
navegador). Un guard permite, durante el intento, solo las rutas de las herramientas
habilitadas; cualquier otra ruta (incluido el acceso manual por URL a una herramienta no
permitida) redirige de vuelta al intento. Volver desde una herramienta no reinicia el
intento ni altera el orden de preguntas.

### Salir del intento (finalización por abandono)

`POST /student/attempts/{attemptId}/exit` finaliza el intento cuando el estudiante decide
salir de la evaluación. El backend valida que el intento sea del estudiante autenticado y
que siga en progreso; entonces:

- califica con las respuestas ya guardadas (las preguntas sin responder quedan en cero,
  con la misma lógica de calificación automática del envío);
- deja el intento en estado **GRADED** con `submittedAt`/`gradedAt`;
- el intento **cuenta como usado**, por lo que **no puede retomarse**: con
  `maxAttempts = 1` el estudiante ya no podrá iniciar otro, y con más intentos solo si le
  quedan disponibles;
- si la evaluación tiene `trackTabExit` activo, registra un evento `ATTEMPT_EXITED` del
  intento (no cuenta como "salida de pestaña") y deja un log de auditoría agregado sin
  respuestas ni payloads.

No se reutiliza el estado del intento como retomable: salir es un cierre definitivo. Si
la evaluación tiene `trackTabExit` activo, un intento de navegación interna también puede
registrarse como evento `NAVIGATION_BLOCKED` (no cuenta como "salida de pestaña" ni satura
el log global de auditoría).

### Trazabilidad del intento

El sistema registra los **eventos importantes** ocurridos durante un intento para que el
docente revise incidencias básicas. Es trazabilidad **del comportamiento del intento**,
separada por completo de los logs generales de auditoría (`/api/admin/logs`) y de la
corrección de respuestas. Vive en su propia tabla (`evaluation_attempt_events`) para no
saturar el visor administrativo.

**Qué se registra**

- **Inicio del intento** (`ATTEMPT_STARTED`): lo registra el backend al crear el intento.
- **Salida/regreso de pestaña** (`TAB_HIDDEN`/`WINDOW_BLUR` y `TAB_VISIBLE`/`WINDOW_FOCUS`):
  solo si `trackTabExit` está activo. El frontend detecta `visibilitychange` y `blur/focus`.
- **Uso de herramientas permitidas** (`TOOL_OPENED`/`TOOL_RETURNED`): cuando el estudiante
  abre la tabla periódica o formación de compuestos durante el intento. La herramienta
  concreta viaja en `metadata` (`tool=PERIODIC_TABLE` o `tool=COMPOUND_FORMATION`); nunca
  se registra qué elemento consultó ni qué fórmula formó.
- **Intento de salida** (`EXIT_ATTEMPTED`): cuando pulsa "Salir del intento" (intención,
  puede cancelarse).
- **Envío** (`ATTEMPT_SUBMITTED`) y **salida confirmada/abandono** (`ATTEMPT_EXITED`): los
  registra el backend al enviar o salir.
- **Tiempo agotado** (`TIME_EXPIRED`): lo registra el backend si el envío llega fuera de tiempo.
- **Tiempo usado** y **estado final**: se exponen en el resumen de trazabilidad.

**Qué NO se registra (privacidad)**

Nunca se almacenan respuestas correctas, claves de evaluación, alternativas elegidas
completas, payloads completos, contenido de preguntas o de otras pestañas, capturas de
pantalla, historial del navegador, contraseñas, tokens ni datos sensibles. La única
metadata permitida es segura y acotada: la herramienta (enum cerrado) y un `source`/`reason`
corto que el backend **sanitiza** (solo letras, dígitos y guion bajo, máx. 60).

**Reglas de registro**

- Los **hitos del ciclo de vida** (`ATTEMPT_STARTED`, `ATTEMPT_SUBMITTED`, `TIME_EXPIRED`,
  `ATTEMPT_EXITED`) los registra **solo el backend**; si llegan por el endpoint de eventos
  se rechazan, para que el cliente no pueda falsificarlos.
- Las **incidencias de foco** solo se registran si la evaluación tiene `trackTabExit`
  activo; si está desactivado, esos eventos se rechazan (no se registran incidencias de
  pestaña), pero los eventos de herramientas, intento de salida e hitos del ciclo de vida
  sí se siguen registrando, porque no son detección de pérdida de foco.
- Se descartan **duplicados** idénticos dentro de una ventana corta (throttling simple).
- El estudiante solo puede registrar eventos de **su propio** intento y mientras esté en
  progreso.

**Cálculo del tiempo usado**

El tiempo usado se calcula **en el backend** con los timestamps del propio intento
(`startedAt`/`submittedAt`), nunca solo con el contador del frontend. Si el intento se
abandona o vence el tiempo, se mide hasta el momento de cierre (`submittedAt`); si sigue en
progreso, hasta el momento actual. Nunca es negativo.

**Qué ve el docente**

`GET /teacher/attempts/{attemptId}/traceability` devuelve, solo para intentos de **sus
propias** evaluaciones, un resumen (estado final, inicio, finalización, tiempo usado,
salidas de pestaña, regresos, intentos de salida y herramientas consultadas) y una **línea
de tiempo simple** de eventos. Además, sigue disponible el **contador** de salidas de
pestaña (`tabExitCount`) en las filas y el detalle de resultados. No expone respuestas ni
claves, ni existe exportación a PDF/Excel.

### Control de tiempo

Al iniciar el intento se registra `startedAt`. El límite efectivo es
`startedAt + timeLimitMinutes`, más un **margen de gracia** de 60 s que tolera la
latencia de red y el desfase de reloj del envío automático del frontend. Superado ese
margen:

- `guardar respuesta` se rechaza con un error claro;
- `enviar intento` **ignora** las respuestas que lleguen tarde en el cuerpo y califica
  solo lo guardado a tiempo, cerrando igualmente el intento como `GRADED` (no se deja
  abierto). El envío fuera de tiempo queda marcado en el log con `outOfTime=true`.

Así el tiempo no depende solo del frontend y no se rompen los intentos existentes.

## Calificación automática

Al enviar un intento, el servicio ejecuta la calificación automática encapsulada en
`EvaluationService.gradeAttempt`, con estas reglas para preguntas de alternativa única:

- Si la alternativa elegida es la correcta: `pointsAwarded = points` de la pregunta y
  `correct = true`.
- Si la alternativa elegida es incorrecta: `pointsAwarded = 0` y `correct = false`.
- Si la pregunta no se respondió: cuenta como no respondida (`pointsAwarded = 0`).
- `score` = suma de `pointsAwarded`; `maxScore` = suma de `points` de las **preguntas
  activas** (las inactivas no se cuentan); nunca se otorga puntaje negativo.
- `percentage` = `score / maxScore * 100` (0 si `maxScore` es 0), redondeado a un decimal.

Si la evaluación es **solo de alternativa única**, la corrección es automática y completa:
el intento pasa directamente a estado `GRADED` y se registra `gradedAt`. Si por
compatibilidad existiera un intento terminal antiguo sin `score`, al consultar su resultado
se recalcula de forma segura (`ensureScored`) sin duplicar respuestas ni tocar intentos en
progreso.

## Preguntas abiertas y calificación manual

Una pregunta **abierta** (`OPEN_TEXT`) no tiene alternativas: el estudiante responde con
texto (máx. 3000 caracteres, sin editor enriquecido ni archivos) y el docente la califica
manualmente. Puede llevar un criterio de corrección (`expectedAnswer`) visible **solo para
el docente**.

**Al enviar un intento con preguntas abiertas:**

- las preguntas de alternativa única se califican automáticamente (puntaje parcial);
- por cada pregunta abierta se garantiza una fila de respuesta (aunque vaya en blanco) para
  que el docente pueda calificarla;
- si queda al menos una abierta sin revisar, el intento pasa a **`PENDING_MANUAL_REVIEW`**
  con `gradedAt = null`: la nota aún **no es definitiva**.

**Revisión manual del docente:**

- `GET /teacher/manual-review` lista los intentos de sus evaluaciones en
  `PENDING_MANUAL_REVIEW` (estudiante, evaluación, fecha de envío, cuántas abiertas faltan).
- `GET /teacher/attempts/{attemptId}/review` muestra, por cada pregunta abierta, el texto
  del estudiante, el puntaje máximo, el criterio de corrección y el puntaje/retroalimentación
  si ya se asignó.
- `PATCH …/answers/{answerId}/manual-grade` asigna un puntaje (entre 0 y el máximo de la
  pregunta) y una retroalimentación opcional. Tras guardar, **recalcula** la nota del
  intento; cuando ya no quedan abiertas pendientes, el intento pasa a **`GRADED`** y se fija
  `gradedAt`.
- `PATCH …/complete-review` marca la revisión como completa (exige que no queden abiertas
  pendientes) y recalcula la nota. Deja el intento en `GRADED` pero **no lo cierra**: la nota
  final solo se hace visible al estudiante al **cerrar la calificación** (ver la sección
  siguiente).

**Cálculo de la nota final.** El sistema usa puntaje por pregunta: `maxScore` es la suma de
los `points` de todas las preguntas activas (de cualquier tipo) y `score` es la suma de lo
obtenido (automático en alternativa única + puntaje manual de las abiertas ya revisadas).
El `percentage = score / maxScore * 100` (un decimal). Las preguntas abiertas sin revisar
aportan 0 mientras están pendientes, por lo que el puntaje mostrado en
`PENDING_MANUAL_REVIEW` es **parcial** y no debe tomarse como final hasta el estado `GRADED`.

> Escala: el módulo trabaja internamente con **puntaje por pregunta y porcentaje**, y expone
> además una **nota final en escala 0–20** (`finalScore`) derivada proporcionalmente como
> `score / maxScore * 20`. Sobre esa escala 0–20 se aplican los ajustes manuales (ver la
> sección siguiente). El puntaje en puntos y el porcentaje se conservan sin cambios para no
> romper los resultados existentes.

### Estados del intento

| Estado | Cuándo |
|--------|--------|
| `IN_PROGRESS` | Intento iniciado; admite guardar respuestas |
| `PENDING_MANUAL_REVIEW` | Enviado con preguntas abiertas sin calificar; nota parcial, no definitiva |
| `GRADED` | Calificación final: directa en intentos solo de alternativa única, o tras completar la revisión manual |
| `SUBMITTED` | Estado heredado/de transición (los intentos pasan a `GRADED` o `PENDING_MANUAL_REVIEW`) |

Un intento cerrado por **salida** (abandono) o por **tiempo agotado** sigue la misma regla:
si tiene abiertas sin revisar, queda `PENDING_MANUAL_REVIEW` (el docente las calificará,
normalmente con 0 si quedaron en blanco); si no, queda `GRADED`. El abandono no se confunde
con la revisión pendiente: son situaciones independientes que pueden coincidir.

## Ajuste manual de puntajes, retroalimentación general y cierre de calificación

Tras revisar las respuestas, el docente puede **ajustar la nota final** del intento, escribir
una **retroalimentación general** y **cerrar la calificación** para dejarla visible al
estudiante. Todo esto solo procede si el intento es del docente **dueño** de la evaluación y
mientras la calificación **no esté cerrada**.

### Nota final (escala 0–20)

```
notaBase   = score / maxScore * 20        (0 si maxScore es 0)
ajustes    = Σ amount de los ajustes activos del intento
finalScore = clamp(notaBase + ajustes, 0, 20)   → redondeada a 2 decimales
```

- El puntaje en **puntos** (`score`/`maxScore`) y el **porcentaje** no cambian: el ajuste se
  aplica sobre la escala 0–20, no sobre los puntos por pregunta.
- La nota final **nunca** es menor a 0 ni mayor a 20 (se acota con *clamp*); por eso un ajuste
  no se rechaza por dejar la nota fuera de rango: la nota resultante se acota.
- `finalScore` se recalcula y persiste tras cada revisión de respuesta, alta/anulación de
  ajuste y cierre. Los intentos terminales antiguos sin `finalScore` la completan al
  consultarse (`ensureScored`), sin recalcular los puntos.

### Ajustes manuales

- `POST …/adjustments` con `{ amount, reason }`. El monto va en escala 0–20 con signo
  (positivo = bonificación, negativo = penalización); el **motivo es obligatorio** y el
  **monto no puede ser cero**. El tipo (`BONUS`/`PENALTY`) se deriva del signo. Cada ajuste
  registra docente y fecha; **no sobrescribe** la nota, queda como registro propio.
- `DELETE …/adjustments/{adjustmentId}` anula un ajuste (borrado lógico, `active = false`):
  deja de afectar la nota pero permanece para la trazabilidad.
- Tras agregar o anular un ajuste se recalcula `finalScore`.

### Retroalimentación general

- `PATCH …/feedback` con `{ overallFeedback }` (opcional, máx. 1500). Se guarda en el intento
  y solo se le muestra al estudiante **cuando la calificación está cerrada**. No debe usarse
  para exponer criterios internos.

### Cierre de calificación

- `PATCH …/close-grade`:
  - exige que **no queden preguntas abiertas por revisar** (si faltan, error);
  - recalcula puntaje y nota final, deja el intento en `GRADED`;
  - marca `gradeClosed = true`, registra `gradeClosedAt`/`gradeClosedBy`;
  - a partir de ahí, el estudiante ve su nota final y la retroalimentación, y **se bloquea**
    toda edición de puntajes, ajustes y retroalimentación.
- Los intentos de **solo alternativa única** se cierran **automáticamente** al enviarse
  (`gradeClosed = true`, sin `gradeClosedBy`), conservando el comportamiento previo: su
  resultado queda visible de inmediato.
- **Reapertura:** no se implementa en esta sesión. Una vez cerrada, la calificación no se
  puede modificar (pendiente documentado).

### Logs de auditoría (acciones docentes)

Se registran como logs seguros (categoría `EVALUATION`), **sin** texto de respuestas,
retroalimentación, montos ni criterios internos —solo el tipo y los identificadores:

| Evento | Cuándo |
|--------|--------|
| `EVALUATION_ANSWER_REVIEWED` | Se actualizó el puntaje manual de una respuesta abierta |
| `EVALUATION_ADJUSTMENT_ADDED` | Se agregó un ajuste de puntaje (incluye `type=BONUS/PENALTY`) |
| `EVALUATION_ADJUSTMENT_REMOVED` | Se anuló un ajuste de puntaje |
| `EVALUATION_FEEDBACK_UPDATED` | Se actualizó la retroalimentación general |
| `EVALUATION_GRADE_CLOSED` | Se cerró la calificación de un intento |

## Resultados y retroalimentación

**Qué ve el docente**
- Lista de resultados de su evaluación: por cada intento terminal, el estudiante
  (código, nombre, grado/sección), número de intento, estado, `score`/`maxScore`,
  porcentaje, **nota final** (0–20, si está cerrada) y si la calificación está cerrada,
  además de las fechas de envío/calificación. Esto permite distinguir los intentos
  **pendientes de revisión**, **revisados pero no cerrados** y **cerrados**.
- Agregados de la evaluación: total de intentos, promedio de puntaje, porcentaje
  promedio, mayor y menor puntaje, y conteo de aprobados/desaprobados (umbral de
  aprobación: 60 %, usado solo para esos contadores).
- Detalle de un intento: corrección pregunta a pregunta. En alternativa única, la
  **alternativa seleccionada, la alternativa correcta**, si fue correcta, puntaje
  obtenido/máximo y la explicación. En preguntas abiertas, el **texto del estudiante**, el
  puntaje asignado, la retroalimentación y si ya fue revisada.
- Bandeja de **revisión manual**: intentos pendientes y, por cada uno, las respuestas
  abiertas a calificar (ver sección anterior).

**Qué ve el estudiante**
- Mientras la calificación **no esté cerrada** (`gradeClosed = false`): el estado
  «pendiente de revisión», sin nota, sin nota final, sin retroalimentación general y sin el
  detalle de puntajes por pregunta. Sí ve su propia respuesta (alternativa elegida o texto).
- Una vez **cerrada** (`gradeClosed = true`): título, número de intento, estado, `score`,
  `maxScore`, porcentaje y **nota final 0–20** (`finalScore`), la **retroalimentación
  general**, y por cada pregunta su respuesta, si fue correcta (alternativa única), el
  puntaje obtenido y la retroalimentación de las abiertas revisadas.
- Los intentos de solo alternativa única están cerrados desde el envío, por lo que su
  resultado es visible de inmediato (sin cambios respecto al comportamiento previo).
- Nunca ve criterios internos (`expectedAnswer`), claves de alternativas mientras le queden
  intentos (ver abajo), ni el detalle técnico de los ajustes.

**Criterio de retroalimentación con más de un intento**

Para no facilitar la trampa entre intentos, la **alternativa correcta** y la
**explicación** solo se muestran al estudiante (`canViewDetailedFeedback = true`)
cuando ya **no le quedan intentos disponibles** (`attemptsUsed >= maxAttempts`) o la
evaluación está **archivada**. Mientras le queden intentos, ve su calificación y
porcentaje pero no las respuestas correctas. El docente siempre ve todo.

## Seguridad de los resultados

- `/api/evaluations/teacher/**` → `DOCENTE`; `/api/evaluations/student/**` →
  `ESTUDIANTE` (segmentación existente en `SecurityConfig`, sin cambios).
- Un docente solo accede a los resultados de **sus propias** evaluaciones y al detalle
  de intentos que pertenezcan a ellas.
- Un estudiante solo accede a **sus propios** intentos; no puede consultar resultados
  de otro estudiante ni recibir la alternativa correcta si aún tiene intentos.
- Un intento `IN_PROGRESS` no se puede consultar como resultado final.
