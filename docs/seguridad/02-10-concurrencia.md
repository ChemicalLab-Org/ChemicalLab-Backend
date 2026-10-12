# T10 — Concurrencia actual / VER-02

12 de octubre de 2026, America/Lima. Trabajo para revisión en
`security/t10-concurrencia`, sin merge. Base backend actualizada mediante fetch:
`67833d10aea1d9d19fce3e3ed92ecf1aa8520ad5`; frontend de referencia:
`ed068f7bc268d0b2cfa3904b1b2a152414a8883c`. No había avances remotos posteriores
al empezar. Los checkouts originales y los anteriores permanecen conservados.
Se buscaron AGENTS.md en ancestros, repositorios y checkouts: ninguno aplicable.
Se leyeron la auditoría original, el cierre local T09 y las notas pertinentes
T02–T09 de ambos repositorios. Esta nota complementa esas fuentes, sin editarlas.

## Resultado y alcance

La base integrada conserva las garantías de intentos de T05 bajo los escenarios
probados. Se reprodujeron y corrigieron carreras en incorporación, presencia y
materiales: error 500 en incorporación repetida, incorporación posterior al cierre,
sobrescritura del permiso docente, dos archivos activos y reemplazo fallido tras
eliminación. También se reprodujo una métrica/consumo huérfano de incorporación
revertida al commit, y se aplazó su escritura hasta después de confirmar.

Las pruebas usan PostgreSQL 18 desechable en `127.0.0.1:55440`, base ficticia
`chemicallab_t01_test`, esquemas aleatorios `t03_<UUID>`. No se conectó a bases
escolares ni a servicios productivos. No se hicieron despliegues, instalaciones
globales, rotaciones, retención real, instituciones, invitaciones, códigos ni T11.
Los borrados y fallos deliberados afectan exclusivamente fixtures ficticias.

## Inventario y mecanismos

| Superficie | Rutas y transacciones | Restricción/coordinar actual |
| --- | --- | --- |
| Inicio | POST `/api/evaluations/student/{evaluationId}/attempts` | EvaluationService transaccional; T05 refresca/bloquea evaluación antes de comprobar asignación, estado, ventana, intento activo y conteo |
| Respuestas y finalización | POST `/api/evaluations/student/attempts/{id}/{answers,submit,exit}`; GET del intento puede recuperar cierre vencido | EvaluationAccessLock bloquea evaluación y después intento; refresca asignación; mismo protocolo docente para archivo/configuración/cierre de asignación |
| Respuestas | Upsert por intento/pregunta, avance ONE_BY_ONE, calificación y timeline | El bloqueo T05 cubre lectura y escritura; @Transactional por sí solo no sería suficiente. No se añadió unicidad nueva ni se cambió la política temporal |
| Incorporación | POST `/api/whiteboards/student/{id}/join` | Única `(session_id,student_id)` ya existente; ahora refresca/bloquea pizarra antes de leer estado y participante |
| Presencia/permisos/cierre | SEND `/app/whiteboards/{id}/presence`; PATCH docente de interacción global/individual; POST docente `/{id}/close` con snapshot | Presencia alumno ahora usa el mismo lock de pizarra que join, controles docentes y dibujo T03; no vuelve a guardar un permiso leído antes del cambio docente |
| Archivos/enlaces | POST `/api/concepts/teacher/{id}/materials/{file,link}`; DELETE `/{id}/materials/{materialId}` | Se bloquea/refresca el contenido antes de buscar/crear/reemplazar/eliminar material. El parser T06 valida antes de adquirir el lock de contenido |
| Cuotas | Triggers T07-B sobre materiales y storage_usage | Reserva atómica por cuenta/recurso, bytes reales y deltas transaccionales; compartida entre contenidos y procesos |
| Auditoría | AuditLogWriter MANDATORY + flush en negocio | T08 conserva SUCCESS junto a la escritura y su rollback; rechazo/fallo se registra aparte con sus límites existentes |
| Difusión y métrica de join | Control PARTICIPANT_JOINED sólo para fila nueva | Broadcast T03 ya era afterCommit. La métrica de mejor esfuerzo REQUIRES_NEW ahora también se inicia afterCommit, para no sobrevivir a rollback de join |

