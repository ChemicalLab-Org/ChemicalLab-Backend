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
Antes de proyectar filas legacy, la misma instancia del resolver B10 de T12-A
comprueba también sus preguntas, opciones y respuestas propias: una raíz vigente
no resuelve un hijo UNRESOLVED/CONFLICT. NEW proyecta campos del JSON de una única
fila de revisión validada; no los convierte en entidades de otro namespace.

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
Los rechazos/fallos HTTP conservan la evidencia técnica T08 y su presupuesto
de rechazo existente; no se confunden con mutaciones o SUCCESS de negocio.

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
fuente/contexto, grant o sesión. Incluyen aplicar una corrección B10 ya aprobada
de A hacia B: respuesta anterior íntegra en A, nueva petición denegada en A y
autorizada íntegramente en B. PostgreSQL pg_stat_activity acredita la espera real.
Otra barrera prueba cierre académico durante una corrección bloqueada; una
conexión escritora que confirma primero prueba también la observación nueva.
Los spies solo colocan barreras, sin simular permisos, datos, locks ni escritores.

**Verificación histórica anterior a P2: SHA de la suite completa y empaquetado:**
`fb002223d7a29a586e69c3c0dffb6d4261e44bf7`.
`clean package` offline, sin filtros: **67 suites, 926 pruebas, cero fallos,
errores u omisiones; BUILD SUCCESS y JAR generado**. Incluye los **22 casos HTTP
T12-B**, los 22 T12-A y las demás regresiones; los 22 nuevos ya están incluidos
en el total, no se suman selecciones. Finalizó el 15 de octubre de 2026 a las
**02:24:26 America/Lima**; Maven informa duración `01:16 h`.
El commit documental del cierre original (241a903) conserva idénticos código, pruebas, configuración,
SQL y POM respecto del SHA ejecutado.

Las selecciones y fallos diagnósticos se conservan separados en
`.codex-work/t12b-evidence/`; no se suman a la suite completa ni se atribuyen al
SHA definitivo. Ese directorio local de evidencia y la DB no forman parte de la PR.

| Ejecución independiente | Fuente y resultado pruebas/fallos/errores/omitidas |
| --- | --- |
| compile-1 | Worktree inicial sobre fae2895, sin commit: 4/0/0/0 T05; diagnóstico, sin atribución al SHA final |
| selection-1 | Worktree inicial: error de compilación del arnés (helper de conexión no estático); sin ejecución JUnit |
| selection-2 | Worktree previo a 345c7a1: 3/0/0/0 HTTP; diagnóstico, sin atribución al SHA final |
| selection-3 | `345c7a183385133b3ddc8a8e7b18c713ab25ecb0`: 43/1/2/0. T12-A: 22/0/0/0; T05: 4/0/0/0; HTTP T12-B: 17/1/2/0. Se conservan log/XML originales |
| selection-4 | `6ed31a492fe5388b17b7035e2bb6378c64532029`: 12/0/0/0. Casos corregidos y afectados; hijos B10 inciertos, DB incorrecta, fallo PostgreSQL, matrícula/período y corrección B10 concurrente |
| full-package, definitiva | `fb002223d7a29a586e69c3c0dffb6d4261e44bf7`: **926/0/0/0**, suite completa y JAR; sin selección de tests |

Los tres problemas de selection-3 pertenecen al arnés: una cuenta global
ESTUDIANTE ya tenía ADMIN_INSTITUCION en el fixture B6 y recibía legítimamente
metadatos, una revocación SQL omitía su motivo obligatorio y un grant histórico
se solapaba con historia anterior. Se corrigen los casos sin ampliar permisos,
deshabilitar constraints/triggers ni modificar los SQL congelados.

Entorno propio: Windows, JDK 17.0.12, Maven 3.9.15 offline y PostgreSQL 18.4
desechable en `127.0.0.1:55443`, DB `chemicallab_t01_test`, rol `t12b_fixture`,
perfil test, locale C y 300 conexiones. La prueba de DB incorrecta crea un schema
vacío en `postgres` de **esa misma instancia desechable**, comprueba el rechazo
y lo elimina; no consulta bases reales. No acredita Linux/SCA, permisos runtime
productivos, inventario real de escritores ni capacidad operativa.

