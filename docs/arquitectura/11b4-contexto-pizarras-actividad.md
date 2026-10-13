# T11-B4 — Contexto institucional de pizarras y actividad

13 de octubre de 2026, America/Lima. Preparación interna y ensayo ficticio.
Rama `feature/t11b4-contexto-pizarras-actividad`; base remota `develop`
`70415ad46f22b4ec9790ce7aa2e1d327918e6fda`, confirmada mediante fetch.
Checkout aislado `.codex-work/t11b4-backend`. No se encontraron AGENTS.md
aplicables en backend ni ancestros, incluidos ocultos. No se emplearon skills
de artefactos ni agentes delegados. Esta entrega no cierra T11-B ni la etapa 3.

Se leyeron [T11-A](11a-modelo-institucional.md), [B1](11b1-instituciones-membresias.md),
[B2](11b2-clases-matriculas.md), [B3](11b3-recursos-intentos.md),
`Documentos/Seguridad/11b3-cierre-recursos-intentos.md` y las notas pertinentes
[T03](../seguridad/02-03-seguridad-pizarras.md),
[T07-B](../seguridad/02-07b-consultas-cuotas-retencion.md),
[T08](../seguridad/02-08-auditoria-eventos.md) y
[T10](../seguridad/02-10-concurrencia.md). Las confirmaciones F02/F03/F04 y
las adicionales de B2 permanecen; no se deciden F01/F05/F06/F08/F09 ni las
políticas complementarias de históricos compartidos, co-docencia o biblioteca.

## Inventario y comprobación de procedencia

| Origen y consumidores actuales | Relación demostrable / resolución requerida |
| --- | --- |
| `whiteboard_sessions`: teacher_id, grade/section, estado, revisión, JSON, timestamps y closed_by; `WhiteboardSessionService`, repositorio y controladores docente/alumno/admin | No contiene período o clase. Institución y clase requieren mapa por PK aprobado. teacher_id demuestra autor global, nunca su colegio; grade/section son etiquetas coincidentes válidas |
| `whiteboard_participants`: PK, session_id/student_id únicos, override, joinedAt/lastSeenAt; join/presence/listados y `WhiteboardParticipantRepository` | session_id demuestra pizarra; student_id → perfil → user_id demuestra cuenta original. La asociación institucional conserva esa cuenta. enrollmentId solo mediante mapa explícito y comprobación del episodio al joinedAt original |
| `whiteboard_object_ownership`: PK, board_id, tipo/ID, owner_user_id nullable, deleted | FK a pizarra permite herencia mediante vista verificable. Conserva autor y tombstone; NULL continúa desconocido. No requiere una copia por objeto ni deduce autor desde participante, matrícula, prefijo o JSON |
| Captura: final_snapshot_data/content_type/file_name/size dentro de la sesión; descarga y cierre | Mismo PK/padre demuestra ámbito. Vista conserva revisión/estado, tamaño real y checksum de bytes. No se duplica bytea en una tabla de capturas; la fotografía del ledger sí lo copia y cobra esa copia |
| current_state_json, state_revision/state_updated_at; draw, snapshot GET/PUT y difusión STOMP | Evidencia T03 conservada sin reinterpretar JSON histórico como autoría. Snapshot B4 guarda incluso texto no interpretable. Writers T03/T10 mantienen su lock, permisos, revisión y afterCommit |
| `usage_events`: cuenta/rol/module/event_type, resource_type/resource_id, metadata y occurredAt; `UsageMetricService`, `UsageEventRepository`, `UsagePanelService` | Cuenta es actor global. Los identificadores de recurso de telemetría general no prueban por sí mismos participación. Cada evento requiere mapa/evidencia aprobados; relación a pizarra exige padre conciliado vigente y recurso/módulo coincidentes |
| Métrica WHITEBOARD_SESSION_JOINED | Writer servidor T10 registra después de commit; un rejoin puede producir otro evento con el mismo participante. Un evento no crea participante ni matrícula; contar eventos y contar participantes son magnitudes diferentes. Fallo opcional conserva el contrato legacy de join |
| `system_logs`: actor global nullable, tipo/category/target, outcome/correlation, timestamps y metadata; `AuditLogService`, repositorio y trazabilidad | Resolución aditiva por logId. En B4 v2 el destino tipado WhiteboardSession exige pizarra conciliada vigente, institución y clase coherentes, incluso con category=ADMIN y boardId omitido; resto usa mapa explícito, sin reinterpretar username/metadata como prueba. Los logs originales no se actualizan |
| `StudentUsageRecordService`, panel de uso y agregados admin, supervisión docente y consultas de actividad/último acceso | Combinan métricas de uso, logs, participantes y datos académicos legacy. Siguen siendo consumidores legacy; no reciben filtro institucional ni capacidades nuevas en B4. Su adaptación integral de selects, joins, count/totales y exportaciones pertenece a T12 |
| Credenciales/sesiones/revocación global, catálogo químico, salud y retención técnica | No se convierten en hechos de un colegio por pertenencia del actor. Eventos globales demostrados conservan scope GLOBAL sin institución/clase/pizarra |

No se importa el alumnado de un grupo ni se transforma matrícula en asistencia.
Un mapa puede incluir solo participantes existentes demostrados; los demás quedan
sin contexto individual y no desaparecen. La pizarra requiere clase identificada;
sin ella queda sin asociación, aunque el autor tenga una o varias membresías.
La conservación del vínculo no decide acceso a captura compartida tras la baja.

