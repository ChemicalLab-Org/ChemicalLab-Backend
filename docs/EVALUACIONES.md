# Evaluaciones

Módulo del backend que permite a los docentes crear evaluaciones de alternativa
única sobre temas de química, agregarles preguntas y alternativas, publicarlas y
asignarlas a los grados y secciones que tienen a cargo. Los estudiantes resuelven
únicamente las evaluaciones publicadas que corresponden a su grado y sección.

> Estado actual: además de crear, publicar y rendir evaluaciones, el módulo ya
> **califica automáticamente** cada intento al enviarse (estado `GRADED`) y expone los
> **resultados/calificaciones** tanto para el docente (resultados de su evaluación y
> detalle de cada intento) como para el estudiante (sus calificaciones). Quedan fuera
> de alcance la exportación a Excel/PDF, las estadísticas avanzadas, la edición manual
> de notas y los comentarios del docente.

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
| `trackTabExit` | Si se detectan y registran salidas de pestaña/pérdida de foco durante el intento (por defecto `false`) |
| `questionDisplayMode` | Modo de presentación de preguntas (`QuestionDisplayMode`: `ALL_AT_ONCE`, `ONE_BY_ONE`; por defecto `ALL_AT_ONCE`) |
| `createdByTeacher` | Docente autor (`TeacherProfile`) |
| `active` | Marca de actividad |
| `createdAt` / `updatedAt` | Auditoría de fechas |

### EvaluationQuestion
Pregunta de alternativa única de una evaluación.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `evaluation` | Evaluación a la que pertenece |
| `questionText` | Enunciado (obligatorio, TEXT) |
| `questionType` | Tipo (`QuestionType`: `MULTIPLE_CHOICE`) |
| `points` | Puntaje de la pregunta (mínimo 1) |
| `orderIndex` | Orden de presentación |
| `explanation` | Explicación opcional (TEXT) |
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
| `status` | Estado (`AttemptStatus`: `IN_PROGRESS`, `SUBMITTED`, `GRADED`) |
| `startedAt` / `submittedAt` | Fechas de inicio y envío |
| `gradedAt` | Fecha de calificación (coincide con el envío en alternativa única) |
| `score` / `maxScore` | Puntaje obtenido y máximo (al enviar/calificar) |
| `active` | Marca de actividad |

### EvaluationAttemptEvent
Incidencia de foco registrada durante un intento (salida/retorno de pestaña o ventana),
solo cuando la evaluación tiene `trackTabExit = true`. Es trazabilidad **a nivel de
intento**, no un log global de auditoría: vive en su propia tabla
(`evaluation_attempt_events`) para no saturar el visor de logs administrativos.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `attempt` | Intento al que pertenece |
| `eventType` | Tipo (`AttemptEventType`: `TAB_HIDDEN`, `TAB_VISIBLE`, `WINDOW_BLUR`, `WINDOW_FOCUS`) |
| `description` | Descripción breve y no sensible (opcional, máx. 200) |
| `occurredAt` | Momento de la incidencia |

Se considera una **"salida"** un evento `TAB_HIDDEN` o `WINDOW_BLUR`. Nunca se almacena
contenido de otras pestañas, capturas de pantalla, historial del navegador, IP ni datos
sensibles.

### EvaluationAnswer
Respuesta de un estudiante a una pregunta dentro de un intento.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `attempt` | Intento |
| `question` | Pregunta |
| `selectedOption` | Alternativa elegida |
| `answerText` | Texto libre (reservado para tipos futuros) |
| `correct` | Resultado de la corrección (al enviar) |
| `pointsAwarded` | Puntos otorgados (al enviar) |
| `answeredAt` | Fecha de la respuesta |

## Flujo del docente

1. Crear evaluación (queda en `DRAFT`).
2. Editar evaluación.
3. Agregar preguntas con sus alternativas (se exige exactamente una correcta).
4. Editar o desactivar preguntas.
5. Publicar la evaluación (validaciones de publicación, ver abajo).
6. Asignar la evaluación a uno o varios grados/secciones.
7. Archivar la evaluación o desactivar una asignación.

## Flujo del estudiante

