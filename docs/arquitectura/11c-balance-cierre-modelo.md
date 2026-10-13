# T11-C — Balance y revisión de cierre del modelo institucional

13 de octubre de 2026, America/Lima. **Dictamen histórico en la base examinada: T11-B1–B5 están integrados, pero
la preparación completa de T11-B todavía no está lista para revisión de cierre.
Queda implementación propia de la etapa 3 antes de T12**, delimitada en §4.
No se requiere implementar autorización, onboarding o interfaz para demostrar
esos faltantes; sus responsables siguen siendo los de T11-A.

**Actualización vigente T11-B6 / P01:** [co-docencia y delegaciones](11b6-docencia-delegaciones.md)
implementa solo P01 persistente, con decisiones humanas confirmadas. Matrices §3/§4
y decisiones §7 se actualizan; las descripciones/evidencias/conclusión originales
siguen identificando la base histórica B1–B5. P03–P06 continúan abiertos y bloquean
el cierre agregado de T11-B y el inicio de T12. No se cierra etapa 3.

**Actualización vigente T11-B7 / P02:** [identificadores de acceso](11b7-identificadores-acceso.md)
implementa únicamente el ciclo persistente global, prueba ficticia vinculada a
cuenta/operación/versión, reserva de username, revisión explícita, revocación e
historia. Las reglas nuevas serán efectivas al adoptarlas en T12; T14 conserva
verificación externa/recuperación. P03–P06 y etapa 3 siguen abiertos. §2 y §8
conservan el inventario y dictamen históricos de la base B1–B5; B6 y B7 agregan
sus evidencias con SHA propio. B7 es ahora el certificador sucesor de la cadena.

## 1. Base, alcance y atribución

Base examinada de `ChemicalLab-Backend/develop`:
`e2bcc1afe347ba9630c1e90ea5d998f09a65812f`, igual a la esperada. El conector GitHub
resolvió `develop` al mismo SHA durante esta revisión; no hay avances adicionales
que incorporar. El fetch inicial falló por conectividad y no se usa como prueba
de frescura remota. Checkout inicialmente limpio, rama `docs/t11c-balance-modelo`.
No se encontraron `AGENTS.md` aplicables en backend ni ancestros.

Se leyeron [T11-A](11a-modelo-institucional.md), las cinco notas y sus cinco cierres
locales `Documentos/Seguridad/11b{1..5}-cierre-*.md`, fuera del repositorio backend.
Los cierres certifican cada bloque, **no el alcance agregado**. Se contrastaron
servicios, repositorios, records JDBC, las ocho expansiones y los casos pertinentes,
además de los escritores legacy y garantías T02/T03/T04/T05/T07-B/T08/T10.
Los criterios siguen siendo T11-A §7.1; no se rebajan para cerrar el trabajo.

En las referencias siguientes:

- `J/` = `src/main/java/com/morales/chemicallab/`.
- `T/` = `src/test/java/com/morales/chemicallab/security/`.
- `S/` = `src/main/resources/db/expansion/`.
- Los números de línea de código/SQL corresponden a la base anterior. Los nombres
  de métodos y objetos SQL permiten verificarlos también con `git show SHA:ruta`.
- E1–E5 identifican evidencia ejecutada anteriormente; leer una prueba en T11-C
  no equivale a volver a ejecutarla. Los enlaces fijan el SHA probado.