## Persistencia aditiva

Fuentes `src/main/resources/db/expansion/t11b4-v1.sql` y su transición explícita
`t11b4-v2.sql`. JDBC, sin nuevas entidades
JPA, migrador de arranque, bootstrap, controlador, listener, job o endpoint.

| Objeto nuevo, prefijo `t11b4_` | Contrato |
| --- | --- |
| reconciliations | Origen ficticio, BOARD/USAGE/AUDIT + PK, versión jsonb-v1, fotografía coherente del origen y referencias, mapa exacto, dos checksums, aprobación/checksum compuesto, estado y actor |
| reconciliation_events | Transiciones generadas por trigger, append-only; cada copia cobra bytes/filas. Rechaza inserción manual ordinaria y UPDATE/DELETE/TRUNCATE |
| board_contexts | PK boardId original; institutionId/classId y autor global original; membresía autora opcional demostrada. FK clase/institución y membresía/institución; procedencia inmutable |
| participant_contexts | PK participante original; pizarra/institución/clase consistentes por FK compuesta; cuenta verificada, matrícula original opcional, timezone explícita si hay matrícula, evidencia y procedencia inmutables. Unique pizarra/cuenta, sin crear participantes legacy |
| usage_contexts / audit_contexts | PK evento original; GLOBAL o INSTITUTION, institución/clase/pizarra opcionales según scope, cuenta original nullable solo en auditoría y evidencia de mapa. FKs compuestas; append-only sin cascada |
| board_observations / ownership_contexts / capture_contexts | Herencia por padre comprobado, con reconciliation_status y source_current; no se crean nuevos autores/tombstones/capturas |
| fact_observations / usage_observations / audit_observations | Vigencia calculada sobre origen, mapa y referencias; conserva un vínculo histórico aunque el origen derive, sin presentarlo como vigente |
| schema_version | Versión/revisión, fingerprint B3 v2 predecesor y fingerprint propio, sin sobrescribir ledgers anteriores |

El episodio se comprueba contra clase, institución y cuenta de la membresía;
`joined_at AT TIME ZONE sourceTimezone` debe caer en
`[activated_at, min(valid_until, ended_at))`. No se exige la membresía actual
como sustituto de evidencia anterior ni se supone America/Lima para todo legacy.
Esa ventana demuestra correspondencia temporal, no permiso de asistencia durante
suspensiones, asistencia por matrícula ni autorización de acceso actual.
Cambiar episodio/membresía después del ensayo no mueve el participante: su
enrollmentId original permanece y source_current informa deriva de referencias.

Las relaciones verifican también procedencia APPROVED y fotografía vigente al
insertar, incluso por SQL directo. El mapa no puede contradecir el autor del
origen, la cuenta del perfil/participante ni el target del evento de pizarra.
Las FKs rechazan cruce de institución/clase/matrícula aunque se omita el servicio.
Actividad/auditoría con padre tipado B1/B2 o recurso ConceptContent/Evaluation B3
comprueba también la institución y clase que ese padre demuestra. Una clase
requiere su classId; un recurso de biblioteca demuestra institución sin fabricar
clase de evento. Recursos B3 requieren conciliación APPLIED vigente. La fotografía
incluye la referencia y su vigencia; modificaciones posteriores producen deriva.
No se transfiere teacher_id, owner_user_id, IDs ni claves de padres legacy.

## Conciliación interna, aprobación y ambigüedad

`BoardActivityReconciliationService` admite propose/approve/apply. Exige DB
`chemicallab_t01_test`, servidor `127.0.0.1` y schema propio `t03_<32 hex>`.
La función SQL de aplicación exige la misma guarda. Estos límites replican B3;
no son permisos productivos ni autorización T12. La expansión puede certificarse
en una restauración separada sin permitir aplicación de datos allí.

Ejemplo exclusivamente ficticio de pizarra:

```json
{
  "institutionId": 101,
  "classId": 102,
  "authorAccountId": 103,
  "authorMembershipId": 104,
  "participants": [{
    "participantId": 105,
    "accountId": 106,
    "enrollmentId": 107,
    "sourceTimezone": "America/Lima",
    "evidence": "FIXTURE-JOIN"
  }]
}
```

enrollmentId/sourceTimezone se omiten conjuntamente si la matrícula no consta.
participants puede estar vacío. Para evento demostrado de pizarra:

```json
{
  "scope": "INSTITUTION",
  "institutionId": 101,
  "classId": 102,
  "boardId": 108,
  "evidence": "FIXTURE-SERVER-RELATION"
}
```

La evidencia FIXTURE-* es una convención cerrada de ensayo, no identificación de
responsables reales F08. No se aplica el mapa por grupo, docente, username o
primera membresía. GLOBAL exige `{ "scope": "GLOBAL", "evidence": "FIXTURE-GLOBAL" }`
y un hecho global demostrado por vocabulario conservador: auditoría de login,
logout, cambio/reset de password, revocación global, gestión global legacy de
cuentas/permisos, salud o retención técnica;
uso SYSTEM_STATUS. Estos eventos rechazan toda institución. Los demás hechos
sin resolución se proponen con `{}`; no se clasifica arbitrariamente un evento
genérico de navegación como global o escolar. Otros tipos globales requieren
una evolución explícita del clasificador cuando su procedencia se demuestre.