No hay mutex de JVM ni bloqueo global nuevo. Materiales diferentes y pizarras
diferentes no comparten estos locks; la cuota común se coordina en sus contadores
existentes. T05 sigue serializando una evaluación compartida, incluso entre alumnos:
es el mecanismo heredado que conserva configuración/fechas/resultados coherentes,
no una medición de su capacidad productiva.

## Matriz de carreras y persistencia

Antes: aplicación de develop `67833d1`, sin cambios en src/main/POM. Después:
implementación y tests del SHA que identifica la sección de verificación.
`ConcurrencyDbTest` verifica las filas finales mediante SQL, no sólo códigos HTTP.

| Operaciones / estado inicial | Resultado permitido e invariantes | Evidencia anterior | Evidencia posterior |
| --- | --- | --- | --- |
| Dos inicios, mismo alumno/evaluación, sin intentos | Un 201 y un rechazo 400; un IN_PROGRESS y número 1 | Tres rondas 201/400; T05 ya protege | Tres rondas; un activo y un número por alumno |
| Dos inicios consumen último intento de máximo 2, con primero terminal | Un 201/400; dos intentos en total, números 1/2; tras finalizar segundo no se admite tercero | Tres rondas conservan límite | Tres rondas, también rechazo con ambos terminales |
| Dos alumnos inician misma evaluación | Ambos 201, un activo de cada alumno | 201/201 | 201/201, dos alumnos distintos persistidos |
| Guardado y submit del mismo intento | Serialización válida; guardar primero puede aceptar; guardar tras submit se rechaza; una respuesta y resultado acorde | Tres rondas; respuesta del submit y nota 4, sin duplicados | Tres rondas más orden submit primero; estado GRADED, respuesta correcta, score 4; DTO oculta corrección mientras ventana abierta |
| Dos submits y reenvío | Un 200/400 y reenvío 400; un hito y un SUCCESS de finalización | 200/400; T05 protege | Mismo resultado; ninguna recalificación o fila adicional |
| Submit y exit; replay de avance ONE_BY_ONE | Una finalización/hito/SUCCESS; una respuesta y un avance | Mecanismo T05 inventariado, sin modificación | Dos procesos; una finalización; ONE_BY_ONE 200/400 y currentQuestionIndex=1 |
| Dos primeras incorporaciones del mismo alumno | Ambas 200 idempotentes respecto a participante; una fila, sin reiniciar permisos | Tres rondas 200/500; única DB impide duplicado, pero segundo request falla | Tres rondas 200/200, una fila |
| Dos alumnos se incorporan a misma pizarra | Ambos 200, una fila por alumno | Restricción por pareja inventariada | Ambos 200; dos participantes |
| Join observa ACTIVE y cierre compite | Join debe ordenarse antes del cierre o rechazarse después; ningún alta posterior al cierre confirmado | Barrera antes de INSERT: cierre 200 y luego join 200, fila creada en CLOSED | Cierre primero bajo barrera de fila: 200/400, cero participantes nuevos, CLOSED |
| Permiso docente BLOCKED y rejoin/presence concurrentes | El latido/rejoin sólo cambia lastSeenAt; BLOCKED debe permanecer | Dos procesos y STOMP real: ambos casos terminan FOLLOW_GLOBAL | Ambos terminan BLOCKED y mantienen participante/estado coherentes |
| Dos archivos, contenido sin archivo | Ambas subidas 201 ordenadas como alta/reemplazo; una identidad activa, bytes de archivo completo | Tres rondas 201/201 dejan dos FILE activos | Tres rondas 201/201, mismo materialId y una fila; un ADDED y un REPLACED por ronda |
| Dos reemplazos, archivo existente | Ambas 201, misma identidad; archivo final completo de uno de los contendientes; cuota exacta | Escrituras SQL serializan la fila, pero no protegen la decisión de alta/eliminación | Tres rondas; bytes finales iguales a uno de los PNG normalizados, tamaño real y contadores correctos |
| Delete primero y reemplazo concurrente con bytes diferentes | DELETE 204 y nueva alta 201; archivo nuevo válido, sin huérfanos | 204/503: reemplazo usa entidad eliminada; cero filas finales; rollback sin SUCCESS de reemplazo | 204/201, una fila nueva; contadores reales |
| Dos deletes del mismo material | 204/404, una eliminación y ningún éxito duplicado | Riesgo de lectura previa inventariado | 204/404; cuota de filas/bytes vuelve a cero |
| Subida inválida concurrente con reemplazo válido | Inválida 400, conserva original hasta commit válido; ningún éxito del rechazo | T06 se conserva | 400/201; sólo un SUCCESS nuevo, material previo intacto durante el rechazo |
| Dos contenidos del mismo docente consumen última cuota | Un 201 y un 409 STORAGE_QUOTA_EXCEEDED; una fila y bytes exactos; sesión válida | Trigger atómico T07-B ya existente | Dos procesos bloqueados en contador compartido; 201/409, una alta/SUCCESS, /auth/me 200 |
| Destino de auditoría rechaza reemplazo, retiro o permiso | 503, rollback de bytes/fila/cuotas/permiso; cero SUCCESS nuevos; recuperación legítima | Atomicidad T08 existente | Trigger sintético de fallo: todas revierten; retiro no pierde archivo; permiso permanece FOLLOW_GLOBAL; al recuperar, reemplazo 201 |
| Join falla en commit por constraint trigger diferido | Cero participantes, cero métrica/consumo de una incorporación fallida | 500, cero participantes, pero métrica y USAGE_COUNT aumentan uno | Mismo fallo sintético: cero delta; join posterior 200 y sesión válida |
| Histórico sintético con dos FILE activos | No deducir cuál conservar ni borrar; reportar conflicto | Duplicados reproducidos en alta concurrente | 409 MATERIAL_STATE_CONFLICT; conserva dos archivos, cuotas y auditoría de éxito sin cambios |

