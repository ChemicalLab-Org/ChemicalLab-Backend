# T12-H — Asignación institucional de publicaciones NEW

T12-H crea una entrega B8 y sus destinatarios iniciales en un fixture institucional. Parte de G revisado localmente en `aa052d6a2c96b3a1ac25c948f8513e773cc64e6d`. `develop` permanece en T12-F, `2169515779c460249ad7c973625e0019a8c81125`. T12 integral y etapa 4 siguen abiertos.

## Comando HTTP

`POST /api/institutions/{institutionId}/classes/{classId}/evaluation-assignments/NEW/v1/evaluations/{evaluationId}/revisions/{revisionId}/deliveries`

```json
{
  "operationId": "fb003ff6-f7c5-4a62-a81b-e9c4150afa7a",
  "expectedRevisionVersion": 2,
  "startsAt": "2026-11-25T15:30:00Z",
  "dueAt": "2026-11-25T17:30:00Z",
  "enrollmentIds": [82, 97]
}
```

- Los cuatro selectores de ruta identifican la evaluación, su revisión B8 exacta, la institución y la clase de destino. El servidor exige una revisión publicada de un recurso NEW disponible y comprueba el cierre B8. La entrega conserva los dos instantes exactos y la unicidad revisión/clase. Ambos instantes son obligatorios y aceptan UTC con sufijo `Z` y hasta seis decimales. El inicio debe preceder al vencimiento y el vencimiento debe seguir abierto al confirmar.
- `enrollmentIds` contiene de 1 a 100 PK positivas y distintas de `enrollments.id`. Una fila B2 representa un episodio concreto de matrícula. El número de episodio `episode_number`, las cuentas, perfiles, membresías y destinatarios pertenecen a otros espacios de identificadores. El servidor une cada episodio a su membresía y cuenta, exige institución y clase exactas, estado y fechas vigentes y el rol ESTUDIANTE vigente. Comprueba también que las cuentas derivadas sean distintas. Una sola fila inválida rechaza el conjunto. [B2](../arquitectura/11b2-clases-matriculas.md)
- La sesión T02 determina la cuenta. El servidor exige institución y clase activas, período abierto, DOCENTE vigente y una asignación RESPONSABLE o CO_DOCENTE vigente en la clase de destino. La autoría se acredita con la cuenta y membresía originales de B8 en esa institución. Otro docente requiere además un grant NEW de ASSIGN exacto y vigente para ese recurso. EDIT_DRAFT, PUBLISH y ASSIGN se verifican por separado. ADMIN_INSTITUCION requiere las mismas condiciones de docencia y facultad. [B6](../arquitectura/11b6-docencia-delegaciones.md) · [T12-G](12g-autoria-publicacion-new.md)
- La respuesta 200 con `Cache-Control: no-store` devuelve `operationId`, `evaluationId`, `revisionId`, `deliveryId`, `version`, `startsAt`, `dueAt` y `recipientCount`. El UUID vincula actor, alcance, versión esperada, instantes y conjunto de episodios. El orden de los episodios y las representaciones UTC equivalentes se normalizan. La recuperación del recibo exige autorización y contexto vigentes y devuelve el resultado confirmado.
- Las rutas apagadas responden 404 al comando válido. La sesión inválida devuelve 401. Un ámbito, episodio, facultad o contexto inadmisible devuelve 403. El JSON inválido devuelve 400 y el límite de transporte devuelve 413. Versión obsoleta, publicación o ventana incompatible, segunda asignación revisión/clase y UUID incongruente devuelven 409. Un fallo de persistencia o el vencimiento del lock de escritura devuelve 503. El cliente puede repetir la misma solicitud con el mismo UUID.

## Entrega, intentos y conciliación

H confirma la entrega, la admisión inicial, los eventos B8, las mediciones de cuota, el recibo durable y SUCCESS T08 en una transacción. Las filas quedan vinculadas a la revisión y al episodio exactos. F09 conserva el presupuesto y la numeración por evaluación/cuenta a través de clases y publicaciones. La asignación tiene efecto cero sobre intentos consumidos, intentos disponibles y numeración. [B8](../arquitectura/11b8-revisiones-academicas.md) · [T12-F](12f-ejecucion-evaluaciones-new.md)

El recibo enlaza el manifiesto anterior a una cabeza B10 aplicada y vigente, a un recibo G comprobable o a un recibo H anterior. Para una evaluación nativa de G, el antecedente incluye la creación y la cadena de autoría. El delta de asignación añade únicamente la entrega, sus destinatarios y los eventos B8 generados por esas filas. La certificación compara el resto del manifiesto y las cabezas y referencias pertinentes. Comprueba que la entrega y los destinatarios recién creados carezcan de versiones B10 y que las aprobaciones previas sigan vigentes.