Estados: PROPOSED → APPROVED → APPLIED / UNRESOLVED / CONFLICT / STALE.
Sin mapa, UNRESOLVED conserva la incertidumbre de contexto. CONFLICT conserva
una contradicción demostrada; toda asociación provisional se revierte mediante
subtransacción. APPLIED/UNRESOLVED/CONFLICT pueden pasar a STALE al detectar
deriva; STALE es terminal. Los snapshots/mapas/aprobaciones no se reescriben.
La desaparición externa de una fuente produce STALE conservando la fotografía;
no admite proponer una PK ausente.
Otro mapa/fotografía genera otro ID; no reemplaza automáticamente contexto ya
insertado. Una PK ya asociada produce conflicto para el nuevo mapa, conservando
la asociación histórica anterior. Resolver/cortar writers reales sigue pendiente.

Propose es idempotente por tipo/PK/checksum de fuente/mapa, bajo advisory lock.
Approve liga MD5(sourceChecksum || proposalChecksum) y evidencia exacta.
MD5 solo detecta cambios del JSONB canónico; no es firma criptográfica ni aval
humano. Propuestas sin aprobación se rechazan antes de comprobar frescura, sin
STALE/SUCCESS ficticio. Reintentos terminales no duplican filas, historia, cuota
o auditoría. Apply compara el retorno SQL con el estado persistido bajo lock.

Las fotografías incluyen bytes, participantes/perfiles/ownership, clase,
institución, membresía autora y matrículas/membresías explícitas. En eventos
con pizarra incluyen su relación, estado de conciliación y vigencia de origen;
un padre STALE o derivado impide adoptar el evento. Una sentencia produce la
fotografía coherente; al aplicar se toman locks de tablas antes del ledger,
se relee y se compara. Propose/approve/apply toman el mismo orden de tablas
antes del ledger y antes de auditar, evitando invertir esos locks entre
operaciones del mantenedor. Son locks amplios de fixtures, sin protocolo productivo.
Un writer legacy posterior puede invalidar la observación; B4 no sincroniza
silenciosamente las relaciones ni afirma conciliación continua.

## Actividad, auditoría y métricas

Las tablas nuevas asocian hechos existentes; no distribuyen ni duplican logs
entre colegios. `system_logs` conserva filas y guarda T08 append-only con
outcome/correlation históricos intactos. La ausencia de un vínculo no concede
acceso global. Una futura consulta institucional debe exigir vínculo demostrado,
scope institucional y vigencia/política de histórico pertinente, junto con T12.
source_current por sí solo tampoco concede acceso.

Los eventos de conciliación reutilizan el vocabulario acumulativo B3
RESOURCE_RECONCILIATION_* con target numérico **BoardActivityReconciliation**,
distinto de ResourceReconciliation para que IDs coincidentes no se confundan.
UNRESOLVED se audita como RESOURCE_RECONCILIATION_CONFLICT; el estado exacto
está en el historial interno. No se amplía ni reescribe el CHECK T08 ni el enum
de roles/eventos. Solo se añade ese target al vocabulario cerrado AuditSafety.
Actor proviene de SessionPrincipal, o NULL en mantenimiento sin sesión; no se
acepta del mapa. Auditoría contiene SUCCESS/correlación y tipo/ID numérico, sin
fotografías, nombres, metadata libre, respuestas, bytea o evidencia del mapa.

T12 debe vincular nuevos hechos desde el contexto confiable de petición/socket
y padre verificado, dentro de la transacción de negocio/T08. No reutilizar el
campo de cliente ni una membresía actual para históricos. El writer opcional
de métricas después de commit mantiene el contrato T10: rollback de join no
publica métrica; si la métrica falla, join ya confirmado permanece. Su futura
relación debe confirmarse en la propia transacción de métrica o no existir.
No se convierte telemetría opcional en evidencia obligatoria de asistencia.

Los índices por institución/clase/cuenta/PK preparan consultas internas. Los
agregados actuales permanecen legacy: no se crean paneles. T12 deberá seleccionar
eventos/participantes únicamente del ámbito autorizado, contar PK/eventos y
cuentas según cada indicador, excluir GLOBAL/UNRESOLVED/CONFLICT del agregado
escolar y declarar cobertura de hechos retenidos/conciliados. Una cuenta común
no une A/B. LastSeenAt, joinedAt, eventos de rejoin, participantes distintos,
capturas existentes y sesiones cerradas no son intercambiables como métricas.

## Bytes, cuotas y atomicidad

Se conservan límites, guards, propietarios y contadores T07-B/T08. B4 no añade
una categoría ni una política institucional de cuota. Usa las categorías B3
REVISION_BYTES=268435456 y REVISION_COUNT=1000 por cuenta original del origen.
Para auditoría sin actor se usa scope SYSTEM/ownerId=0, con los mismos límites;
es un contador técnico, no cuenta inventada, institución por defecto ni actor
ficticio. El presupuesto final institucional sigue pendiente de decisión.

Se cobra **cada fila/copia** de ledger, historial y relaciones por el tamaño de
su JSONB serializado. La fotografía de captura incluye bytea en hexadecimal,
con expansión completa y sin descuento de compresión. Actualizaciones permitidas
del ledger cobran su delta; el historial cobra la copia nueva de mapa/metadata.
Las vistas no almacenan otra copia de bytes. No es medición de índices/WAL/backups
o almacenamiento físico PostgreSQL. Exceso original se conserva: fallar una
copia nueva no borra ni reduce el origen o su contador legacy.