Artefactos locales verificados:

| Archivo en `.codex-work/t12b-evidence/` | SHA-256 |
| --- | --- |
| verified-backend.jar | `d4b5e131c9646eb02aa94dffb426e06cd1cc206b406a37acedf8a716c9409ef0` |
| verified-reports.zip | `6268238477d556e7eeaec5b82952849dfb9a6b6d99240d029940bb19097065a5` |
| full-package.log | `f85c2e3a9bff0f67c490a8422342bf0b9e19fe2df34bfb28f4fe76728d611265` |

`collect.py` contrasta código/POM con el SHA ejecutado, cuenta directamente los
67 XML y los coteja con el resumen final del log. Verifica las tres clases nuevas
en el JAR, la configuración desactivada y los **14 SQL históricos** empaquetados
contra sus fuentes, normalizando únicamente CRLF/LF. `verified-results.json` y
`verified-suites.json` conservan la atribución y conteos. `clean` eliminó informes
previos antes de ejecutar la suite completa. Los logs/exit/SHA/XML de selecciones
y el error de compilación se conservan separados.

Conservación comprobada por SHA-256: **185 archivos previos** intactos,
incluidos documentos del workspace/backend, auditoría y SQL; **16 E2E** intactos.
Los HEAD de los otros **26 checkouts** permanecen iguales. Backend principal
limpio en develop/fae2895; frontend conserva
`e88589635a8269c50ee0f53fccf157f0d0985034` y su `?? e2e-selenium/` previo.
`before.json`, `preserve.py` y `preservation.json` registran la comprobación sin
absorber, sobrescribir o borrar cambios ajenos.

Reproducción con JDK 17, Maven 3.9.15 y una instancia PostgreSQL desechable propia
preparada como B1, con el puerto/rol ficticios de esta ejecución:

```powershell
# Solo ensayo; nunca una base escolar. El arnés crea/elimina sus schemas propios.
./mvnw.cmd -o '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55443/chemicallab_t01_test' '-Dspring.datasource.username=t12b_fixture' '-Dspring.datasource.password=fictitious' clean package
```

Para una selección durante desarrollo, sustituir `clean package` por `test` y
agregar `-Dtest=InstitutionalEvaluationReadDbTest`. El arnés habilita únicamente
su schema explícito por caso; la configuración distribuida permanece desactivada.

## Superficies pendientes y entrega

T12 integral/etapa 4, adopción de los demás consumidores/escritores, rutas legacy,
listados/agregados, archivos, revisión/calificación, mutaciones de intentos,
frontend/STOMP, T13/F03, P08, SEC-13, VER-01, Linux/SCA, privacidad y operación
siguen pendientes. No se modifican esquemas, SQL históricos, auditoría original,
documentos previos, frontend institucional ni los 16 E2E.

Entrega con commits y PR backend **en borrador hacia develop**, adjunta al chat,
detenida para revisión. Sin merge, despliegues, datos reales, rotaciones o
activación productiva.


## Corrección P2 de la revisión T12-B

La revisión de `241a903d5e977d2d36ba5bf0d3d7d588891b2f97` detectó IDs emitidos
por `questionOrder` legacy sin comprobar pertenencia ni contexto B10. Las
preguntas inactivas sin respuestas quedaban fuera de la validación de hijos.
Se corrige exclusivamente esta omisión en la misma rama y PR #81.

Para READ_OWN_ATTEMPT legacy, `validateReadRows` interpreta el CSV persistido
con el mismo parser que usa la proyección. Para cada ID exige una pregunta
existente de la evaluación autorizada y consulta el mismo `CurrentContexts`
que autorizó raíz/intento: cabeza vigente, propuesta fresca, INSTITUTION y
ámbito solicitado. Todo ocurre dentro de la unidad coherente y sus locks,
antes de construir el DTO. Una pregunta ajena, inexistente o UNRESOLVED
provoca HTTP 403 completo; no se devuelve un prefijo válido del orden ni un
DTO parcial. La sintaxis inválida conserva el rechazo 503 de la proyección.

