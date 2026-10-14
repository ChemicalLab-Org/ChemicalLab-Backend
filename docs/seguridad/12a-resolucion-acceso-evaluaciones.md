# T12-A — Resolución y decisión interna de acceso a evaluaciones

14 de octubre de 2026, America/Lima. Base `develop` comprobada por fetch:
`0e91c8bc364e1e782ed7ee72be557c47648a3fc0` (T11-D/#79). Checkout aislado
`.codex-work/t12a-backend`, rama `feature/t12a-resolucion-acceso`. Sin AGENTS.md
aplicables en backend ni ancestros. No se delegó trabajo. Se leyeron T11-D §7,
matrices/contratos vigentes T11-A/T11-C, B1/B2/B3/B6/B8/B10, T02/T05 y el cierre
`Documentos/Seguridad/11d-cierre-integracion-modelo.md`.

Se implementa únicamente una decisión interna **sobre fixtures**. No hay llamadas
desde controladores, login, STOMP, jobs, frontend ni consumidores existentes.
Los mantenedores conservan sus guardas; este servicio exige también
`t11b5_fixture()` (DB `chemicallab_t01_test`, loopback `127.0.0.1`, schema propio
`t03_<32 hex>`). No instala SQL ni crea relaciones, intentos, consumos, entregas,
revisiones, cuotas, historia o SUCCESS de negocio. No ejecuta calificaciones.

## Contrato de entrada y salida

`EvaluationDeliveryAccessService.decide(SessionPrincipal, Request)` recibe un
principal **obtenido de la autenticación T02**, nunca reconstruido desde un body,
header o rol de cliente. `Request` contiene contrato, acción e IDs positivos de
institución, clase, evaluación lógica, entrega, revisión y cuenta destinataria;
`attemptId` es obligatorio para las acciones de intento/corrección/calificación,
opcional para supervisión y ausente para consulta de entrega.

| Contrato | Espacio de IDs y autoridad |
| --- | --- |
| LEGACY | Entrega/revisión/destinatario B3; intento original `evaluation_attempts`. La revisión se identifica como `TECHNICAL_SNAPSHOT`, sin inventar una publicación académica. Publicación, ventanas y resultado proceden del estado original vigente T05 |
| NEW | Entrega/revisión/destinatario y simulación de intento B8, recurso `EXPLICIT_NEW`. Revisión exacta `PUBLISHED` con publicación efectiva, identificada como `PUBLISHED_ACADEMIC`. El `Evaluation` DRAFT generado es un ancla técnica; no se exige publicarla ni se utiliza como contenido académico |

No se intercambian namespaces aunque coincidan los números. Una revisión sucesora
publicada no sustituye la revisión de una entrega anterior. Las entregas B8 de
origen LEGACY no se hacen pasar por NEW ni se enlazan arbitrariamente a un intento
original: este bloque decide el acceso legacy por su entrega B3. Su adopción
académica requiere un bloque posterior que demuestre la relación exacta.

`Decision` devuelve `ALLOW`, `DENY` o `UNAVAILABLE` y la acción solicitada.
Solo ALLOW contiene `Context`: contrato/tipo de revisión, IDs comprobados,
versiones B10 de raíz/entrega, proyección permitida e instante de observación del servidor. No contiene
payloads, preguntas, respuestas, criterios, claves, notas, feedback, saldos,
miembros de otras instituciones ni listados. DENY/UNAVAILABLE no identifican la
relación que falló y no devuelven contexto. UNAVAILABLE también deniega.

La proyección distingue contenido de entrega sin corrección, intento propio sin
corrección, metadatos de supervisión, revisión docente, corrección propia y solo
permiso de calificación. READ_DELIVERY de un supervisor devuelve la proyección
de metadatos: no autoriza cargar un payload académico crudo con claves ocultas.

La decisión **no es un token ni un permiso durable**, no tiene un plazo de gracia
y no puede almacenarse para autorizar otro acceso. Las versiones son trazabilidad,
no una exención de revalidación. Una nueva operación debe resolver de nuevo;
la futura adopción tendrá que coordinar lectura/escritura y revocaciones con los
locks/versiones de todos sus escritores. Este bloque no demuestra ausencia de
carreras entre una decisión ya retornada y una operación posterior.

## Fuentes de autoridad y resolución

1. `AccountSessionService.revalidate` comprueba sesión persistida, cuenta activa,
   revocación, expiración, versiones y principal T02, sin caché de autorización.
   Rechaza contraseña temporal persistida; este servicio rechaza también su
   bandera en el principal. Se revalida antes y después de la resolución B10.
   El rol global no concede facultades académicas.
2. Institución solicitada como selector: membresía única de esa cuenta/colegio,
   institución ACTIVE, membresía ACTIVE y ventana/estado ya efectivos. Roles
   exclusivamente desde `membership_role_grants`, ACTIVE, no revocados y vigentes.
   Clase ACTIVE, período OPEN y ventana/estados actuales coherentes. Se consulta
   también cuenta/membresía/rol ESTUDIANTE y matrícula del destinatario.
3. Última resolución B10 por fuente y PK, válida y scope INSTITUTION. Se comprueban
   raíz tipada `evaluations`, recurso, revisión, entrega, assignment original si
   aplica, destinatario e intento. Institución/clase/cuenta/episodio efectivos
   deben coincidir con los solicitados y con sus padres. Las relaciones físicas
   enlazan evaluación/revisión/entrega/destinatario exactos; el contexto corregido
   procede de B10, no de los snapshots B3/B4 originales.
4. `UNRESOLVED`, `CONFLICT`, `STALE`, contexto ausente y padres contradictorios
   deniegan. GLOBAL describe procedencia y no abre acceso público. No hay fallback
   a B3/B4, grado/sección, primera membresía, autor actual u otro colegio. La
   frescura B10 conserva su comprobación de fotografía, referencias y aprobaciones
   F08; no se debilita para dar acceso cuando cambian sus fuentes.
5. RESPONSABLE B2 y CO_DOCENTE B6 requieren relación ACTIVE, activación y ventana
   vigentes, misma membresía/clase/colegio y grant DOCENTE actual. No se deducen
   facultades por autoría o grants de biblioteca. Una revocación local corta solo
   su colegio; T02 puede revocar globalmente la sesión compartida.

Las lecturas usan una transacción independiente, read-only, REPEATABLE_READ,
con timeout de 15 segundos. No heredan el persistence context del llamador.
El tiempo procede de PostgreSQL; ventanas nuevas en UTC y legacy en America/Lima,
sin reinterpretar timestamps escolares. Los fallos de sesión, persistencia o
transacción no tienen una rama de autorización por defecto.

## Matriz verificable de permisos

Siempre se exige resolución coherente y vigencias anteriores. «Docente asignado»
incluye RESPONSABLE y CO_DOCENTE, con las facultades ya confirmadas en B6/F04.

| Acción | Estudiante destinatario | ADMIN_INSTITUCION | Docente asignado |
| --- | --- | --- | --- |
| READ_DELIVERY | Propia; publicación y ventana disponible T05/B8 | Metadatos de supervisión | Metadatos de su clase; esta decisión no entrega claves |
| READ_OWN_ATTEMPT | Solo intento propio; sin anticipar inicio ni liberar corrección | No por administrar | No por enseñar; puede coincidir con su permiso propio de estudiante |
| SUPERVISE | No por rol de alumno | Su institución | Su clase |
| REVIEW_ATTEMPT | No por ser alumno | Exige además DOCENTE + asignación | Intento terminal ajeno; sin autocalificación |
| READ_CORRECTION | Propia, iniciada, GRADED/FINAL y nota cerrada, cierre global T05/B8 | No por administrar | Su revisión docente tiene acción separada |
| GRADE_ATTEMPT | No por rol de alumno | Exige además DOCENTE + asignación | Intento terminal ajeno y nota abierta; decide permiso, sin calificar |

ADMINISTRADOR global sin grants/relaciones institucionales no obtiene acceso.
La combinación de roles no anula los vetos de autocalificación y nota cerrada.
Un grant EDIT_DRAFT/PUBLISH/ASSIGN de biblioteca no concede lectura de alumnos,
supervisión, revisión ni calificación de intentos.

## T05, conservación y F03

Se comparte `EvaluationAccessPolicy.reviewAvailable` con el cálculo existente de
`EvaluationService`: misma política de cierre global, sin cambiar el motor ni
conectar el nuevo servicio. LEGACY cuenta **todas las asignaciones actuales**, no
solo las mapeadas a la entrega consultada: futuras/vacías/sin dueAt bloquean;
archivo/desactivación explícitos y vencimiento global conservan T05. Solo GRADED
y nota cerrada pueden exponer corrección propia y nunca antes de startAt.

NEW comprueba todas las entregas/revisiones de la raíz lógica, incluso vacías,
futuras y sin vencimiento, reutilizando `t11b8_closed` y los cierres explícitos de
raíz/archivo B8, y que no queden notas de simulación abiertas. Cerrar
solo una entrega, terminar o agotar intentos no basta. Se rechaza NEW si aparecen
writers/consumidores legacy (assignments/intentos) en su ancla. No se interpreta
un presupuesto B3/B8 como saldo vivo ni se consulta para conceder oportunidades.
El motor legacy permanece como única autoridad productiva; B8 sigue siendo ensayo.

**F03 permanece íntegro**: la lectura individual histórica tras baja está confirmada
bajo T05. Este servicio acota accesos de contexto vigente y no implementa su flujo
tras baja; un DENY aquí no redefine ni extingue ese derecho. T13 deberá ofrecerlo
en el contexto y proyección correctos. Ningún consumidor histórico fue sustituido.

## Verificación, integración futura y límites

`EvaluationDeliveryAccessDbTest` usa PostgreSQL desechable y la cadena congelada
B1–B11; sesiones T02 reales, dos instituciones ficticias y relaciones persistidas.
Comprueba A→B/B→A, cuenta común con facultades distintas, siete selectores/IDs
manipulados, propios/ajenos, roles combinados, admin sin DOCENTE/asignación,
RESPONSABLE/CO_DOCENTE, biblioteca sin calificación, suspensión/terminación,
grants revocados/vencidos/futuros, revocación local/global, expiración/versiones,
contraseña temporal/inactividad/sesión ausente, contextos omitidos/inciertos/stale,
revisión exacta, autocalificación/nota cerrada y bloqueo global T05.
Las firmas de tablas comprueban ausencia de cambios en negocio, historia, cuotas
y SUCCESS; se induce un error PostgreSQL real para verificar UNAVAILABLE.

La futura adopción de consumidores/escritores, mapas reales/P08, carreras de acceso
con escritores, privacidad y operación sigue fuera del bloque. T12-A no cierra
aislamiento integral, SEC-13, VER-01, Linux/SCA ni las puertas T13–T17. No despliegue,
DDL/backfill real, datos escolares, rotaciones, activación multiinstitucional o merge.
Entrega mediante commits y PR backend en borrador hacia develop, detenida para revisión.

La evidencia exacta de selecciones, suite completa y empaquetado se incorpora tras
su ejecución; no se suman selecciones ni se atribuyen ensayos a un SHA posterior.