Un fallo de cuota P7501 sale de la subtransacción de contradicción y revierte
todo. Relación, participantes/contextos, historial, cuotas y SUCCESS obligatorio
comparten la transacción Spring/JDBC/JPA existente. Fallo de auditoría al pasar
a APPLIED, CONFLICT o STALE revierte también esa transición y sus contadores.
El ledger no anuncia un éxito que no se haya persistido. SQL directo comprueba
integridad, pero no sustituye el mantenedor auditado.

No se habilita retención. Los FKs nuevos preservan fuentes referenciadas:
retirar físicamente usage_events conciliados requiere una evolución explícita
de conservación/retención y revisión de su impacto; la FK no permite borrar
esa evidencia inadvertidamente. T07-B conserva su contrato en datos legacy sin
esas asociaciones. Resolverlo antes de un corte real, sin CASCADE destructivo.

## Transición, repetición, certificación y recuperación

Cadena: legacy T07-B/T08 con CHECK cerrado → B1 v1 → B2 v1 → B2 v2 → B3 v1 →
B3 v2 → B4 v1 → **B4 v2**. No usar Hibernate update como reparación o instalador.
Solo ensayos ficticios autorizados en esta entrega. Procedimiento futuro real
requiere autorización operativa propia, writers detenidos, backup probado,
search_path/DB explícitos, checksum de fuente y permisos revisados.

```powershell
# Primera instalación, un archivo por transacción y ON_ERROR_STOP:
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b1-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b2-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b2-v2.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b3-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b3-v2.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b4-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b4-v2.sql
# Desde B4 v1 certificado: aplicar únicamente B4 v2.
# Certificar el esquema actual: B1 v1, B3 v2, B4 v2.
```

Los cinco archivos SQL anteriores y sus ledgers/fingerprints quedan intactos.
B4 exige B3 v2 instalado antes de ejecutar una copia literal del certificador
B3 v2: valida B1, el sucesor B2 y todo B3, sin instalar un predecesor ausente.
No cambia objetos/firma B3 ni CHECK T08; por ello B1 v1/B3 v2 siguen certificando
sus catálogos después de B4. B2 v1/v2 y B3 v1 siguen rechazando deliberadamente
sucesores conforme a B3; no se usan para reparar un catálogo posterior.

La firma B4 cubre columnas/defaults/identity, constraints/validación, índices y
su estado, triggers habilitados, funciones, vistas y secuencias B4; también
columnas/constraints de fuentes y cuotas/auditoría, triggers legacy pertinentes,
funciones storage/T08 y máximos de cuotas. Excluye consumo dinámico de secuencias y datos; el manifiesto compara filas y
contadores por separado. pg_dump restaura posiciones de secuencia, pero el arnés
no compara su consumo dinámico. nextval puede dejar huecos tras rollback;
no es un contador de negocio ni cambia IDs ya existentes.
Cuatro CHECK legacy de enum (rol de auditoría/uso, categoría y severidad) se
normalizan en la firma solo para casts text/varchar sin longitud y distribución
de esos casts en ARRAY, equivalentes tras dump/restore PostgreSQL. No se altera
el CHECK persistido ni se omiten valores, operadores, paréntesis lógicos o
validación. La prueba negativa amplía un CHECK con UNKNOWN y exige rechazo.
Primera instalación registra firma/predecesor nuevos; repetición verifica y no
reinstala ni repara. Objetos parciales sin versión y versiones desconocidas se
rechazan. No se presenta la firma como defensa frente a un DBA que altere el ledger.

El DO es atómico y usa el advisory lock de la cadena. Fallo/interrupción antes
de commit revierte objetos y versión; se puede certificar B3 v2 y repetir B4.
Commit desconocido se resuelve por ledger/correlación y reintento del mismo ID,
sin recrear pizarra/participantes. Restaurar dump completo a DB ficticia separada
con el mismo schema; certificar B1/B3 v2/B4 v2 y comparar todas las firmas de
datos, IDs, autores, tombstones, bytes, checksums, historia y contadores.
Restauración carga datos antes de triggers; no deshabilitar guards en datos vivos.
Deriva de catálogo exige detener y resolver mediante otra transición versionada;
no recalcular firmas ni DROP/CASCADE para ocultarla. Revertir funcionalmente
deja las tablas inertes y conserva sus relaciones/historia.

## Corrección de revisión: padre WhiteboardSession, B4 v2

Hallazgo de `.codex-work/t11b4-review/revision.md` y arnés
`VerifyBoardAuditParent.java`: en B4 v1 un ADMIN_ACTION/category=ADMIN con
target_type=WhiteboardSession conciliada en A aceptaba un mapa de B sin boardId.
El padre no formaba parte de su fotografía y cambiar state_revision no invalidaba
source_current. La aprobación no legitima contradicciones del origen.

Antes de cambiar el SQL se repitió el arnés sobre los servicios compilados de
`3531ff445c22ba057703be7974cf7c848eb50a31`, checkout documental
`dcbd7ab7416da666b10fe5a4e86e670e04c426ad`. La nueva reproducción obtuvo
APPLIED, boardId omitido, institución de auditoría B frente a pizarra A,
source_current=true tras deriva y log original idéntico. Exit 0. El error
deliberado de auditoría corresponde al escenario de rollback. Se conserva en
`.codex-work/t11b4-fix-evidence/board-audit-parent-results.json` y
`board-audit-parent.log`, separados de los resultados de revisión y de las 705
pruebas de la entrega anterior. El intento inicial sin permiso de loopback no
completó la reproducción; retiró su servidor y no constituye evidencia positiva.