La idempotencia de join se refiere a la fila de participante y su permiso: los
rejoins legítimos conservan la marca de última conexión y la telemetría de uso
previa. Submit/exit no prometen devolver otra vez un 200: su contrato rechaza el
intento ya enviado con 400 y no duplica efectos. No se inventó una clave de
idempotencia universal ni se cambió la semántica de reintentos académicos.

## Reproducción y coordinación real

`ConcurrencyServer` arranca cada backend en una JVM distinta con ProcessBuilder,
devtools restart deshabilitado, heap 384 MiB, puertos HTTP aleatorios y dirección
127.0.0.1. Comparten PostgreSQL, esquema y una clave exclusivamente ficticia.
El contexto del proceso de tests sólo prepara/consulta fixtures. Los contendientes
ejecutan HTTP contra los dos hijos; presencia usa STOMP real contra un hijo.
Los PID de los hijos y los resultados se guardan en la evidencia.

La barrera abre una conexión JDBC independiente y mantiene un lock de fila. Los
requests se lanzan mediante latch; no se libera el lock hasta observar dos PID
PostgreSQL distintos en pg_stat_activity con wait_event_type=Lock. Para imponer
un orden se observa primero un contendiente bloqueado y luego el segundo. En la
reproducción de altas desprotegidas, un trigger BEFORE INSERT exclusivo del esquema
ficticio adquiere un advisory lock compartido retenido por la conexión de control:
ambos llegan después de leer que no existe la fila. Para join/cierre se espera a
ese punto y se confirma el cierre antes de liberar el INSERT. Las pausas de polling
sólo consultan una condición observada; no se usan como evidencia de simultaneidad.

Topes: arranque de cada hijo 90 s, polling de barrera 18 s, HTTP 25 s, futures 30 s,
lock_timeout de fixture 30 s; tres rondas para inicio/último intento, guardado/envío,
doble join, alta y reemplazos. No es carga sostenida. Conexiones de control se
cierran/revierten en finally; triggers de coordinación se retiran; stop files y
espera/terminación acotada cierran los hijos antes de DROP SCHEMA CASCADE. Los
esquemas son nuevos y guardados por host/puerto/nombre, nunca un esquema escolar.

