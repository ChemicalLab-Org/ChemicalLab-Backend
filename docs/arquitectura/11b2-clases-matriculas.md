# T11-B2 — Períodos, clases, matrículas y responsabilidad docente

12 de octubre de 2026, America/Lima. Rama `feature/t11b2-clases-matriculas`,
PR backend en borrador hacia develop. Base actualizada mediante fetch:
`ec0ef0b4b8b093687517dc07a279b02d74d0084c`, merge T11-B1.
Checkout aislado `.codex-work/t11b2-backend`; se conservan checkouts y trabajo ajeno.
No se encontraron AGENTS.md aplicables en ancestros ni backend (ocultos incluidos).
Se leyeron [T11-A](11a-modelo-institucional.md), [T11-B1](11b1-instituciones-membresias.md),
cierre local T11-B1, auditoría original y notas pertinentes T02/T03/T05/T08/T10.

## Decisiones confirmadas y límites

1. Una clase representa un curso y grupo dentro de un período académico: por ejemplo,
   Química de 3.º A en 2026. Su identidad es independiente del docente.
2. Un alumno puede matricularse simultáneamente en varias clases del mismo colegio,
   con matrículas independientes y una sola cuenta.

Estas confirmaciones resuelven solamente esos aspectos de F02/F07. Permanecen:
cuenta en varias instituciones con permisos separados; ADMIN_INSTITUCION gestiona/
supervisa, calificar exige DOCENTE y asignación, sin autocalificación; lectura
individual histórica tras salir bajo T05. No se implementa su autorización aquí.
Co-docencia, padrón/entregas, traslados con intentos, F01, F05–F09 y complementos
restantes siguen pendientes en lo no confirmado. No se inicia T11-B3, T12 ni T13.

## Esquema y expansión independiente

Fuente explícita `src/main/resources/db/expansion/t11b2-v1.sql`, revisión
`t11b2-v1`, ledger propio `t11b2_schema_version`. Records Java y JDBC;
sin @Entity, migrador de arranque, seeder, backfill ni dependencia de Hibernate update.
El archivo T11-B1 y su firma quedan intactos. No se evoluciona ningún objeto B1:
la expansión B2 verifica primero su versión y su catálogo usando la misma definición
de firma. B1 puede volver a validarse después de B2, sin divergencia artificial.

La nueva expansión sí incluye una transición explícita de los CHECK legacy de
system_logs.event_type cuando esa tabla existe en el esquema seleccionado: conserva
su predicado anterior y agrega mediante OR sólo los ocho eventos B2. No modifica
filas ni la guarda append-only, versión o presupuesto T08. Rechaza CHECK no validado
o que mezcle event_type con otra columna, para exigir conciliación explícita.
La firma B2 incluye estos CHECK evolucionados; Hibernate update no los sustituye.
Un esquema de preparación sin system_logs puede expandirse, pero no ejecutar negocio
auditado; añadir después CHECK de eventos produce divergencia y exige transición.

| Tabla | Clave y datos | Integridad |
| --- | --- | --- |
| academic_periods | ID, institución, código local, nombre, ventana UTC, estado, versión/actor/instantes | Unique institución/código; inicio finitos y estrictamente anterior al fin; no año natural ni período global único |
| academic_classes | ID estable, institución, período, código local, nombre/curso, grado/sección | Unique institución/período/código; FK período/institución; grado 1–5/sección A–Z iniciales conforme T11-A |
| teaching_assignments | ID, clase/membresía/institución, RESPONSABLE, ventana, activación/fin, estado/versión/actor | Dos FKs compuestas a clase y membresía; único responsable por intervalo efectivo mediante exclusión GiST |
| enrollments | ID, clase/membresía/institución, número de episodio, ventana/activación/fin, estado/versión/actor | FKs compuestas; unique clase/membresía/episodio; exclusión de intervalos efectivos por pareja |
| *_events | FK entidad/institución, versión, instante efectivo/registro, actor/motivo, snapshot JSONB completo | Unique entidad/versión; append-only generado por trigger; FK tipada a cada tabla, sin historia polimórfica huérfana |

Los cuatro tipos de evento tienen tablas separadas: academic_period_events,
academic_class_events, teaching_assignment_events y enrollment_events. Identidades,
padres, códigos, atributos descriptivos y ventanas originales quedan fijos en este
mantenedor mínimo. No hay borrado/cascada ni edición cosmética general. Evolucionar
esos datos requiere un mantenedor y transición explícitos posteriores; no recrear IDs.
Los códigos técnicos son etiquetas internas, nunca códigos de acceso/incorporación.