B4 v2 reemplaza únicamente event_parent y reference_guard mediante DDL explícito.
El destino tipado original WhiteboardSession se resuelve por su target_id/resource_id,
independientemente de boardId en el mapa. Incluye contexto de pizarra, estado de
conciliación y fotografía completa del padre, con su checksum/vigencia. La
institución y clase deben coincidir exactamente, y el padre debe estar APPLIED y
vigente. ADMIN_ACTION con destino tipado comprobado puede conservar un contexto
válido aunque la categoría sea ADMIN; esto demuestra destino, no participación,
asistencia, rol efectivo ni permisos. boardId omitido sigue NULL: no se modifica
el mapa aprobado ni se inventa una columna del vínculo.

Mapa institucional contrario, ID inválido, padre ausente, no conciliado o ya
desactualizado al proponer: CONFLICT sin nueva fila institucional. Mapa vacío:
UNRESOLVED sin institución. Deriva entre propuesta/aprobación y aplicación:
STALE antes de insertar. Asociación válida seguida de deriva: source_current=false
y el reintento explícito pasa a STALE una vez. La categoría WHITEBOARD sin destino
WhiteboardSession comprobable continúa rechazada. La relación de uso con boardId
conserva además su requisito de módulo/recurso servidor coincidentes.

Los hechos GLOBAL demostrados mantienen su clasificación y fotografía previa;
no incorporan un padre escolar aunque tengan un destino tipado atípico. Ningún
log original se reescribe. Las copias nuevas de la fotografía de pizarra en el
ledger de auditoría se cobran íntegramente, incluida la captura y las copias de
historial, mediante las cuotas existentes. SQL directo conserva guards y cuotas;
la auditoría T08 obligatoria se verifica en la transacción del servicio, como en
B4 v1. No se atribuye un SUCCESS del servicio a una llamada SQL directa.

Los seis SQL anteriores, incluido B4 v1, conservan sus bytes y fingerprints.
V2 exige B4 v1 instalado y su cadena B3 v2 vigente; compara el catálogo contra
el fingerprint original antes de cambiarlo. Añade t11b4_apply_version con
predecessor_fingerprint igual a la firma de B4 v1 y fingerprint sucesor completo;
las versiones previas no se actualizan. El sucesor cubre los mismos elementos
que v1, además de la tabla y guards de su versión. Repetir v2 certifica sin
reinstalar; deriva o versión desconocida se rechazan sin recalcular la firma.
B1 v1 y B3 v2 siguen certificando. B4 v1 solo rechaza deliberadamente el catálogo
sucesor: el certificador actual es B4 v2. No ejecutar v1 para intentar degradarlo.

Instalación en fixture propio: certificar B3 v2 y B4 v1, ejecutar B4 v2 con
search_path/DB explícitos, --single-transaction y ON_ERROR_STOP, repetir B4 v2,
comparar las filas de versiones y las firmas de los datos. No hay backfill de
datos ni aplicación automática. Rollback de la transición restaura funciones,
catálogo y ausencia de versión v2, y permite certificar v1 antes de reintentar.
Commit desconocido: consultar la versión y repetir v2; no recalcular fingerprints.
La recuperación después de commit conserva el sucesor inerte o restaura un dump
íntegro a otra base ficticia. Certificar B1/B3 v2/B4 v2, comparar tablas, historia,
cuotas y logs append-only, manteniendo bloqueada la aplicación fuera de la DB de
fixtures. No retirar guards ni borrar evidencia para volver a v1.

Las fotografías v1 de eventos tipados de pizarra afectados cambian de interpretación
verificable y quedan source_current=false; sus filas, mapas, contextos y estados
no se reescriben en la instalación. El reintento aprobado pasa a STALE. Una
propuesta v2 genera una fotografía nueva y exige aprobación nueva; el mapa
contradictorio queda CONFLICT. Una asociación errónea v1 ya persistida se conserva
como evidencia histórica desactualizada, nunca se reasigna a otro colegio.
Resolver/corregir esa fila histórica mediante otra política aditiva permanece
pendiente: v2 no borra el vínculo ni elude su PK para crear un reemplazo automático.

## Matriz T11-A, antes de T12 y tareas posteriores

“Cumplido” se limita a persistencia/ensayo ficticio del criterio indicado; no
certifica autorización, operación real ni datos escolares conciliados. La
evidencia definitiva nueva y su SHA se registran abajo.

