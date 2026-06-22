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
| `maxAttempts` | Intentos permitidos por estudiante (mínimo 1) |
| `timeLimitMinutes` | Límite de tiempo en minutos (opcional) |
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
- El título es obligatorio; `maxAttempts >= 1`; `timeLimitMinutes` positivo si se envía.
- No publicar sin preguntas activas, ni con preguntas sin alternativas.
- Cada pregunta de alternativa única debe tener exactamente una alternativa correcta.
- No asignar una evaluación archivada ni duplicar una asignación activa en la misma sección.

**Estudiante**
- No iniciar un intento si la evaluación no está asignada a su sección.
- No superar `maxAttempts` ni tener más de un intento `IN_PROGRESS` a la vez.
- Si la asignación tiene `dueAt` vencido, se bloquea el inicio del intento.
- No responder preguntas ajenas a la evaluación ni elegir alternativas ajenas a la pregunta.
- No enviar un intento ya enviado.

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
