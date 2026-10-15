# T12-C — Guardado institucional de una respuesta LEGACY

15 de octubre de 2026, America/Lima. Base `develop`:
`70ee61f6250efdfac9ee758f26b2e9b066623852` (T12-B/#81).
Checkout aislado `.codex-work/t12c-backend`, rama
`feature/t12c-guardado-institucional`. Principal y otros checkouts conservados;
sin AGENTS.md aplicables al backend ni delegación.

Se contrastaron T11-D, T12-A/B, contratos SQL y mantenedores B10/B11,
T02/T05/T10, EvaluationService/saveAnswer/upsertAnswer, sus repositorios,
EvaluationAccessPolicy y EvaluationAccessLock. Este bloque adopta una única
escritura sobre fixtures; no reabre la preparación de etapa 3.

## Contrato y autorización

`POST /api/institutions/{institutionId}/classes/{classId}/evaluation-writes/LEGACY/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}/own-attempts/{attemptId}/answers`

Cuerpo: `operationId` UUID obligatorio, `questionId` positivo,
`selectedOptionId` positivo opcional y `answerText` opcional de hasta 3000
caracteres. Se guarda una respuesta en un intento propio existente. No hay
inicio, creación, finalización, calificación ni escritor NEW. El contrato NEW
se rechaza, aunque coincidan numéricamente sus IDs.

La cuenta procede exclusivamente de `SessionPrincipal` T02. La acción del
servidor `SAVE_OWN_ANSWER` vuelve a resolver institución, membresía y rol
ESTUDIANTE vigentes, clase/período, matrícula, raíz, revisión, entrega,
destinatario e intento. Usa las relaciones exactas y cabezas B10 actuales;
leer un intento, administrar, enseñar o presentar un ALLOW anterior no concede
escritura. Se validan también todos los hijos y cada ID de questionOrder,
incluidos los inactivos no respondidos (P2). La escritura rechaza órdenes
duplicados. Pregunta y opción deben existir, estar activas y pertenecer al
recurso/pregunta correspondientes, con contexto B10 válido.

`EvaluationService.saveAnswerMutation` comparte con la ruta legacy la
propiedad, IN_PROGRESS, disponibilidad, sección actual, actividad, duración,
ventanas `[startAt,dueAt)`, validación de alternativa/texto, orden ONE_BY_ONE,
prohibición de salto/retroceso y avance. La entrada institucional usa el reloj
PostgreSQL en America/Lima y no construye AttemptResponse. No crea intentos,
números, consumos ni presupuestos nuevos; LEGACY sigue siendo su autoridad.
Las rutas `/api/evaluations/**` mantienen su contrato y **no** pasan a estar
institucionalmente aisladas.

200 y `Cache-Control: no-store` devuelven exclusivamente operationId,
attemptId, questionId, selectedOptionId, answerText propio normalizado,
currentQuestionIndex y savedAt. No hay puntuaciones, correct, reviewed,
expectedAnswer, soluciones, claves, explicación ni feedback. Sesión inválida:
401; temporal: 403 PASSWORD_CHANGE_REQUIRED de T02; autorización/semántica:
403 genérico; clave incompatible: 409 genérico; activación incompleta: 404; fallo transaccional/persistente:
503 genérico. Los fallos no devuelven SQL ni entidades académicas.

## Por qué se necesita un sucesor

`t11p05_manifest` incluye filas completas y checksums de padres. Un INSERT o
UPDATE de respuesta modifica ese manifiesto; avanzar cambia además el intento
y los checksums de sus hijos. Por ello `t11p05_fresh` correctamente devuelve
false después de un guardado legítimo. Recapturar aprobaciones o reescribir
manifest/versiones anteriores alteraría la evidencia F08 y no es una solución.

`t12c-v1.sql` es aditivo: incorpora íntegramente el certificador B11 congelado
y añade el schema técnico `t12c_<mismo hex>`. Los catorce SQL y ledgers históricos
permanecen intactos. El censo B10 no gana fuentes ocultas ni exclusiones.

Una operación conserva petición exacta (actor, selectores y contenido original
y normalizado), propuesta y versión raíz aprobadas, predecesor, manifiestos
anterior/sucesor, cabezas, referencias, contexto derivado de respuesta, resultado,
auditoría, medición y XID completo. BEGIN verifica fotografía vigente antes de
capturarla. FINISH admite exclusivamente la respuesta seleccionada y el
orden/cursor del intento; compara las filas reales y rechaza cambios laterales.
Recalcula el manifiesto mediante el scanner B10 original. La fila incompleta
no puede confirmar: un constraint trigger diferido exige resultado/auditoría
y manifiesto sucesor. La evidencia completa no admite UPDATE/DELETE/TRUNCATE.

T12-A/B consultan primero `t11p05_fresh`, sin modificarlo. El puente puede
aceptar el sucesor exacto de esa misma propuesta APPLIED, únicamente si su raíz
sigue siendo cabeza, coinciden manifiesto/cabezas/referencias actuales y siguen
vigentes las dos fotografías originales F08. No pide otra doble aprobación por
respuesta. Una respuesta nueva obtiene contexto únicamente desde la respuesta
confirmada y el intento aprobado de ese sucesor. No se inventa una versión B10
histórica para un ID nuevo.

Una corrección F08 posterior cambia la cabeza: invalida todos los recibos del
mapa anterior. Si el nuevo mapa deniega o es incierto no se recupera autoridad
desde recibos antiguos. Una alteración directa antes del primer guardado o tras
un recibo produce divergencia del manifiesto y falla cerrada; el siguiente
guardado no la incorpora automáticamente. El propietario/DBA capaz de falsificar
funciones o evidencia sigue fuera de la frontera certificada del fixture.

## Atomicidad, reintentos y cuotas

Transacción independiente READ_COMMITTED de hasta 30 s, lock_timeout 5 s.
Se comprueban DB, loopback, schema aprobado y `t11b5_fixture` antes de bloquear.
Primero `evaluations`: SHARE para T12-B; EXCLUSIVE para T12-C, que espera
también los SELECT FOR UPDATE T05 anteriores. Después se bloquean las demás
tablas del fixture en orden estable (SHARE / SHARE ROW EXCLUSIVE), antes de
tomar los locks de fila evaluación → intento del motor. B8/B10 empiezan también
por evaluations. La lectura no intenta convertir su SHARE en escritura.

El intervalo protege revocaciones, membresías/grants, clase/matrícula,
contextos y versiones, fuentes, preguntas/opciones y escritores legacy,
incluidas inserciones fantasma. READ_COMMITTED observa al escritor que confirmó
antes de adquirirlo. Al final se resuelven otra vez las relaciones, se revalida
la sesión y se comprueba el plazo exclusivo con reloj DB: un vencimiento durante
el trabajo revierte la unidad. Un cambio posterior al punto de consistencia
puede confirmar antes de transmitir la respuesta ya materializada.

Respuesta, cursor, recibo/contexto sucesor, auditoría obligatoria T08 y cuotas
aplicables confirman o revierten juntos con synchronous_commit=on. El evento
EVALUATION_UPDATED / SAVE_INSTITUTIONAL_ANSWER solo contiene IDs/actor,
SUCCESS y correlationId; nunca texto de respuesta. Usa AuditLogWriter MANDATORY.
La medición sucesora conserva atribución B10 y los límites B9 existentes,
sin reescribir mediciones anteriores ni crear un balance vivo paralelo. Los
triggers legacy ACCOUNT/SYSTEM siguen vigentes; no se suman dimensiones.
El control T12-C tiene techo independiente de 10000 recibos/64 MiB JSON.

La misma operationId con idéntico actor/contexto/contenido devuelve el resultado
durable original, tras autorización actual, sin otro avance/evento/medición.
Una reutilización incompatible se rechaza con 409. Un contendiente puede agotar el lock_timeout y recibir 503; repetir después consulta el resultado durable sin duplicarlo. La coordinación se adquiere antes
de consultar el recibo: una petición que perdió la respuesta puede repetir
después de un commit desconocido; ausencia previa al lock no acredita rollback.
No se promete aceptar un reintento cuando la autorización o ventana ya venció.

Bloqueos globales, scanner completo y serialización son deliberadamente acotados
al ensayo ficticio. No certifican rendimiento, ausencia universal de deadlocks,
adopción de DDL concurrente ni inventario de escritores productivos.

## Activación, certificación y recuperación

Desactivado por defecto. Se requieren simultáneamente las barreras T12-B:
enabled=true, perfil único test, schema explícito `t03_<32 hex>` igual al
current_schema, DB exactamente `chemicallab_t01_test` y servidor 127.0.0.1;
además `app.evaluations.institutional.answer-save-enabled=true` y el sucesor SQL
instalado explícitamente. No hay DDL/backfill automático ni consumidor productivo.

En un fixture B11 certificado, aplicar el archivo completo transaccionalmente:

```powershell
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t12c-v1.sql
```

Repetir certifica y no repara. La firma nueva incluye columnas, constraints,
índices/estado, triggers/enablement, funciones, relaciones y configuración de
secuencias; conserva vínculo con el fingerprint B11. No recalcular un ledger
para aceptar deriva. Tras fallo, confirmar rollback y corregir la causa; ante
commit desconocido, repetir la misma petición bajo coordinación.

El respaldo debe incluir **t03, t06 y t12c juntos**, y sus extensiones/dependencias completas, incluidos negocio,
aprobaciones, recibos, cuotas y auditoría. Restaurar en instancia aislada,
sin Hibernate reparador; certificar el sucesor y comparar todas las tablas.
Para recuperación con escrituras posteriores, conservar WAL continuo de la
misma instancia conforme al contrato B11. Un journal de recibos por sí solo
no reemplaza WAL ni incluye escritores ajenos o archivos externos.

## Evidencia y pendientes

Las ejecuciones y hashes definitivos se registrarán aquí al terminar la
verificación. Los intentos fallidos se conservan separados en
`.codex-work/t12c-evidence/`, con SHA, parche, comando, logs, exit y XML.
No se atribuye evidencia histórica T12-A/B al código nuevo ni se suman
selecciones a suites completas.

Permanecen pendientes T12 integral y etapa 4, consumidores productivos,
inventario/capacidad real de escritores, permisos operativos, T13/F03, P08,
SEC-13/VER-01, Linux/SCA y despliegue. Frontend, auditoría, documentos anteriores
y los 16 E2E se preservan. Entrega en PR **en borrador hacia develop**, detenida
para revisión; sin merge ni inicio del siguiente bloque.