| Criterio | Estado del ensayo T11-B | Antes de T12 / posterior |
| --- | --- | --- |
| A01 coincidencias | Parcial; B1/B2 y B4 separan institución/clase y grupos iguales A/B | F05 códigos académicos/aliases/reservas institucionales aún pendientes; no cambiar login global |
| A02 membresías/roles | Parcial; una cuenta en A/B y grants cerrados B1 conservados | PlatformRoleGrant y episodios completos pendientes B1; autorización/combinación F04 se prueba en T12 |
| A03 integridad | Cumplido para relaciones implementadas, con FKs/guards y carrera concreta B4 | Co-docencia/delegaciones y demás relaciones aplazadas requieren evolución propia; gate de acceso T12 |
| A04 cuenta | Cumplido en ensayo: no cambia cuentas/perfiles/credentials/IDs | Conciliación real y procedimiento F08 pendientes; no fusión de identidades |
| A05 evidencia | Cumplido en fuentes cubiertas B1–B4: autoría, capturas, tombstones, participantes, logs y presupuestos conservados | Inventario real completo, fuentes históricas irrecuperables y aprobación/certificación operativa pendientes |
| A06 reemplazo | Parcial; B2 conserva clase/episodios, B4 no transfiere autores | Acceso/pérdida de facultades durante sustitución T12/T13 |
| A07 traslado/simultaneidad | Parcial; matrícula nueva no reescribe participante original ni raíz legacy | Traslado con intento abierto y continuidad/revocación real T13, políticas complementarias pendientes |
| A08 mapa/ambigüedad | Parcial; ledger UNRESOLVED/CONFLICT/STALE y mapa exacto en fixtures | F08 responsables/mapas reales y exhaustividad por todas las PK antes de un corte; no aplicación real automática |
| A09 repetición/recuperación | Cumplido para cadena y fixtures cubiertos: rollback, reintento, dump/restore | Lotes/checkpoints/capacidad e interrupción real de writers requieren procedimiento posterior autorizado |
| A10 cuotas/auditoría | Cumplido en ensayo cubierto, copias cobradas y logs append-only | Política/cuotas institucionales, retención de telemetría conciliada, privilegios y operación SEC-13/T15–T17 pendientes |
| A11 compatibilidad cerrada | Parcial; no nuevos endpoints/roles/UI/registro ni activación multiinstitucional | Legacy aún no implementa filtro/contexto institucional: su gate completo y cierre de superficies ambiguas son T12 |
| A12 regresión/revisión | Parcial; suite nueva completa registrada abajo; Linux/operación aún pendientes | Suite Linux/JDK17 y PostgreSQL nativo Linux, SCA/operación VER-01 y revisión de entrega siguen separados |
| A13 máximo agotado | Cumplido en ensayo B3; B4 no modifica autoridad legacy | Corte/sincronización de writers y exposición/admisión institucional T12 conservando T05 |
| A14 entregas/raíz/carrera | Cumplido en ensayo B3, autoridad/cierre original conservados | Mismo pendiente de corte T12; F09 sigue pendiente para evaluaciones realmente nuevas |
| A15 activo previo | Cumplido en ensayo B3; sin reset de activo/consumo por B4 | Continuidad/baja y resolución de conflictos reales T13/procedimiento F08 |
| A16 readmisión/clases | Cumplido en ensayo B3; episodio original de participante conservado B4 | Gate de simultaneidad/acceso bilateral T12 y continuidad T13 |
| A17 cierre/conciliación | Cumplido en ensayo B3; B4 no cambia T05 ni exposición | Corte real explícito, autorización F03 T12/T13 y resolución real F08 |

Pendientes persistentes anteriores: grants de plataforma/códigos académicos/aliases
e historia completa B1; facultades co-docentes/edición general B2; delegación de
biblioteca, ciclo académico de revisiones y writers/presupuestos sincronizados B3.
B4 añade ámbito de pizarra/participante y hechos; no completa automáticamente
todo T11-B ni autoriza comenzar T12 sin revisar esos pendientes.

T12 debe cubrir REST/STOMP entrante/saliente/pasivo, sesiones, descargas/capturas,
agregados/actividad, jobs/cachés/exportaciones y aislamiento A↔B con cuenta común.
T13 cubre continuidad/sustitución/traslado/readmisión y lectura propia F03 bajo
T05. Registro/invitaciones/recuperación y paneles pertenecen a tareas posteriores.
SEC-13, VER-01 y certificación operativa permanecen abiertos. No hay merge,
despliegue, bases reales, activación institucional ni implementación T12/T13.

## Evidencia de primera entrega, anterior a la corrección B4 v2

Evidencia heredada: cierre B3 registra 675 pruebas sobre
`e02c7acfe5dd796f93d33825492a2208169adc03`, restauración de 61 tablas y carreras
B3. Esos resultados no se atribuyen a B4; las selecciones previas tampoco se
suman a una ejecución nueva. La evidencia B4 se conserva aparte en
`.codex-work/t11b4-evidence`, fuera de la PR.

Primera selección B4: 21 pruebas, dos fallos de arnés, cero errores/omisiones.
La barrera adquiría lock antes de terminar los arranques de JVM; se movió
después de readiness. La prueba de TRUNCATE esperaba la guarda T08, pero la FK
aditiva rechazaba antes; se usó TRUNCATE CASCADE exclusivamente sobre restore
ficticio para comprobar también la guarda append-only. Log/XML originales
conservados. No se presentan como aceptación ni se modificó negocio para ocultarlos.
Segunda selección: 21 pruebas, cero fallos, un error de certificación restore,
cero omisiones. Se compararon las definiciones completas: cuatro CHECK tenían
casts de ARRAY equivalentes tras pg_dump/pg_restore. Tras normalización acotada,
la selección individual restore pasó (1/0/0/0). Se conserva el diagnóstico y
esas selecciones no se suman a la ejecución definitiva posterior.

Comando reproducible, Windows/JDK17/PostgreSQL 18, Maven offline con caché:

```powershell
# Clúster nuevo, datos únicamente ficticios y puerto libre:
initdb -D ../t11b4-postgres -U t11b4_fixture -A trust --encoding=UTF8 --locale=C
pg_ctl -D ../t11b4-postgres -l ../t11b4-evidence/postgres.log -o '-h 127.0.0.1 -p 55434 -c max_connections=300 -c shared_buffers=32MB' -w start
createdb -h 127.0.0.1 -p 55434 -U t11b4_fixture chemicallab_t01_test
./mvnw.cmd -o '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55434/chemicallab_t01_test' '-Dspring.datasource.username=t11b4_fixture' '-Dspring.datasource.password=fictitious' clean package
# Selección B4: añadir '-Dtest=BoardActivityReconciliationDbTest' y ejecutar test.
pg_ctl -D ../t11b4-postgres -m fast -w stop
```

