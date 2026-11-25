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

La revisión de código añadió la comprobación de que toda cabeza existente del agregado pertenezca a la propuesta que ancla el recibo, reforzó la comparación de la ventana con la solicitud y volvió a comprobar los episodios tras sellar el delta. Se probaron deriva, rollback ante auditoría diferida y cuota y rechazo de divergencia del catálogo. Los informes por SHA, XML de Surefire, registros de PostgreSQL y huellas de recuperación se guardan bajo `.codex-work/t12h-evidence`.

Se compararon 192 archivos protegidos, incluidas las 16 huellas E2E originales, y los 19 SQL históricos hasta G. Se conservaron el frontend, los checkouts anteriores y los resultados G. El bundle de G coincide con su SHA-256 anterior y la copia Git restaurada pasó `git fsck --full`.

En la carrera de revisión/clase se observó un 503 por el límite de espera. El registro técnico de rechazo T08 tuvo un fallo de persistencia durante esa contención. Se verificó una única entrega, un único recibo y un único SUCCESS transaccional. El cliente de prueba descartó el cuerpo de la respuesta inicial y recuperó el resultado con el mismo UUID. El comando competidor devolvió 409 al repetirlo.

La ejecución final en `04039750d9fe25b27b84f1fb93e29be3e55d49f8` aprobó los cuatro casos H con HTTP real y PostgreSQL 18.4, sin fallos, errores ni omisiones. Cubrió el aislamiento bilateral y la correspondencia de episodios, la matriz de facultades y revocaciones, el rollback y reintento bajo contención, y el contexto pendiente con dos aprobaciones F08 distintas. La entrega anterior mantuvo lectura, escritura y consumo compartido. Se repitió la certificación F/G/H antes y después de la restauración física con WAL en streaming. `pg_verifybackup` confirmó la copia, las 143 firmas coincidieron y el recibo se recuperó por HTTP.

- XML Surefire final SHA-256 `a2f948ed0ff415370089dfc63058f55ed4bd5217e086d97b18bedb6aef5fa982`.
- Firmas de recuperación SHA-256 `7e05dc70d99eee64874271ce6007a107fe762e6641dece53d7719d956d18b058`.
- `.codex-work/t12h-evidence/results.json` conserva los resultados y fallos por SHA. `preservation.json` registra el cotejo de archivos y checkouts.

## Recuperación de Git

El bundle de H, `t12h-<SHA>.bundle` bajo `.codex-work/t12h-evidence`, contiene el historial completo y las referencias de `develop`, G y H. Consulte el manifiesto `bundle-<SHA>-verification.json` para obtener el nombre exacto y la huella SHA-256. Clone el bundle en un directorio nuevo.

```powershell
git clone --branch feature/t12h-asignacion-publicaciones-new <bundle> <directorio-nuevo>
git -C <directorio-nuevo> fsck --full
```
