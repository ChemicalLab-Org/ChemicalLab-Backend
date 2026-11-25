# T12-G — Autoría y publicación institucional NEW

Propuesta concretada para este encargo sobre T12-F integrado en `2169515779c460249ad7c973625e0019a8c81125` (PR #85). El cierre de F deja G sin iniciar y la base consultada carece de una definición versionada de G. T12 integral y etapa 4 siguen abiertos.

## Contratos que se conservan

| Antecedente | Aplicación en G |
| --- | --- |
| [T11-D](../arquitectura/11d-cierre-preparacion-modelo.md), P01/P03/P05 | Adopción efectiva bajo guardas de ensayo. Se conservan los cierres persistentes, el aislamiento y el último contexto aprobado. |
| [B6](../arquitectura/11b6-docencia-delegaciones.md) | DOCENTE y pertenencia vigentes. RESPONSABLE y CO_DOCENTE valen para su clase durante su episodio. EDIT_DRAFT y PUBLISH son facultades separadas. ASSIGN sigue fuera de estas rutas. |
| [B8](../arquitectura/11b8-revisiones-academicas.md) | Creación explícita, raíz estable, publicación inmutable, antecedente exacto y F09 por evaluación/cuenta. Toda revisión comparte el máximo original. |
| [B10](../arquitectura/11b10-conciliacion-contextos.md) | Última cabeza, procedencia institucional compatible, precedentes y F08 vigentes. Los cambios controlados del propio agregado requieren un recibo atómico verificable. |
| [T12-A](12a-resolucion-acceso-evaluaciones.md) y [B](12b-lecturas-institucionales-evaluaciones.md) | Principal y contexto resueltos bajo la unidad de ensayo. Las entregas y el contenido de alumno siguen perteneciendo a sus proyecciones existentes. |
| [T12-C](12c-guardado-respuestas-institucional.md), [D](12d-ciclo-intentos-institucional.md), [E](12e-revision-calificacion-institucional.md) | Recibo, cuotas, historia y SUCCESS T08 atómicos. Los comandos LEGACY y su autoridad se conservan. |
| [T12-F](12f-ejecucion-evaluaciones-new.md) | Las publicaciones son B8 y F conserva el intento exacto, su numeración, activo y consumo. La integración de G se limita a resolver el contexto de esa entrada. |

Las dependencias detectadas en la base fueron autorizadas como integración acotada. La FK `t11b6_resource_grants(evaluation_id,institution_id)` referencia B3. G aporta delegaciones tipadas a la raíz B8 NEW sin migrar grants. `t11p05_fresh` compara el manifiesto completo, incluidas revisiones B8. G acredita cambios propios, conserva la cabeza y las referencias aprobadas, y deja cualquier deriva sin autoridad. Las anclas técnicas creadas por G quedan fuera de las rutas legacy de evaluación y supervisión.

## Contrato HTTP

Prefijo `/api/institutions/{institutionId}/classes/{classId}/evaluation-authoring/NEW/v1/evaluations`. Perfil único test, habilitación explícita institucional y de autoría, schema aprobado, PostgreSQL loopback y base desechable. Las mutaciones toman el lock de escritura de la unidad T12 y las lecturas toman el de lectura. La sesión se revalida dentro de la unidad y al cierre. Respuestas académicas con `Cache-Control: no-store`.

| Método y sufijo | Facultad | Entrada |
| --- | --- | --- |
| POST al prefijo | DOCENTE con RESPONSABLE o CO_DOCENTE vigente en la clase activa | `operationId`, `payload` |
| GET `/{evaluationId}/revisions/{revisionId}` | EDIT_DRAFT | Versión y contenido del borrador |
| PUT `/{evaluationId}/revisions/{revisionId}` | EDIT_DRAFT | `operationId`, `expectedVersion`, reemplazo de `payload` |
| POST `/{evaluationId}/revisions/{revisionId}/publish` | PUBLISH | `operationId`, `expectedVersion` |
| POST `/{evaluationId}/revisions/{revisionId}/successor` | EDIT_DRAFT | `operationId`, `expectedVersion` de la última publicación |

El autor tiene ambas facultades sobre su recurso cuando mantiene DOCENTE y asignación en la clase de origen. Para una evaluación anterior, la clase debe estar vinculada por la cabeza B10 o por una entrega con contexto aprobado. Los gestores requieren DOCENTE vigente y el grant exacto NEW para la acción. La ruta valida clase y período de la institución. Una asignación docente ajena al recurso y ADMIN_INSTITUCION por sí solo no dan acceso. Las delegaciones se preparan internamente en el fixture con procedencia administrativa B6. La ruta no acepta identificadores de autor, pertenencia o delegación en el cuerpo.

El árbol `payload` utiliza `resource`, `questions` y `options`, con tipos estrictos y llaves permitidas B8/F. IDs de preguntas y opciones identifican elementos internos de esa revisión. El borrador puede estar incompleto y la publicación exige el subconjunto ejecutable F. El máximo se fija al crear. La edición preserva procedencia y autoría. Se permite un borrador de trabajo a la vez y el sucesor copia la última publicación. Los comandos solo devuelven metadatos y el GET de borrador es la única ruta G que entrega su contenido. Un UUID repetido con actor, alcance, acción y datos iguales devuelve el recibo original después de comprobar la facultad vigente. Otro comando con el mismo UUID y una versión obsoleta devuelven 409.

Una creación con borrador vacío acepta este cuerpo. Para publicar, el borrador debe contener preguntas válidas.

```json
{
  "operationId": "fb003ff6-f7c5-4a62-a81b-e9c4150afa7a",
  "payload": {
    "resource": {
      "title": "Evaluación de ensayo",
      "max_attempts": 3,
      "question_display_mode": "ALL_AT_ONCE"
    },
    "questions": [],
    "options": []
  }
}
```

El transporte aplica el menor de 1 MiB y el límite HTTP configurado. El borrador admite 200 preguntas y 2 000 opciones. Rutas apagadas devuelven 404. Sesiones inválidas devuelven 401 y la falta de facultad o contexto devuelve 403. El JSON inválido devuelve 400, el cuerpo excesivo 413 y un fallo de la unidad persistente devuelve 503.

Crear, editar y publicar no entregan la evaluación ni cambian entregas previas. Para entregar una publicación nueva a un estudiante se sigue usando la preparación interna de entrega y reconciliación explícita de los antecedentes. La capa de contexto G conserva las entregas F ya aprobadas durante las operaciones de autoría legítimas sobre su raíz.

## Instalación y límites de ensayo

Certificar la cadena hasta `t12f-v1.sql` antes de aplicar `t12g-v1.sql`, cada archivo completo en una transacción PostgreSQL de ensayo. G utiliza un schema de control separado por fixture. Conserva los objetos y hashes históricos de F y sus antecesores. La repetición verifica el catálogo de G y su enlace con el ledger F. La habilitación requiere las propiedades institucionales, `app.evaluations.institutional.new-authoring-enabled=true` y el nombre exacto del fixture. Para consumir el contenido de una revisión nueva siguen vigentes las guardas de las rutas de lectura y ejecución.

El recibo G conserva actor, acción, selectores confirmados, versiones, hashes de la entrada y de los manifiestos, resultado y referencia a SUCCESS. El contenido vive en B8 y en la historia que genera B8. Los límites de B8 y la medición institucional compartida con F se verifican dentro de la unidad. El control de G tiene un límite independiente de 10 000 filas / 64 MiB lógicos. El lock compartido con los escritores T12 conserva su espera máxima de 5 s. Si la contención devuelve 503, el cliente puede repetir la misma petición con el mismo UUID. Autoría, auditoría y cuotas revierten en conjunto.

Cuando la cabeza B10 vigente existe, un recibo G queda vinculado a ella y al manifiesto y las referencias posteriores verificables. El puente conserva la vigencia únicamente si también coinciden las aprobaciones F08 y sus privilegios. Los lectores vuelven a comprobar sus relaciones exactas y, en F, el resto de la operación sigue usando el contrato congelado. Una creación nativa G se reconoce para autoría a partir de su propio recibo, hasta la conciliación explícita de esa raíz. La conciliación conserva sus dos aprobaciones.

## Diagnóstico de partida

La aplicación y los SQL del merge `2169515779c460249ad7c973625e0019a8c81125` se compilaron para una sonda externa a la rama. La sonda ejecutó HTTP real y PostgreSQL 18.4 desechable. Observó (1) la respuesta 200 de detalle legacy para un ancla técnica NEW propia, (2) el rechazo PostgreSQL de una delegación de ese recurso por la FK B6 a B3 y (3) una transición 200 → 403 al leer un intento existente tras un borrador sucesor B8, con retorno a 200 tras conciliación F08 y número de intento preservado. Un caso de diagnóstico aprobado. Su fuente, registro y SHA de la aplicación están en `.codex-work/t12g-evidence`.

Evidencia reutilizada para la preparación: T11-D atribuye B6 a `b99a3be4b230c14095e6429ffea42557477c9142`, B8 a `6893c2743daa1e18491768b91db56e72543ddd40` y B10 a `578837a6ebdf19f5c3b8c88b5f9b9060dad26d3b`. El cierre F conserva su selección aprobada de A–F y las pruebas de presupuesto y continuidad de publicación. Se mantiene la atribución de cada bloque a la ejecución original.

## Verificación seleccionada

Entorno Windows, JDK 17, Maven 3.9.15 offline, PostgreSQL 18.4 desechable en loopback y HTTP real para los casos G y F. Los XML Surefire, sus hashes, la invocación y los registros se conservan en `.codex-work/t12g-evidence`. Las filas cuentan casos distintos, sin sumar ejecuciones que los repiten.

| Evidencia aceptada | SHA de fuente probado | Resultado |
| --- | --- | --- |
| `TransportInputFilterTest`, `AdminServiceTest`, `EvaluationServiceTest` y `NewEvaluationExecutionDbTest#twoPublicationsAndDraftChangesPreserveContentAndShareNumberingAcrossClasses` | `cc23f0a0640ecda15742f2906e3af5d89f605167` | 112 casos aprobados individualmente en sus XML (4 + 19 + 88 + 1). Los cambios de aplicación de los commits posteriores se acotan al controlador y al servicio G. |
| G, creación bilateral y separación de delegaciones con continuidad de F09 | `303fb28647a7bcb63ae417a52015e376eb2480dd` | 2 casos aprobados. Rol, sesión, asignación y delegación vigentes, revocados, vencidos o ajenos en los supuestos de la matriz. Continuidad de lectura y escritura F con la entrega anterior y numeración compartida en la siguiente. Deriva ajena y cabeza incierta rechazadas. |
| G, concurrencia, atomicidad, certificación y recuperación | `661983dff0a2e6c3276ca79cfc1f9e1a455500f3` | 1 caso aprobado. Reintento idempotente tras 503 de contención, un solo alta, una edición concurrente aceptada y la otra obsoleta. Fallos PostgreSQL de T08 y cuota revierten negocio, historia y recibo. Instalación y repetición F/G, rechazo de deriva y recuperación física. |

La primera invocación reunió 115 casos y registró tres fallos G de conversión MVC. El controlador corregido entrega bytes acotados al validador G. La segunda invocación de G que alcanzó los casos aprobó dos y registró un 503 real por espera del lock en una carrera. El caso focalizado conserva el límite del servidor y reintenta el mismo comando tras contención. Se preservan ambas ejecuciones y se excluyen sus fallos de la evidencia aprobada.

Se compararon físicamente los 18 SQL históricos y los 192 archivos protegidos de la evidencia previa, incluidos los 16 E2E. Los hashes coincidieron. Los checkouts preexistentes, el frontend original, la auditoría y la documentación y anexos anteriores se conservaron.

La recuperación ejecutó `pg_basebackup` con WAL en streaming, `pg_verifybackup`, arranque de la copia en otro puerto loopback y certificación F/G. Las 141 firmas de tablas coincidieron y la repetición HTTP devolvió el recibo original de publicación. SHA-256 de `signatures.json` igual a `a12e9123718f0ad388ab3219a431c418d0642f5391fe09e43bd9b2c792e627c4`. La prueba verifica esa copia física de ensayo.

La rama agrega este documento después de los SHA ejecutados. El código de aplicación y el SQL son idénticos en los dos SHA aceptados de G. La única diferencia de prueba entre ambos reintenta las mismas operaciones si vence el lock de la carrera.

## Revisión local

T12-G sigue en la rama local `feature/t12g-autoria-publicacion-new`. El checkout de `develop` conserva el merge de T12-F `2169515779c460249ad7c973625e0019a8c81125`.

La revisión identificó que una inserción ordinaria en la tabla de recibos podía omitir la función que comprobaba la auditoría y el manifiesto. La tabla solo recibe ahora filas generadas por el disparador de comandos. La escritura certifica un antecedente exacto de B10 o un recibo anterior válido de la misma cadena y conserva la comprobación transaccional de auditoría, manifiesto, referencias y cuota. La prueba focalizada comprueba también el rechazo de la inserción directa.

La corrección pertenece al SQL de ensayo de G, que todavía está en su rama local. Los 18 SQL anteriores conservan sus bytes. El bundle y la evidencia de `36fe2149de25ff214e43eab68d6fb9b50902c4da` conservan el catálogo previo a esta revisión. Para repetir los ensayos de la revisión, iniciar el fixture con el G revisado sobre F certificado. El certificador conserva el rechazo de un catálogo divergente.

La corrida de revisión del 25 de noviembre de 2026 probó `95f83a1f6f56742cdb0630237501bd56a2d8bcf6` con HTTP real y PostgreSQL 18.4 desechable. Pasaron los 3 casos G, sin fallos, errores ni omisiones. La copia física restaurada conservó 141 firmas de tablas y el recibo reproducido por HTTP. La evidencia anterior conserva su atribución. Los tres casos de revisión reiteran los escenarios G y no aumentan el recuento de escenarios distintos.

El informe verificable se guardó como `.codex-work/t12g-evidence/local-review-verified.json`, junto con el XML Surefire, los registros y las firmas restauradas. El SHA-256 del XML es `a234b043a01cef26f21360c222d2960a47fab2e6a5a9fef4c682ea55da52177d`. La revisión comparó sin diferencias 192 archivos protegidos, incluidos los 16 E2E, y 7 archivos de evidencia G previamente atribuidos.

El despliegue sigue pendiente. T12 integral y etapa 4 permanecen abiertos.
