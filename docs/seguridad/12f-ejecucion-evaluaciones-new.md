# T12-F — Ejecución institucional NEW

16 de octubre de 2026, America/Lima. Base `aaebf4cd0e1674df1f62b3e256a3c000ec0e9a0d`
(T12-E integrado). Checkout aislado `.codex-work/t12f-backend`, rama
`feature/t12f-ejecucion-new`. Sin AGENTS.md aplicables ni delegación.

## Contrato elegido antes de implementar

La ruta explícita `evaluation-execution/NEW/v1` identifica el motor F. Los IDs
de intento de esa ruta pertenecen exclusivamente a `t12f_<fixture>.attempts`.
Las rutas T12-A/B NEW conservan la simulación `t11b8_fixture_attempts`; C/D/E
LEGACY conservan `evaluation_attempts` y sus recibos C/D/E. Nunca se busca un ID
en tablas alternativas. La entrega y revisión NEW siguen siendo las filas B8
publicadas exactas, previamente preparadas mediante el mantenedor de fixtures.

El primer inicio F hace explícita la adopción de ejecución mediante su ruta,
recibo y versión persistida. Se exige una raíz EXPLICIT_NEW sin
intentos de simulación B8 y sin assignments/attempts legacy. Las claves B8
budgets creadas al admitir destinatarios son preparación, nunca saldo F. Una raíz simulada
no se migra ni recibe saldo real. Cualquier mezcla posterior se rechaza como
ambigua. No se modifican las restricciones de B8 ni sus filas históricas.

Las propias filas F son el único ledger de oportunidades: COUNT por
recurso/cuenta, numeración consecutiva y unique parcial de activo. No hay
contador de consumo duplicado, devoluciones, budgets B3/B8 operativos ni
filas legacy para el ancla NEW. Cambiar clase, episodio o revisión no reinicia
ese ledger. Otro activo solo se recupera desde su entrega exacta.

Cada intento conserva institución, raíz, revisión, entrega, destinatario,
cuenta, episodio, configuración publicada, checksum, orden, inicio y plazo.
Respuestas, revisión y ajustes son parte de su agregado académico persistente;
las preguntas y opciones se identifican dentro de la revisión fijada.

El sucesor `t12f-v1.sql` conserva íntegros los diecisiete SQL anteriores y
certifica E antes de instalar F. El inventario F amplía explícitamente la
observación B10 con los agregados académicos F; no los clasifica como CONTROL.
Contextos y cuotas se verifican bajo la última cabeza F08 de la raíz y de sus
relaciones preparadas. Un nuevo F08 nunca recupera una autorización histórica
por replay. Los recibos F incluyen fotografías del agregado y de la raíz B10,
contexto aprobado, actor, acción, contenido, auditoría y medición.

## Límites y estado

Desactivado por defecto; perfil único test, schema aprobado, base ficticia y
loopback. Sin creación/publicación HTTP, frontend, paneles, activación real,
merge, despliegue, DDL/backfill real ni rotaciones. T12 integral y etapa 4
permanecen abiertos. Evidencia y pendientes se completan con resultados reales.
