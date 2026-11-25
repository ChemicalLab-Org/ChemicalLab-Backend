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
