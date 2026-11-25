# T12-G — Autoría y publicación institucional NEW

Propuesta concretada para este encargo sobre T12-F integrado en `2169515779c460249ad7c973625e0019a8c81125` (PR #85). El cierre de F deja G sin iniciar y la base consultada carece de una definición versionada de G. T12 integral y etapa 4 siguen abiertos.

## Contratos que se conservan

| Antecedente | Aplicación en G |
| --- | --- |
| [T11-D](../arquitectura/11d-cierre-preparacion-modelo.md), P01/P03/P05 | Adopción efectiva bajo guardas de ensayo. Se conservan los cierres persistentes, el aislamiento y el último contexto aprobado. |
| [B6](../arquitectura/11b6-docencia-delegaciones.md) | DOCENTE y pertenencia vigentes; RESPONSABLE y CO_DOCENTE valen para su clase durante su episodio. EDIT_DRAFT y PUBLISH son facultades separadas. ASSIGN sigue fuera de estas rutas. |
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

El árbol `payload` utiliza `resource`, `questions` y `options`, con tipos estrictos y llaves permitidas B8/F. IDs de preguntas y opciones identifican elementos internos de esa revisión. El borrador puede estar incompleto y la publicación exige el subconjunto ejecutable F. El máximo se fija al crear; la edición preserva procedencia y autoría. Se permite un borrador de trabajo a la vez y el sucesor copia la última publicación. Los comandos solo devuelven metadatos y el GET de borrador es la única ruta G que entrega su contenido. Un UUID repetido con actor, alcance, acción y datos iguales devuelve el recibo original después de comprobar la facultad vigente. Otro comando con el mismo UUID y una versión obsoleta devuelven 409.

Crear, editar y publicar no entregan la evaluación ni cambian entregas previas. Para entregar una publicación nueva a un estudiante se sigue usando la preparación interna de entrega y reconciliación explícita de los antecedentes. La capa de contexto G conserva las entregas F ya aprobadas durante las operaciones de autoría legítimas sobre su raíz.