El arnés usa schema aleatorio y limpia sus fixtures. La carrera B4 tiene dos
JVM/OS independientes, sin Hibernate update posterior a expansión; ambas están
listas antes de la barrera. Deben observarse dos PID PostgreSQL esperando lock
antes de liberarla; aplica una asociación y un SUCCESS aun si ambos devuelven
APPLIED. No se crean carreras artificiales para lecturas sin conflicto.
El dump/restore nativo compara todas las tablas y vuelve a certificar; no es
restauración productiva ni prueba de capacidad/carga sostenida.

### Resultado de primera entrega B4 v1 (evidencia anterior)

**SHA probado:** `3531ff445c22ba057703be7974cf7c848eb50a31`. `clean package` completo:
**57 suites, 705 pruebas, cero fallos, errores u omisiones; JAR generado**.
Incluye 30 escenarios B4 y las regresiones T03/T07-B/T08/T10, B1/B2/B3,
cuentas/sesiones, intentos/ventanas/materiales y demás suites existentes.
Los mensajes deliberados SECURITY_AUDIT_EVIDENCE_UNAVAILABLE y arranques rechazados
pertenecen a controles negativos; los XML Surefire confirman los totales.

Entorno: Windows, JDK 17.0.12, PostgreSQL 18.4 desechable, Maven offline,
perfil test, loopback 127.0.0.1:55434. Los comandos reproducibles figuran arriba.
No se ejecutó Linux, SCA nueva, base/roles/TLS reales ni carga sostenida.
La restauración mantiene el mismo clúster y TimeZone de sesión; para un futuro
procedimiento verificar también `SHOW TimeZone` y conservar la representación
de origen de las fotografías. No se certifica aquí recuperación entre zonas
distintas ni se convierte una marca local histórica por suposición.

JAR SHA-256: `0a27686766a1f01035bf7b3f5b2e249aba6b8621eeb1bdce9ca5c654633f108e`.
SQL B4 v1 UTF-8/LF SHA-256: `1c28b9e95889e03cc1f0feb3b6aa32f2487ee684b4d535ae6f63a21efc9a21b9`.
El JAR contiene los seis SQL idénticos al SHA probado. Los cinco hashes SQL
anteriores coinciden con la evidencia B3, y sus fuentes/fingerprints no cambian.
CRLF del checkout puede cambiar el hash físico; contrastar contenido Git/LF.

Recuperación nueva dentro de esta suite: **68 tablas del esquema propio**, con
firmas iguales antes/después, IDs, autores, tombstones, bytes, snapshots/checksums,
historia y contadores. Certifica B1 v1/B3 v2/B4 v1. UPDATE/DELETE/TRUNCATE CASCADE
de auditoría rechazados; guarda de aplicación rechaza la DB restaurada separada;
alterar un trigger B4 se detecta sin reparación. Prueba de instalación/rollback
mantiene el predecesor certificable y admite repetición explícita.

Carrera nueva B4: PID OS [35220, 2172], PID PostgreSQL bloqueados
[23284, 36628]; ambos retornan APPLIED, con una asociación,
un historial APPLIED y un SUCCESS. Barrera liberada solo después de observar
las dos conexiones esperando Lock. Las carreras B3/T10 de la suite se atribuyen
a esta ejecución; no se suman como tests adicionales.

Ejecución intermedia independiente: `a2f5b6b01d749c0c6c3df2c14415b4b0b830c9eb`,
57 suites/701 pruebas/0/0/0, anterior a los cuatro casos y ajustes finales.
Selecciones B4 de 26 y después 30 casos aprobadas se conservan aparte.
No se suman entre sí, a las 705 finales ni a las 675 históricas de B3.

Evidencia local nueva: `.codex-work/t11b4-evidence/verified-results.json`,
`full-package-final.log`, `verified-reports.zip`, `verified-native-signatures.json`,
`verified-native/`, `verified-processes/`, `preservation-final.json` y `cleanup.log`.
Los logs/XML del primer y segundo ensayo, diagnóstico de casts y resultado
intermedio tienen nombres separados y permanecen fuera de la PR.

Limpieza confirmada: cero esquemas propios pendientes y cero conexiones ajenas
en la DB ficticia; después de retirar su base, cero bases chemicallab% en el
clúster propio y servidor detenido. Dumps, manifiestos y logs quedan conservados.

Conservación: **29/29 archivos protegidos**, incluidos los 16 E2E y trece
documentos locales de seguridad. Los 27 de la evidencia anterior coinciden;
se añadieron al manifiesto los dos cierres B2/B3. Auditoría original SHA-256
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Los 56 archivos documentales backend previos conservan sus blobs. El único documento
backend nuevo es esta nota. Frontend sigue en
`feature/validaciones-datos-institucionales`, HEAD
`e88589635a8269c50ee0f53fccf157f0d0985034`, con `?? e2e-selenium/` previo.
Backend original conserva develop limpio en la base declarada.

