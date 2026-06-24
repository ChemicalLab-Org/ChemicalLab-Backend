# Métricas de usabilidad e interacción

Este documento describe la base de **métricas de uso** de ChemicalLab: cómo se registran las
interacciones educativas de los usuarios y en qué se diferencian de los **logs de auditoría**.

## 1. Diferencia entre logs de auditoría y métricas de uso

Son dos sistemas **independientes**, con tablas, entidades y endpoints separados. No deben
mezclarse.

| | Logs de auditoría (`system_logs`) | Métricas de uso (`usage_events`) |
|---|---|---|
| **Propósito** | Seguridad y trazabilidad administrativa | Interacción educativa y de producto |
| **Responden a** | ¿Quién hizo qué acción crítica? | ¿Cómo se usa la plataforma? |
| **Ejemplos** | Login, creación/edición de usuarios, reset de contraseñas, publicación de evaluaciones | Acceso a módulos, uso de la tabla periódica, formación de compuestos, apertura de contenidos/evaluaciones, clics relevantes |
| **Entidad** | `SystemLog` | `UsageEvent` |
| **Servicio** | `AuditLogService` | `UsageMetricService` |
| **Consulta** | `/api/admin/logs/**` | `/api/admin/usage-metrics/**` |

> Una métrica de uso **nunca** debe convertirse en un log de auditoría ni duplicar un evento
> de seguridad ya registrado por `AuditLogService`.

## 2. Eventos registrados

**Módulos** (`UsageModule`): `DASHBOARD`, `PERIODIC_TABLE`, `COMPOUNDS`, `CONCEPTS`,
`EVALUATIONS`, `RESULTS`, `ADMIN`, `USERS`, `SYSTEM_STATUS`.

**Tipos de evento** (`UsageEventType`):

| Tipo | Cuándo se registra |
|---|---|
| `MODULE_ACCESS` | El usuario entra a un módulo principal |
| `IMPORTANT_CLICK` | Clic relevante dentro de un módulo (no cada botón) |
| `CONTENT_VIEW` | Se abre o visualiza un contenido conceptual |
| `EVALUATION_OPENED` | El estudiante abre el detalle de una evaluación |
| `EVALUATION_STARTED` | El estudiante inicia un intento |
| `COMPOUND_FORMATION_USED` | Se usa la formación de compuestos (intento/validación) |
| `PERIODIC_ELEMENT_VIEWED` | Se abre el detalle de un elemento |
| `RESULTS_VIEWED` | Se visualizan resultados de evaluación |

## 3. Datos permitidos

Cada `UsageEvent` guarda:

- `userId`, `username`, `userRole` — resueltos del **token**, nunca de la petición.
- `module`, `eventType` — obligatorios y validados.
- `resourceType`, `resourceId` — opcionales (p. ej. `EVALUATION` / `15`, o `ELEMENT` / `Na`).
- `description` — texto corto y legible.
- `metadata` — texto corto `clave=valor; clave2=valor2`, **sanitizado**.
- `occurredAt` — marca temporal del evento.

Ejemplos de metadata segura:

- Tabla periódica: `symbol`, `atomicNumber`, `category`.
- Compuestos: `compoundType`, `success`, `selectedElementsCount`.
- Contenidos: `contentId`, `category`, `status`.
- Evaluaciones: `evaluationId`, `questionCount`, `assignedSection`.
- Resultados: `evaluationId`, `roleContext`.

## 4. Datos prohibidos

Nunca se almacenan:

- contraseñas ni contraseñas temporales;
- tokens (JWT, API keys, credenciales);
- respuestas completas de evaluaciones ni alternativas seleccionadas;
- claves de respuesta / alternativa correcta;
- payloads completos o información innecesaria del usuario;
- datos personales sensibles.

La metadata se sanitiza en `UsageMetadataSanitizer` antes de guardarse: descarta claves que
contengan fragmentos sensibles (`password`, `token`, `answer`, `respuesta`, `correct`,
`alternativa`, `clave`, etc.), limita la cantidad de entradas (8), la longitud de cada valor
(120 caracteres) y la del texto serializado (500 caracteres). Ante la duda, descarta.

## 5. Endpoints

### Registro (cualquier usuario autenticado)

```
POST /api/usage-metrics/events
```

- Requiere autenticación. El usuario y el rol se toman del token; el cuerpo **no** acepta
  `userId` ni `role`.
- Cuerpo (`RecordUsageEventRequest`): `module` (obligatorio), `eventType` (obligatorio),
  `resourceType?`, `resourceId?`, `description?`, `metadata?` (mapa clave→valor).
- Responde `201 Created`. Cada usuario registra solo sus propias interacciones.

### Consulta (solo ADMINISTRADOR)

```
GET /api/admin/usage-metrics/summary?from=&to=
GET /api/admin/usage-metrics/recent?limit=&module=&role=
GET /api/admin/usage-metrics/by-module?from=&to=
GET /api/admin/usage-metrics/by-role?from=&to=
```

- `summary`: total general más desgloses por módulo, tipo de evento y rol. `from`/`to`
  (ISO date-time) son opcionales.
- `recent`: últimos eventos (DTO seguro), con filtros opcionales por `module` y `role`.
  `limit` por defecto 20, máximo 100.
- `by-module` / `by-role`: desgloses simples para tarjetas del panel.

Los filtros se aplican con `Specification` dinámicas (solo se agregan predicados de filtros
no nulos), evitando el error de PostgreSQL «could not determine data type of parameter».

## 6. Consideraciones de privacidad

- El cliente no puede falsificar la identidad ni el rol: ambos se derivan del token.
- Solo el ADMINISTRADOR consulta las métricas agregadas; DOCENTE y ESTUDIANTE únicamente
  registran sus propias interacciones.
- No se registran movimientos de mouse, grabaciones de pantalla, datos biométricos ni cada
  clic común de navegación. El alcance es deliberadamente acotado.

## 7. Uso esperado para el análisis del MVP

Esta primera versión permite medir el **uso real** del sistema sin construir todavía
analítica avanzada: qué módulos se usan más, con qué frecuencia, por qué rol, y qué recursos
educativos se abren. Es la base para, más adelante, evaluar paneles más completos sin tener
que rediseñar el modelo de datos.

**Fuera de alcance en esta versión:** dashboards con gráficos avanzados, exportación
Excel/PDF, analítica predictiva, seguimiento de mouse/pantalla y tracking invasivo.
