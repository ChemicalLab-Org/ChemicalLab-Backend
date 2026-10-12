# T11-A — Propuesta del modelo institucional de ChemicalLab

**Versión:** 1.0 · **Fecha:** 12 de octubre de 2026, America/Lima.

**Estado:** propuesta completa para revisión; no autoriza T11-B ni apertura multiinstitucional.

## 0. Alcance y bases comprobadas

Se recomienda una identidad global de cuenta, membresías por institución y clases con identidad estable. El alumno conserva su cuenta al cambiar de clase, docente o colegio. Los recursos pertenecen a la institución; la responsabilidad de su uso en una clase cambia mediante asignaciones, sin sustituir al autor histórico.

Esta entrega contiene solamente diseño. No modifica entidades, endpoints, pantallas, migraciones, configuración ni pruebas. No se ejecutó DDL, arrancó la aplicación, consultó una base escolar, desplegó, instaló software global ni habilitó registro público. No redacta textos legales. La rama institucional previa y los 16 archivos E2E se conservan.

| Fuente | Base examinada y conservación |
| --- | --- |
| Backend `develop` | `30a7e7f757bae2684140a2d20b9681ac2a06ec23`, T10 integrada; checkout inicialmente limpio. GitHub confirmó la misma base durante T11-A |
| Frontend `develop` | `ed068f7bc268d0b2cfa3904b1b2a152414a8883c`, inspeccionado en el checkout existente `.codex-work/t02-frontend`, limpio. GitHub confirmó la misma base |
| Frontend institucional previo | `feature/validaciones-datos-institucionales`, `e88589635a8269c50ee0f53fccf157f0d0985034`; se inspeccionó su estado y se preservó, sin cambiarlo por develop |
| Instrucciones | Búsqueda de `AGENTS.md` en ancestros y dentro de ambos repositorios, incluidos ocultos/ignorados: ninguno aplicable. El del proyecto de vídeo queda fuera de alcance |
| Auditoría original | `Documentos/Seguridad/01-auditoria-seguridad.md`; SHA-256 `1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`, conservado |
| Cierres | T01 local y notas versionadas T01–T04; cierres locales T05, T06, T07-A, T07-B, T08, T09 y T10; notas de implementación de seguridad y contratos actuales |

Los cierres locales están en `Documentos/Seguridad/` del workspace, fuera de este repositorio. Las notas versionadas se enlazan en §8. No existe un cierre local separado T02/T03/T04 en ese directorio: sus resultados y correcciones se documentan en las notas versionadas. La auditoría describe una base anterior; las referencias de diagnóstico siguientes corresponden a las bases actuales, no reproducen como vigentes los defectos corregidos por T01–T10. Si develop avanza después de esta revisión, T11-B debe incorporar esos avances y revalidar referencias e invariantes, sin volver a las bases antiguas.

## 1. Diagnóstico del modelo actual

En las referencias backend, `B` significa `src/main/java/com/morales/chemicallab/`, relativo a la raíz del repositorio. En frontend, `F` significa `src/app/` del commit `ed068f7`. Los números son líneas de esas bases, verificables en Git; los enlaces frontend fijan el commit para evitar confundirlo con la rama institucional previa.