Errores del arnés corregidos antes de aceptar evidencia: variable de lambda no
final; devtools reiniciaba el helper con argumentos de Spring; ID de material se
llama materialId; se intentó borrar auditoría append-only para contar desde cero
(se sustituyó por delta); un reemplazo con bytes idénticos no emite UPDATE y no
alcanzaba la barrera; presence exige cuerpo vacío. Las ejecuciones incompletas
se conservan localmente y no se contabilizan como suites pasadas.

La reproducción principal previa completa terminó con 9 pruebas, cero
fallos/errores/omisiones, validando explícitamente los comportamientos defectuosos
y los controles T05 existentes. No significa que la base fuera segura. La
reproducción adicional de rollback de métrica terminó con 1 prueba y los mismos
ceros; se hizo antes de cambiar recordJoinMetric, sobre la primera corrección de
locks, cuyo escritor de métricas aún era idéntico a develop.

## Compatibilidad, históricos y esquema

No hay cambios de esquema ni SQL de instalación T10. No se ejecutó DDL contra
bases existentes. El DDL de triggers de pruebas vive sólo en PostgreSQL desechable.
Se mantienen FK, única de participantes, cuotas T07-B y auditoría T08. La garantía
de archivo único cubre los escritores de las APIs corregidas que adquieren el lock;
no pretende impedir SQL externo ni a un administrador que desactive controles.
Todas las instancias escritoras deben adoptar el protocolo antes de reanudar
escrituras: mezclar una versión anterior que lee antes de bloquear no acredita
esta garantía. No se realizó ese cambio operativo.

Antes de una futura actualización autorizada, revisar mediante consultas de sólo
lectura los contenidos con más de un FILE activo, participantes duplicados si una
base antigua careciera de la única, y pares evaluación/alumno con dos IN_PROGRESS,
números duplicados o conteos sobre maxAttempts. Por ejemplo:

```sql
SELECT concept_content_id, array_agg(id ORDER BY id) AS material_ids
FROM concept_materials WHERE active AND type = 'FILE'
GROUP BY concept_content_id HAVING count(*) > 1;
```

Esta consulta es preparación documentada, no ejecución en datos reales. Un
histórico inconsistente no se repara ni elimina automáticamente. El reemplazo
reporta 409 MATERIAL_STATE_CONFLICT y conserva todo; decidir reconciliación,
backup, responsables y ventana con datos reales queda fuera de T10. Los permisos
de retiro existentes no son una orden de purgar esos históricos.

T05 conserva `[startAt,dueAt)`, duración sin gracia, cierre por archivo/desactivación,
respuestas tardías ignoradas, calificación manual y exposición tras cierre de todas
las oportunidades y de la nota. T06 conserva parser acotado, normalización,
rechazo de bytes/tipos falsos y entrega autenticada. T07-B conserva deltas por bytes
reales, cuotas entre recursos y rollback; T08 conserva auditoría de éxito atómica.

No hace falta cambiar Angular. Su interceptor sólo borra sesión por un 401 vigente;
400/404/409/503 de negocio conservan autenticación. Se ejecutaron las pruebas
existentes query-pagination.spec.ts y material-delivery.spec.ts sobre frontend
`ed068f7`: 11 aprobadas, cero omitidas, dos archivos; es ejecución parcial, no suite
frontend completa ni navegador E2E nuevo. No se crea una segunda PR artificial.

## Comandos y verificación

La implementación realmente probada es
`c21b15eee974aad640c2ff9a9c7e7e78869eebd9`. La entrega añade después únicamente
esta nota y evidencia JSON: no altera src/main, src/test, POM ni recursos respecto
a ese SHA. El HEAD publicado se entrega con la PR; no se atribuye una nueva
ejecución al commit documental.

