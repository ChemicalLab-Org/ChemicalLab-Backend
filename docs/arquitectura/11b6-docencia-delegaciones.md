# T11-B6 / P01 — Co-docencia y delegaciones de biblioteca

13 de octubre de 2026, America/Lima. Alcance exclusivo: **preparación persistente
de P01 y mantenedores internos**. Rama `feature/t11b6-docencia-delegaciones`, PR
backend en borrador hacia `develop`. Base esperada y remota tras fetch:
`8d9aaf5a8b82cea59b9c7783f19d42a78252ae33`; no avances posteriores que incorporar.
Checkout aislado `.codex-work/t11b6-backend`. No AGENTS.md aplicables en backend
ni ancestros; el proyecto de vídeo queda fuera del alcance. Leídos T11-A, T11-C,
B1–B5, sus cierres locales y garantías pertinentes T05/T07-B/T08/T10.

## Decisiones humanas confirmadas

1. CO_DOCENTE tendrá facultades académicas completas en sus clases asignadas:
   sesiones, asignación de materiales/evaluaciones y calificación, conservando las
   restricciones existentes. Su aplicación efectiva pertenece a T12/T13.
2. ADMIN_INSTITUCION designa y revoca gestores de recursos de su colegio.
3. EDIT_DRAFT, PUBLISH y ASSIGN son independientes: ninguna implica otra.
4. Autoría y evidencia histórica permanecen intactas.
5. ADMIN_INSTITUCION solo no califica: requiere DOCENTE vigente y asignación a la
   clase. Se conservan prohibición de autocalificación y restricciones de nota cerrada.

No se rediseñan calificación, publicación, principal institucional ni derechos F03.
Las decisiones complementarias ajenas a P01 permanecen abiertas.

## Persistencia y contratos internos

Expansión explícita `src/main/resources/db/expansion/t11b6-v1.sql`. JDBC/records,
sin entidades JPA, instalador de arranque, bootstrap, listeners, jobs, endpoints ni
paneles. No se consulta este modelo como autoridad desde ningún consumidor legacy.

| Tabla | Contrato y protección PostgreSQL |
| --- | --- |
| `t11b6_co_assignments` | Episodio CO_DOCENTE con institución/clase/membresía, ventana original UTC finita, estados PLANNED/ACTIVE/ENDED/CANCELLED, activación/fin, actor/motivo y versión. FKs compuestas; ventana dentro del período; activación exige institución/clase/período y pertenencia/grant DOCENTE válidos |
| `t11b6_co_events` | Snapshot tipado por ID/institución/versión, instante efectivo y registrado, actor/motivo; creado por trigger. Inserción manual ordinaria, UPDATE/DELETE/TRUNCATE rechazados |
| `t11b6_teaching_slots` | Reserva derivada tipada con FK al RESPONSABLE B2 o al co-docente. Exclusión GiST por clase/membresía/intervalo impide duplicados y funciones contradictorias; otra exclusión impide dos responsables. Escritura ordinaria prohibida; trigger comprueba correspondencia exacta con el padre |
| `t11b6_resource_grants` | ResourceManagerGrant con exactamente un conceptId o evaluationId. FKs compuestas a recursos institucionales B3 y a destinatario/otorgante/revocante; facultad única, ventana UTC, ACTIVE/REVOKED, actores, fechas y motivos DESIGNATED/REMOVED |
| `t11b6_grant_events` | INSERT y revocación UPDATE con snapshot completo y FK tipada; append-only, sin reconstrucción posterior de evidencia |
| `t11b6_usage` / `t11b6_schema_version` | Contador transaccional de filas/bytes lógicos y ledger propio ligado al fingerprint B5; los ledgers precedentes no se sobrescriben |

B2 conserva su tabla RESPONSABLE y su CHECK original. La expansión añade una
relación independiente para CO_DOCENTE. Las reservas iniciales se derivan de las
activaciones B2 existentes bajo lock exclusivo, sin modificar sus filas, IDs o eventos.
El trigger nuevo `teaching_assignments.t11b6_slot_sync` mantiene la reserva en
cada activación/fin de B2. No hay copia de matrículas, recursos o cuentas.

