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

Evidencia local fuera de la PR: `.codex-work/t12c-evidence/`, con SHA,
parche, comando, logs, exit y XML por ejecución. Una selección se cuenta desde
su resumen Maven, sin agregar XML residuales de otro test. El empaquetado
definitivo limpia target antes de ejecutar las ocho clases completas:
T12-A (`EvaluationDeliveryAccessDbTest`), T12-B (`InstitutionalEvaluationReadDbTest`),
T12-C (`InstitutionalAnswerSaveDbTest`), `EvaluationServiceTest`,
`EvaluationOpenQuestionPersistenceDbTest`, `EvaluationAccessPolicyTest`,
`EvaluationWindowHttpDbTest` y `ConcurrencyDbTest`.
No es la suite completa del repositorio; no se atribuyen aquí las ejecuciones
históricas de 904/926 pruebas ni se suman selecciones a las suites completas.

Intentos de desarrollo independientes, pruebas/fallos/errores/omitidas:

| Ejecución | SHA de código, o base + parche conservado | Resultado y límite |
| --- | --- | --- |
| compile-1 | `70ee61f6250efdfac9ee758f26b2e9b066623852` + parche | 4/0/0/0; política T05, sin atribuir el parche a develop |
| selection-1 | `9bbb6e8ac866b03d988189b9e0a8b23dc96f99cd` | 1/0/1/0; loopback bloqueado por sandbox |
| selection-2 | `0361b2866551ac75f4174afd7d856f0b2682078f` | 1/0/1/0; dos triggers nuevos compartían nombre |
| selection-3 | `e7c5b1074d475549e3cf7d36531f84075283580e` | 1/1/0/0; serialización horaria distinta de referencias B10 |
| diagnostic-4 | `e7c5b1074d475549e3cf7d36531f84075283580e` + parche | Error de compilación del arnés: método origin no estático; sin JUnit |
| diagnostic-5 | `e7c5b1074d475549e3cf7d36531f84075283580e` + parche | 1/1/0/0; confirma el rechazo de referencias, con instrumentación temporal ya retirada |
| selection-6 | `76c97be872a914b13921f893b369f334460545d9` | 2/2/0/0; precedencia de operadores JSONB, corregida con paréntesis |
| diagnostic-7 | `76c97be872a914b13921f893b369f334460545d9` + parche | 1/1/0/0; confirma ese diagnóstico |
| selection-8 | `a264793dfa2a251f7b35134a96df9582493637d4` | 2/0/2/0; cliente de 15 s agotado por escaneos repetidos; se comparte la comprobación dentro del resolver y el cliente T12-C espera hasta 45 s |
| candidate-c, T12-C completa anterior | `1735418005a30451fa72b4e06d40e749ec5bef6d` | 19/3/0/0; recuperación selectiva sin dependencia btree_gist, aserción incompatible con campos null legacy y contendiente con lock_timeout. Sin JAR exitoso |
| critical-9 | `bdccb58ecdb4b2304637e04a8539db0eb5ffff3f` | 5/0/0/0; copia física/certificación/reintento, dos raíces/instituciones, ventana y carreras |
| deadline-10 | `31cc7c175ab7f096f50453557d329b87b059ffca` | 2/0/0/0; plazo vence después de persistir respuesta/recibo aún no confirmados y clave reutilizada entre instituciones |

Los fallos se conservan como fallos. No se deshabilitan restricciones ni se
ajustan fingerprints para volverlos verdes. candidate-c informó también un
carácter de una nueva cadena fuera de UTF-8: se corrigió su codificación antes
de critical-9. La comprobación independiente lee estrictamente todas las
fuentes Java como UTF-8. Los errores deliberados de auditoría/cuota/certificación
y los timeouts de contendientes pueden producir logs ERROR esperados; el
resultado JUnit verifica el rollback y el reintento, no se infiere de esos logs.

El clúster propio comenzó con los valores conservadores que initdb pudo elegir
en sandbox (20 conexiones/400 kB de shared_buffers). Se reinició para los ensayos
posteriores con **100 conexiones, shared_buffers=32 MB y max_locks_per_transaction=512**.
La primera conexión de preparación fue denegada por sandbox y un reintento de
createdb informó que la base ficticia ya existía; no fueron ejecuciones JUnit.
Los ensayos HTTP se ejecutan autorizados fuera de esa restricción, exclusivamente
en 127.0.0.1:55446 / chemicallab_t01_test / t12c_fixture, perfil test.

La recuperación definitiva usa pg_basebackup físico posterior al guardado y
pg_verifybackup, arranca una **copia en puerto aleatorio propio**, certifica B11
y T12-C, compara las tablas de t03/t06/t12c y repite HTTP con el mismo JWT y
operationId. No altera el primario ni emplea Hibernate reparador. La copia se
detiene en finally; firmas y logs nativos se archivan aparte. El dump selectivo
anterior falló, y su dump/log se conserva. Esta nueva comprobación no es PITR ni
se presenta como una reejecución de los ensayos WAL históricos B11.

