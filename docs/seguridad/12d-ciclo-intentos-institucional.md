# T12-D — Inicio, recuperación y finalización institucional LEGACY

15 de octubre de 2026, America/Lima. Base local/remota `develop` comprobada:
`bd3a1ca3cdcb23be54d47a1984ea2a340589a3f9` (T12-C/#82). Rama aislada
`feature/t12d-ciclo-intentos`, checkout `.codex-work/t12d-backend`.
Sin AGENTS.md aplicables ni delegación. Se contrastaron T11-D, T12-A/B/C,
B3/B8/B10/B11, T02/T05/T10, motor, repositorios, política y coordinación.

Este bloque continúa limitado a fixtures. T12 integral y la etapa 4 siguen
abiertos. No adopta motor NEW, revisión/calificación docente, frontend, STOMP,
invitaciones, datos reales ni permisos de operación productiva.

## Contrato HTTP y recuperación

Prefijo:

```text
/api/institutions/{institutionId}/classes/{classId}/evaluation-writes/{contract}/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}/own-attempts
```

| Operación | Método y sufijo | Cuerpo |
| --- | --- | --- |
| Inicio explícito | POST al prefijo | `operationId` UUID obligatorio |
| Finalización explícita | POST `/{attemptId}/submit` | `operationId` UUID obligatorio; `answers` opcional, máximo 200 |

Cada respuesta opcional de cierre contiene `questionId` positivo,
`selectedOptionId` positivo opcional y `answerText` opcional de hasta 3000
caracteres. El transporte acota los cuerpos institucionales a 1 MiB incluso
sin Content-Length (o el máximo HTTP configurado si es menor) y aplica el parser estructural T07-A: profundidad, cadenas,
propiedades, colecciones y detección de claves duplicadas. MVC valida además
los 200 elementos y sus campos. Los duplicados de pregunta en un cuerpo que
sí se vaya a procesar se rechazan. Un cuerpo ignorado por tiempo/ONE_BY_ONE
permanece acotado, pero no adquiere autoridad académica para sus IDs.

La cuenta procede exclusivamente del principal autenticado T02. Las acciones
`START_OWN_ATTEMPT` y `SUBMIT_OWN_ATTEMPT` son fijadas por el servidor. Inicio
requiere LEGACY, entrega disponible y relaciones/rol ESTUDIANTE actuales;
administración, docencia o permiso de guardar respuestas no suplen estas
acciones. El resolver conserva institución, pertenencia, clase/período,
matrícula, raíz lógica, entrega, destinatario, revisión y última cabeza F08.
No selecciona asignación por grado/sección: usa el `legacy_assignment_id`
exacto de la entrega autorizada. El motor comprueba también las condiciones
legacy de disponibilidad y sección; las etiquetas no crean autoridad.

Ambas operaciones devuelven 200, `Cache-Control: no-store`, y únicamente
operationId, attemptId, status, attemptNumber, startedAt y submittedAt. Nunca
devuelven entidades, puntuaciones, soluciones, corrección o feedback. La
revisión/corrección T12-B conserva íntegra la ocultación global T05. Un intento
GRADED no implica que la corrección ya sea visible.

Activación incompleta: 404. Sesión inválida/revocada: 401. Modo temporal:
403 PASSWORD_CHANGE_REQUIRED T02. Autorización o regla académica incumplida:
403 genérico. Reutilización incompatible de operación: 409 genérico. Entrada
mal formada/acotaciones MVC: 400; límite del transporte: 413. Fallo persistente,
de auditoría, cuota o coordinación: 503 genérico, sin SQL ni datos académicos.

La recuperación usa exclusivamente los GET T12-B existentes. La única ampliación
de su DTO es startedAt (Instant): permite reconstruir el temporizador al
recuperar. Para LEGACY convierte el timestamp persistido desde America/Lima;
para la simulación NEW conserva su instante, sin habilitar su motor. No llama al
`getAttempt` legacy, que puede finalizar y reparar un orden ausente. Los GET
institucionales no crean, reparan, recalculan ni cierran intentos. Se conservan
ID, inicio, orden, cursor, respuestas y consumo. P2 continúa validando cada ID
persistido de questionOrder, incluidos los inactivos sin respuesta.

## Una autoridad de intentos y cierre

El motor comparte `startAttemptMutation` y `submitAttemptMutation` con sus
rutas legacy; no existe otro motor ni un saldo operativo institucional.
El inicio mantiene el lock evaluación → intento y cuenta todas las filas
históricas de evaluación/cuenta, incluidas las inactivas o sin asignación.
Comprueba máximo y cualquier IN_PROGRESS, fija número, orden e inicio desde
el servidor y registra ATTEMPT_STARTED. Un activo de otra entrega no se
devuelve, repara, reasigna ni transfiere para permitir el inicio. Cambiar
entrega, revisión, clase, docente o matrícula no restituye consumo. Los
presupuestos B3/B8 continúan siendo preparación/observaciones.

La finalización exige intento propio activo, asignación y evaluación
disponibles, condiciones académicas y acceso institucional vigentes.
`EvaluationAccessPolicy.requireFinish` admite AVAILABLE o EXPIRED y excluye
SCHEDULED/UNAVAILABLE. La excepción afecta al plazo académico, no a una baja,
revocación, retiro de permisos, contexto incierto o recurso desactivado.
`SAVE_OWN_ANSWER` conserva su exigencia de ventana abierta.

La mutación de cierre comparte exactamente la semántica legacy: a tiempo y
ALL_AT_ONCE procesa las respuestas opcionales, exige abiertas obligatorias,
crea filas abiertas faltantes y califica alternativas. Fuera de tiempo ignora
el cuerpo y permite cerrar abiertas obligatorias vacías, usando solo lo ya
guardado. ONE_BY_ONE nunca reprocesa las respuestas del envío ni modifica
cursor/orden. Las abiertas no revisadas dejan PENDING_MANUAL_REVIEW; solo
alternativas produce GRADED y cierre automático. Se conservan los eventos
ATTEMPT_SUBMITTED y TIME_EXPIRED cuando corresponde. Finalizar libera el
activo sin borrar filas ni devolver consumo.

El reloj institucional procede de PostgreSQL, interpretando timestamps
académicos en America/Lima. Si se incorporaron respuestas del cierre, el
deadline exclusivo se comprueba nuevamente al final y el vencimiento dentro
de la unidad revierte todo; el reintento vencido podrá cerrar solo lo guardado.

## Sucesor, procedencia y compatibilidad

`t12d-v1.sql` contiene como prefijo íntegro el certificador T12-C congelado,
que a su vez conserva B11/B10. Añade exclusivamente el schema técnico
`t12d_<mismo hex>`; no modifica t12c-v1.sql ni los catorce SQL históricos,
aprobaciones, versiones o fingerprints anteriores. Repetir certifica, no
repara. El fingerprint D incluye funciones, columnas, constraints, índices y
estado, relaciones, triggers/enablement y configuración de secuencias, y
enlaza al fingerprint C. No hay DDL ni backfill automático.

La tabla técnica `saves` D es un journal de operaciones START/SAVE/SUBMIT,
compatible con la firma begin_save/finish_save de T12-C. No almacena intentos
de negocio ni reemplaza evaluation_attempts/evaluation_answers. Después de
instalar D, T12-C utiliza ese sucesor para permitir guardar sobre intentos
nuevos. Sin D conserva su contrato y SQL C originales.

BEGIN exige raíz aprobada y fotografía vigente B10, C o D, verifica contadores
B9 y captura petición, propuesta, versión raíz, predecesor, manifiesto,
cabezas, referencias y XID. FINISH compara fuentes reales antes/después usando
el scanner B10 congelado. Admite solo el intento exacto y sus respuestas y
eventos del tipo de operación; no admite eliminaciones ni cambios laterales.
El cierre puede cambiar únicamente estado/calificación/fechas inherentes del
intento y las respuestas previstas. El inicio verifica también el consumo
histórico y el activo anterior; no valida una oportunidad desde B3/B8.

Las filas nuevas reciben contexto derivado del destinatario/episodio aprobado
de la entrega exacta, con raíz, cuenta, institución, clase y zona temporal
demostrables. El journal conserva esas derivaciones sin inventar versiones
B10 históricas para PK nuevas. T12-A/B resuelven primero la última cabeza
explícita F08; solo en ausencia de versión de esa PK pueden usar la derivación
confirmada de su misma raíz actual, tras verificar el manifiesto completo.
Las derivaciones de C anteriores siguen siendo reconocibles.

Una corrección F08 posterior prevalece. Si vuelve incierto el intento, el
replay de inicio también lo resuelve y deniega devolver su recibo. Las
fotografías/aprobaciones originales permanecen comprobadas; alterar datos
fuera del protocolo produce STALE y no se legitima al repetir otra operación.
El propietario/DBA capaz de falsificar funciones/evidencia queda fuera de la
frontera certificada del fixture.

## Atomicidad, cuotas y reintentos

Unidad REQUIRES_NEW / READ_COMMITTED, hasta 30 segundos, lock_timeout 5 segundos.
Se conservan la guarda independiente y el orden T12-B/C: primero tabla
evaluations EXCLUSIVE para escritura, después todas las demás tablas del
fixture en orden estable SHARE ROW EXCLUSIVE, antes de locks de fila del
motor. Los lectores usan SHARE. Esta coordinación incluye escritores
T05/T10, B8/B10, sesiones, revocaciones, relaciones y fantasmas. Tras esperar
se observa el commit anterior, no una fotografía REPEATABLE_READ previa.

Intento, respuestas, notas internas, eventos, contexto/recibo sucesor,
auditoría T08 obligatoria y cuotas confirman o revierten juntos, con
synchronous_commit=on. Las mutaciones compartidas no emiten la auditoría
best effort de las rutas legacy: D añade su SUCCESS obligatorio mediante
AuditLogWriter MANDATORY, con IDs/actor/correlación y sin respuestas. El estado
pendiente manual conserva además EVALUATION_ATTEMPT_PENDING_REVIEW, también
obligatorio y dentro de la misma transacción. Un
constraint trigger diferido impide confirmar un recibo incompleto. La
evidencia terminada rechaza UPDATE/DELETE/TRUNCATE.

La medición sucesora conserva atribución de versiones F08 y de filas nuevas
de todas las raíces, mantiene límites B9 y compara crecimiento con la última
observación C/D o medición B10. No reescribe observaciones anteriores ni suma
dimensiones ACCOUNT/SYSTEM/INSTITUTION. Siguen vigentes los triggers legacy de
cuota. D tiene techo independiente de 10000 recibos / 64 MiB JSON.

La operationId liga tipo de operación, actor, selectores y contenido original,
además de su normalización académica. Se consulta solo después de adquirir
coordinación: perder la respuesta no demuestra rollback. El replay idéntico
retorna el resultado durable original sin duplicar intento, número, consumo,
evento o cierre. Revalida acceso actual y procedencia del intento; no exige
IN_PROGRESS a un recibo que produjo el terminal. Un cuerpo/contexto/actor
distinto recibe 409. La autorización puede haber vencido o sido retirada:
el recibo no es una capacidad durable ni concede acceso por sí mismo.

## Activación y recuperación

Por defecto permanecen false `app.evaluations.institutional.enabled`,
`answer-save-enabled` y `attempt-lifecycle-enabled`. D requiere además perfil
único test, schema aprobado explícito `t03_<32 hex>` igual al current_schema,
base exactamente chemicallab_t01_test, loopback 127.0.0.1 y sucesor D instalado.
El guard DB sigue siendo independiente de las propiedades Java. Guardar con
T12-C sigue requiriendo su propio flag. Las rutas legacy restantes no quedan
institucionalmente aisladas por esta entrega.

Aplicación explícita únicamente en un fixture C/B11 certificado, como archivo
transaccional completo. Repetir tras commit desconocido; ante deriva se debe
investigar, sin recalcular ledgers para aceptarla. Respaldo/restauración deben
incluir t03, t06, t12c y t12d juntos, dependencias completas, negocio,
aprobaciones, recibos, eventos, cuotas y auditoría. El journal no reemplaza WAL
ni respalda escritores ajenos o archivos externos.

Los ensayos de esta entrega reutilizan el arnés físico existente: backup con
pg_basebackup/streaming WAL, pg_verifybackup, arranque de copia en puerto propio,
certificación D y comparación de todas las tablas de esos cuatro schemas,
seguida de lectura/replay HTTP. Se limita a recuperar el estado nuevo; no se
repite ni se reatribuye el PITR/WAL histórico B11.

## Evidencia y pendientes

Las ejecuciones y artefactos se conservan en `.codex-work/t12d-evidence/`,
fuera del PR, con SHA/parche, comando, logs, exit y reportes por ejecución.
Los fallos de desarrollo se conservan como tales. El resultado definitivo,
SHA probado, suites completas, JAR y recuperación se registran tras completar
la verificación; una compilación o selección parcial no acredita el total.

Ejecuciones de desarrollo independientes; pruebas/fallos/errores/omitidas:

| Ejecución | SHA ejecutado | Resultado y límite |
| --- | --- | --- |
| selection-1 | `399c7d32e106e8d19087a6b862896510b44152f2` | 1/0/1/0; sandbox denegó conexión loopback; sin prueba de negocio |
| selection-2 | `399c7d32e106e8d19087a6b862896510b44152f2` | 1/1/0/0; alias SQL ambiguo en la nueva medición; inicio revertido |
| selection-3 | `59c7ba4833ecc63c1713fc0d9ce28cfe222e62f8` | 1/1/0/0; alias del predecesor incompleto; inicio revertido |
| selection-4 | `b7e553c52b5e85aed8df292ecf2527769d00894f` | 1/1/0/0; precedencia JSONB al validar el cierre; inicio/lectura/guardado previos confirmados, cierre revertido |
| candidate-d | `883bd3434bc1681a145d26c8442530401c38d729` | 21/0/1/0; veinte casos pasan, incluida recuperación física de 132 tablas; la carrera falla en el arnés por aplicar una prohibición de campo a `score:null` del DTO legacy. No prueba el resultado final de esa carrera |

El último error del arnés se corrigió verificando ausencia de corrección real
y nulidad del puntaje en el DTO legacy, mientras las respuestas institucionales
siguen exigiendo ausencia total del campo. No se relajaron restricciones de
negocio ni fingerprints. Las correcciones posteriores añaden procedencia del
replay, auditoría pendiente obligatoria, reloj de recuperación y seis casos D.
Estos cambios no se atribuyen retroactivamente a candidate-d.

La compilación inicial terminó con BUILD SUCCESS (sin JUnit ni JAR verificado).
Los intentos de preparación de PostgreSQL en sandbox informaron conexión
denegada y conexión rechazada; luego se inició únicamente el clúster propio
fuera de esa restricción. No son pruebas JUnit adicionales. Los ERROR
deliberados de auditoría/cuota/certificación se evalúan por las aserciones de
rollback y el resultado JUnit, no por su sola presencia en los logs.

La selección final abarca T12-A/B/C/D y las suites afectadas de política,
ventanas HTTP, guardado/envío legacy, abiertas y concurrencia; también la
regresión del filtro de entrada modificado. No se ejecuta toda la suite del
repositorio. Se comprueban JAR, SQL congelados, flags y preservación de
auditoría, documentos/evidencias previos, frontend y los 16 E2E.

Bloqueos globales, escaneo completo y serialización pertenecen al sobre de
fixtures existente. No acreditan rendimiento, capacidad real de escritores,
ausencia universal de deadlocks, DDL concurrente, permisos productivos,
inventario real, Linux/SCA, SEC-13/VER-01 ni operación escolar. Permanecen
pendientes T12 integral/etapa 4, T13/F03 y P08/P12. Entrega mediante commits y
PR en borrador hacia develop, detenida para revisión, sin merge, despliegue,
datos reales, DDL/backfill real o rotaciones.