| Ejecución | Alcance y resultado | Identidad / fecha local |
| --- | --- | --- |
| Reproducción principal | Selección parcial de 9 casos; 9/0/0/0 (tests/fallos/errores/omisiones), comportamientos defectuosos afirmados | src/main/POM de `67833d1`; 08:41:12 |
| Reproducción adicional | 1 caso de rollback diferido; 1/0/0/0, consumo huérfano demostrado | Escritor de métrica original, primera corrección de locks; 08:47:06; no es el SHA final |
| Corrección intermedia | Selección parcial de concurrencia y unitarias: 62/0/0/0 | Antes del arreglo de métrica y de ampliar a 17 casos; 08:44:45; no se cuenta como suite final |
| Backend final | `clean package` completo: 593/0/0/0, BUILD SUCCESS; incluye los 17 casos de dos procesos y todas las regresiones existentes | `c21b15e`; 08:57:42, duración 08:55 min |
| Artefacto final | 10 controles empaquetados: PDF/PPT/PPTX/PNG/JPEG válidos y truncados, comando hijo con outer JAR y límites comprobados | Mismo JAR de package; 09:01–09:02 |
| Frontend | Selección parcial de dos archivos existentes: 11 aprobadas, cero omitidas; no se repitió suite completa ni build de producción | `ed068f7`; 08:38:42, duración 24.66 s |

SHA-256 del JAR probado:
`8eb9affcb5ef60c6a4e42229ba0d4daecbe74fff03ce855f57b1bfc8c125c316`.
Las 593 pruebas se verificaron tanto en el resumen Maven como sumando los XML
Surefire del clean final. No hubo omisiones de las pruebas DB por falta de conexión.
El warning Angular de polyfills.ts fuera del programa de type-check ya existente
permanece; no se lo presenta como resuelto.

Limpieza comprobada después: cero esquemas t03_/t10_ y cero otras conexiones en
la base ficticia; PID backend 24516 y 29496 terminados; pg_ctl detuvo únicamente
el clúster t10-postgres. Hashes previos y finales coinciden en los 8 documentos
locales de seguridad y los 16 archivos E2E. Auditoría original SHA-256
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Frontend original sigue en feature/validaciones-datos-institucionales, con el
directorio E2E previo sin seguimiento; backend original develop limpio.