| Dependencia | Evidencia actual | Consecuencia para el diseño |
| --- | --- | --- |
| Rol único global | `B/entity/Role.java:3–6`; `B/entity/UserAccount.java:20–42` | Solo existen `ADMINISTRADOR`, `DOCENTE`, `ESTUDIANTE`; no institución, membresía ni `ADMIN_INSTITUCION`. Username/email son únicos globalmente; rol y credenciales viven en la cuenta |
| Cuenta de alumno ligada a un docente | `B/entity/StudentProfile.java:19–40`; `B/entity/TeacherProfile.java:19–21` | Perfil 1:1 con cuenta, docente obligatorio, código único global, grado/sección obligatorios. No hay matrícula independiente |
| Código académico usado para login | `B/service/UserManagementService.java:150–178`, `236–246`, `484`; `B/service/AdminService.java:224–252` | Alta usa `username = studentCode`; edición docente puede cambiar ambos; generador `EST%04d`. No admite el mismo código académico en dos colegios sin colisión de identidad |
| Gestión por docente de perfil | `B/service/UserManagementService.java:197–214`, `441–466`; `B/repository/RosterQueryRepository.java:31–43` | Padrón/páginas/resumen dependen de `teacher_id`; reasignar el perfil no transfiere coherentemente recursos y resultados |
| Reasignación limitada y revocación | `B/service/AdminService.java:304–333`; `B/service/UserManagementService.java:220–252` | Cambio de grado/sección/docente invalida sesiones T02, pero no crea episodios históricos de matrícula ni responsabilidad |
| Autor usado como dueño | `B/entity/ConceptContent.java:78–80`; `B/entity/Evaluation.java:85–87`; `B/entity/WhiteboardSession.java:44–52` | Contenido/evaluación tienen `createdByTeacher`; pizarra `teacher`, grado y sección. Autoría y gestión actual están acopladas |
| Asignación a textos de grupo | `B/entity/ConceptAssignment.java:26–42`; `B/entity/EvaluationAssignment.java:28–48` | Asignación apunta al recurso/docente y grado/sección, sin clase, período o institución |
| Visibilidad por grado/sección | `B/repository/ConceptContentRepository.java:36–64`; `B/repository/EvaluationAssignmentRepository.java:27–55`; `B/repository/WhiteboardQueryRepository.java:38–49` | Coincidencia textual concede visibilidad independientemente del docente creador. Es el alcance del modelo de un colegio; no acredita aislamiento futuro |
| Autorización de resultados y archivos | `B/service/EvaluationService.java:888–895`, `1359`, `1924–1960`; `B/service/ConceptMaterialService.java:268–294` | Edición/revisión docente por autor; descarga de alumno por publicación y grupo. Cambiar docente del perfil no entrega al reemplazo la revisión de una evaluación ajena |
| Revisión grupal y padrón mutable | `B/service/EvaluationService.java:1584`; nota T05 | El padrón actual usa grado/sección. T05 exige cierre definitivo de todas las oportunidades y cierre de nota; una migración no debe reabrirlas ni usar nuevo padrón para revelar corrección |
| Históricos académicos existentes | `B/entity/EvaluationAttempt.java:29–40`, `55–105`; `B/entity/EvaluationAttemptAdjustment.java:32–63`; `B/entity/WhiteboardParticipant.java:19–41`; `B/entity/WhiteboardSession.java:65–103` | Intento referencia evaluación, alumno y asignación opcional; conserva orden/nota/cierre/calificador. Pizarra conserva participantes, estado y captura; no acredita matrícula histórica completa |
| REST por rol global | `B/config/SecurityConfig.java:83–148`; `B/dto/AuthResponse.java:5–16`; `B/dto/CurrentUserResponse.java:10–20` | Prefijos teacher/student/admin y un rol en respuesta. No basta añadir un rol escolar a `hasRole(ADMIN)` ni un filtro visual |
| Sesión central T02 | `B/security/AccountSessionService.java:36–88`, `127–132`; `B/entity/UserAccount.java:44–70` | Valida sesión persistida, cuenta, versión, UUID y expiración; autoridades de cuenta actual. Debe evolucionar a roles/contexto por membresía sin confiar en claims antiguos |
| STOMP actual T03 | `B/security/WhiteboardRealtimeAccess.java:23–27`; `B/security/WhiteboardConnections.java:47`, `87`, `185`; `B/security/WhiteboardStompAuthChannelInterceptor.java` | Verifica docente dueño o alumno del grupo unido, además de sesión. Incluye revalidación entrante, saliente y vigilancia de sockets pasivos; falta ámbito institucional |
| Consultas administrativas y métricas | `B/repository/AdminQueryRepository.java`; `B/repository/SupervisionQueryRepository.java:45–74`; `B/repository/StudentUsageQueryRepository.java:39–65`; `src/main/resources/security/student-usage.sql` | Paginación/agregados T07-B están presentes, pero ámbitos globales. Un detalle por `userId` puede sumar actividad de toda la cuenta; hay que segmentar hechos por institución, no por membresía actual del actor |
| Cuotas ligadas al autor | `src/main/resources/security/t07b-storage.sql:32–60`, `72–97` | Materiales/pizarras se cargan a la cuenta autora; guards impiden transferir padres y autoría. Un reemplazo no debe reescribir esos campos para cambiar consumo |
| Auditoría y retención | `B/service/AuditLogWriter.java:16–32`; `B/entity/SystemLog.java:48–53`; `B/entity/UsageEvent.java:54–69`; `B/service/TechnicalRetentionService.java:13–40` | SUCCESS atómico T08, rechazos separados/acotados. Logs/uso sin institución; retención técnica global, apagada por defecto, sin scheduler. No debe atribuirse retrospectivamente por rol/membresía actual |
| Contrato Angular | [F/shared/models/auth.models.ts:8–37](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/blob/ed068f7bc268d0b2cfa3904b1b2a152414a8883c/src/app/shared/models/auth.models.ts#L8); [F/shared/models/student.models.ts:7–38](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/blob/ed068f7bc268d0b2cfa3904b1b2a152414a8883c/src/app/shared/models/student.models.ts#L7); [F/shared/models/evaluation.models.ts:72–79](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/blob/ed068f7bc268d0b2cfa3904b1b2a152414a8883c/src/app/shared/models/evaluation.models.ts#L72) | Un rol, alumno con teacherId/grupo, asignaciones por grado/sección; no contexto activo ni matrícula |
| Navegación y transporte Angular | [F/core/guards/role.guard.ts:10–20](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/blob/ed068f7bc268d0b2cfa3904b1b2a152414a8883c/src/app/core/guards/role.guard.ts#L10); [F/core/interceptors/auth.interceptor.ts:13–39](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/blob/ed068f7bc268d0b2cfa3904b1b2a152414a8883c/src/app/core/interceptors/auth.interceptor.ts#L13); [F/core/services/auth.service.ts:153–170](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/blob/ed068f7bc268d0b2cfa3904b1b2a152414a8883c/src/app/core/services/auth.service.ts#L153); [F/core/services/whiteboard-realtime-connection.ts:55–66](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/blob/ed068f7bc268d0b2cfa3904b1b2a152414a8883c/src/app/core/services/whiteboard-realtime-connection.ts#L55) | SessionStorage y `/auth/me`; interceptor conserva sesión ante errores de negocio y limita Bearer a API. STOMP relee token al reconectar. Guards orientan UI; servidor sigue siendo autoridad |

No se conoce cuántos duplicados, cuentas multirol, colegios, años o asignaciones huérfanas existen en datos reales. T10 reprodujo duplicados FILE con fixtures y define `409 MATERIAL_STATE_CONFLICT` conservador; no demuestra su existencia en el colegio. La palabra «institucional» en validadores previos no crea un tenant.

## 2. Modelo propuesto

### 2.1 Identidad y principios

1. **Una cuenta global estable por persona verificada**, independiente de matrículas, docentes y códigos escolares. Conservar `UserAccount.id`, hash, username y relaciones existentes; no recrear cuentas por migración. No exigir DNI ni correo a alumnos ni usar nombres como prueba de identidad.
2. **Una membresía por cuenta e institución**, con roles y vigencias propios. La cuenta puede pertenecer simultáneamente a varias instituciones y clases. Un rol en A no concede nada en B. Roles combinados no implican consultar todos los recursos: cada acción conserva límites de clase y separación de funciones.
3. **Clase estable por institución y período académico**, con área/curso y código local. Grado/sección son atributos, no claves de acceso. Otra clase con el mismo grupo en distinto colegio, período o curso es independiente.
4. **Responsabilidad y participación como relaciones temporales**, sin editar identidades ni autoría. Bajas no borran cuentas, notas, archivos ni eventos.
5. **Integridad en DB y autorización en servidor**. Una FK simple a una cuenta global no prueba pertenencia; referencias académicas deben demostrar misma institución y, cuando corresponde, misma clase.

PK recomendadas: `bigint` técnicas globalmente únicas en la instalación, compatibles con IDs actuales; no usar nombres/códigos ni presumir que IDs difíciles de adivinar autorizan acceso. Para entidades nuevas, `id` independiente de su clave de negocio. No se obliga a convertir los IDs existentes a UUID. Fechas nuevas como instantes UTC y ventanas `[desde,hasta)`; institución registra zona horaria (inicialmente America/Lima). Las fechas legacy locales se conservan; conversión solo con zona/procedencia demostrada, sin reinterpretar todos los timestamps silenciosamente.

### 2.2 Entidades, claves, restricciones y estados

| Entidad propuesta | Datos y claves | Restricciones / estados |
| --- | --- | --- |
| `Institution` | id, código de plataforma único, nombre, zona horaria, versión de acceso, fechas. Identificador oficial opcional, con tipo/fuente de verificación | `DRAFT → ACTIVE ↔ SUSPENDED → ARCHIVED`; archivada no se reactiva por alta rutinaria. El nombre o un código oficial aislado no identifican inequívocamente una institución. Confirmar si institución representa colegio/sede (§7) |
| `UserAccount` / identidad | id existente, username global, correo opcional, hash y versión T02, estado global, contraseña temporal; datos personales básicos separados de datos académicos | Mantener active/temporaryPassword actuales y bloqueo global. Username no cambia con código escolar. Correo como login solo si verificado/único bajo política de alias; no tratar correo familiar compartido como identidad de varias personas |
| `AccountLoginIdentifier` | id, accountId, tipo USERNAME/EMAIL, valor y valor de comparación, verificación/estado; registro común de aliases | Valor de comparación único entre tipos de acceso para evitar username de A = email de B. Preservar aliases legacy y comprobar colisiones antes de imponer normalización. `ACTIVE/REVOKED`; identificador académico no entra automáticamente aquí |
| `InstitutionMembership` | id, institutionId, accountId, versión de acceso, desde/hasta, estado; código académico opcional y datos de presentación propios del colegio | Única `(institutionId,accountId)`. `PENDING → ACTIVE ↔ SUSPENDED → ENDED`; readmisión auditada reutiliza membresía y abre episodio de vigencia. Código académico de alumno único por institución incluso inactivo; registro de aliases/reservas evita reciclar códigos históricos |
| `MembershipRoleGrant` | id, membershipId, institutionId, rol, desde/hasta, otorgante, revocante y motivo | Solo `ADMIN_INSTITUCION`, `DOCENTE`, `ESTUDIANTE`; no `ADMINISTRADOR`. `ACTIVE/REVOKED` más intervalos; evitar grants solapados del mismo rol. Cambios incrementan versión de membresía. Multirol permitido estructuralmente; prohibida autocalificación de un intento |
| `PlatformRoleGrant` | accountId, rol `ADMINISTRADOR`, vigencia y evento de concesión/revocación | Autoridad exclusivamente de plataforma, independiente de membresías. Importar solo el privilegio legacy confirmado; nunca promover docentes ni futuros administradores escolares por tener actividad global |
| `AcademicPeriod` | id, institutionId, código local, fechas y estado | Única `(institutionId,code)`, desde < hasta. `PLANNED/OPEN/CLOSED/ARCHIVED`; período cerrado no borra clases ni evidencias |
| `Class` | id, institutionId, periodId, código local, nombre, área/curso, grado, sección; sede/turno opcionales | Única `(institutionId,periodId,localCode)`; FK período/institución consistente. `PLANNED → ACTIVE → CLOSED → ARCHIVED`; código 3A puede repetirse en otro período/colegio. Grado 1–5/sección A–Z iniciales por compatibilidad; ampliación posterior explícita |
| `TeachingAssignment` | id, institutionId, classId, teacherMembershipId, función RESPONSABLE/CO_DOCENTE, desde/hasta, estado y autor del cambio | Membresía de misma institución y grant DOCENTE vigente. `PLANNED/ACTIVE/ENDED/CANCELLED`; intervalos válidos y un RESPONSABLE vigente por clase, sin solapamiento; una clase activa puede quedar sin responsable durante una baja de emergencia, con gestión solo del administrador escolar hasta sustitución |
| `Enrollment` | id, institutionId, classId, studentMembershipId, número de episodio, desde/hasta, estado, motivo y actor | Grant ESTUDIANTE válido para activar; misma institución. Única `(classId,studentMembershipId,episodeNumber)`; sin episodios efectivos solapados por pareja. `PENDING/ACTIVE/SUSPENDED/COMPLETED/WITHDRAWN/TRANSFERRED/CANCELLED`; transiciones auditadas. Varias clases activas permitidas; salida/entrada conserva episodios, nunca mueve un intento antiguo |
| Recursos (`ConceptContent`, `Evaluation`) y `ResourceRevision` | IDs existentes, institutionId, autorAccountId/autorMembershipId demostrado, estado existente, revisión publicada; permisos explícitos de gestión de biblioteca | Autor y tenant inmutables. Borrador gestionado por autor con membresía vigente o responsable de biblioteca designado; publicar congela revisión/preguntas/materiales. Nueva revisión no altera intentos anteriores. No crear una tabla genérica que elimine entidades académicas existentes |
| `ResourceManagerGrant` | institutionId, recurso, membershipId, facultad EDIT_DRAFT/PUBLISH/ASSIGN y vigencia | Delegación explícita dentro de institución; administrador escolar asigna gestores, no obtiene calificación por esa facultad. Docente de una clase no edita la plantilla compartida por otras clases solo por recibirla |
| Entregas a clase (`ConceptAssignment`, `EvaluationAssignment`) | IDs conservados, institutionId, classId, resourceRevisionId, actor de asignación, estado/fechas T05 | Misma institución de recurso y clase; una entrega lógica por revisión/clase, reactivación auditada y cierre terminal de oportunidad. Puede haber muchas clases por recurso. Separar reapertura de una nueva entrega, sin reabrir corrección ya expuesta |
| `DeliveryRecipient` | institutionId, deliveryId, enrollmentId, studentAccountId, incorporación/retirada/ventana y motivo | Unique `(deliveryId,studentAccountId)`; la matrícula debe pertenecer a esa clase. Registra padrón al asignar y posteriores altas/bajas explícitas; el mismo alumno no duplica cupos por readmisión. No habilita acceso sin comprobar estado/ventana actual |
| Intentos, respuestas, ajustes y eventos | IDs actuales; institutionId, classId, enrollmentId/recipientId, studentAccountId, entrega/revisión y actores históricos | Contexto fijado al iniciar; sin cambio de institución/clase/alumno. Respuestas por intento/pregunta, número de intento por entrega/cuenta, un intento activo por pareja. Reintentos de matrícula no reinician límite. Nota cerrada y reglas de ocultación T05 conservadas; ajustes por eventos auditados, no sobrescritura silenciosa |
| Pizarra, participantes, propiedad de objetos y captura | IDs actuales; institutionId/classId, autorAccountId, episodio de matrícula al join, accountId participante, revisiones y captura existente | Un participante por `(boardId,accountId)`, evitando duplicación por readmisión. Autor de objeto inmutable, tombstones T03; cerrado terminal. Actor de cierre independiente del creador. Matrícula/participación histórica no concede dibujo actual |
| Eventos de uso, auditoría y conciliación | Ámbito PLATFORM/INSTITUTION/UNRESOLVED, institutionId comprobado, actorAccountId, membershipId/roles efectivos y recurso/fecha/correlación | Institucional exige institutionId; PLATFORM no lleva tenant académico. UNRESOLVED solo legacy en cuarentena lógica. Históricos no se reasignan según membresía actual. Auditoría append-only: añadir resolución en registro separado, no actualizar evento legacy |

Los perfiles `TeacherProfile` y `StudentProfile` existentes pueden mantenerse con sus IDs como puentes legacy y atributos de identidad; no crear otra cuenta por cada perfil. En el modelo final no conceden permisos ni son el padrón actual. Campos teacher/grade/section/studentCode legacy se conservan como procedencia al corte y dejan de ser fuente de autorización después de este. Antes del corte, cada escritura compatible actualiza su mapa o marca su resolución pendiente; no mantener un backfill que ignore cambios posteriores. Una cuenta puede tener facetas docente/alumno si sus grants lo justifican; no inferir ambas de un solo rol anterior.

Para vigencias de membresías, roles y matrículas, guardar episodios/eventos de suspensión y reactivación: el estado actual no puede sobrescribir la cronología. No usar borrado en cascada sobre clases, membresías, cuentas o recursos con referencias históricas; `RESTRICT` y cierre lógico. Los catálogos químicos generales siguen siendo de plataforma.

### 2.3 Integridad entre instituciones

Cada raíz académica lleva `institutionId NOT NULL` una vez conciliada; hijos pueden heredarlo por padre, pero en enlaces entre agregados se recomienda persistirlo y comprobarlo mediante FKs compuestas. Por ejemplo, una matrícula referencia `(classId,institutionId)` y `(membershipId,institutionId)`, cuyos padres tienen UNIQUE `(id,institutionId)`. Una entrega enlaza recurso/revisión y clase con la misma institución; un intento enlaza entrega, destinatario y matrícula de la misma clase. Los actores globales pueden actuar sin membresía local solo en acciones de plataforma explícitas y auditadas, no mediante una FK falsa a una membresía.

UNIQUE/FK/CHECK cubren estructura; grants vigentes y ausencia de solapamiento requieren exclusión temporal o constraint trigger, además de validación del servicio bajo locks. T11-B debe elegir y probar el mecanismo PostgreSQL sin cambiar la semántica. Los estados `ACTIVE` deben comprobar tiempo de servidor; no basta una bandera. Índices previstos comienzan por institución y filtro frecuente: membresía `(institutionId,accountId)`, matrículas/asignaciones `(institutionId,classId,state)`, entregas `(institutionId,classId)`, logs/uso `(institutionId,time,id)`. Su rendimiento sigue sujeto a SEC-13.

### 2.4 Diagrama y tabla de relaciones

```mermaid
erDiagram
    USER_ACCOUNT ||--o{ PLATFORM_ROLE_GRANT : privilegio_global
    USER_ACCOUNT ||--o{ LOGIN_IDENTIFIER : acceso
    USER_ACCOUNT ||--o{ MEMBERSHIP : pertenece
    INSTITUTION ||--o{ MEMBERSHIP : admite
    MEMBERSHIP ||--o{ ROLE_GRANT : autoriza
    INSTITUTION ||--o{ ACADEMIC_PERIOD : organiza
    ACADEMIC_PERIOD ||--o{ CLASS : contiene
    CLASS ||--o{ TEACHING_ASSIGNMENT : responsable
    MEMBERSHIP ||--o{ TEACHING_ASSIGNMENT : docente
    CLASS ||--o{ ENROLLMENT : matricula
    MEMBERSHIP ||--o{ ENROLLMENT : alumno
    INSTITUTION ||--o{ RESOURCE : posee
    USER_ACCOUNT ||--o{ RESOURCE : autor_historico
    MEMBERSHIP ||--o{ RESOURCE_MANAGER_GRANT : delegacion
    RESOURCE ||--o{ RESOURCE_MANAGER_GRANT : gestion
    RESOURCE ||--o{ RESOURCE_REVISION : versiona
    RESOURCE_REVISION ||--o{ DELIVERY : asigna
    CLASS ||--o{ DELIVERY : recibe
    DELIVERY ||--o{ DELIVERY_RECIPIENT : padron
    ENROLLMENT ||--o{ DELIVERY_RECIPIENT : habilita
    DELIVERY_RECIPIENT ||--o{ ATTEMPT : evidencia
    ATTEMPT ||--o{ ANSWER : conserva
    ATTEMPT ||--o{ ADJUSTMENT : trazabilidad
    CLASS ||--o{ WHITEBOARD : sesion
    WHITEBOARD ||--o{ PARTICIPANT : asistencia
    ENROLLMENT ||--o{ PARTICIPANT : contexto_original
    WHITEBOARD ||--o{ OBJECT_OWNERSHIP : autoria
```

`RESOURCE`, `DELIVERY` y `ROLE_GRANT` resumen familias del cuadro anterior; no son una orden de reemplazar tablas actuales por un diseño polimórfico.

| Relación | Cardinalidad / regla | Efecto en continuidad |
| --- | --- | --- |
| Cuenta ↔ institución | N:M vía membresía, una por pareja | Traslado no cambia accountId; pertenencia simultánea explícita |
| Institución → período → clase | 1:N y 1:N, institución consistente | Mismo grado/sección en años distintos no mezcla alumnos |
| Membresía docente ↔ clase | N:M vía asignación temporal | Reemplazo termina relación y abre otra; no cambia autor |
| Membresía alumno ↔ clase | N:M vía episodios de matrícula | Dos clases y readmisión sin duplicar identidad |
| Institución → recurso → revisión → entrega | 1:N, 1:N, 1:N; entrega recibe una clase | Compartir dentro de un colegio; no compartir datos escolares entre colegios |
| Entrega ↔ matrícula | N:M vía destinatarios, cuenta única por entrega | Padrón auditable y cupos estables ante traslados |
| Destinatario → intento → respuestas/ajustes/eventos | 1:N; hijo conserva contexto del padre | Cuenta y evidencia siguen enlazadas después de baja |
| Clase → pizarra → participantes/objetos/captura | 1:N; participantes por cuenta | Historial no depende de sección actual ni docente creador |
| Cuenta/membresía → uso/auditoría | 1:N con ámbito fijado al hecho | Cambios actuales no reasignan actividad pasada |

### 2.5 Código interno, login e incorporación futura

Ejemplo exclusivamente ficticio: Colegio DEMO-A y Colegio DEMO-B tienen alumno académico `EST0001`, grado `3`, sección `A`, clase local `QUI-3A` y período `2026`. Son códigos coincidentes válidos porque sus claves incluyen institutionId. Sus alumnos distintos tienen cuentas/login globales distintos. Una misma cuenta que pertenece a ambos colegios puede tener códigos internos distintos y conserva un solo login.

Conservar el username legacy que hoy coincide con `EST0001`; después del corte queda como alias global de esa cuenta y ya no sigue las ediciones del código académico. Nuevas cuentas usarán identificador global generado/asignado independiente, sin correo obligatorio. No introducir acceso ambiguo por código escolar desnudo. No retirar la unicidad legacy de `student_profiles.student_code` mientras existan escritores que crean o renombran `username = studentCode`; los nuevos códigos institucionales viven en membresía y los flujos nuevos permanecen apagados hasta T12/T14. Si después se pide login con colegio+código, será otro contrato de acceso con resolución explícita y protección T04, no un atajo de matrícula.

Reservar para T14 dos conceptos diferentes: invitación de registro/membresía con rol, emisor, institución, hash, caducidad y uso único; y código de clase rotativo con ámbito, plazo/cupo y aprobación de matrícula. Ninguno es password, identificador global de login ni permiso directo de acceso a recursos. Al aceptar, una cuenta existente se autentica y conserva identidad; la cuenta nueva se crea solo por un flujo autorizado. No deduplicar personas por nombre, código, correo sin verificar o dispositivo. Casos con dos cuentas de una persona requieren procedimiento de vinculación revisado, separado de esta migración.

## 3. Propiedad y autorización

### 3.1 Propiedad lógica y responsabilidad

Esta sección define pertenencia técnica y facultades, no titularidad legal.

| Ámbito | Datos | Responsable y límites |
| --- | --- | --- |
| Plataforma | Credenciales, login global, sesiones, grants globales, catálogo químico compartido, configuración/estado técnico, límites de autenticación | ChemicalLab; administrador global gestiona plataforma. Administrador escolar no desactiva cuenta global, cambia alias global, secretos o privilegio global |
| Institución | Membresías, códigos académicos, períodos, biblioteca/contenidos/evaluaciones, evidencias y métricas institucionales | Administrador institucional gestiona solo su institución; asigna responsabilidades y suspende membresías propias |
| Clase | Matrículas, asignaciones docentes, entregas, destinatarios, pizarras y contexto de intentos | Responsable vigente y co-docentes según facultades; gestión escolar puede reemplazarlos sin apropiarse de autoría |
| Usuario | Identidad de acceso y datos personales básicos; respuestas/participación propia dentro de su contexto institucional | El usuario consulta su cuenta y evidencia propia permitida. Ser autor/alumno no da acceso permanente a datos ajenos ni permite editar una evidencia cerrada |

`createdBy`/`gradeClosedBy`/autor de objeto son hechos históricos inmutables. `TeachingAssignment` y delegaciones son facultades actuales. Un autor saliente sigue citado pero pierde gestión y consulta escolar al cesar su asignación/membresía, salvo una concesión histórica explícita. El reemplazo recibe la clase y su información necesaria; no modifica antiguos autores, accountId del alumno ni institutionId del recurso. Un recurso asignado a varias clases mantiene su revisión compartida; docente de una clase revisa solo sus destinatarios/intentos y no las notas de las otras.

### 3.2 Matriz propuesta de facultades

| Acción | ADMINISTRADOR global | ADMIN_INSTITUCION | DOCENTE | ESTUDIANTE |
| --- | --- | --- | --- | --- |
| Crear/suspender institución y nombrar administrador escolar | Sí, en superficie de plataforma auditada | No crea otras instituciones ni se otorga rol global | No | No |
| Consultar estado técnico y métricas de plataforma | Sí; agregados mínimos por defecto | Solo estado/consumo de su servicio institucional, sin secretos ni logs de otros colegios | No | No |
| Gestionar miembros, roles, código académico y períodos/clases | Soporte explícito por institución y auditado | Solo propia institución; sin modificar identidad o cuenta global ajena a su ámbito | Consulta padrón de sus clases; propone correcciones/altas posteriores según T14 | Consulta matrícula propia |
| Suspender/terminar membresía | Soporte explícito | Solo membresía local, preservando otras instituciones y cuenta | No suspende globalmente; retirada de clase según permiso delegado | No retira a otros |
| Contraseña, aliases y cierre global de sesiones | Cuenta propia o recuperación privilegiada auditada | No obtiene/reseteará credencial global de miembro multiinstitucional; inicia recuperación verificada posterior | No recibe password global de alumno; inicia ayuda autorizada posterior | Cuenta propia bajo T02/T04 |
| Asignar/reemplazar docente o trasladar alumno | Soporte explícito y limitado | Sí, dentro de su institución; traslados externos coordinados T13 | No se adjudica otra clase; puede gestionar matrícula solo si se delega | No autoasigna pertenencia |
| Crear/editar/publicar recurso y asignarlo | No por privilegio global rutinario; soporte excepcional explícito | Biblioteca y delegación; lectura de supervisión según proyección, sin asumir rol docente | Borrador propio/delegado y asignación a clases donde tiene facultad; revisión publicada inmutable | Solo contenido entregado/publicado y autorizado |
| Consultar/calificar intentos | Supervisión excepcional por institución, auditada; no califica por rol global | Supervisión escolar acotada; no claves de corrección ocultas ni calificación directa | Solo clase asignada, sin autocalificación; nota cerrada no se reabre rutinariamente | Propios; corrección/feedback según T05 |
| Pizarra en vivo/control/dibujo | Sin acceso realtime por ser global | Sin acceso realtime por ser administrador | Clase asignada; controles docentes y autoría T03 | Matrícula vigente + join + permisos efectivos, solo objetos propios |
| Descargas/capturas/historial | Soporte específico con alcance y auditoría; no URL pública | Supervisión de evidencia escolar autorizada | Evidencia necesaria de clases gestionadas actualmente | Propia evidencia; captura compartida solo si cumple política histórica siguiente |
| Logs, métricas individuales y exportaciones | Ámbito plataforma o institución explícita, con justificación para datos personales | Solo hechos de su colegio; sin actividad externa del mismo accountId | Datos pedagógicos necesarios de clase, no auditoría global | Resultados propios; sin padrones ajenos |

**Recomendación funcional:** no trasladar los resets escolares actuales sin cambios al modelo de identidad global. Un reset por el colegio A puede bloquear acceso a B. T12 debe deshabilitar/restringir esas rutas legacy para ámbito multiinstitucional y separar recuperación central verificada, cuya interfaz se diseña en T14. El administrador escolar corrige datos académicos locales; cambios de identidad global requieren control del titular o recuperación auditada. Protección del último administrador global y último ADMIN_INSTITUCION activo requiere bloqueo/validación, con baja de emergencia y recuperación global explícitas.

Históricos: recomendar que un alumno activo conserve lectura de sus intentos/resultados/evidencias personales del período en que tuvo matrícula, aun terminado el episodio. No ve intentos de compañeros ni toda captura de una sección a la que ingresó después. Para capturas compartidas exigir participación documentada y membresía activa con permiso histórico; si se terminó la membresía institucional, denegar el historial compartido ordinario. Lectura individual tras salida del colegio se habilitará en T13 mediante acceso propio separado por institución de origen, sin permitir acciones actuales; queda decisión funcional F03. Una membresía SUSPENDED pierde acceso académico hasta resolver suspensión. Los históricos legacy sin prueba de asistencia quedan en revisión; no se fabrica participación para conservar la visibilidad antigua por grado/sección.

### 3.3 Institución activa y comprobación en servidor

Se propone **contexto explícito por petición/conexión**, evitando un tenant mutable global en la sesión que produzca carreras entre pestañas. Un selector futuro puede recordar la preferencia, pero el servidor valida cada uso. Contratos siguientes son diseño ilustrativo, no endpoints implementados:

1. Autenticar cuenta/sesión por T02. `/auth/me` evoluciona a identidad global, grants de plataforma y membresías accesibles con roles/estado; modo temporal sigue limitado a la lista exacta actual, sin datos escolares nuevos.
2. Petición institucional bajo `/api/institutions/{institutionId}/...`. El ID selecciona el contexto solicitado; no lo autoriza. El servidor carga institución y membresía de **accountId del principal**, confirma estado/vigencia/roles y crea un contexto interno confiable. Ignora actor, membershipId o roles declarados por cliente como prueba de acceso.
3. Recurso por ID se busca conjuntamente con institución autorizada; después se comprueban clase, asignación/matrícula, entrega, estado y operación. POST también verifica todos los padres. Un body con otra institución se rechaza; el contexto del servidor determina la propiedad nueva.
4. Si no hay contexto: solo identidad/catálogos/plataforma enumerados; no elegir «primer colegio» ni recurrir a consulta global. Legacy solo podrá usar un contexto único previamente conciliado y aprobado, con feature multiinstitucional apagada. Ambigüedad devuelve rechazo, nunca acceso global.
5. ADMINISTRADOR usa superficie de plataforma explícita, sin membresía inventada ni bypass general de servicios académicos. Para soporte escolar: institución objetivo, acción autorizada, motivo, plazo/correlación y auditoría obligatoria; sin impersonación silenciosa ni calificación arbitraria.
6. Sesión inválida: 401. Sesión válida sin membresía/contexto/facultad: 403 genérico; ID de recurso fuera del ámbito: 404 sin confirmar existencia. Fallo de validación/DB: rechazar, sin fallback global. Contratos legacy que hoy retornan 400 necesitan evolución coordinada; no se cambian aquí.

Rol activo se deriva de grants válidos de esa membresía para la acción, no de `UserAccount.role` legacy ni de sessionStorage. Dos pestañas A/B pueden coexistir con la misma cuenta; respuestas, cachés y solicitudes pendientes se etiquetan por institución y generación de contexto para impedir que una respuesta tardía de A aparezca en B. Angular debe limpiar datos escolares, conexiones, blobs y examen al cambiar institución; si hay intento activo, el servidor controla su ventana y no permite usar el cambio para eludir T05. El selector y los paneles quedan para tareas de interfaz posteriores, no T11-B.

### 3.4 Sesiones, revocación y concurrencia

Mantener UUID de sesión, expiración, cuenta activa y `credentialsVersion` T02; no guardar JWT en nuevas tablas. Cambio/reset de credenciales o bloqueo **global** revoca todas las sesiones como hoy. Cambio de membresía/grant incrementa su versión; se consulta estado vigente en cada petición y frame, conservando sesiones/otras membresías cuando el cambio es solo local. Conexiones de ese contexto se bloquean/cierran; reactivar membresía no resucita sockets o permisos revocados. Cambiar asignación/matrícula invalida acceso a la clase aunque la cuenta siga activa; revalidar también antes de mutaciones sensibles bajo locks.

T12 debe separar principal de cuenta y contexto institucional, ya que hoy el principal lleva un único rol global. No confiar en roles/tenant de JWT como fuente actual, ni cachear grants indefinidamente. Puede incluirse una versión/contexto firmado como comprobación adicional, pero siempre se contrasta DB. Durante el corte desde rol legacy, definir versión de contrato y renovación/relogin controlado de sesiones sin cambiar hashes ni cuentas. Una sesión vieja jamás puede reactivar permisos de docente por el rol legacy.

Protocolo de escritura propuesto: institución/guard de acceso y cuenta cuando cambia seguridad → membresía/grant → clase → matrícula/asignación → recurso/evaluación → intento/material/pizarra según T05/T10 → contadores. Orden determinista por ID en operaciones con varios padres; relectura tras lock, restricción DB y timeout/conflicto controlado. T11-B/T12 deben medirlo y validar ausencia de deadlocks con dos procesos antes de adoptarlo; no introducir locks globales para todo trabajo docente. Revocación solo cancela tráfico en los puntos de comprobación, no promete retirar bytes ya entregados o cancelar operaciones ya confirmadas.

### 3.5 Impacto en todas las superficies

| Superficie | Cambio requerido y garantía heredada |
| --- | --- |
| REST y repositorios | Inventario desde controladores actuales, no reutilizar el recuento antiguo de 122 como completo. Añadir contexto a CRUD, reads por ID, listas, count/summary, joins/subconsultas, supervisión, trazabilidad y errores. No filtrar en memoria después de paginar; totales y páginas deben compartir ámbito. Rechazo por defecto a rutas no clasificadas |
| Frontend/contratos | Separar accountId, membershipId, classId, enrollmentId y perfiles legacy. UserRole deja de ser rol único; nuevas respuestas incluyen contexto/roles efectivos. Sustituir asignación por grupo por classId; mantener grado/sección como etiquetas. Guards y paneles futuros reflejan permisos del servidor |
| STOMP/SockJS | CONNECT valida T02 más contexto institucional solicitado; socket ligado a contexto fijo, cambiar colegio exige conexión nueva. SEND/SUBSCRIBE, entrega saliente y vigilancia pasiva comprueban membresía, clase, matrícula/asignación y participación. Mantener lista exacta de destinos/comandos, veto de publicación directa al broker, cola privada y límites T07-A. Puede conservarse destino por boardId si servidor resuelve tenant; prefijo con institución tampoco autoriza por sí solo |
| Broker y jobs | Broker simple sigue sin distribuir eventos entre instancias. No presentar aislamiento como solución de distribución; resolver en etapa operativa antes de escalar colaboración. Jobs/exportaciones/cachés llevan contexto explícito y revalidación al ejecutar, nunca ThreadLocal residual o usuario técnico con lectura global implícita |
| Archivos/materiales/capturas | Autorizar institución y recurso antes de acceder/validar bytes, comprobar pareja de IDs y revisiones. Mantener parsers acotados y entrega autenticada T06; no URLs públicas, tokens en URL ni caché compartida de datos personales. Cache key/blob/exportación incluye institución/revisión; revocar URLs de Blob al cambiar contexto. Conservación de bytes existentes, incluso inválidos, sin entregarlos fuera de política |
| Evaluación/resultados | Entrega, destinatario, matrícula y revisión inmutables por intento; no filtrar por grupo actual. Conservar ventanas `[startAt,dueAt)`, duración, máximo, locks, pregunta ordenada, cierres y ocultación T05. Institución B o traslado no completan el cierre de A ni revelan respuestas |
| Pizarra | Clase/autor histórico separados del responsable actual. Sustitución no cambia ownerUserId de objetos ni borra tombstones; acceso live para responsable vigente y matrícula+join. Snapshots/historial no dependen de sección actual |
| Métricas | Fijar institución/classId/membershipId al hecho autorizado; actor/fecha servidor. Cliente no elige pertenencia ni demuestra visitas/notas. Actividad global sin contexto es de plataforma; legacy ambiguo no se duplica en todas las membresías. Informes individuales segmentan eventos/intentos, evitando mostrar actividad de B al admin A |
| Auditoría | SUCCESS de cambios institucionales dentro de transacción T08: actor, ámbito, objetivo, facultad, resultado y correlación. Soporte global y exportaciones quedan registrados. Rechazos acotados no almacenan cuerpos, passwords/códigos crudos, respuestas o datos ajenos; no convierten log en catálogo de existencia. Resolver legacy en ledger separado por append-only |
| Cuotas | Añadir presupuesto institucional y, cuando corresponda, clase/actor; mantener límites globales/origen/cuenta T04 y T07-A. Consumo de almacenamiento institucional sigue institutionId inmutable, no docente responsable. T11-B prepara versión nueva de contadores/triggers T07-B con doble conteo verificado, sin desactivar guards ni retirar cuotas legacy antes de corte. Cambio docente no mueve bytes ni libera consumo; intentos fallidos no dejan cuota o SUCCESS huérfanos |
| Retención/backups/exportación | Mantener eliminación técnica apagada. Ninguna migración purga logs/notas/respuestas/materiales. Jobs futuros y restauración por tenant requieren límites e integridad de referencias; restaurar A no puede sobrescribir cuenta global compartida ni borrar B. Plazos y gobierno siguen en etapas posteriores |

## 4. Transición de datos actuales

### 4.1 Qué puede inferirse y qué debe reconciliarse

No hay un campo actual que demuestre el colegio. Un docente creador, username, patrón `EST`, email, grado/sección o configuración «colegio» no prueban institutionId. Se necesita identificación del origen con responsables autorizados y mapa aprobado fuera del repositorio público. No se crea institución «legacy», «por defecto» o inventada para absorber registros reales. Las instituciones DEMO de aceptación son solo fixtures.

Registro propuesto `MigrationResolution`: lote/versión/origen, tipo y PK legacy, destino(s) propuestos, institución/período/clase, estado `UNRESOLVED/CANDIDATE/APPROVED/APPLIED/CONFLICT`, evidencia de origen, aprobador/fecha y checksums. Unique por fuente/tabla/PK/versión; relación 1:N cuando un recurso tiene entregas a varias clases. Reglas deterministas e idempotentes, sin inferencia por nombres. Los mapas reales restringidos no entran a commits, PR ni logs generales. Distinguir estado técnico de conciliación del estado funcional de una institución.

| Origen legacy | Destino / mapa verificable | Ambigüedad que bloquea aplicación automática |
| --- | --- | --- |
| `user_accounts` | Mismo id/hash/username/estado/credentialsVersion; registro de identificadores y grant global o membresías aprobadas | Rol global no indica colegio. Email/username que colisionan bajo comparación nueva, perfiles ausentes/múltiples, cuentas potencialmente repetidas: conservar y reportar, no fusionar |
| `teacher_profiles` | Mismo perfil/id y accountId; membresías DOCENTE y asignaciones a clases aprobadas | Un docente puede trabajar en varias instituciones/períodos; autor de recurso no demuestra asignación actual |
| `student_profiles` | Mismo perfil/accountId, código legacy preservado; código académico en membresía y matrícula(s) aprobada(s) | Docente/grupo actual no prueban períodos anteriores ni institución; no trasladar todas las notas a una clase nueva por el perfil actual |
| Grupo legacy | Candidato `(origen,institución aprobada,período aprobado,grado,sección,curso/turno)` → classId | Mismo 3/A puede ser otra clase/curso/año; tampoco separar automáticamente clases solo por docente si hay reemplazo/co-docencia |
| `concept_contents`, colecciones, `concept_materials` | IDs/textos/bytes/metadatos actuales conservados; institución aprobada y autor preservado; revisión inicial con estado disponible | Autor puede pertenecer a varios colegios; múltiples FILE activos, recursos sin clase/asignación y pertenencia contradictoria. Bytes reemplazados en el pasado que ya no existen no son recuperables por esta migración |
| `evaluations`, preguntas/opciones, assignments | IDs/estados/ventanas/preguntas conservados; institución/revisión y entregas a clase aprobadas | Assignment sin período; varios candidatos de clase; recurso asignado a poblaciones de origen distinto. No duplicar revisión con notas de alumnos de otra institución |
| `evaluation_attempts`, answers, adjustments, events | IDs y padre original preservados; institución/clase/matrícula/entrega solo con resolución consistente; autores/cierre/nota sin recalcular | assignmentId null, matrícula histórica no demostrable, número repetido o dos IN_PROGRESS, alumno trasladado, fechas locales inciertas. No inventar enrollmentId retroactivo como asistencia probada |
| `whiteboard_sessions`, participants, ownership, snapshot | IDs/estado/revisión/bytes/cierre/actores intactos; clase/tenant aprobado; participante vinculado a cuenta y matrícula original cuando demostrable | Falta participación para alumnos que hoy ven historial por grupo; objetos sin autor T03 permanecen sin atribución; no reconstruir presencia por prefijo del ID |
| `usage_events` y `system_logs` | Mismos hechos y fechas; clasificación de ámbito mediante resolución separada. Cuenta global permanece actor | Evento sin recurso verificable, actor en varios colegios, login global o metadata declarativa: PLATFORM si consta hecho global, o UNRESOLVED; no repartir/duplicar entre colegios |
| Sesiones/límites/cuotas | Sesiones/versiones preservadas durante preparación; renovación explícita al corte; recalcular contadores de ensayo por institución y comparar con bytes/filas | Cuotas actuales por autor y datos sobre límite: conservar exceso histórico. Límite no autoriza recortar. No recalcular credenciales por cambio de código académico |

Un recurso con pruebas contradictorias de pertenencia queda en `CONFLICT`; no se cambia tenant, elimina ni copia a ambos colegios para «resolver». Si se demuestra que un contenido reusable debe copiarse, será nueva copia institucional sin datos de alumnos, con procedencia y aprobación; los intentos mantienen original. Ninguna copia/fusión automática en T11-B.

### 4.2 Fases propuestas (ejecución futura autorizada)

| Fase | Trabajo de T11-B / dependencias | Puerta de salida |
| --- | --- | --- |
| P0 — Inventario y contrato | Revisar bases futuras, writers, entidades/tablas auxiliares, controles T07-B/T08 y consumidores; aprobar decisiones §7. Preparar mapas ficticios y plan de reconciliación real, sin consultar escuelas en T11-A | Modelo/reglas y responsables revisados; lista de campos legacy y de datos que no pueden inferirse |
| P1 — Ensayo aislado | Solo fixtures aprobadas en entorno desechable futuro. Ensayar backup/restauración y manifiesto de conteos/checksums; diseñar cadena versionada explícita, sin confiar en Hibernate update como plan de migración | Restauración e idempotencia demostradas; estimación de locks/backfill y validaciones de base, no despliegue |
| P2 — Expansión persistente | Tablas nuevas/columnas temporales nullable y ledger; versión de esquema/guardas. No eliminar campos/FK existentes ni promover roles. Recursos no resueltos fuera del modelo activo multiinstitucional | El viejo dominio sigue íntegro; nuevas referencias no conceden permisos; no exposición de nuevos flujos |
| P3 — Conciliación y backfill | Identificar instituciones reales solo por mapa aprobado en ventana posterior; lotes con checkpoint/checksum, sin recrear cuentas. Rellenar tenant/period/class y relaciones demostradas; conservar unresolved sin acceso institucional nuevo | Cada agregado candidato tiene trazabilidad, conteos y consistencia; conflictos visibles, sin decisiones implícitas |
| P4 — Compatibilidad y validación paralela | Lecturas de comparación sin conceder acceso nuevo; escritores únicos/coordinados. Si hay doble escritura, misma transacción y versión, sin dos fuentes autoritativas. Congelar/restringir acciones legacy ambiguas; ensayar contadores y auditoría | Viejo/nuevo divergen cero en ámbitos resueltos; reintentar no duplica; no ampliar visibilidad respecto de política aprobada |
| P5 — Corte del ámbito | **Dependencia T12:** servicios/query/REST/STOMP/archivos/informes y contratos preparados. Detener writers no compatibles; renovar contexto de sesiones; aplicar NOT NULL/FK al dominio resuelto y guardas sin saltarlas | Matriz integral de aislamiento aprobada; legacy global inaccesible a administradores escolares. T11-B por sí sola no llega a apertura |
| P6 — Contracción posterior | Tras T12/T13 y período de recuperación acordado: retirar lectura/escritura legacy redundante solo cuando todos los registros que la requieren estén resueltos o aislados explícitamente | Evidencia intacta; preservación/copia restringida y reversión probada; sin DROP/purga automática por vencimiento |

Los registros sin institución demostrable se mantienen en sus tablas/origen con resolución pendiente y acceso administrativo de plataforma restringido. No deben aparecer en consultas escolares ni heredar el tenant activo de quien los consulta. Durante preparación puede mantenerse únicamente la instalación legacy ya autorizada, con acceso nuevo apagado; después del corte no hay fallback a sus consultas globales. Si un agregado esencial permanece ambiguo, se detiene su adopción y se conserva acceso legacy solo en un entorno segregado aprobado, o se bloquea su publicación; nunca se mezcla con tenants nuevos.

### 4.3 Validaciones, reconciliación y recuperación

Validar por tabla/lote y agregado: mismo número de cuentas y PK; hashes, aliases y versiones intactos salvo cambio explícito de credenciales fuera de migración; perfiles y FKs antiguas conservados; conteos de recursos/materiales/bytes y checksums; preguntas/opciones/orden/respuestas; intento/nota/estado/feedback/ajuste/cierre; participantes/propiedad/tombstones/estado/capturas; auditoría y uso sin reescritura. Contar recursos originales separadamente de nuevas revisiones/relaciones: una entrega desdoblada no debe parecer duplicación de evidencia. Checksums de contenido real se calculan solo en entorno autorizado y se guardan restringidos.

Controles de calidad: referencias huérfanas y cruzadas; códigos escolares duplicados por institución incluyendo inactivos; colisiones de alias global; múltiples perfiles por cuenta; solapamientos temporales; roles incompatibles con matrícula/asignación; períodos inciertos; assignments o intentos sin contexto; duplicados FILE o participante/número de intento; cuota excedida preexistente; notas cerradas o respuestas expuestas; timestamps ambiguos. Reconciliar con responsables mediante decisiones registradas; no afirmar cero duplicados reales sin ejecutar inventario autorizado. Invalidar un candidato de backfill no implica eliminar su fuente.

Recuperación: un fallo de lote revierte solo relaciones/deltas del lote en la transacción, conserva fuente y evidencia del intento fallido, y reanuda desde checkpoint verificado. Interrumpir tras cada fase, repetir lote y simular fallo de auditoría/cuota/constraint en fixtures. Ledger de aplicación y SUCCESS no deben sobrevivir a rollback de negocio como aplicación exitosa. Fallo entre fases obliga a detener writers/feature, comprobar versión y restaurar o continuar hacia adelante; nunca arrancar un backend viejo que ignore las nuevas fronteras con tenants activos.

Antes del corte, reversión de aplicación solo sobre esquema expandido compatible y escritores detenidos. Después de habilitar tenants, **no rollback a binario legacy global**: usar versión compatible con aislamiento o deshabilitar acceso hasta recuperar. Restauración debe incluir datos/ledger/contadores/auditoría y cambios posteriores; no restaurar un backup antiguo destruyendo escrituras nuevas. Ensayar journal/copia de cambios durante ventana, acordar RPO/RTO y compensación hacia adelante. No retirar columnas originales hasta probar recuperación. Migración de hash Argon2id T04 sigue siendo legible; no volver a lector BCrypt-only.

Condiciones de adopción: instituciones confirmadas; cuentas conservadas; mapa revisado; ningún agregado publicable ambiguo; integridad entre raíces; reconciliación sin pérdida; respaldo restaurable y presupuesto de ventana; writers compatibles de todas las instancias; cuotas/auditoría verificadas; aislamiento T12 demostrado. Retención sigue apagada y la resolución del diseño no cierra SEC-13/VER-01.

## 5. Separación de tareas y orden de habilitación

| Tarea | Entrega / alcance | Fuera de esa tarea / puerta |
| --- | --- | --- |
| **T11-A (esta)** | Diseño versionado, diagnóstico, entidades/estados/relaciones, propiedad/autorización, mapa/fases, decisiones y aceptación | No código, DDL, migración ejecutada, flujos, despliegue ni inicio T11-B |
| **T11-B** | Modelo persistente y cadena de transición explícita; ledger, FKs/unicidad/vigencias, puentes legacy, revisión/padrón/contexto histórico y evolución preparada de cuotas; ensayo ficticio reversible/idempotente | No onboarding/paneles/invitaciones. No acceso multiinstitucional externo. Tablas y roles preparados no prueban aislamiento; operación real requiere ventana/backup/mapa autorizados |
| **T12** | Aislamiento efectivo en todos los writers/readers REST, servicios, repositorios SQL/JPA, agregados, STOMP entrante/saliente/pasivo, archivos, métricas/logs, exportación/cachés/jobs y permisos ADMIN_INSTITUCION; principal/contexto y contratos coordinados | Mantener nuevas altas/invitaciones apagadas; no aceptar un «tenant filter» como cobertura completa. Gate bilateral y de cuenta con varias membresías |
| **T13** | Reemplazo docente, co-docencia delegada, bajas/traslados/readmisión, gestión de intentos abiertos, históricos propios/compartidos y pérdida de acceso saliente; recuperar continuidad sin reescribir autores | No transportar notas/datos de otra institución por traslado. Gate de continuidad/revocación/carreras antes de ofertar flujos de sustitución/traslado |
| **T14** | Altas autorizadas, vincular cuenta existente, invitaciones de registro/membresía distintas de códigos de clase; expiración/uso único/cupo/rotación/antiabuso. Recuperación y MFA/reautenticación administrativa según auditoría original | Sin registro público de alumnos hasta decisiones funcionales y gobierno/operación revisados. No usar código como password ni exponer membresías ajenas |

Orden: revisar T11-A → autorizar T11-B → revisar persistencia/ensayo → T12 y gate de aislamiento → T13 y gate de continuidad → T14 y gate de incorporación. Contratos pueden prepararse junto al aislamiento; las altas, paneles e interfaces se entregan en sus tareas posteriores.

**Habilitación:** antes de T12 solo pruebas ficticias y preparación interna; no alojar colegios independientes a través de flujos legacy. Tras T12 aprobado se puede evaluar un piloto cerrado con instituciones provisionadas mediante procedimiento controlado y mapas confirmados, sin ofrecer reemplazos/traslados aún no probados. Los flujos institucionales de T13/T14 se habilitan solo al aprobar sus respectivos gates, sobre aislamiento ya efectivo. La salida a servicio público exige también controles operativos/gobierno de T15–T17 de la auditoría; este documento no declara lista la plataforma para SaaS.

## 6. Alternativas y recomendación concreta

| Decisión | Alternativas | Recomendación y consecuencia |
| --- | --- | --- |
| Identidad | Cuenta por colegio; cuenta global con membresías | Global: preserva acceso/historial y pertenencias simultáneas. Obliga a separar recuperación y baja escolar de bloqueo global; no permite fusionar identidades automáticamente |
| Ubicación del rol | Enum único de cuenta; roles en membresía + privilegio de plataforma | Grants institucionales y global independiente. ADMIN_INSTITUCION jamás es alias de ADMINISTRADOR; mayor trabajo en principal y contratos |
| Persistencia institucional | BD por colegio; schema por colegio; esquema compartido con tenant explícito | Esquema compartido para continuidad del sistema actual, FKs compuestas y autorización central. RLS puede añadir defensa posterior, pero no sustituye T12; operación/backup por tenant exige pruebas. BD separada reservar para necesidad operativa acordada |
| Contexto activo | Tenant mutable de sesión; contexto por petición/conexión | Contexto explícito validado; soporta dos pestañas/colegios. Exige etiquetar respuestas/cachés y fijar contexto del socket |
| Identidad de clase | grado/sección; docente+grupo; clase/período institucional | Clase independiente: evita mezclar años/colegios y permite reemplazo sin recrearla. Código/turno/curso forman clave local, no permiso |
| Gestión de recursos | Reescribir autor al reemplazo; responsabilidad temporal y revisiones | Conservar autor y tenant; reemplazo gestiona entregas de su clase. Una revisión publicada no se altera para todos los alumnos; biblioteca con delegación explícita |
| Códigos | Código escolar como username global; código local separado | Código único por institución, login global independiente, conservar username legacy. Coincidencias entre colegios permitidas y sin enumeración pública |
| Migración incierta | Colegio por defecto; deducción por docente; ledger y conciliación | Ledger con unresolved/conflict. Más trabajo manual y posible bloqueo parcial de publicación; evita falsificar procedencia o ampliar acceso |
| Padrón y límite de intentos | Padrón calculado de perfil actual; destinatarios e intentos ligados a entrega | Padrón auditable con altas/bajas explícitas; máximo por entrega/cuenta no se reinicia por readmisión. Mantener cierre definitivo T05 |
| Historial | Todo grupo actual; solo evidencia con contexto demostrable | Propio por matrícula/entrega, compartido por participación y política. Datos legacy ambiguos quedan restringidos; conservación no implica acceso indiscriminado |

## 7. Decisiones funcionales pendientes de revisión y aceptación

Las recomendaciones anteriores forman una propuesta revisable; ninguna de estas decisiones debe resolverse creando datos reales ficticios. Son puntos para revisión del producto antes de iniciar implementación dependiente, no una petición de permiso para completar este documento.

| ID | Decisión que requiere revisión | Propuesta por defecto / impacto |
| --- | --- | --- |
| F01 | Unidad institucional: colegio, sede o grupo de sedes; identificador oficial y administradores por sede | Una frontera por colegio independiente; sedes como atributo interno salvo que exijan aislamiento propio. No usar nombre/código oficial sin confirmar granularidad |
| F02 | Pertenencia simultánea, multirol y co-docencia | Soportar varias instituciones/clases; grants combinables, RESPONSABLE único y co-docentes con facultades explícitas. Prohibir revisar intento propio. Implica UI contextual y matriz por acción |
| F03 | Acceso del alumno tras egreso/traslado/baja y capturas compartidas | Conservar siempre evidencia; lectura de resultados propios de origen mediante canal separado T13; historial compartido solo participación demostrada y permiso vigente. Suspensión deniega acceso académico; período de consulta/recuperación por acordar, sin fijar retención legal |
| F04 | Facultad administrativa sobre notas/biblioteca/soporte global | ADMIN_INSTITUCION supervisa y delega, no califica; soporte global con motivo/ámbito/auditoría y sin realtime. Correcciones excepcionales de nota exigen procedimiento propio, sin reabrir silenciosamente notas cerradas |
| F05 | Código académico: estabilidad/reutilización y alias/login | Código estable y no reutilizable en el colegio aun con baja; login global separado. Cambios conservan alias académico histórico; normalización de aliases de acceso solo tras revisión de colisiones |
| F06 | Recuperación para menores sin correo y poderes de reset escolar | Ayuda del colegio inicia recuperación central verificada; no entrega credential global ni reset multiinstitucional unilateral. T14 define pruebas/recuperación y MFA administrativa; registro público de alumnos permanece apagado |
| F07 | Clase, períodos, padrón, incorporaciones y traslados con intento abierto | Clase por curso/período; nuevas matrículas no acceden retroactivamente a todo el historial; alta a entrega explícita. Traslado deja intento en origen, se resuelve/finaliza bajo reglas originales sin reset de límite ni copia de nota |
| F08 | Identificación de legacy y responsables de reconciliación | Mapa por origen aprobado; sin asignación por inferencia. Si falta período/asistencia, mantener incertidumbre registrada y denegar nueva publicación hasta resolver |

### 7.1 Criterios verificables de T11-B

Ensayo exclusivamente ficticio, futuro, con dos instituciones `DEMO-A` y `DEMO-B`, IDs técnicos distintos, mismo período local `2026`, grado `3`, sección `A`, código de clase `QUI-3A` y código académico `EST0001`. Dos alumnos diferentes tienen accountId/login distintos; una tercera cuenta tiene membresías en ambas instituciones. Docente saliente y reemplazo tienen cuentas existentes diferentes. Añadir institución/período desconocido y duplicados sintéticos como controles negativos.

| Criterio | Evidencia de aceptación requerida |
| --- | --- |
| A01 — Coincidencias válidas | Crear códigos/grupos locales iguales en A/B sin colisión global; mismo código de clase en otro período permitido. Duplicado del código académico dentro de A, incluso inactivo, y duplicado de login global rechazados |
| A02 — Membresías y roles | Una membresía por cuenta/colegio; varios grants válidos y episodios conservados. ADMIN_INSTITUCION no puede persistirse como grant global ni recibir ADMINISTRADOR vía membresía; rol docente/alumno ausente no permite activar su relación |
| A03 — Integridad | Inserts/updates ficticios que cruzan institución, clase, matrícula, destinatario, revisión o intento fallan en DB, aunque omitan el servicio. Solapamiento de matrícula/responsable y fechas inválidas rechazados. Dos procesos no admiten dos responsables vigentes |
| A04 — Conservación de cuenta | Todos los accountId/hash/username/estado y versiones originales iguales tras backfill; editar código académico o reemplazar docente no modifica credenciales. No crea cuenta nueva para una clase/colegio añadido |
| A05 — Conservación de evidencia | Conteos/checksums y FKs de intentos/respuestas/orden/ajustes/notas/feedback/cierres, eventos, materiales y capturas coinciden antes/después. Autores/calificadores/ownerUserId/tombstones no cambian; nuevas relaciones/revisiones se cuentan separadamente |
| A06 — Reemplazo persistente | Terminar asignación saliente y abrir reemplazo conserva classId, matrículas, cuentas, entregas y evidencia. Verificar restricciones y cronología; acceso real del saliente/reemplazo aún exige T12/T13 y no se declara aprobado por un test de entidades |
| A07 — Traslado y simultaneidad | Cuenta entra a otra clase o institución sin mover sus intentos previos; termina episodio y abre otro. Reingreso no duplica destinatario/participante ni reinicia máximo de intento por entrega/cuenta |
| A08 — Mapa y ambigüedad | Cada PK legacy está preservada y mapeada o marcada unresolved/conflict con motivo. assignmentId null, autor en dos colegios, período desconocido y FILE duplicados no reciben tenant inventado ni se eliminan. Recurso contradictorio no aparece como nuevo recurso publicable |
| A09 — Idempotencia/recuperación | Repetir lotes no duplica membresías/matrículas/revisiones; parar/reanudar desde checkpoint y fallos antes/durante commit mantienen fuente y cuotas. Restauración ficticia reproduce manifiesto y no pierde escrituras posteriores del ensayo |
| A10 — Cuotas/auditoría | Preservar versiones/guards T07-B/T08 o sustituirlos por versión comprobada, sin apagarlos. Deltas institucionales y legacy reconciliados; exceso histórico conservado; fallo de cuota/evidencia revierte negocio. Logs legacy append-only sin backfill destructivo |
| A11 — Compatibilidad cerrada | Feature multiinstitucional/registro apagados; esquema preparado no habilita endpoints/paneles nuevos. Clientes legacy solo en ámbito único aprobado; ambigüedad rechazada. Rutas globales no quedan disponibles para rol escolar por preparación del enum |
| A12 — Regresión y revisión | Checks apropiados de modelo/transición, T02/T03/T05/T06/T07/T08/T10 afectados, constraints y dos procesos cuando haya carreras. Reportar SHA/entorno/resultados y límites; no atribuir suites anteriores al nuevo commit ni sumar ejecuciones parciales |

### 7.2 Gates posteriores que T11-B no satisface por sí sola

T12 exige probar en ambos sentidos A→B y B→A: cuentas sin membresía, membresía suspendida, varios roles y colegios, ID/body/header/claims manipulados, creación con padre ajeno, consultas/count/agregados, material/captura/exportación, REST, STOMP SEND/SUBSCRIBE/outbound/pasivo, jobs/cachés y auditoría/métricas. Cambiar `institutionId` o rol de cliente no cambia autoridad. Añadir prueba de admin A que consulta cuenta compartida y no ve la actividad B; prueba de revocación local que corta A conservando B y de revocación global que corta ambas. Ausencia de DB no concede acceso.

T13 exige sustitución durante actividad, baja de docente/alumno, traslado con intento abierto, readmisión y acceso propio histórico: nuevo responsable tiene solo sus clases, saliente pierde facultades actuales, autor sigue intacto, ninguna nota/captura cruza colegio y las ventanas/cierres T05 siguen válidos. T14 exige aceptación concurrente única, expiración/revocación/cupo, cuenta existente conservada, recuperación y roles no escalables. Ningún gate se da por cumplido en T11-A.

## 8. Garantías y pendientes que permanecen abiertos

| Etapa / fuente versionada | Garantía que debe conservarse | Pendiente que no cierra T11-A |
| --- | --- | --- |
| [T01](../seguridad/02-01-configuracion-bootstrap.md) | Producción explícita, secretos obligatorios, bootstrap/demos opt-in | Inventario/configuración efectiva y recuperación operativa |
| [T02](../seguridad/02-02-autenticacion-sesiones.md) | Cuenta activa, temporal restringida, sesiones/versiones/revocación y locks de cuenta | Operaciones ya admitidas; adaptación institucional requiere T12 |
| [T03](../seguridad/02-03-seguridad-pizarras.md) | Lista de destinos, revalidación entrante/saliente/pasiva, autoría y proyección mantenida por eventos verificados del servidor | Broker simple sin distribución; aislamiento institucional todavía ausente |
| [T04](../seguridad/02-04-login-contrasenas.md) | Login uniforme, límites persistidos, política/hash y recuperación de sesión | Proxy/NAT, coste y dimensionamiento reales; no debilitar límites por selector de colegio |
| [T05](../seguridad/02-05-evaluaciones-ventanas.md) | Ventanas/cierre definitivo/nota cerrada/ocultación y locks | Reconciliación de históricos y continuidad de traslados por diseñar/validar |
| [T06](../seguridad/02-06-materiales-archivos.md) | Formato real, parser acotado y entrega autenticada | No acredita antimalware, sandbox OS ni capacidad real de workers |
| [T07-A](../seguridad/02-07a-limites-entradas-transporte.md), [T07-B](../seguridad/02-07b-consultas-cuotas-retencion.md) / **SEC-13 abierto** | Entradas/transporte, páginas/agregados SQL, cuotas/deltas y retención apagada | Medición sostenida acordada de planes/índices/latencia/memoria/backfill; cuotas aprobadas, crecimiento/alertas, privilegios DB y política/plazos/impacto de retención. Separar tenants no certifica capacidad |
| [T08](../seguridad/02-08-auditoria-eventos.md) | SUCCESS durable/atómico; rechazos limitados; append-only | Instalación/privilegios autorizados, alertas externas, backups/reloj y cobertura acotada de rechazos |
| [T09](../seguridad/02-09-dependencias.md) / **VER-01 abierto, parcialmente corregido** | Dependencias corregidas y evidencia SBOM/SCA heredada | Jetty Site findings/aviso; no `site:run`. JDK/Node mantenidos pendientes; imagen real/digest/OS/JRE y PostgreSQL/TLS productivo no verificados. No aceptación de riesgo implícita |
| [T10](../seguridad/02-10-concurrencia.md) / VER-02 | Locks entre procesos y afterCommit; preservación conservadora de duplicados | Matriz acotada no certifica toda carrera/carga; suite Linux completa, Java 17/Linux y PostgreSQL nativo Linux siguen pendientes |

No se repitieron suites ni builds durante T11-A: el único cambio entregado es documental. La verificación corresponde a lectura de fuentes/bases, consistencia del diseño, referencias, diff y conservación del trabajo previo. No se declara corregida toda la seguridad ni preparada la plataforma para varias instituciones. Las decisiones de §7 se revisan con esta propuesta completa antes de autorizar T11-B; no se hace merge ni se inicia implementación.