Las lecturas y escrituras F pueden seguir usando las filas con contexto aprobado. Para una entrega nueva, F exige la conciliación explícita F08 con las dos aprobaciones distintas de plataforma e institución, y la aplicación de ese contexto. G puede continuar editando y publicando revisiones bajo el antecedente comprobado. Esos comandos certifican su propio delta de revisión e historia. La deriva de filas, cabezas o referencias que no corresponde a un comando verificable invalida el puente. [B10](../arquitectura/11b10-conciliacion-contextos.md)

## Instalación del fixture

Use PostgreSQL desechable en `127.0.0.1`, con la base `chemicallab_t01_test`, ordenación `C` y el schema `t03_<32 hex>` configurado explícitamente. Certifique los SQL en orden hasta `t12f-v1.sql` y `t12g-v1.sql`. Ejecute `t12h-v1.sql` completo en una transacción. La certificación H vincula su catálogo al fingerprint G y rechaza divergencia. Los SQL históricos conservan sus blobs Git. El límite del recibo H es de 10 000 filas y 64 MiB lógicos por fixture, además de los límites B8, B9 y F aplicables.

`app.evaluations.institutional.new-assignment-enabled=false` por defecto. Para ensayar hacen falta el perfil único `test`, la habilitación institucional, la bandera H y el schema aprobado. La gestión posterior de destinatarios, sustituciones, readmisiones, traslados, invitaciones y el despliegue quedan fuera del alcance de H. [T11-D](../arquitectura/11d-cierre-preparacion-modelo.md)

## Publicación futura

1. Cuando vuelva el acceso, publique primero la rama de G, `feature/t12g-autoria-publicacion-new`, desde `aa052d6a2c96b3a1ac25c948f8513e773cc64e6d`. Abra la PR de G hacia `develop`.
2. Tras la integración autorizada de G, compruebe que `develop` contiene el código revisado, incluido el sellado de recibos de `95f83a1`. Si la estrategia de integración cambió los SHA, cree una rama temporal desde el `develop` actualizado y aplique en orden solo el rango propio de H que devuelve `git log --reverse --format=%H aa052d6..feature/t12h-asignacion-publicaciones-new`. Si G conserva sus SHA y es ancestro de `develop`, compruebe igualmente el diff de H antes de publicar.
3. Publique H y abra su PR hacia `develop`. Registre la PR de G como dependencia y adjunte la evidencia por SHA. La publicación de ramas es independiente de la autorización operativa.

## Verificación y revisión local

La sonda de `391f994fa9285d3da3cb05b443218969cfb78508` certificó el delta B10 antes de conectar la ruta H. Ejecutó SQL H y HTTP real F en PostgreSQL 18.4 desechable. Conservó la cabeza anterior, la lectura y escritura F, y el número compartido tras la conciliación. Consta un caso aprobado. El intento con loopback bloqueado por el sandbox terminó antes de Maven. El intento en un clúster con ordenación regional llegó a B10 y falló al certificar su catálogo. Los tres resultados se conservan por separado.

La selección HTTP de `fa1c30760f98e1542378765a985c3d345f087767` ejecutó diez casos. Pasaron los cuatro casos de transporte, el caso focalizado de G sobre facultades y continuidad F09, el caso focalizado de F sobre readmisión y consumo, y dos casos H sobre delegación, reintentos, atomicidad, concurrencia y recuperación. La copia física restaurada tuvo 143 firmas coincidentes y devolvió el recibo H por HTTP. Quedaron dos errores del ensayo H. El alumno adicional requería un docente para crear su perfil y el rechazo HTTP intercalado entre aprobaciones cambió la medición que B10 firma. La revisión corrigió el fixture y dejó la comprobación de la aplicación prematura dentro del censo aprobado.