Se observó el estado automático Vercel success del frontend develop `ed068f7`:
[deployment existente](https://vercel.com/carlos-morales-projects1/chemical-lab-frontend/5baxQiva7x7vFQrGyKYoSL4tHcbS).
Es evidencia heredada del frontend sin modificaciones T10; no se abrió una preview
nueva ni se realizó despliegue manual. Los estados de la PR backend se informan
al entregarla, sin confundir CI externo con estas verificaciones locales.

Maven 3.9.15 ya cacheado por el wrapper, Java local 17.0.12; Node local 24.13.0 y
npm 11.6.2. No son patches productivos certificados. Creación local: initdb en
`.codex-work/t10-postgres/data`, pg_ctl con loopback:55440, shared_buffers=32MB y
max_connections=100; createdb crea únicamente chemicallab_t01_test. Trust local
ficticio no acredita TLS. La sandbox bloqueó una conexión loopback inicial y un
lanzamiento de Maven; se ejecutaron las mismas operaciones locales con aprobación
automática. No hubo acceso escolar ni cambio de software global.

Desde el checkout backend, con parámetros exclusivamente ficticios:

```text
mvn -o test -Dtest=ConcurrencyDbTest#simultaneousStartAndLastAttemptAcrossProcesses+differentStudentsMayStartOnSharedEvaluation+saveSubmitAndRepeatedSubmitHaveOnePersistedLifecycle+duplicateJoinHasOneParticipantAndControlledSuccess+simultaneousFileCreationKeepsOnlyOneActiveFile+closeCompetesWithFirstJoin+rejoinCannotOverwriteTeacherPermission+presenceCannotOverwriteTeacherPermission+deleteThenReplaceAndDuplicateDeleteAreControlled -Dt10.baseline=true -Dspring.profiles.active=test -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55440/chemicallab_t01_test -Dspring.datasource.username=postgres -Dspring.datasource.password=<ficticia>
mvn -o test -Dtest=ConcurrencyDbTest,ConceptMaterialServiceTest,WhiteboardSessionServiceTest -Dspring.profiles.active=test -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55440/chemicallab_t01_test -Dspring.datasource.username=postgres -Dspring.datasource.password=<ficticia>
mvn -o clean package -Dspring.profiles.active=test -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55440/chemicallab_t01_test -Dspring.datasource.username=postgres -Dspring.datasource.password=<ficticia>
java -Dloader.path=target/test-classes -Dloader.main=com.morales.chemicallab.security.files.PackagedFormatsProbe -cp target/chemical-lab-backend-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

El primer comando selecciona los nueve casos del arnés sobre la aplicación
base antes de corregir, no el src/main final. Para reproducirlo hoy usar un checkout
aislado de `67833d1` y copiar únicamente ConcurrencyDbTest/ConcurrencyServer desde
esta rama, sin trasladar implementación corregida. La propiedad
t10.join-rollback-baseline identifica la reproducción
individual del escritor original; nunca se activa en la suite final.

Frontend aislado, dependencias T09 locales reutilizadas mediante junction:

```text
npm test -- --watch=false --include=src/app/core/services/query-pagination.spec.ts --include=src/app/features/concepts/material-delivery.spec.ts
```

Los resultados completos finales, SHA probado, hashes y preservación se registran
en `t10/validation.json`. Los snapshots de respuestas/filas ficticias se guardan
en `t10/baseline-results.json`, `t10/join-rollback-before-results.json` y
`t10/final-results.json`; no contienen JWT,
credenciales reales, contenido escolar ni logs completos. Los logs locales están
en `.codex-work/t10-evidence/`.

## Balance T01–T10 y límites que permanecen

| Etapas | Corrección previamente integrada / resultado actual | Límite pendiente |
| --- | --- | --- |
| T01 | Configuración productiva explícita, secretos obligatorios, bootstrap y demos opt-in: SEC-01/02 | Inventario efectivo de entorno/cuentas, adopción operativa y claves reales |
| T02 | Cuenta activa, modo temporal limitado, sesiones/versiones/revocación REST: SEC-03/04/05 | Retiro operativo de credenciales reales; requests que ya pasaron autenticación pueden terminar conforme al contrato |
| T03 | Autorización STOMP, revocación continua, autoría/estado de objetos: SEC-06/15 | Broker simple no distribuye eventos entre instancias; infraestructura y capacidad efectiva no certificadas |
| T04 | Login uniforme, límites compartidos y política/hash: SEC-07/08/09 | Ajuste de costes, proxy/origen real, dimensionamiento y monitorización |
| T05 | Ventanas/cierre/exposición de corrección: SEC-11/12 | Históricos/operación; T10 acredita sólo concurrencia acotada sobre sus locks actuales |
| T06 | Formato real y entrega/validadores acotados: SEC-10 | No es antimalware ni sandbox OS; presupuesto real de contenedor y lectores externos |
| T07-A/B | Entradas/transporte acotados, páginas/agregados SQL, cuotas y retención técnica apagada: SEC-13 | Medición sostenida con distribución acordada, planes/latencias/memoria/índices y coste de backfill; aprobación de cuotas, alertas, crecimiento, permisos reales y plazos/impacto de retención |
| T08 | Auditoría de éxito durable y atómica, rechazo/fallo clasificados: SEC-14 | Instalación autorizada, privilegios DB/almacenamiento, alertas externas, backup/reloj y cobertura limitada de rechazos |
| T09 | Dependencias de aplicación/build corregidas y evidencia SBOM/SCA: VER-01 parcial | Jetty Site, JDK/Node vigentes, imagen real/digest/OS/JRE y PostgreSQL/TLS real |
| T10 | VER-02: errores reproducidos corregidos; intentos protegidos bajo las condiciones probadas; garantías entre procesos | No certificación de carga, infraestructura productiva ni de toda la seguridad |

SEC-13 conserva esos pendientes de operación: esta matriz de carreras no satisface
las mediciones sostenidas que notas T07-B habían señalado como T10. El alcance
actual no autoriza transformarlo en una certificación de infraestructura. Retención
real permanece deshabilitada y no se aprobó una política de privacidad/conservación.

VER-01 sigue parcialmente corregido: Jetty Site mantiene los findings y aviso
oficial descritos en T09, no se usó site:run; Java/Node locales no se actualizaron;
no se construyó ni escaneó imagen real, ni se inspeccionó DB/TLS productivo. La
ausencia de una carrera en estas pruebas no prueba imposibilidad absoluta. No se
declara cerrada toda la seguridad ni lista la plataforma para SaaS. Detener en PR
para revisión, sin merge ni T11.