1. Listar las evaluaciones publicadas asignadas a su grado/sección.
2. Ver el detalle de una evaluación asignada (sin las respuestas correctas).
3. Iniciar un intento.
4. Guardar respuestas de forma incremental.
5. Enviar el intento (se califica automáticamente y queda en `GRADED`).
6. Consultar sus resultados/calificaciones y el detalle de un intento propio.

## Endpoints principales

Base: `/api/evaluations`

### Docente (`/teacher`)
| Método | Ruta | Acción |
|--------|------|--------|
| POST | `/teacher` | Crear evaluación |
| GET | `/teacher` | Listar evaluaciones propias |
| GET | `/teacher/{evaluationId}` | Detalle de evaluación propia |
| PUT | `/teacher/{evaluationId}` | Editar evaluación |
| POST | `/teacher/{evaluationId}/questions` | Agregar pregunta con alternativas |
| PUT | `/teacher/{evaluationId}/questions/{questionId}` | Editar pregunta y alternativas |
| PATCH | `/teacher/{evaluationId}/questions/{questionId}/deactivate` | Desactivar pregunta |
| PATCH | `/teacher/{evaluationId}/publish` | Publicar evaluación |
| PATCH | `/teacher/{evaluationId}/archive` | Archivar evaluación |
| POST | `/teacher/{evaluationId}/assignments` | Asignar a grado/sección |
| PATCH | `/teacher/{evaluationId}/assignments/{assignmentId}/deactivate` | Desactivar asignación |
| GET | `/teacher/{evaluationId}/results` | Resultados de la evaluación (agregados + intentos) |
| GET | `/teacher/{evaluationId}/results/summary` | Solo los agregados de resultados |
| GET | `/teacher/attempts/{attemptId}/result` | Detalle del resultado de un intento (con alternativa correcta) |

### Estudiante (`/student`)
| Método | Ruta | Acción |
|--------|------|--------|
| GET | `/student` | Listar evaluaciones disponibles |
| GET | `/student/{evaluationId}` | Ver detalle de evaluación asignada |
| POST | `/student/{evaluationId}/attempts` | Iniciar intento |
| GET | `/student/attempts/{attemptId}` | Ver intento |
| POST | `/student/attempts/{attemptId}/answers` | Guardar/actualizar una respuesta |
| POST | `/student/attempts/{attemptId}/submit` | Enviar intento |
| POST | `/student/attempts/{attemptId}/events` | Registrar incidencia de salida de pestaña (solo si `trackTabExit`) |
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
  **no recibe** el campo `correct` de las alternativas.

## Validaciones de negocio

**Docente**
- El título es obligatorio; `1 <= maxAttempts <= 10`; `timeLimitMinutes` entre 1 y 240 si se envía.
- La configuración avanzada (`allowChemicalCalculator`, `trackTabExit`,
  `questionDisplayMode`) toma valores por defecto seguros si no se envía.
- No publicar sin preguntas activas, ni con preguntas sin alternativas.
- Cada pregunta de alternativa única debe tener exactamente una alternativa correcta.
- No asignar una evaluación archivada ni duplicar una asignación activa en la misma sección.

**Estudiante**
- No iniciar un intento si la evaluación no está asignada a su sección.
- No superar `maxAttempts` ni tener más de un intento `IN_PROGRESS` a la vez.
- Si la asignación tiene `dueAt` vencido, se bloquea el inicio del intento.
- No responder preguntas ajenas a la evaluación ni elegir alternativas ajenas a la pregunta.
- No enviar un intento ya enviado.
- No guardar ni enviar respuestas una vez vencido el tiempo (con su margen de gracia).
- Solo registrar incidencias de salida de pestaña de su **propio** intento y únicamente
  si la evaluación tiene `trackTabExit` activo.

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

Las **incidencias de salida de pestaña** se registran como eventos del intento
(`evaluation_attempt_events`), **no** como logs globales de auditoría, para no saturar el
visor administrativo.

## Configuración avanzada del intento

El docente define, al crear o editar la evaluación, un conjunto de reglas que el
estudiante debe respetar al rendir. Todas tienen un valor por defecto que **preserva el
comportamiento histórico**, de modo que las evaluaciones existentes no cambian.