Ventanas `[desde,hasta)`. El episodio de docencia reserva
`[activated_at,min(valid_until,ended_at))`; los terminales conservan el intervalo
histórico. Planificar no concede acceso; cancelar no crea intervalo. Un nuevo episodio
puede empezar exactamente en el fin del anterior. No se reactiva ni modifica un
episodio terminal, identidad, función, ventana original o activación ya registrada.

`AcademicMaintenanceService.replaceResponsible` sigue terminando y creando solo
responsables. Las nuevas reservas impiden promover a un co-docente con episodio
solapado: hay que terminarlo explícitamente antes, conservando ambos históricos.
El reemplazo por otra persona deja intactos todos los co-docentes independientes,
classId, matrículas, cuentas, recursos y autoría. Un sucesor inválido revierte también
el fin del predecesor, sus eventos, reserva, cuota y evidencia.

Los grants tienen vigencia finita y `valid_from >= granted_at`. Otorgar exige
destinatario con membresía y DOCENTE vigentes, administrador con membresía y
ADMIN_INSTITUCION vigentes, cuentas activas y colegio ACTIVE. La procedencia
administrativa se comprueba en DB incluso por SQL directo: la membresía indicada
debe corresponder a la cuenta otorgante/revocante y al mismo colegio.
El servicio obtiene esa cuenta exclusivamente de SessionPrincipal autenticado;
una llamada sin actor se rechaza. No se acepta un actor aportado por el llamador.

Revocar requiere administrador vigente del mismo colegio; puede revocar aunque
el destinatario ya haya salido. Conserva facultad, destinatario, ventana, autoría
y otorgante originales; añade revocante/fecha/motivo e historia. No se restaura un
grant revocado. El intervalo efectivo es
`[valid_from,max(valid_from,min(valid_until,revoked_at)))`, vacío si se revocó antes
del inicio. Las exclusiones por recurso/membresía/facultad incluyen historia revocada;
impiden solapamiento retroactivo y admiten adyacencia. Otra facultad permanece intacta.

El recurso apunta a procedencia tipada e inmutable B3, no a la pertenencia actual
del autor. Un grant almacenado no certifica frescura posterior de la conciliación
ni constituye autoridad: T12 deberá componer tiempo del servidor, estados, roles y
procedencia vigente. Co-docencia o recepción de una entrega no genera grants de
biblioteca. Ningún grant concede calificación, padrón/alumnos, transferencia de autor
o cambio de institución. No se altera raíz/consumo/cierre T05.

## Atomicidad, cuotas, auditoría y concurrencia

`DelegationMaintenanceService` mantiene planificar/activar/finalizar/cancelar
co-docencia y otorgar/revocar una facultad tipada. Las mutaciones JDBC, eventos por
triggers, reservas, cuotas pertinentes y SUCCESS T08 participan en el transaction
manager existente. Auditoría usa ADMIN_ACTION con objetivos CoTeachingAssignment
o ResourceManagerGrant, ID, institutionId permitido y correlación. Se amplía solo
la allowlist de objetivos seguros; no enum/CHECK, guardas o presupuestos T08.
Sin contenido, nombres, facultades libres, credenciales o datos de alumnos en logs.
La historia tipada conserva el cambio detallado y su actor.

El techo técnico propio de preparación es 100000 filas / 64 MiB lógicos,
incluyendo relaciones, eventos y reservas derivadas; UPDATE cobra delta lógico.
Es un límite de ensayo, no política escolar ni P04. Si las reservas existentes
lo superan, la expansión falla completa; no elimina ni recorta históricos. Los
guards/consumos T07-B y límites anteriores siguen operativos.

Orden interno: membresías por ID ascendente → institución → período → clase →
relación; grants: membresías ordenadas → institución → grant. Las activaciones
releen padres bajo locks. La exclusión de reservas arbitra entre tablas y procesos,
sin mutex JVM ni validación basada únicamente en un SELECT susceptible a carreras.
SQL arbitrario que invierta locks puede sufrir deadlock; no se certifica ausencia
universal de deadlocks ni capacidad productiva. El ensayo exige conflicto 23P01
en la restricción concreta, sin aceptar timeout/deadlock como éxito.

## Instalación, repetición, divergencia y recuperación