## Estados, intervalos y conservación

Período: PLANNED → OPEN → CLOSED → ARCHIVED.
Clase: PLANNED → ACTIVE → CLOSED → ARCHIVED.
No se reabren cerrados/archivados ni se borra su historia. Abrir período exige
institución ACTIVE e instante dentro de su ventana; activar clase exige institución
ACTIVE y período OPEN, ventana vigente y estado del período ya efectivo.

Asignación: PLANNED → ACTIVE → ENDED; PLANNED → CANCELLED.
Sólo función RESPONSABLE. CO_DOCENTE se rechaza en DB: no se habilita ni se definen
facultades pendientes. Una clase puede quedar sin responsable durante una baja,
sin reconstruirla ni conceder acceso académico.

Matrícula: PENDING → ACTIVE ↔ SUSPENDED; ACTIVE/SUSPENDED →
COMPLETED/WITHDRAWN/TRANSFERRED; PENDING → CANCELLED.
TRANSFERRED conserva solamente el motivo de terminación local del episodio;
no crea matrícula destino, traslada intentos ni implementa traslado T13.
Readmisión crea otro ID/episodeNumber de matrícula para la misma clase/membresía,
conservando los anteriores. La readmisión de la membresía sigue el contrato B1.

Todas las ventanas son finitas UTC `[valid_from, valid_until)`, contenidas en el
período. No se obliga a años naturales ni a períodos disjuntos. Activación exige
`valid_from <= state_since < valid_until` y fija `activated_at` una sola vez.
El intervalo efectivo/reservado de un episodio es
`[activated_at, min(valid_until, ended_at))`; fin ausente significa valid_until.
PLANNED/PENDING y CANCELLED no tienen intervalo efectivo. Los terminales conservan
su intervalo histórico; una nueva relación puede empezar exactamente en su fin,
pero no superponerlo retroactivamente.

SUSPENDED conserva el intervalo reservado del episodio, impidiendo otro episodio
incompatible por pareja; no representa permiso de participación durante la suspensión.
Reactivar conserva activated_at y vuelve a validar pertenencia/grant/estado/ventana.
Las matrículas en otras clases tienen exclusiones independientes.

Transiciones son estrictamente crecientes, salvo primera activación desde pendiente/
planificada que puede coincidir con su creación. Esto permite un reemplazo adyacente
en el mismo instante, sin introducir hueco artificial. Una baja exige instante
posterior a activated_at; puede registrar un cierre tardío tras vencimiento natural,
sin ampliar el intervalo efectivo. Estado ACTIVE puede permanecer almacenado después
del fin de ventana. Cerrar institución/período/clase o suspender membresía no reescribe
automáticamente relaciones ni eventos. **T12 deberá combinar todos los estados,
vigencias y tiempo del servidor para autorizar; ACTIVE almacenado jamás basta.**
Los instantes efectivos del mantenimiento son parámetros del llamador interno
confiable, separados de recorded_at/updated_at PostgreSQL; no son un reloj de acceso.

## Activación, concurrencia y auditoría

Activar o reactivar comprueba en PostgreSQL: institución ACTIVE; período OPEN y
clase ACTIVE efectivos; membresía de misma institución ACTIVE en su ventana y
state_since; grant institucional DOCENTE o ESTUDIANTE ACTIVE y vigente en ese instante.
Un rol global legacy, ADMIN_INSTITUCION o grant ausente/futuro/vencido/revocado
no sustituye el grant requerido. Bajas/reactivaciones posteriores no fabrican permisos.

AcademicMaintenanceService es infraestructura interna confiable, sin autorización
externa ni conexión con controlador/login/job. Usa el transaction manager existente:
negocio JDBC, versiones/eventos por triggers y AuditLogWriter T08 confirman o
revierten juntos. Reemplazar responsable termina el anterior, crea y activa el
siguiente atómicamente; cualquier error de relación o evidencia restaura el anterior.
Se conservan classId, cuentas y matrículas. No se prueba acceso del reemplazo.

