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
| `system_logs`: actor global nullable, tipo/category/target, outcome/correlation, timestamps y metadata; `AuditLogService`, repositorio y trazabilidad | Resolución aditiva por logId. Para hechos de pizarra se verifica target_type/target_id/category y pizarra conciliada; resto usa mapa explícito, sin reinterpretar username/metadata como prueba. Los logs originales no se actualizan |
| `StudentUsageRecordService`, panel de uso y agregados admin, supervisión docente y consultas de actividad/último acceso | Combinan métricas de uso, logs, participantes y datos académicos legacy. Siguen siendo consumidores legacy; no reciben filtro institucional ni capacidades nuevas en B4. Su adaptación integral de selects, joins, count/totales y exportaciones pertenece a T12 |
| Credenciales/sesiones/revocación global, catálogo químico, salud y retención técnica | No se convierten en hechos de un colegio por pertenencia del actor. Eventos globales demostrados conservan scope GLOBAL sin institución/clase/pizarra |

No se importa el alumnado de un grupo ni se transforma matrícula en asistencia.
Un mapa puede incluir solo participantes existentes demostrados; los demás quedan
sin contexto individual y no desaparecen. La pizarra requiere clase identificada;
sin ella queda sin asociación, aunque el autor tenga una o varias membresías.
La conservación del vínculo no decide acceso a captura compartida tras la baja.

## Persistencia aditiva

Fuente `src/main/resources/db/expansion/t11b4-v1.sql`. JDBC, sin nuevas entidades
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
B3 v2 → **B4 v1**. No usar Hibernate update como reparación o instalador.
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
# Desde B3 v2 certificado: aplicar solo B4 v1.
# Certificar el esquema actual: B1 v1, B3 v2, B4 v1.
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
funciones storage/T08 y máximos de cuotas. Excluye valores dinámicos de contadores,
secuencias y datos; el manifiesto de recuperación compara estos por separado.
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
con el mismo schema; certificar B1/B3 v2/B4 v1 y comparar todas las firmas de
datos, IDs, autores, tombstones, bytes, checksums, historia y contadores.
Restauración carga datos antes de triggers; no deshabilitar guards en datos vivos.
Deriva de catálogo exige detener y resolver mediante otra transición versionada;
no recalcular firmas ni DROP/CASCADE para ocultarla. Revertir funcionalmente
deja las tablas inertes y conserva sus relaciones/historia.

## Matriz T11-A, antes de T12 y tareas posteriores

“Cumplido” se limita a persistencia/ensayo ficticio del criterio indicado; no
certifica autorización, operación real ni datos escolares conciliados. La
evidencia definitiva nueva y su SHA se registran al finalizar las ejecuciones.

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
| A12 regresión/revisión | Parcial hasta ejecución nueva registrada abajo; no reutilizar totales anteriores | Suite Linux/JDK17 y PostgreSQL nativo Linux, SCA/operación VER-01 y revisión de entrega siguen separados |
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

## Ejecuciones nuevas y evidencia previa

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

Resultados definitivos, SHA probado, HEAD y hashes se completan tras la ejecución.