No se filtran ni reordenan IDs ni se escriben `question_order`, cursor o
contextos. Las preguntas inactivas resueltas sin respuestas pueden conservar
su posición histórica. Ante orden nulo/en blanco continúa el fallback al
contenido activo, cuyos hijos ya valida el resolver compartido. El cambio
solo se invoca para la lectura propia LEGACY de T12-B; `decide` de T12-A,
contrato NEW, corrección, metadatos y demás políticas mantienen sus caminos.

### Reproducción anterior a la corrección

Sobre el HEAD revisado `241a903d5e977d2d36ba5bf0d3d7d588891b2f97`, con solo
las nuevas regresiones sin commit y sin cambios de producción, se ejecutó:

```powershell
& .codex-work/t12b-p2-evidence/run.ps1 -Name red -Selection 'InstitutionalEvaluationReadDbTest#legacyOrderRejects*'
```

Resultado esperado de reproducción: **3 pruebas / 3 fallos / 0 errores /
0 omitidas**: los tres HTTP devolvieron indebidamente **200** con pregunta
ajena, ID inexistente y pregunta inactiva sin respuestas cuyo contexto se
confirmó explícitamente UNRESOLVED tras propuesta APPLIED. Cada orden incluye
primero un ID propio válido para detectar una eventual respuesta parcial.
`red-sha.txt`, `red-uncommitted.patch`, `red.log` y el XML específico T12-B
guardan separadamente la fuente y el resultado previo. Los otros informes
copiados por el arnés red proceden del target anterior y no cuentan en esta
reproducción; solo se ejecutaron esos tres casos.

### Verificación de la corrección

Las cinco regresiones HTTP nuevas comprueban los tres rechazos, la historia
válida con pregunta inactiva resuelta sin respuestas y el fallback nulo/en
blanco. Los éxitos conservan todas las firmas de filas y secuencias; los
rechazos comprueban ausencia de campos del DTO, respuestas propias y
mutaciones de negocio/contextos. Solo se permite la evidencia técnica T08
de rechazo y su secuencia de logs, conforme al contrato existente.
El caso previo de preservación ahora crea contenido legacy válido antes de
leer el intento: los IDs sintéticos 17/23 del arnés de decisión T12-A no
constituían preguntas legacy existentes. No se modifica el arnés T12-A.

La primera ejecución completa de ambos bloques solicitó también el empaquetado:

```powershell
& .codex-work/t12b-p2-evidence/run.ps1 -Name green-package -Selection 'EvaluationDeliveryAccessDbTest,InstitutionalEvaluationReadDbTest' -Package
# Equivalente desde el worktree con el JDK/Maven indicados:
./mvnw.cmd -o '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55445/chemicallab_t01_test' '-Dspring.datasource.username=t12b_p2_fixture' '-Dspring.datasource.password=fictitious' '-Dtest=EvaluationDeliveryAccessDbTest,InstitutionalEvaluationReadDbTest' clean package
```

Entorno independiente: JDK 17.0.12, Maven 3.9.15, PostgreSQL 18.4, loopback
127.0.0.1:55445, DB chemicallab_t01_test, rol t12b_p2_fixture, locale C,
max_connections=20 y max_locks_per_transaction=512. Cada prueba crea/retira
su schema ficticio. No se usa la base ni se sobrescriben los artefactos de
las evidencias previas.

Resultados y atribución separados (pruebas/fallos/errores/omitidas):

| Ejecución | SHA | Alcance y resultado |
| --- | --- | --- |
| green-package, diagnóstico | `b7a6bb3d8f05e93c4f7304278ed468961f127f14` | T12-A **22/0/0/0**; T12-B 27/1/0/0; agregado 49/1/0/0, sin JAR |
| fixture-check | `6f6f8aef0112f28cdbfb0bd9ef361e4a79fea815` | Caso de conservación **1/0/0/0**, no se suma a los totales |
| final-package | `6f6f8aef0112f28cdbfb0bd9ef361e4a79fea815` | T12-B completa **27/0/0/0**, BUILD SUCCESS y JAR generado |