Orden interno: membresías por ID ascendente → institución → período → clase →
relación. Primero se bloquean padres y se releen estados; no mutex de JVM.
La asignación de episodeNumber se hace bajo esos locks. B1 grant/revoke/terminación
bloquea la membresía, serializando su estado con la activación. La exclusión GiST
y unique son defensas finales también ante otras conexiones/SQL directo.
Los nuevos triggers de inserción siguen ese orden. UPDATE SQL directo adquiere
su fila antes del trigger: no se promete ausencia universal de deadlocks para
escritores arbitrarios que omitan el protocolo; PostgreSQL revierte al perdedor
y conserva integridad. Futuros mantenedores compuestos deben tomar previamente
todas las membresías ordenadas y los padres para evitar inversión de locks.
No se certifica capacidad productiva de estos locks de mantenimiento.

Nuevos eventos T08: ACADEMIC_PERIOD_CREATED/STATE_CHANGED,
ACADEMIC_CLASS_CREATED/STATE_CHANGED, TEACHING_ASSIGNMENT_CREATED/STATE_CHANGED,
ENROLLMENT_CREATED/STATE_CHANGED. Actor procede sólo de SessionPrincipal autenticado,
o null en llamada interna sin contexto. Motivo cerrado CREATED/estado destino;
snapshots conservan actor y cronología. Auditoría contiene objetivo/ID numérico,
institutionId permitido, SUCCESS y correlación, sin nombres/códigos ni texto libre.
Se extiende sólo vocabulario de targets/eventos, sin relajar filtro de secretos.
No hay cola, éxito posterior al commit ni historia recuperada por reconstrucción.

## Aplicación, repetición, divergencia y recuperación

El DO completo es atómico y comparte advisory transaction lock con B1. Exige B1
en el esquema seleccionado; objetos B2 sin ledger, versión desconocida o divergencia
B1/B2 se rechazan sin reparación. Firma de catálogo cubre columnas/defaults/identity,
constraints/validación, índices/estado, triggers/enablement, funciones y secuencias;
excluye datos/OIDs/consumo de secuencia. Ledger B2 también forma parte del catálogo.
No se actualizan firmas para esconder cambios; toda evolución necesita otra versión.
Firma no autentica criptográficamente fuente ni protege de un DBA que altera guardas/ledger.

Procedimiento futuro sólo tras autorización operativa específica: identificar DB/
schema, detener escritores, revisar checksum de fuente, backup/restauración y
permisos/extensión, comprobar search_path. Credenciales fuera de argumentos/logs:

```powershell
$env:PGOPTIONS='-c search_path=public'
psql --set ON_ERROR_STOP=1 --command "select current_database(),current_schema(),current_setting('search_path');"
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b1-v1.sql
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b2-v1.sql
# Repetir cada archivo valida su catálogo sin recrear ni cambiar datos.
```

Un fallo de expansión revierte el DO; un fallo de evidencia revierte todo el negocio.
Después de recuperar auditoría se reintenta explícitamente; commit desconocido exige
investigar IDs/correlación antes de repetir altas. Ante divergencia, detener mantenimiento,
comparar fuente/backup y corregir mediante transición autorizada, sin sobrescribir firma.
Restaurar dump completo a DB separada con mismo schema, revalidar B1/B2 y hashes/IDs/
historia antes de habilitar escritores. Restore carga datos antes de crear triggers,
sin deshabilitarlos sobre datos vivos. Reversión de aplicación conserva tablas inertes;
DROP/CASCADE destruiría historia y no es procedimiento productivo de recuperación.
Rol runtime futuro sin ALTER/DROP/TRUNCATE ni escritura del ledger; permisos reales
siguen sin certificar.

## Compatibilidad y pendientes

Sin cambios en UserAccount, perfiles legacy, credenciales/sesiones, evaluación/intentos,
materiales/pizarras/cuotas, login, JWT, principal ni autoridades REST/STOMP.
No cuentas nuevas para matrículas, inferencia desde docente/grado/sección legacy,
backfill ni traslados de intentos. No endpoints, paneles, selector ni códigos de ingreso.
Historia individual y calificación conservan T05; implementación externa T12/T13.
Se preservan auditoría, cierres anteriores, frontend institucional y los 16 archivos E2E.

SEC-13 y VER-01 siguen abiertos. No merge, despliegue, DDL real, registro público
ni activación multiinstitucional. Este bloque no completa T11-B ni la etapa 3.
La entrega termina con PR borrador para revisión.

## Verificación

Resultados definitivos y SHA probado se incorporan después de las verificaciones.