Cadena: **B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2 → B4 v1 → B4 v2 → B5 v1 → B6 v1**.
Los ocho SQL históricos y todas sus filas/fingerprints quedan intactos. B6 exige
B5 instalado, incluye su certificador congelado y certifica B1–B5 antes de evolucionar.
La única normalización del certificador B2 excluye exactamente el nuevo trigger
`t11b6_slot_sync` sobre `teaching_assignments`; su definición y enablement completos
forman parte de la firma B6. No se excluyen otras guardas ni se recalcula un
fingerprint previo para admitir divergencias.

Después de B6, **B6 v1 es el certificador de la cadena actual**; B1 aún puede
revalidarse. B3/B4/B5 anteriores rechazan correctamente el catálogo B2 extendido.
No ejecutar la cadena vieja completa sobre un sucesor instalado. La firma B6
cubre columnas/defaults/identity, constraints/validación, índices/estado, triggers,
funciones, vistas y secuencias, más el trigger añadido a B2. No cubre datos ni
consumo de secuencias, ni protege de un DBA que pueda cambiar fuente/ledger/guardas.

Procedimiento **futuro, solo con autorización operativa específica**: identificar
DB/schema/search_path, detener escritores, revisar fuentes/checksums, backup y
restauración, extensión y privilegios. Usar pgpass; sin credenciales en argumentos/logs.
No se ejecuta este procedimiento sobre datos escolares en esta entrega.

```powershell
# En esquema nuevo, aplicar una vez los ocho predecesores en el orden arriba indicado.
# En esquema B5 existente, aplicar solamente B6 tras verificar B5.
$env:PGOPTIONS='-c search_path=public'
psql --set ON_ERROR_STOP=1 --command 'select current_database(),current_schema(),current_setting(''search_path'');'
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b6-v1.sql
# Repetir B6 valida la cadena actual y no reinstala ni escribe negocio/ledgers.
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b6-v1.sql
```

DO de expansión atómico y advisory transaction lock común. Objetos B6 sin ledger,
versiones desconocidas, predecesor divergente o firma propia divergente se rechazan
sin reparación. Tras fallo, comprobar rollback y reintentar explícitamente después
de recuperar la causa; no sobrescribir firmas. Commit desconocido de mantenedores
exige investigar IDs/correlación antes de repetir altas o revocaciones no idempotentes.

Recuperar un dump completo en otra DB aislada, ejecutar B6 como certificador y
comparar manifiesto de datos/IDs/historia y guards T08 antes de habilitar escritores.
pg_restore carga datos antes de instalar triggers; no se deshabilitan guards sobre
datos vivos. Revertir binario conserva tablas inertes; DROP/CASCADE destruye historia
y no es recuperación. Este restore de fixture estático no resuelve P06/checkpoints
ni recuperación con escrituras posteriores. El rol runtime futuro no debe tener
ALTER/DROP/TRUNCATE ni escritura de ledgers/contadores; permisos reales no certificados.

## Verificación y límites

La evidencia definitiva con SHA probado, suite/empaquetado, carrera y restore se
registra al final de esta nota después de verificar el commit. Las selecciones de
desarrollo no se suman a la ejecución definitiva ni certifican un HEAD posterior.
Prueba reproducible: Windows/JDK17/PostgreSQL18, DB desechable loopback
`chemicallab_t01_test`, esquema propio `t03_<32 hex>`, perfil test:

```powershell
# Inicializar un cluster nuevo ficticio, puerto libre, solo loopback y max_connections=300.
./mvnw.cmd '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55436/chemicallab_t01_test' '-Dspring.datasource.username=t11b6_fixture' '-Dspring.datasource.password=fictitious' clean package
# Selección: añadir '-Dtest=DelegationPersistenceDbTest' y usar test.
```

Se preservan auditoría original, B1–B5/cierres/documentos históricos, frontend
institucional y 16 archivos E2E. Solo se actualizan decisiones confirmadas y matrices
vigentes T11-A/T11-C. **Se cierra únicamente P01 persistente**, sujeto a la evidencia
definitiva de esta entrega. P02–P06 permanecen abiertos; T11-B y etapa 3 no cierran.
T12/T13 conservan autorización y flujos externos; no se inician. SEC-13/VER-01,
Linux/SCA/operación siguen pendientes. Sin merge, despliegue, DDL/backfill real,
activación multiinstitucional, paneles ni endpoints públicos.
