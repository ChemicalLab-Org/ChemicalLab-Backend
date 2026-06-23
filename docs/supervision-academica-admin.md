# Panel administrativo de supervisión académica

Este documento describe el alcance del **panel de supervisión académica** del rol
`ADMINISTRADOR`. El panel es de **solo lectura** y permite revisar, de forma
centralizada, el avance académico del sistema sin reemplazar al rol `DOCENTE` en la
gestión pedagógica (creación, edición, publicación, asignación o calificación).

## Principio rector

El administrador **supervisa**; el docente **gestiona**. Ninguna pantalla ni endpoint
de supervisión permite crear, editar, publicar, asignar, desasignar, eliminar ni
calificar. El administrador tampoco accede a la clave de respuestas de las
evaluaciones ni altera notas o resultados.

| El administrador puede… | El administrador no puede… |
|--------------------------|-----------------------------|
| Ver el resumen académico general | Crear o editar contenidos |
| Supervisar contenidos conceptuales (metadatos) | Crear, editar o publicar evaluaciones |
| Supervisar evaluaciones (metadatos) | Asignar o desasignar recursos |
| Supervisar asignaciones a grados/secciones | Ver la alternativa correcta / clave de respuestas |
| Revisar la actividad general reciente | Alterar notas, intentos o resultados |

## Endpoints (solo lectura, rol ADMINISTRADOR)

Todos cuelgan de `/api/admin/**`, restringido a `ADMINISTRADOR` en `SecurityConfig`.
No existen endpoints `POST`, `PUT`, `PATCH` ni `DELETE` para la supervisión.

| Endpoint | Descripción | DTO de respuesta |
|----------|-------------|------------------|
| `GET /api/admin/academic-supervision/summary` | Conteos académicos generales. | `SupervisionSummaryResponse` |
| `GET /api/admin/academic-supervision/concepts` | Contenidos conceptuales con docente autor, estado, categoría y secciones asignadas. | `SupervisionConceptResponse` |
| `GET /api/admin/academic-supervision/evaluations` | Evaluaciones con docente autor, estado, número de preguntas, asignaciones e intentos enviados. | `SupervisionEvaluationResponse` |
| `GET /api/admin/academic-supervision/assignments` | Vista unificada de asignaciones de contenidos y evaluaciones. | `SupervisionAssignmentResponse` |
| `GET /api/admin/academic-supervision/activity` | Actividad reciente (reutiliza `AdminService.getActivity`). | `AdminActivityResponse` |

### Garantía sobre la clave de respuestas

`SupervisionEvaluationResponse` es una vista de **metadatos**: no contiene preguntas,
alternativas ni la clave de respuestas. Para el detalle de preguntas existe el
endpoint previo `GET /api/evaluations/admin/{id}`, que responde con
`AdminEvaluationDetailResponse` / `AdminEvaluationOptionResponse`, DTOs que omiten de
forma deliberada el campo `correct` y la explicación. En ninguna ruta administrativa
viaja la alternativa correcta; puede verificarse desde la pestaña Network del
navegador.

## DTOs seguros

Se usan DTOs específicos para la supervisión, evitando exponer entidades completas o
campos innecesarios:

- `SupervisionSummaryResponse`: conteos agregados.
- `SupervisionConceptResponse` + `SupervisionSectionRef`: metadatos del contenido y
  secciones asignadas (sin el cuerpo completo del contenido).
- `SupervisionEvaluationResponse` + `SupervisionSectionRef`: metadatos de la
  evaluación (sin preguntas ni alternativas).
- `SupervisionAssignmentResponse`: asignación unificada de contenido o evaluación.

No se exponen contraseñas, contraseñas temporales, tokens ni payloads internos.

## Acceso por rol

| Situación | Resultado |
|-----------|-----------|
| `ADMINISTRADOR` con sesión | Accede al panel y consume los endpoints. |
| `DOCENTE` con sesión | `403` en backend; el frontend redirige a `/forbidden`. |
| `ESTUDIANTE` con sesión | `403` en backend; el frontend redirige a `/forbidden`. |
| Sin sesión | `401` en backend; el frontend redirige a `/auth/login`. |
| Ruta inexistente | Componente `not-found` (comodín `**`). |

## Frontend

La pantalla vive en la ruta protegida `/admin/academic-supervision` (guards
`[authGuard, temporaryPasswordGuard, roleGuard]` con `roles: ['ADMINISTRADOR']`) y se
ofrece en el menú lateral del administrador como **Supervisión académica**. Presenta
pestañas de Resumen, Contenidos, Evaluaciones, Asignaciones y Actividad, con estados
vacíos y manejo de error. No incluye acciones de edición ni botones que no funcionen,
y nunca muestra la clave de respuestas.