El único fallo diagnóstico fue `readsPreserveAllBusinessRowsSequencesQuotasHistoryAndSuccess`:
el cotejo de todas las tablas cambió exclusivamente en `authentication_limits`.
La tarea real `AuthenticationLimitStore.cleanup()`, programada cada 60 segundos,
retiró buckets de login expirados durante esa observación larga. El ajuste de
fixture mantiene sus expiraciones al menos una hora hacia delante **antes** de
capturar la firma. No se excluye la tabla, no se desactiva el mantenimiento ni
se cambia el código de producción o la política de autenticación. Las cinco
regresiones P2 ya habían pasado en la ejecución diagnóstica.

Tras comprobar el caso afectado se repitió **completa T12-B**, con limpieza de
informes previos y empaquetado:

```powershell
& .codex-work/t12b-p2-evidence/run.ps1 -Name fixture-check -Selection 'InstitutionalEvaluationReadDbTest#readsPreserveAllBusinessRowsSequencesQuotasHistoryAndSuccess'
& .codex-work/t12b-p2-evidence/run.ps1 -Name final-package -Selection 'InstitutionalEvaluationReadDbTest' -Package
# Equivalente del empaquetado definitivo desde el worktree:
./mvnw.cmd -o '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55445/chemicallab_t01_test' '-Dspring.datasource.username=t12b_p2_fixture' '-Dspring.datasource.password=fictitious' '-Dtest=InstitutionalEvaluationReadDbTest' clean package
```

**Cobertura verde: T12-A completa (22) y T12-B completa (27), 49 casos distintos,
en ejecuciones separadas**, con los SHA de la tabla. No se presenta el primer
agregado 49/1/0/0 como verde ni se suman repeticiones. Entre ambos SHA ejecutados
son idénticos `src/main`, configuración/POM y la clase T12-A; solo se ajustó el
fixture de una prueba T12-B. Por ello el resultado T12-A sobre el código corregido
sigue vigente y no se atribuye artificialmente su reejecución al SHA posterior.

Empaquetado definitivo finalizado en `2026-10-15T08:35:39-05:00`; duración Maven
`17:18 min`. `collect.py` comprueba cada XML y su SHA de ejecución, el log,
los cambios solo de fixture entre ambos SHA y la ausencia de cambios de src/POM
posteriores al empaquetado. Verifica el flag distribuido desactivado, las clases
y los 14 SQL históricos empaquetados. El ZIP reúne los dos XML verdes con
`execution-manifest.json`, que identifica sus ejecuciones independientes.

| Artefacto en `.codex-work/t12b-p2-evidence/` | SHA-256 |
| --- | --- |
| verified-backend.jar | `74095d6d67bc18dabdb36e24f8f0bb537ba82cb76d0098f051d3f02577e53828` |
| verified-reports.zip | `927c48ce4e4c869f525304c56bee16d6ca9cfe2350b774f6d11f28fdbb79ec83` |
| final-package.log | `c9f9a3e12efe22348d1ec0352d8044ce3eb721bb70e0045e945902c7b521fccc` |

`verified-results.json`, los archivos command/SHA/patch/log de cada ejecución,
`green-package-reports`, `fixture-check-reports`, `final-package-reports` y
`t12a-green.xml` conservan por separado resultados definitivos y diagnóstico.

Las **926 pruebas** se conservan atribuidas exclusivamente al SHA histórico
`fb002223d7a29a586e69c3c0dffb6d4261e44bf7`. **No se reejecutó esa suite sobre
el código corregido.** Los resultados de esta sección corresponden solo a
las suites completas T12-A y T12-B y se registran por separado en
`.codex-work/t12b-p2-evidence/`. La revisión de alcance confirma que no hay
cambios en otras políticas que requieran ampliar la regresión.

Preservación nueva por SHA-256: **240 archivos protegidos intactos**, incluidas
las evidencias anteriores y de revisión, SQL, auditoría/documentos protegidos y
**16 E2E**. La única documentación anterior actualizada es esta ficha T12-B,
autorizada para registrar la corrección. Los HEAD de los otros
**26 checkouts** y el HEAD/estado previo del frontend permanecen iguales.
`before.json`, `preserve.py` y `preservation.json` guardan el cotejo. El clúster
ficticio P2 se detiene al terminar; no se toca el clúster de evidencia previo.

Entrega de la corrección en la misma PR #81 abierta/en borrador para revisión;
sin merge, despliegues, activación productiva, datos escolares, DDL/backfill
real ni avance a otro bloque.