La revisión de código añadió la comprobación de que toda cabeza existente del agregado pertenezca a la propuesta que ancla el recibo, reforzó la comparación de la ventana con la solicitud y volvió a comprobar los episodios tras sellar el delta. Se probaron deriva, rollback ante auditoría diferida y cuota y rechazo de divergencia del catálogo. [Reproducción con suites versionadas](12m-administracion-institucional.md#pruebas-de-postgresql-desechable).

Se compararon 192 archivos protegidos, incluidas las 16 huellas E2E originales, y los 19 SQL históricos hasta G. Se conservaron el frontend, los checkouts anteriores y los resultados G. El bundle de G coincide con su SHA-256 anterior y la copia Git restaurada pasó `git fsck --full`.

En la carrera de revisión/clase se observó un 503 por el límite de espera. El registro técnico de rechazo T08 tuvo un fallo de persistencia durante esa contención. Se verificó una única entrega, un único recibo y un único SUCCESS transaccional. El cliente de prueba descartó el cuerpo de la respuesta inicial y recuperó el resultado con el mismo UUID. El comando competidor devolvió 409 al repetirlo.

La ejecución final en `04039750d9fe25b27b84f1fb93e29be3e55d49f8` aprobó los cuatro casos H con HTTP real y PostgreSQL 18.4, sin fallos, errores ni omisiones. Cubrió el aislamiento bilateral y la correspondencia de episodios, la matriz de facultades y revocaciones, el rollback y reintento bajo contención, y el contexto pendiente con dos aprobaciones F08 distintas. La entrega anterior mantuvo lectura, escritura y consumo compartido. Se repitió la certificación F/G/H antes y después de la restauración física con WAL en streaming. `pg_verifybackup` confirmó la copia, las 143 firmas coincidieron y el recibo se recuperó por HTTP.

- XML Surefire final SHA-256 `a2f948ed0ff415370089dfc63058f55ed4bd5217e086d97b18bedb6aef5fa982`.
- Firmas de recuperación SHA-256 `7e05dc70d99eee64874271ce6007a107fe762e6641dece53d7719d956d18b058`.

## Código de la ejecución histórica

Para inspeccionar el código de la ejecución final, abre su commit en un checkout separado desde un clon que contenga la rama del piloto.

```powershell
git worktree add --detach ../chemicallab-t12h-verificacion 04039750d9fe25b27b84f1fb93e29be3e55d49f8
```

## Comprobación focalizada de auditoría T08 en la carrera H

La revisión del 25 de noviembre de 2026 cotejó la evidencia existente de dos corridas. En `fa1c30760f98e1542378765a985c3d345f087767` y `04039750d9fe25b27b84f1fb93e29be3e55d49f8`, el caso `rollbackContendedDeliveryAndRestoreItsDurableReceipt` pasó. PostgreSQL registró primero el vencimiento de la espera por el lock exclusivo de `evaluations` y, en el mismo proceso de base, otro vencimiento al actualizar `audit_rejection_budget` unos 250 ms después. El XML del caso y el log HTTP de cada corrida contienen el aviso SLF4J exacto `SECURITY_AUDIT_EVIDENCE_UNAVAILABLE signal=rejection_persist_failed`.

**SUCCESS de negocio.** `AuditLogWriter.persist` participa en la transacción de H. El sellado exige un `ASSIGN_NEW_DELIVERY` con resultado SUCCESS y el mismo XID, actor, operación y destino. En el ensayo de auditoría diferida, el trigger detectó el recibo ya preparado y forzó el fallo al confirmar. Se compararon las firmas y se verificó el rollback de entrega, destinatarios, historia, cuotas, recibo y SUCCESS. Este ensayo es distinto de la contención del registro técnico. [Escritor T08](../../src/main/java/com/morales/chemicallab/service/AuditLogWriter.java) · [Sellado H](../../src/main/resources/db/expansion/t12h-v1.sql)

**Registro técnico del rechazo.** El primer lock de H vence antes de autorizar o escribir la asignación competidora. El filtro T08 observa el estado HTTP ya resuelto y clasifica el 503 como `OPERATION_FAILED`. El intento de evidenciarlo usa una transacción `REQUIRES_NEW` con espera de lock de 250 ms. Ante la excepción, `AuditRejections` registra la señal mediante `AuditSignals`. Primero incrementa el contador Micrometer `security.audit{signal="rejection_persist_failed"}` y luego emite, sujeto al límite de un aviso por minuto e instancia, el código fijo por SLF4J. Ambas salidas son independientes de `system_logs` y de la reserva fallida. El mensaje observado contiene solo el código y la etiqueta fija. El filtro conserva el estado HTTP y el tratamiento del fallo prescinde del texto de la excepción y de los datos de la petición. [Contrato T08](02-08-auditoria-eventos.md#rechazos-señales-y-límites) · [Filtro HTTP](../../src/main/java/com/morales/chemicallab/security/audit/AuditRequestFilter.java) · [Señales](../../src/main/java/com/morales/chemicallab/security/audit/AuditSignals.java)

En ambos registros, la competencia dejó una sola entrega de esa revisión/clase, un recibo y un SUCCESS. El competidor obtuvo 409 al repetir el comando. El cliente recuperó con el mismo UUID el resultado confirmado cuyo cuerpo había descartado y volvió a obtenerlo desde la copia restaurada. H observó el aviso SLF4J. La medición directa del contador procede de los ensayos históricos `AuditSafetyTest#failedDestinationIsObservableAndDbAttemptsStopAtLocalBudget` y `AuditReliabilityDbTest#destinationFailureRejectsBusinessCommitAndRecoveryNeedsNoQueue`, que también verifican el límite local y la conservación de 401 ante el destino averiado. El comportamiento comprobado cumple T08. Esta revisión reutilizó la evidencia y añadió únicamente documentación. La recolección y conservación externas de las señales continúan como trabajo operativo T08. [Caso HTTP de H](../../src/test/java/com/morales/chemicallab/security/InstitutionalEvaluationAssignmentDbTest.java) · [Verificación histórica T08](02-08-auditoria-eventos.md#verificación-y-cierre)

## Propuesta para el siguiente bloque de T12

**Listado institucional de entregas NEW disponibles para el estudiante autenticado.** A resuelve permisos sobre una entrega identificada. B ofrece lecturas unitarias y deja expresamente pendientes los listados y agregados. C–E cubren guardado, ciclo de intentos y revisión/calificación LEGACY. F ejecuta NEW con evaluación, entrega y revisión exactas. G publica y H crea el vínculo inicial con matrículas concretas. Después de F08, el estudiante puede abrir la entrega NEW si ya dispone de sus identificadores. Falta una consulta institucional que le permita encontrarlos. El listado actual `GET /api/evaluations/student` consulta asignaciones LEGACY por grado y sección del perfil. T11-D P09 y §7, punto 4 y T11-A §§3.5 y 7.2 exigen extender el ámbito comprobado a listados, paginación y agregados. Este es un bloque propuesto a partir de esos contratos. [T11-D](../arquitectura/11d-cierre-preparacion-modelo.md#7-contratos-que-t12-deberá-adoptar-y-primer-bloque-acotado) · [T11-A](../arquitectura/11a-modelo-institucional.md#35-impacto-en-todas-las-superficies) · [T12-B](12b-lecturas-institucionales-evaluaciones.md#superficies-pendientes-y-entrega) · [Listado actual](../../src/main/java/com/morales/chemicallab/service/EvaluationService.java)

**Dependencias y alcance.** Una lectura REST paginada por institución y clase, apagada por defecto y acotada a fixtures. T02 identifica la cuenta. A/B aportan la autorización de lectura propia. B2/B8 vinculan la matrícula vigente con el destinatario y la revisión. F aporta el contrato de ejecución NEW, y G/H y B10/F08 determinan la procedencia y la disponibilidad de contexto. La proyección mínima incluye identificadores para abrir la lectura exacta y metadatos de disponibilidad autorizados. La selección y la paginación usan el ámbito autorizado dentro de la misma unidad de consistencia. Las escrituras y el cálculo de intentos siguen en sus comandos existentes.

**Aceptación.** Probar por HTTP real y PostgreSQL desechable en ambos sentidos A→B y B→A, incluida una cuenta con matrículas en ambos colegios. Cambiar selectores de institución o clase y revocar una pertenencia debe evitar filas ajenas y conservar el acceso válido al otro colegio. La revocación global debe cortar ambos accesos. Una entrega H pendiente, incierta o con deriva queda excluida de resultados y de los metadatos de página, mientras una entrega previa válida sigue visible. Tras F08 explícita, la entrega entra en el listado si conserva matrícula y ventana vigentes. Comprobar la coherencia entre páginas y cualquier total que exponga el contrato, así como la lectura unitaria con los identificadores devueltos. El listado debe dejar intactos los intentos, el saldo, las notas y las correcciones.

**Aislamiento que seguiría pendiente.** Quedarían los listados y agregados de LEGACY, docentes y supervisión, los demás consumidores y escritores REST y el corte coordinado de rutas legacy. También quedarían STOMP entrante, saliente y pasivo, archivos, exportaciones, jobs, cachés y consultas de actividad/auditoría. Sustitución, baja, traslados, readmisión y F03 corresponden a T13. Altas, invitaciones y verificación externa corresponden a T14. P08 y la operación efectiva siguen pendientes y esta propuesta deja abiertos T12 integral y la etapa 4. [T11-D §6](../arquitectura/11d-cierre-preparacion-modelo.md#6-bloqueos-y-pendientes-posteriores) · [T11-A §7.2](../arquitectura/11a-modelo-institucional.md#72-gates-posteriores-que-t11-b-no-satisface-por-sí-sola)