| Configuración | Campo | Por defecto | Qué hace |
|---------------|-------|-------------|----------|
| Calculadora química | `allowChemicalCalculator` | `false` | Habilita el acceso a la herramienta de apoyo químico durante el intento. |
| Detección de salida de pestaña | `trackTabExit` | `false` | Permite registrar incidencias de pérdida de foco/cambio de pestaña asociadas al intento. |
| Modo de preguntas | `questionDisplayMode` | `ALL_AT_ONCE` | `ALL_AT_ONCE` muestra todas las preguntas juntas; `ONE_BY_ONE`, una por pantalla. No afecta la calificación. |
| Límite de intentos | `maxAttempts` | `1` | Intentos permitidos por estudiante (1 a 10). |
| Tiempo máximo | `timeLimitMinutes` | `null` (sin límite) | Minutos para resolver (1 a 240) desde el inicio del intento. |

### Qué valida el backend y qué maneja el frontend

- **Backend (autoridad):** persiste y respeta la configuración; impide superar
  `maxAttempts`; controla el tiempo al guardar/enviar; solo registra incidencias de
  salida de pestaña si `trackTabExit` está activo y el intento es del propio estudiante;
  nunca expone la alternativa correcta antes de tiempo. El frontend **no** puede evadir
  estas reglas.
- **Frontend (experiencia):** muestra la pantalla previa con las reglas; presenta las
  preguntas según el modo; muestra el contador regresivo y dispara el envío automático
  al agotarse el tiempo; muestra/oculta el acceso a la calculadora; detecta la pérdida
  de foco y la reporta. Es una capa de usabilidad, no de seguridad.

### Calculadora química

`allowChemicalCalculator` solo controla si el estudiante **ve y puede abrir** la
herramienta de apoyo químico durante el intento. La calculadora **reutiliza** el motor
químico ya existente del proyecto (no se duplica lógica química) y **nunca** tiene
acceso a la clave de respuestas de la evaluación: opera sobre fórmulas/nomenclatura
general, no sobre las alternativas correctas. Si está desactivada, el acceso no se
muestra.

### Detección de salida de pestaña

Cuando `trackTabExit` está activo, el frontend detecta `visibilitychange` y `blur/focus`
y reporta el evento al endpoint `POST /student/attempts/{attemptId}/events`. El backend:

- registra solo `attemptId`, tipo de evento, momento y una descripción breve;
- descarta duplicados idénticos dentro de una ventana corta (throttling simple);
- rechaza el registro si la evaluación no tiene `trackTabExit` o si el intento no es del
  estudiante autenticado.

Es una **detección básica** de pérdida de foco del navegador, no un bloqueo ni una
vigilancia perfecta. El docente ve un **contador simple** de salidas por intento en sus
resultados (`tabExitCount`); no hay todavía un panel de trazabilidad detallado (queda
para una sesión futura).

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

Como la corrección de alternativa única es automática y completa, el intento pasa
directamente a estado `GRADED` y se registra `gradedAt`. Si por compatibilidad
existiera un intento terminal antiguo sin `score`, al consultar su resultado se
recalcula de forma segura (`ensureScored`) sin duplicar respuestas ni tocar intentos
en progreso.

## Resultados y retroalimentación

**Qué ve el docente**
- Lista de resultados de su evaluación: por cada intento terminal, el estudiante
  (código, nombre, grado/sección), número de intento, estado, `score`/`maxScore`,
  porcentaje y fechas de envío/calificación.
- Agregados de la evaluación: total de intentos, promedio de puntaje, porcentaje
  promedio, mayor y menor puntaje, y conteo de aprobados/desaprobados (umbral de
  aprobación: 60 %, usado solo para esos contadores).
- Detalle de un intento: corrección pregunta a pregunta con la **alternativa
  seleccionada, la alternativa correcta**, si fue correcta, puntaje obtenido/máximo y
  la explicación.

**Qué ve el estudiante**
- Siempre: título, `score`, `maxScore`, porcentaje, número de intento, estado y fecha
  de envío de sus propios intentos.
- Detalle por pregunta: su respuesta, si fue correcta y el puntaje obtenido.

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