El empaquetado definitivo `final-package` ejecutó las ocho suites completas sobre
`31cc7c175ab7f096f50453557d329b87b059ffca`, sin cambios de src/POM durante su
ejecución. El commit posterior de documentación conserva exactamente esas
fuentes y POM. Resultado: **194 pruebas, 0 fallos, 0 errores, 0 omitidas**.

| Suite completa | Pruebas | Fallos | Errores | Omitidas |
| --- | ---: | ---: | ---: | ---: |
| EvaluationDeliveryAccessDbTest (T12-A) | 22 | 0 | 0 | 0 |
| InstitutionalEvaluationReadDbTest (T12-B) | 27 | 0 | 0 | 0 |
| InstitutionalAnswerSaveDbTest (T12-C) | 21 | 0 | 0 | 0 |
| EvaluationWindowHttpDbTest | 10 | 0 | 0 | 0 |
| ConcurrencyDbTest | 17 | 0 | 0 | 0 |
| EvaluationServiceTest | 88 | 0 | 0 | 0 |
| EvaluationOpenQuestionPersistenceDbTest | 5 | 0 | 0 | 0 |
| EvaluationAccessPolicyTest | 4 | 0 | 0 | 0 |
| **Total** | **194** | **0** | **0** | **0** |

Comando del empaquetado (credencial exclusivamente ficticia):

```powershell
mvn -o -Dspring.profiles.active=test -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55446/chemicallab_t01_test -Dspring.datasource.username=t12c_fixture -Dspring.datasource.password=fictitious -Dtest=EvaluationDeliveryAccessDbTest,InstitutionalEvaluationReadDbTest,InstitutionalAnswerSaveDbTest,EvaluationWindowHttpDbTest,ConcurrencyDbTest,EvaluationServiceTest,EvaluationOpenQuestionPersistenceDbTest,EvaluationAccessPolicyTest clean package
```

Windows, JDK 17.0.12, Maven 3.9.15 offline, PostgreSQL 18.4 desechable,
configuración de memoria/conexiones indicada arriba. Maven terminó con exit 0,
**BUILD SUCCESS**, duración 45:58 min y marca final
`2026-10-15T10:44:35-05:00`. Se generó y reempaquetó el JAR Spring Boot
`chemical-lab-backend-0.0.1-SNAPSHOT.jar`. Se verificaron dentro del JAR las
clases nuevas, flags desactivados, los catorce SQL históricos intactos
(normalizando únicamente CRLF/LF para comparar el recurso empaquetado) y el
decimoquinto SQL aditivo con el prefijo B11 congelado íntegro.

La recuperación física de esta ejecución verificó el backup con pg_verifybackup,
arrancó la copia en 127.0.0.1:59614, certificó y comparó **130 tablas** de
t03/t06/t12c, y verificó lectura/reintento HTTP después de restaurar. Las firmas
anteriores y restauradas coinciden. La copia se detuvo al finalizar.

Artefactos locales conservados en `.codex-work/t12c-evidence/`; SHA-256:

| Artefacto | SHA-256 |
| --- | --- |
| verified-backend.jar | `07d263a423471eb436f0203876114fa8ea965fe7dd724438b1f435d34f357506` |
| verified-reports.zip (ocho XML y reportes) | `0d88c24cf0f4016fe730c90786662d6950ca69a8cecf4abff226edf5d331357c` |
| final-package.log | `c2ef7cea162395d76bd72cd205d2f09f5a98ac3bf885ab18addbc9d9f967061d` |
| signatures.json de recuperación | `37d818e9f318460c74a40fac702462f46cbaa348a7489d5136230f86e55e44e8` |
| pg_verifybackup.log | `0754d7d15ec01e79210439c42400038c0590df438c3a0b0f404b47d782030ea3` |

`verified-results.json` registra el detalle; `final-package-sha.txt`, comando,
exit y parche vacío de src/POM permiten atribuir la ejecución. El control de
preservación compara los 187 archivos previos protegidos (incluidos los 16 E2E),
HEAD/estado de frontend y checkout principal, y HEAD de los otros 27 checkouts.
Los documentos anteriores y los SQL históricos se comparan contra sus hashes
previos; solo este documento es nuevo. Resultado final del control: cero
diferencias, con los 187 archivos y los 27 HEAD ajenos preservados.

Permanecen pendientes T12 integral y etapa 4, consumidores productivos,
inventario/capacidad real de escritores, permisos operativos, T13/F03, P08,
SEC-13/VER-01, Linux/SCA y despliegue. Frontend, auditoría, documentos anteriores
y los 16 E2E se preservan. Entrega en PR **en borrador hacia develop**, detenida
para revisión; sin merge ni inicio del siguiente bloque.