La nota de resultados se confirma después del SHA probado; no cambia producción,
SQL, pruebas ni POM. El HEAD completo final se entrega con la PR en borrador,
separado del SHA probado. T11-B y etapa 3 siguen abiertos; sin merge, despliegue,
bases reales, activación multiinstitucional o implementación T12/T13.


## Resultados nuevos de la corrección B4 v2

Esta sección corresponde exclusivamente a la corrección de revisión del PR #70;
no suma ejecuciones focalizadas, reproducción ni evidencia anterior B4 v1/B3.

**SHA probado:** `9c60faaa4dd50aa9c34e65a7c44da1f5b5cd0f58`. `clean package` completo offline: **58 suites,
714 pruebas, cero fallos, errores u omisiones; BUILD SUCCESS y JAR generado**.

Incluye 38 casos BoardActivityReconciliationDbTest y un caso de transición
BoardAuditParentTransitionDbTest, frente a los 30 casos B4 de la primera entrega.
Las 675 pruebas preexistentes se ejecutan nuevamente sobre este SHA, incluidas
las regresiones T03/T07-B/T08/T10 y B1–B3. El empaquetado y sus resultados nuevos
no se atribuyen a los SHAs anteriores.

Comando nuevo, checkout `.codex-work/t11b4-backend`, Windows/JDK 17.0.12 y
PostgreSQL 18.4, DB ficticia propia en loopback, TimeZone America/Bogota:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
./mvnw.cmd -o '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55434/chemicallab_t01_test' '-Dspring.datasource.username=t11b4_fixture' '-Dspring.datasource.password=fictitious' clean package
```

Ejecuciones nuevas separadas:

| Ejecución | Resultado / límite |
| --- | --- |
| Reproducción v1, antes de editar código | Caso adverso confirmado y cinco controles existentes positivos; exit 0. Servicios compilados del SHA previo, no corrección |
| Selección inicial B4 | 37 pruebas, 2 fallos de aserción, 0 errores: dos contadores nuevos miraban todas las fixtures acumuladas; se corrigieron a los eventos de su pizarra. No se ocultan ni se suman a la ejecución final |
| Selección corregida B4 + transición | 39 pruebas, 0 fallos/errores/omisiones. SQL directo y servicio, padre/deriva, idempotencia, cuotas, rollback T08, transición/recuperación y GLOBAL |
| Empaquetado completo del SHA indicado | 58 suites, 714 pruebas, 0 fallos/errores/omisiones |

Restauración nueva: **69 firmas de tablas idénticas**, incluida
t11b4_apply_version. Catálogos original/restaurado coinciden; B1/B3 v2/B4 v2
certifican, UPDATE/DELETE/TRUNCATE CASCADE del log siguen rechazados, la aplicación
en DB restaurada separada continúa bloqueada, y alterar un guard hace fallar la
certificación. La misma carrera concreta de aplicación duplicada se repitió con
dos JVM, PIDs OS `[34276, 21064]`, dos esperas de PostgreSQL
`[35160, 2920]` y resultados APPLIED/APPLIED,
una asociación y un SUCCESS. No se agregaron carreras hipotéticas.

JAR SHA-256: `3447a5e41b96363e43000553eefdd7fce3e6ec7f59d4daa44828991ddccfaa5c`.
SQL B4 v2 UTF-8/LF SHA-256: `96adca19b81028eda16eda7efad8b69ae74a50beb74d2149678b2f2f11e2115d`.

El JAR contiene los siete SQL idénticos al SHA probado. Los seis anteriores
mantienen sus blobs y hashes, incluida B4 v1; las filas y fingerprints anteriores
se comparan antes/después de la transición y tras restauración.

Evidencia nueva en `.codex-work/t11b4-fix-evidence/`: reproducción Java/PowerShell,
board-audit-parent-results.json, board-audit-parent.log, focused.log,
focused-first.xml, focused-corrected.log, focused-corrected.xml,
focused-transition.xml, full-package.log, verified-results.json,
verified-reports.zip, verified-native-signatures.json, verified-native/,
verified-processes/, predecessors.json, preservation.json, cleanup.log y estado
del PR. La evidencia `.codex-work/t11b4-evidence/` y la revisión independiente
`.codex-work/t11b4-review/` no se sobrescribieron.

Preservación nueva: 29/29 hashes protegidos idénticos, incluidos auditoría original,
13 documentos de seguridad locales y 16 archivos E2E. Los demás documentos backend
anteriores no cambian; únicamente se actualiza esta nota. Frontend mantiene HEAD
e88589635a8269c50ee0f53fccf157f0d0985034 y el estado previo `?? e2e-selenium/`.
El checkout backend original sigue limpio en develop de la base integrada.
El clúster dedicado queda sin fixtures/restauraciones propias y detenido.

El commit posterior al SHA probado únicamente completa esta nota, sin cambiar
SQL, código, pruebas ni POM. Se publica sobre la misma rama y PR #70 en borrador.
Quedan pendientes revisión de esta corrección, Linux/JDK17/PostgreSQL nativo
Linux, SCA nueva y certificación operativa/SEC-13/VER-01. No se certifican roles,
TLS, capacidad ni datos reales; no se prueba restauración entre timezones distintas
ni consumo dinámico de secuencias. La corrección no resuelve la política funcional
de reemplazo de un contexto erróneo histórico: lo conserva desactualizado. La matriz
A01–A17 mantiene su alcance y pendientes antes de T12 y posteriores; T11-B y etapa
3 permanecen abiertos. Sin merge, despliegue, activación multiinstitucional ni T12/T13.