| Evidencia | Nota, SHA de código probado y resultado reutilizado | Integración y límites |
| --- | --- | --- |
| E1 | [B1](11b1-instituciones-membresias.md), [34547cfebcb8cf29ba951cb6dc3797f0cb44ee71](https://github.com/ChemicalLab-Org/ChemicalLab-Backend/commit/34547cfebcb8cf29ba951cb6dc3797f0cb44ee71): 54 suites / 611 pruebas / 0 fallos, errores u omisiones | PR #67, merge `ec0ef0b4b8b093687517dc07a279b02d74d0084c`; restore de siete firmas y carreras psql, separados de JUnit |
| E2 | [B2 corregida](11b2-clases-matriculas.md), [827bb8c9554718efb799ac6edd4811a93cad73f1](https://github.com/ChemicalLab-Org/ChemicalLab-Backend/commit/827bb8c9554718efb799ac6edd4811a93cad73f1): 55 / 644 / 0–0–0 | PR #68, merge `97d6fe5da8fb2b22ffda8c81d4f0b9761b619cf3`; v2 acumula catorce eventos B1/B2, restore de veinte firmas, carreras de responsable/matrícula |
| E3 | [B3 corregida](11b3-recursos-intentos.md), [e02c7acfe5dd796f93d33825492a2208169adc03](https://github.com/ChemicalLab-Org/ChemicalLab-Backend/commit/e02c7acfe5dd796f93d33825492a2208169adc03): 56 / 675 / 0–0–0 | PR #69, merge `70415ad46f22b4ec9790ce7aa2e1d327918e6fda`; v2 exige aprobación antes de STALE y contrasta retorno/estado; restore de 61 tablas; carreras apply y último intento legacy |
| E4 | [B4 corregida](11b4-contexto-pizarras-actividad.md), [9c60faaa4dd50aa9c34e65a7c44da1f5b5cd0f58](https://github.com/ChemicalLab-Org/ChemicalLab-Backend/commit/9c60faaa4dd50aa9c34e65a7c44da1f5b5cd0f58): 58 / 714 / 0–0–0 | PR #70, merge `4e0454562e0046fd90e9c981439961afacfef5f6`; padre WhiteboardSession tipado incluso ADMIN/sin boardId; restore de 69 tablas y aplicación duplicada entre JVM |
| E5 | [B5](11b5-identidad-codigos.md), [f3f633766ece835e0bd37cc018505f7dc89e3e50](https://github.com/ChemicalLab-Org/ChemicalLab-Backend/commit/f3f633766ece835e0bd37cc018505f7dc89e3e50): 59 / 739 / 0–0–0 | PR #71, merge de la base examinada; 25 casos B5, restore de 78 tablas y reserva concurrente COMMITTED/REJECTED:23505 |

Todos esos ensayos usaron Windows/JDK 17.0.12/PostgreSQL 18.4 desechable y perfil
test. No son certificación Linux, capacidad, TLS/permisos ni inventario escolar.
No se suman ejecuciones, controles independientes o selecciones a las 739.
Los commits de cierre posteriores al código probado son documentales según notas
y cierres; sus resultados no se atribuyen al SHA de merge ni al de T11-C.

T11-C contrastó los cinco `verified-results.json` conservados, y contó directamente
los XML de `.codex-work/t11b5-evidence/verified-reports.zip`: 59 suites,
739/0/0/0. Esto verifica evidencia archivada, no ejecuta una suite.
El resultado E5 incluye regresiones B1–B4 sobre E5, sin reemplazar la atribución
de ensayos nativos/revisiones anteriores a sus propios SHA.

## 2. Implementado frente a preparación interna

| Área | Implementación comprobada | Límite de lo entregado |
| --- | --- | --- |
| Instituciones/membresías/roles | B1: claves, versiones, estados, grants con exclusión temporal e historia append-only. B5 consulta episodios desde eventos B1 y separa privilegio global con prueba de procedencia | Mantenedores internos; `ACTIVE` almacenado no compone autoridad. B5 solo prepara privilegio ADMINISTRADOR legacy demostrado, no altas reales de privilegios |
| Períodos/clases/matrículas | B2: clase estable curso/grupo/período, episodios, responsable temporal, reemplazo interno atómico y restricciones DB | Solo RESPONSABLE; `CO_DOCENTE` se rechaza. Atributos/ventanas originales inmutables. No gestión externa de sustituciones/traslados |
| Recursos y resultados | B3: asociación explícita, revisiones congeladas, entregas/destinatarios/contexto probado, raíz única y consumo completo legacy/cuenta, conflictos conservados | Copia técnica de fuente aprobada. No ResourceManagerGrant ni ciclo de nuevas revisiones académicas. Presupuesto reservado es fotografía, no saldo autoritativo vivo |
| Pizarras/actividad/auditoría | B4: asociación aditiva por PK, participante original, herencia de ownership/captura, hechos GLOBAL/INSTITUTION y incertidumbre. V2 comprueba padres tipados | No reconstruye asistencia ni autores. No cambia queries/STOMP. Un vínculo v1 erróneo queda desactualizado; no existe sustitución aditiva aprobada de ese vínculo |
| Identidad/códigos | B5: candidatos/conflictos entre tipos, binding global único PREPARED sin autenticación, códigos permanentes por colegio/cuenta F05, cuotas técnicas e historia | `verified=false` obligatorio, binding solo PREPARED; no ciclo ACTIVE/REVOKED de aliases, correo verificado ni renombrados académicos |
| Transición | Cadena explícita, fingerprints de predecesor/sucesor, rollback T08/cuotas, repetición y restore de fixtures | B3/B4/B5 bloquean mutación fuera de DB/schema propios de ensayo. No backfill real, proceso de lotes/checkpoint completo ni adopción coordinada de writers |

`InstitutionMaintenanceService` y `AcademicMaintenanceService` no están conectados
a consumidores externos. `ResourceReconciliationService.fixtureOnly()`,
`t11b4_lock_sources` y `t11b5_fixture` restringen conciliación a fixtures propios.
La búsqueda de dependencias en `src/main/java` encuentra los cinco servicios
solo en sus declaraciones; no controladores, jobs o login que los adopten.
No hay nueva autoridad REST/STOMP. Se conserva `UserAccount.role` como autoridad
legacy y el login T02/T04, incluido trim exacto/prioridad de username.

La cadena es B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2 → B4 v1 → B4 v2 → B5 v1.
Certificadores actuales: **B1 v1, B3 v2, B4 v2, B5 v1**. Un certificador viejo
puede rechazar correctamente el sucesor; no se reescriben fingerprints ni se
reparan divergencias con Hibernate. Este documento no ejecuta la cadena.

## 3. Matriz A01–A17

“Demostrado acotado” significa el escenario persistente/fixture citado, no el
criterio completo ni el servicio efectivo. “Parcial” conserva expresamente el
faltante. P01–P13 remiten a responsables y bloqueos en §4.

| ID / balance | Evidencia, archivo y referencia verificable | Faltante y límite |
| --- | --- | --- |
| A01 — Demostrado acotado | E2/E5 preservan códigos/reservas F05. B7 GlobalAccessIdentifierDbTest prueba claims entre tipos, reserva de username, correo verificado/opcional, reutilización revisada y carrera entre dos JVM; SHA/restore en nota B7 | P02 persistente implementado; login legacy conserva sus consultas hasta T12/P09. Verificación externa T14, sin nueva normalización |
| A02 — Demostrado acotado | E1 `T/InstitutionPersistenceDbTest.java:48,67,89` pertenencias independientes, roles cerrados e historia; E5 `T/IdentityPreparationDbTest.java:99,107,189` no promoción global y timeline; `S/t11b5-v1.sql:362` | F04 persiste estructuralmente, no autoriza calificación. P01/P02 preparados; autorización efectiva P09 |
| A03 — Parcial del modelo completo | E2 `T/AcademicPersistenceDbTest.java:95,157,409,415` padres/fechas/carreras; E3 `T/ResourceReconciliationDbTest.java:324` contexto ajeno; E4 `T/BoardActivityReconciliationDbTest.java:171` padre de auditoría; E5 `T/IdentityPreparationDbTest.java:78` | B2 histórico rechaza CO_DOCENTE; B6 añade relación separada y reserva común, grants tipados y procedencia administrativa, con ensayos en DelegationPersistenceDbTest y SHA en la nota B6. P01 persistente implementado; P03 pendiente |
| A04 — Demostrado acotado | E1 test `repeatExpansionPreservesLegacyAccountsSessionsAndNewAuthoritiesHaveNoEffect` en `T/InstitutionPersistenceDbTest.java:155`; E5 `T/IdentityPreparationDbTest.java:59,70` cuenta/código/readmisión | No cambia credenciales al reservar código; writer legacy de edición aún puede cambiar username. Corte/adopción P09; inventario real P08 |
| A05 — Demostrado para fuentes cubiertas | E3 `T/ResourceReconciliationDbTest.java:357,371` bytes/orden/nota; E4 `T/BoardActivityReconciliationDbTest.java:66` autores/tombstones/captura/participantes; E5 `T/IdentityPreparationDbTest.java:265` firmas de 78 tablas | Firmas de todas las tablas del fixture no equivalen a mapa por todas las PK ni recuperan bytes históricos perdidos. P05/P06/P08 |
| A06 — Parcial | E2 `T/AcademicPersistenceDbTest.java:226,275,282` reemplazo con clase/cuentas/matrículas iguales y rollback del predecesor | B6 demuestra reemplazo conservando co-docentes independientes/evidencia y rollback por conflicto. P01 persistente implementado; saliente/reemplazo y capacidades efectivas P09/P10. No atribuir gate de acceso al test de reemplazo |
| A07 — Parcial | E2 `T/AcademicPersistenceDbTest.java:108,191` matrículas independientes/episodios; E3 `T/ResourceReconciliationDbTest.java:144` readmisión no duplica destinatario/saldo; E4 `T/BoardActivityReconciliationDbTest.java:104` `readmissionDoesNotRewriteOriginalParticipantEpisode` | Continuidad con intento abierto y pérdida de acceso son T13 (P10); persistencia de relaciones delegadas P01 implementada en B6; no mover hechos por perfil actual |
| A08 — Parcial | E3 `S/t11b3-v1.sql:78,175,203` ledger/fotografía/mapa; test B3 `duplicatedProfilesArePreservedInConflictWithoutMerging`:344; E4 `T/BoardActivityReconciliationDbTest.java:110,225` unresolved y padre sin resolución; E5 test `identifierInventoryPreservesCrossTypeCollisionAndBothClaims`:147 | No catálogo exhaustivo por PK de cuentas/perfiles/grupos independientes ni resolución aditiva de contextos obsoletos. P05/P08; fixtureEvidence no es aprobación humana real |
| A09 — Parcial | E3 `T/ResourceReconciliationDbTest.java:248` rollback explícito + mismo ID; E4 `T/BoardAuditParentTransitionDbTest.java:13` evolución/rollback; E5 `T/IdentityPreparationDbTest.java:265` dump/restore y guardas | No checkpoint durable de lote completo, caída de proceso/commit desconocido demostrados ni recuperación de escrituras posteriores al dump. P06; el restore es del manifiesto estático |
| A10 — Parcial | E1 fallo T08 en `T/InstitutionPersistenceDbTest.java:130`; E2 test `auditFailureRollsBackB1AndB2TogetherWithTheirHistories`:446; E3 fallo cuota/auditoría:235; E4 bytes de copias:252; E5 rollback compuesto:208 | T07-B/T08 conservados; B6 añade cuotas técnicas de relaciones/reservas/historia y rollback obligatorio P01. `S/t11b3-v1.sql:243` cobra ACCOUNT, B4:347 ACCOUNT/SYSTEM; falta preparación de deltas institucionales/doble conteo P04. Política/capacidad real P12 |
| A11 — Parcial | E1 test de autoridades legacy:155; E2 `newAcademicRelationsLeaveSessionsCredentialsRestAndStompAuthoritiesIntact`:394; E5 prueba de colisiones/prioridad:127; `S/t11b5-v1.sql:445` authentication_enabled=false | No endpoints nuevos; eso no prueba rechazo integral de ámbitos legacy ambiguos. Cerrar estas superficies y contexto único aprobado corresponde a T12, P09 |
| A12 — Parcial | E1–E5 y cierres de §1; XML archivado E5 reconfirmado; E3/E4 carreras apply, E5 reserva entre dos JVM | Evidencia acotada reutilizable. P01/P02 verificados según SHA en B6/B7; faltan evoluciones P03–P06 y revisión conjunta; Linux/SCA/operación P13 continúan abiertos, sin exigir repetir suite por T11-C |
| A13 — Demostrado acotado | E3 `T/ResourceReconciliationDbTest.java:103` `exhaustedInactiveAndUnassignedAttemptsReserveOneBudgetA13`; `S/t11b3-v1.sql:279` cuenta todas las filas del origen | used=2, saldo=0, sin contexto inventado; rechazo por writer legacy, nunca admisión institucional. Adopción/conjunto de inicios autorizado P09 |
| A14 — Demostrado acotado | E3 `T/ResourceReconciliationDbTest.java`, `multipleAssignmentsAndTechnicalRevisionsShareRootA14`:116 y `legacyLastOpportunityRaceWithPreparedDeliveriesStillHasOneWinner`:391; `S/t11b3-v1.sql:384` cierre original | Una raíz, último inicio legacy número 3, carrera concreta. Cambia fuente y snapshot queda stale; no hay sincronización. P03/P09; F09 solo nuevas evaluaciones |
| A15 — Demostrado acotado | E3 `T/ResourceReconciliationDbTest.java`, `previousActiveContextIsFixedAtStartAndUnassignedHasNoInventedEpisodeA15`:134; `consistentUnassignedActiveIsReservedWithoutInventingContext`:319; test conflictos:154 | Conserva ID/consumo/contexto cuando consta. No demuestra todas las finalizaciones/bajas institucionales; variante principal cambia estado por SQL de fixture, no flujo T13. P09/P10 |
| A16 — Demostrado acotado | E3 `T/ResourceReconciliationDbTest.java`, `readmissionDoesNotDuplicateRecipientsOrRefundA16`:144; `zeroConsumptionRecipientHasOneReservedBudgetAndIndependentOriginStaysSeparate`:336; E5 `T/IdentityPreparationDbTest.java:70` readmisión/reserva | Unicidad/raíz conservadas. No escenario completo de readmisión + sustituto + carrera institucional + aislamiento A↔B. P01 persistente implementado en B6; P09/P10 abiertos |
| A17 — Demostrado acotado | E3 `T/ResourceReconciliationDbTest.java`, conflictos:154; `originalT05ClosureCountsFutureEmptyAndNoDeadlineAssignments`:256; `terminalOriginalClosureSurvivesDeliveriesAndTechnicalCopy`:265; `S/t11b3-v1.sql:393` frescura y admisión/exposición false | T05 y cierre original conservados; no restitución de saldo. Exposición F03 y corte real P09/P10/P08; conciliación que cambia no se recertifica sola |

Las referencias A15/A16 describen la prueba existente con su alcance concreto:
ni un nombre Axx ni una tabla bastan para declarar cubiertas todas las variantes
de aceptación. Los gates externos incluidos en los criterios permanecen abiertos.

## 4. Pendientes, responsables y bloqueos

Columnas: **M** bloquea cerrar preparación T11-B; **I** bloquea iniciar T12 según
el orden T11-A §5; **S** bloquea activar el servicio multiinstitucional. “Sí, por M”
no obliga a implementar autorización antes de T12. Una dependencia funcional
pendiente no se resuelve eligiendo una política por conveniencia.

| ID / pendiente concreto y salida verificable | Responsable conforme T11-A | M | I | S |
| --- | --- | --- | --- | --- |
| P01 — **Implementado: solo preparación persistente B6.** Relaciones temporales CO_DOCENTE y ResourceManagerGrant tipado; institución/roles/procedencia administrativa, historia/revocación, facultades independientes y exclusiones PostgreSQL. Ensayo/rollback/carrera/restore y SHA en [B6](11b6-docencia-delegaciones.md). Se conservan las notas B1–B5 con su ausencia histórica | **T11-B:** P01 preparado; **T12:** autorización efectiva; **T13:** flujos externos. No conecta autoridad legacy ni anticipa el flujo T13 | No: alcance persistente P01 implementado | No por P01/P02; P03–P06 aún bloquean por M | Sí: requiere T12/T13 antes de ofrecer esas capacidades |
| P02 — **Implementado: solo ciclo persistente B7.** USERNAME/EMAIL, estados/versiones, claims globales entre tipos, reserva del username anterior, prueba ficticia de correo ligada a cuenta/operación/versión, revocación y revisión explícita antes de reutilizar, historia append-only y atomicidad T08/cuota. Candidatos/conflictos B5 intactos; evidencia/SHA en [B7](11b7-identificadores-acceso.md) | **T11-B:** P02 preparado; **T12:** adopción del resolver; **T14:** verificación/recuperación externas. Correo opcional, igualdad exacta y decisiones confirmadas §7 | No: alcance persistente P02 implementado | No por P02; P03–P06 todavía bloquean por M | Sí: las reglas nuevas serán efectivas al adoptar T12; PREPARED nunca autoriza |
| P03 — Contrato persistente de nuevas revisiones académicas/publicación/entregas: revisión publicada inmutable, relación al recurso y raíz correcta, destinatarios y cierre original. `t11b3_reference_guard` solo acepta payload de conciliación y `technicalRevision` clona el mismo payload; no soporta una revisión académica nueva distinta. Definir/ensayar el almacenamiento y la transición interna, sin ofrecer endpoints ni decidir F09 por copia técnica | **T11-B**, revisión/padrón/puentes §2.2 y §5; **T12**, writers autorizados de editar/publicar/asignar; F09 para raíz de evaluación realmente nueva | Sí | Sí, por M | Sí para producción académica institucional |
| P04 — Preparación de contadores/guards por institución con reconciliación de deltas legacy/nuevos y exceso histórico conservado. B3/B4 cobran copias por ACCOUNT/SYSTEM; B5 tiene techo técnico propio. Falta ensayo de doble conteo institucional sin retirar guards antiguos, inmutabilidad del tenant y rollback conjunto de cuota/evidencia | **T11-B**, evolución preparada T07-B, T11-A §3.5/§5/A10; **operación/T15–T17**, máximos aprobados, dimensionamiento y retención SEC-13 | Sí | Sí, por M | Sí; preparación no aprueba máximos reales |
| P05 — Cobertura completa y evolución aditiva de conciliación: inventario ficticio de cada PK/fuente, incluidas cuentas/perfiles/grupos sin recursos, mapa o incertidumbre con motivo; no convertir una propuesta omitida en cobertura. Política/mecanismo para conservar y superseder un contexto erróneo/desactualizado aprobado sin borrar ni cambiar su tenant: B3 nueva fotografía encuentra raíz existente en conflicto; B4 v2 conserva vínculo v1 incorrecto sin reemplazo | **T11-B**, ledger/puentes e integridad §4.1–4.3/A08; **F08**, reglas/aprobadores; operación hace inventario real. Resolver la procedencia no concede acceso | Sí, falta soporte/ensayo exhaustivo | Sí, por M | Sí para adoptar esos agregados; los no resueltos quedan aislados |
| P06 — Lotes/checkpoint y recuperación de ensayo: checkpoint durable ligado a versión/PK/checksum y commit, parar/reanudar entre lotes, caída real de proceso/commit desconocido y restauración sin perder escrituras posteriores del ensayo. El test B3 hace rollback programático; el restore B5 compara un dump sin journal de cambios posteriores. Falta implementación de transición/reanudación y evidencia ficticia A09; no ejecutar un backfill escolar | **T11-B**, cadena/ensayo reversible e idempotente §4.2–4.3/A09; **operación**, ventana, WAL/journal elegido, RPO/RTO y backup real. No imponer una tecnología nueva | Sí | Sí, por M | Sí antes de corte real |
| P07 — Edición general de atributos/ventanas, renombrado académico/reservas históricas adicionales, presentación escolar y datos oficiales/sedes aún no definidos. La inmutabilidad mínima actual es explícita. Revisar cuando se confirme una necesidad/política, sin recrear identidad | Persistencia futura **T11-B** si la decisión exige evolución; flujos posteriores **T13/T14**; F01/F05 complementarios | No por sí sola: no se añade CRUD opcional como requisito de cierre | No | Condicional al flujo/granularidad que se adopte |
| P08 — F08 real: responsables y mapas aprobados, inventario autorizado por PK, resolver/aislar incertidumbre, confirmar colegios/períodos/zona histórica, permisos y ventana de ejecución. FIXTURE-* no prueba esas aprobaciones | **T11-B**, reglas/ensayo del plan (P05/P06); **operación con responsables F08**, datos reales y ejecución de P3. No se requiere conocer datos escolares para empezar aislamiento sobre fixtures | No, separada de P05/P06 | No | Sí para los datos/ámbitos del piloto; nunca tenant ficticio |
| P09 — Adopción/corte de todos los writers/readers, único presupuesto vivo, principal/contexto, rechazo legacy ambiguo, F04/no autocalificación, consultas/archivos/REST/STOMP/jobs/cachés y revocación local/global. Contratos mínimos §6; implementación y pruebas bilaterales aún ausentes | **T12**, T11-A §3.3–3.5/§5/P5/§7.2. T11-B entrega puentes y invariantes; no debe implementar ahora esos endpoints | No, salvo puentes P01–P06 | No: es trabajo de T12 tras cerrar M | Sí, incluso para piloto cerrado |
| P10 — Sustitución/bajas/traslado con intento abierto/readmisión, facultades delegadas y acceso individual histórico F03 bajo T05; política complementaria de capturas compartidas/suspensión | **T13**, con aislamiento T12. Parte persistente P01 no entrega estos flujos | No | No | Sí para ofertar esos flujos; piloto sin ellos solo se evalúa tras T12 |
| P11 — Altas/vinculación/invitaciones, poderes de recuperación para menores, MFA/reautenticación y ciclo externo de cuenta | **T14**, F06 y auditoría. Ni códigos F05 ni roles preparados son credenciales/invitaciones | No | No | Sí para habilitar incorporación pública/recuperación ofrecida |
| P12 — Cuotas definitivas, medición sostenida planes/índices/latencia/memoria/backfill, crecimiento/alertas, permisos DB/TLS/reloj, retención y recuperación real, gobierno y broker distribuido antes de escalar | **Operación/T15–T17**, SEC-13; preparación técnica P04/P06 sigue en T11-B | No | No | Sí conforme alcance operativo; retención sigue apagada |
| P13 — Linux/JDK17/PostgreSQL nativo Linux, SCA/runtime mantenido y revisión/certificación de entrega; VER-01 sigue abierto | **Validación/operación** heredada T09/T10, sin atribuirla a T12 ni declarar aceptación de riesgo | No para persistencia ficticia Windows; A12 parcial | No por sí sola | Sí para certificar el entorno de servicio |

En el balance histórico P01–P06 eran faltantes de **preparación**; actualmente P01 está implementado en B6 y P02–P06 permanecen abiertos. Son requisitos de preparación, no propuestas de mejora opcional. Se derivan
de entidades/relaciones, cuotas y transición exigidas por T11-A. Sus decisiones
dependientes deben delimitarse antes de implementar. No se pide ahora un panel,
registro, reset, traslado completo o autorización de co-docente para cerrar B.
Las restricciones actuales de fixtures y la conservación de ambiguos son correctas;
el faltante consiste en no haber completado el soporte/ensayo exigido, no en abrir
esas guardas a una base real.

## 5. Fuentes legacy e incertidumbre

| Fuente | Cobertura comprobada | Ausencia/ambigüedad que permanece |
| --- | --- | --- |
| `user_accounts`, `account_sessions`, límites T04 | E1/E5 firmas y contratos intactos; B5 inventario username/email, prueba global y reserva por mapa | No ledger universal por cada cuenta/sesión como origen migrado. No fusionar personas por nombre/correo ni inferir colegio desde rol. Sesiones/límites son globales; no requieren tenant inventado |
| `teacher_profiles`, `student_profiles`, grupo legacy | B3 fotografía autor/perfiles de intentos y referencias del mapa; B5 código por membresía explícita | Perfiles sin esos recursos/mapas y grupos sin período/curso no quedan automáticamente clasificados. Múltiples perfiles por cuenta se preservan en conflicto. Falta catálogo completo P05; mapa real P08 |
| Conceptos/colecciones/materiales/assignments | `t11b3_snapshot` en `S/t11b3-v1.sql:175`: textos ordenados, todos los materiales/bytes y assignments; recursos/revisiones/deliveries por mapa | FILE duplicados no se depuran; T10 conserva rechazo conservador. Autor en A/B no demuestra tenant. Arrays de entregas vacíos no prueban que no haya asignaciones pendientes de mapear. Bytes históricos reemplazados/perdidos no se fabrican |
| Evaluaciones/preguntas/opciones/assignments | Snapshot íntegro; clase/revisión/destinatarios demostrados; conserva todos los assignments para cierre | Falta período o población inequívoca impide publicar la adopción. No repartir recurso contradictorio entre colegios ni copiar notas para arreglarlo. Revisión técnica no reconstruye todas las versiones históricas |
| Intentos/respuestas/ajustes/eventos | Todas las filas evaluation/perfil original en snapshot/consumo, incluidas inactive/sin assignment. Contexto solo si episodio/fecha/asignación constan | Sin assignment o matrícula histórica: consume y se conserva sin contexto inventado. Dos activos/números incoherentes reservan CONFLICT y bloquean adopción. No renumerar, devolver saldo ni atribuir por grupo actual |
| Pizarras/participantes/ownership/capturas | B4 fotografía de sesión/participantes/objetos; vistas heredan del padre, preservan ownerUserId nullable, tombstones, revisión y checksum de captura | Matrícula no demuestra asistencia. Participante omitido del mapa queda sin contexto individual. Autor desconocido permanece NULL. Tiempo local requiere zona demostrada, no conversión masiva por defecto |
| Uso/auditoría | Mismos IDs/actores/hechos originales; ledger separado, GLOBAL demostrado o INSTITUTION por padre/mapa; B4 v2 comprueba WhiteboardSession tipado | Navigation/evento sin padre no se reparte entre membresías. `{}` puede quedar UNRESOLVED; contradicción CONFLICT; deriva STALE. Clasificador GLOBAL es cerrado, no comodín para todo lo desconocido. Logs legacy nunca se backfillean destructivamente |
| Cuotas/contadores/versiones | Guards y ledgers previos conservados; copias/historia cobran bytes lógicos y filas, rollback T08 | No cotejo institucional exhaustivo P04. Exceso legacy se conserva, no se recorta para superar cuota. Bytes lógicos no certifican espacio físico/WAL/backups ni política real |

B3 soporta propuesta/aprobación exacta/aplicación/conflicto/deriva; B4 añade
UNRESOLVED explícito. B5 guarda claims y conflictos, con frescura calculada.
No hay un único estado de conciliación intercambiable entre ellos: preservar
origen/PK/snapshot/mapa/aprobación/checksum y motivo, distinguiendo omitido,
no demostrado, contradictorio y desactualizado. Un checksum MD5 de JSONB detecta
deriva; no acredita aprobación humana ni autenticidad criptográfica.

P05 exige inventario/recuento por PK además del manifiesto de firmas: en B3/B4 se
llama `propose` por origen conocido y se rechaza un padre ausente; no existe una
enumeración que garantice que todas las PK se propusieron. Un dump completo prueba
conservación del fixture, no cobertura de conciliación. El mapa real permanece
restringido fuera del repositorio/PR/logs generales. Sin procedencia demostrada,
conservar origen e incertidumbre y excluir de consultas escolares/publicación;
ningún colegio “legacy” o “por defecto” resuelve esa ausencia.

## 6. Contratos mínimos de adopción por writers actuales

Esta es la lista de condiciones que T12 deberá convertir en contratos ejecutables.
Documentarla no sincroniza writers ni autoriza corte. En la preparación actual
solo legacy decide inicio/finalización/exposición; `source_current=false` informa
deriva y no se puede usar un presupuesto preparado como saldo vivo.

| Writer/consumidor actual, referencia en `J/` | Contrato de adopción y evidencia que faltará en T12 |
| --- | --- |
| `service/UserManagementService.java` alta/edición, `service/AdminService.java` aprovisionamiento/reasignación/reset | Separar código escolar de username; conservar accountId/hash/sesiones. Alta/reserva/episodio/rol con ámbito probado y SUCCESS atómico. No reset global unilateral por colegio, ni dos escritores que eludan reserva F05; revocación escolar no bloquea B |
| `service/AuthService.java:108`, `security/AuthenticationLimits.java`, `security/AccountSessionService.java`, `security/CustomUserDetailsService.java` | Mantener comparación/prioridad y T02/T04 hasta corte explícito; nuevo resolver solo con aliases/grants vigentes y colisiones resueltas. Renovación del contrato de sesión, sin autoridad desde PREPARED ni claims/tenant del cliente |
| `service/ConceptContentService.java`, `service/ConceptMaterialService.java`, `service/EvaluationService.java` y repositorios de recursos/assignments | Autorización por institución/clase/facultad; autoría fija. Publicación congela revisión, entrega apunta a ella y a destinatarios; editar no cambia hechos/nota previos. Operación/versión/guardas/cuotas y T08 confirman juntas. Necesita P01/P03/P04 |
| `service/EvaluationService.java:439–469`, `service/EvaluationAccessLock.java`, repositorios de intentos/respuestas/ajustes/eventos | Una autoridad por raíz legacy/cuenta, todas las filas consumidas y único activo. Lock compartido entre entregas, relectura y número únicos; no dual-write independiente ni snapshot stale como saldo. Antes del corte count/activo/next/máximo/ventanas/cierre deben concordar cero diferencias. F09 no divide raíces legacy |
| `service/WhiteboardSessionService.java`, `service/WhiteboardDrawEventService.java`, `security/WhiteboardRealtimeAccess.java`, conexiones/interceptor STOMP | Resolver pizarra/clase y participante demostrado sin reescribir author/owner/tombstone; preservar lock/revisión/afterCommit. Contexto fijo por socket y revalidación SEND/SUBSCRIBE/outbound/pasiva. Snapshot/captura/informes con el mismo ámbito; vínculo stale no autoriza |
| `service/UsageMetricService.java`, `service/StudentUsageRecordService.java`, `service/UsagePanelService.java`, `service/AuditLogService.java`, `service/AuditLogWriter.java` y repositorios de supervisión/actividad | Fijar ámbito al hecho autorizado con actor servidor. SUCCESS obligatorio en transacción; métrica opcional afterCommit conserva su contrato. No deducir tenant de membresía actual ni mezclar B en actividad de cuenta compartida vista por A; padre tipado comprobado, historial append-only |
| `service/TechnicalRetentionService.java`, jobs/exportación/cachés y scripts T07-B/T08 | Retención apagada; no purgar incertidumbre. Contexto y versión explícitos al ejecutar, reconciliación de contadores. Solo writers de todas las instancias compatibles antes de corte; runtime sin permisos que eludan guards/ledger |

Adopción exige ordenar/bloquear padres de forma consistente entre procesos,
releer estado tras lock y probar carreras reales del flujo afectado; las carreras
de preparación no prueban ese protocolo integral. El orden mínimo interno B2
(membresías ordenadas → institución → período → clase → relación) y el lock
legacy T05/T10 deben armonizarse con raíz/presupuesto/contadores, no copiarse
como promesa de ausencia universal de deadlocks. Cualquier doble escritura debe
compartir transacción/versión y una sola autoridad. P06 debe demostrar recuperación
con escrituras posteriores; operación fija ventana/RPO/RTO. Tras tenants activos
no es válido volver a un binario global legacy: recuperación con aislamiento o
acceso deshabilitado, conservando evidencia.

## 7. Decisiones funcionales

Se conservan **F02** pertenencia simultánea, **F03** lectura individual tras salida
bajo T05, **F04** gestión/supervisión y calificación solo con DOCENTE + asignación,
sin autocalificación ni bypass de nota cerrada, y **F05** reserva permanente por
cuenta/colegio independiente del login. También están confirmados en B2: clase
como curso/grupo/período y alumno en varias clases del mismo colegio. Aunque la
redacción histórica F02/F07 de T11-A aún mencione múltiples clases como cuestión
general, no se vuelve a decidir esa confirmación ni se alteran sus secciones.

| Pendiente funcional real | Efecto / etapa |
| --- | --- |
| F01 unidad colegio/sede, identificador oficial y administradores por sede | Frontera técnica actual válida para ensayo; decidir granularidad antes de mapas reales/servicio. Persistencia específica posterior si la decisión la requiere; no inventar identificador oficial |
| Complementos F02/F04 **confirmados en B6:** CO_DOCENTE con facultades académicas completas en sus clases; ADMIN_INSTITUCION designa/revoca gestores del mismo colegio; EDIT_DRAFT/PUBLISH/ASSIGN independientes; autoría/historia intactas. ADMIN_INSTITUCION solo no califica: DOCENTE vigente + asignación, sin autocalificación ni bypass de nota cerrada | P01 persistente implementado en B6; aplicación T12/T13. Otros roles combinados, soporte global/correcciones excepcionales siguen pendientes; P03 conserva su propio alcance |
| Complementos F03: capturas compartidas, suspensión institucional y mecanismo de consulta/recuperación | T13/T14; no revocar ni caducar por defecto el derecho individual confirmado |
| Acceso global confirmado B7, independiente de F05: docentes/alumnos con username o correo verificado y único; correo opcional; renombrado con reserva del anterior; reutilización solo tras revocación/nueva prueba/revisión; igualdad exacta | P02 persistente implementado; reglas efectivas con resolver T12 y flujos externos T14. Historia/sesiones/resultados permanecen en accountId original. Renombrado académico, recuperación y normalización nueva siguen fuera del alcance |
| F06 recuperación de menores/poderes de ayuda escolar | T14; no trasladar los resets legacy al ámbito multiinstitucional |
| F07 padrón/incorporaciones/traslado con intento abierto | T13 y contratos T12; clase y simultaneidad ya confirmadas. No acceso retroactivo automático ni transporte de intentos |
| F08 origen/aprobadores, resolución de conflicto y corrección aditiva | Reglas de ensayo P05/P06; datos reales P08. Evidencia ausente permanece ausente |
| F09 presupuesto de evaluaciones realmente nuevas | Delimitar contrato P03/T12; política todavía pendiente. Cualquier alternativa conserva sin cambios raíces/consumo/cierre legacy de A13–A17 |

No se piden decisiones nuevas sobre funcionalidades opcionales para conseguir
un cierre. Las decisiones anteriores se resuelven solo cuando condicionen la
entrega respectiva; no se aprueban por la existencia de una tabla o CHECK.

## 8. Conclusión histórica y conservación

**Queda implementación propia de etapa 3: P01 relaciones/facultades delegadas;
P02 ciclo persistente de aliases; P03 contrato persistente de revisiones académicas;
P04 contadores institucionales; P05 conciliación exhaustiva y corrección aditiva;
P06 lotes/checkpoints y recuperación con escrituras posteriores del ensayo.**
Requieren delimitación funcional donde se indicó, evolución explícita sin tocar
SQL históricos y comprobaciones ficticias focalizadas que demuestren sus salidas.
Por ello no procede declarar T11-B cerrado ni iniciar T12 desde esta base.
El balance sí queda listo para revisión documental y priorización de esos faltantes.

Se actualiza únicamente la matriz vigente T11-A §7.3 para reflejar A03/A08/A09/A10
y esos límites; no cambia aceptación, diseño ni decisiones históricas. P08–P13
permanecerán abiertos aun después de completar la preparación. T12 conserva
aislamiento efectivo; T13 continuidad/F03; T14 incorporación/recuperación; operación
y T15–T17 conservan SEC-13/VER-01 y sus demás gates. No se declara servicio listo.

Validación T11-C: lectura cruzada, referencias a fuente/prueba, XML archivado,
diff documental y preservación. No se ejecutaron tests/builds/DDL/backfill ni se
abrió PostgreSQL: ninguna duda restante requiere repetir una suite para este diff.
Manifiesto local `.codex-work/t11c-evidence/` conserva la comprobación de evidencia
y hashes: 40 archivos protegidos únicos sin discrepancias, incluidos auditoría,
los cinco cierres B1–B5, evidencia previa de revisión y los 16 E2E. Auditoría
SHA-256 `1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Frontend sigue en `feature/validaciones-datos-institucionales`,
`e88589635a8269c50ee0f53fccf157f0d0985034`, con su `?? e2e-selenium/` previo.
Código, SQL, pruebas, POM, notas B1–B5 y cierres históricos quedan intactos.
Entrega en PR backend **borrador** hacia `develop`, sin merge, despliegue,
activación multiinstitucional ni inicio de T12.
