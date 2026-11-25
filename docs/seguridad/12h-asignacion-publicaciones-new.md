# T12-H propuesto — Asignación institucional de publicaciones NEW

Estado local. Este alcance se propone a partir de los contratos versionados. El repositorio consultado no contiene una definición previa de T12-H. Este documento prepara el bloque. T12 integral y etapa 4 siguen abiertos.

## Alcance

La asignación es el siguiente paso del recorrido de evaluaciones NEW. G deja una publicación inmutable en B8 y F ejecuta intentos vinculados a una entrega y un destinatario exactos. G deja la creación de esa entrega y sus destinatarios en el mantenedor interno. T11-D P01/P03/P05 asigna a T12 la autorización efectiva de facultades, entregas y adopción de contexto. B6 y B8 conservan ASSIGN separado de EDIT_DRAFT y PUBLISH. [T11-D](../arquitectura/11d-cierre-preparacion-modelo.md) · [B6](../arquitectura/11b6-docencia-delegaciones.md) · [B8](../arquitectura/11b8-revisiones-academicas.md) · [T12-G](12g-autoria-publicacion-new.md) · [T12-F](12f-ejecucion-evaluaciones-new.md)

- Incorporar un único comando institucional de ensayo. Crea una entrega B8 para una revisión académica NEW publicada y una clase exacta. Admite en la misma transacción un conjunto explícito y acotado de episodios vigentes de esa clase.
- Aceptar como selectores evaluación, revisión, institución y clase en ruta, y un UUID, la versión esperada de publicación, las fechas UTC y los IDs de episodio en el cuerpo. Proponer un máximo de 100 episodios distintos por comando, con al menos uno. El servidor resuelve cuenta, matrícula, vigencia de ESTUDIANTE, institución y clase a partir de las filas propias. Devuelve el identificador, la versión y las fechas de la entrega y el número de destinatarios.
- Exigir sesión T02, DOCENTE actual y asignación RESPONSABLE o CO_DOCENTE vigente en la clase de destino. Para el recurso propio, verificar la autoría original y la pertenencia institucional. Para un recurso ajeno, exigir además una delegación NEW de ASSIGN exacta y vigente. EDIT_DRAFT, PUBLISH y el rol ADMIN_INSTITUCION por sí solo carecen de facultad de asignación. La asignación de destino se comprueba también cuando existe delegación.
- Conservar la revisión publicada exacta, la ventana inmutable de B8, revisiones y entregas previas, y la raíz F09 por evaluación/cuenta. Aplicar la unicidad revisión/clase y las condiciones de disponibilidad y cierre de B8. La asignación y admisión no consumen ni restituyen intentos.
- Confirmar entrega, destinatarios, historia, cuotas y SUCCESS T08 en una transacción. Vincular los reintentos al actor, alcance y contenido normalizado. Revalidar autorización antes de devolver el recibo durable. El UUID incompatible, una versión obsoleta y la segunda escritura concurrente para la misma revisión/clase deben fallar sin efecto adicional.

Se propone la ruta `POST /api/institutions/{institutionId}/classes/{classId}/evaluation-assignments/NEW/v1/evaluations/{evaluationId}/revisions/{revisionId}/deliveries`. El cuerpo usa `operationId`, `expectedRevisionVersion`, `startsAt`, `dueAt` y `enrollmentIds`. La bandera propuesta es `app.evaluations.institutional.new-assignment-enabled`.

El bloque se limita a crear una entrega y sus destinatarios iniciales. La sustitución docente, las bajas, las readmisiones, los traslados y F03 siguen con las reglas posteriores de T11-D. La activación por defecto queda apagada y la ejecución se limita a fixtures. [Responsabilidades P09–P11](../arquitectura/11d-cierre-preparacion-modelo.md)

## Dependencia de G y contexto B10

H depende de la publicación NEW de G, de la facultad ASSIGN tipada en el mismo schema de ensayo y de una forma comprobable de extender su trazabilidad. Crear la entrega y sus destinatarios cambia el manifiesto B10. Antes de implementar las rutas, fijar la prueba del delta H y los consumidores que necesitan verificarla. Debe enlazar el antecedente de G o la cabeza B10 vigente, demostrar que solo cambiaron las filas B8 de la entrega, sus destinatarios y su historia y conservar los contextos de las entregas ya aprobadas.

Una publicación nativa de G puede originar una entrega en espera de conciliación. Las nuevas relaciones se habilitan para alumnos mediante la conciliación F08 explícita y sus dos aprobaciones. Durante ese intervalo deben conservarse la autoridad de autoría que G pueda acreditar y la ejecución F de las entregas anteriores que ya tenían contexto vigente. Una corrección, incertidumbre o deriva ajena mantiene el rechazo. Esta dependencia es parte del diseño H y no convierte la asignación en aprobación F08. [B10](../arquitectura/11b10-conciliacion-contextos.md) · [Contrato G](12g-autoria-publicacion-new.md)

## Verificación de H

Verificar en HTTP real y PostgreSQL desechable ambos sentidos entre dos instituciones, la matriz de facultades, su revocación y vigencia, y las matrículas manipuladas, caducadas o retiradas. Cubrir la atomicidad con fallos de auditoría y cuota, la carrera de revisión/clase, el reintento tras respuesta perdida y la deriva de contexto. Demostrar que una entrega anterior sigue ejecutándose durante la asignación, que la nueva requiere F08 y que ambas comparten consumo y numeración después de la conciliación. Si H añade esquema, verificar instalación, repetición y recuperación.

## Ramas y publicación futura

- Base local de G en develop, T12-F integrado en `2169515779c460249ad7c973625e0019a8c81125`.
- G sigue en `feature/t12g-autoria-publicacion-new`. H parte del HEAD revisado local de G, `aa052d6a2c96b3a1ac25c948f8513e773cc64e6d`, en `feature/t12h-asignacion-publicaciones-new`. El historial propio de H empieza con este alcance.
- Cuando vuelva el acceso, publicar primero la PR de G hacia develop. Una vez que G esté integrado, comprobar que develop contiene la corrección revisada, trasladar únicamente los commits propios de H si la estrategia de integración cambió sus SHA y abrir la PR de H hacia develop. Registrar la PR de G como dependencia. La decisión de integrar, activar o desplegar sigue pendiente.
