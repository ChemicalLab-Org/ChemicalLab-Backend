# T11-D — Revisión final de la preparación del modelo institucional

14 de octubre de 2026, America/Lima.

## 1. Dictamen y alcance

**Preparación cerrada y apta para iniciar T12.** La revisión conjunta de B1–B11
no identifica faltantes persistentes o de ensayo que bloqueen P01–P06 ni
contradicciones que impidan adoptar sus contratos. Este dictamen cierra la
**preparación técnica de etapa 3**, sobre la base indicada; no declara cumplidos
íntegramente A01–A17 ni autoriza adopción, corte o servicio multiinstitucional.
T12 no se implementa en esta entrega.

La base local y remota de `ChemicalLab-Org/ChemicalLab-Backend:develop` coincide
en `68b4e95432cdadf99c237d5c3c2aeb65036a87fc` (**M**, merge B11/#78).
Se comprobó mediante `git ls-remote origin refs/heads/develop`, además de HEAD,
estado y padres locales. Checkout inicialmente limpio; rama documental
`docs/t11d-cierre-preparacion-modelo`. No hay AGENTS.md aplicables en el backend,
sus ancestros ni las revisiones consultadas; las instrucciones del proyecto de
vídeo quedan fuera del alcance. Se preserva el trabajo de los demás checkouts.

Leídos T11-A, T11-C, las once notas B1–B11, sus cierres correspondientes en
`Documentos/Seguridad/` y las revisiones locales pertinentes. Se contrastaron
servicios, repositorios, SQL y cuerpos de pruebas: el cierre no se deduce de los
resúmenes. Se conserva el dictamen histórico T11-C en su base B1–B5, sus
atribuciones y las notas/cierres sucesivos. Solo se actualizan matrices y
conclusiones vigentes de T11-A/T11-C.

## 2. Evidencia y atribución

`J/` = `src/main/java/com/morales/chemicallab/`; `T/` =
`src/test/java/com/morales/chemicallab/security/`; `S/` =
`src/main/resources/db/expansion/`. E1–E11 fijan el **SHA de ejecución original**,
no el merge. Los nombres de objetos/métodos permiten comprobar la fuente con
`git show <SHA>:<ruta>`. El código contrastado en M, incluidos SQL, pruebas y POM,
es idéntico al de E11; los cambios posteriores a E11 son documentales.

| ID | Nota y SHA probado exacto | Ejecución completa histórica reutilizada |
| --- | --- | --- |
| E1 | [B1](11b1-instituciones-membresias.md), `34547cfebcb8cf29ba951cb6dc3797f0cb44ee71` | 54 suites / 611 pruebas / 0 fallos, errores u omisiones |
| E2 | [B2 corregida](11b2-clases-matriculas.md), `827bb8c9554718efb799ac6edd4811a93cad73f1` | 55 / 644 / 0–0–0 |
| E3 | [B3 corregida](11b3-recursos-intentos.md), `e02c7acfe5dd796f93d33825492a2208169adc03` | 56 / 675 / 0–0–0 |
| E4 | [B4 corregida](11b4-contexto-pizarras-actividad.md), `9c60faaa4dd50aa9c34e65a7c44da1f5b5cd0f58` | 58 / 714 / 0–0–0 |
| E5 | [B5](11b5-identidad-codigos.md), `f3f633766ece835e0bd37cc018505f7dc89e3e50` | 59 / 739 / 0–0–0 |
| E6 | [B6](11b6-docencia-delegaciones.md), `b99a3be4b230c14095e6429ffea42557477c9142` | 60 / 765 / 0–0–0 |
| E7 | [B7](11b7-identificadores-acceso.md), `edc8bc3a9adf8665f2881952f1215a3eb614d884` | 61 / 795 / 0–0–0 |
| E8 | [B8](11b8-revisiones-academicas.md), `6893c2743daa1e18491768b91db56e72543ddd40` | 62 / 829 / 0–0–0 |
| E9 | [B9](11b9-cuotas-institucionales.md), `d666635666efe749250acaa2a98a0043c2d2fefa` | 63 / 849 / 0–0–0 |
| E10 | [B10](11b10-conciliacion-contextos.md), `578837a6ebdf19f5c3b8c88b5f9b9060dad26d3b` | 64 / 868 / 0–0–0 |
| E11 | [B11](11b11-lotes-recuperacion.md), `0152f851761b56ecd6078cc04d248ae4c0bc1117` | **65 / 882 / 0–0–0**, clean package, BUILD SUCCESS y JAR; 14 casos P06 incluidos |

Las filas son ejecuciones distintas, **no se suman**. E11 incluye regresiones de
los bloques anteriores, sin trasladar a E11 la atribución de sus ensayos nativos
o revisiones adicionales. Las selecciones e iteraciones fallidas conservan sus
registros originales; tampoco se suman a E11. Entorno acreditado: Windows,
JDK 17.0.12, PostgreSQL 18.4 desechable y perfil test; E11 usó Maven 3.9.15 offline.
No acredita Linux, nueva SCA, datos escolares o capacidad productiva.

**R11**, separado de E11: siete comprobaciones aprobadas registradas en
`.codex-work/t11b11-review/revision.md`, `review-results.json` y `review.log`,
sobre HEAD `d28841e51e240de28397e28224b57b93860f8ac3`, con código E11:
pausa/reanudación desde otra JVM, muerte antes de commit, commit sin respuesta y
consulta/reintento, dos ejecutores/un SUCCESS, rollback por auditoría, rollback
del sobre posterior a escritura y recuperación física/WAL posterior al respaldo.
M tiene el mismo árbol que ese HEAD. R11 no constituye siete pruebas adicionales
al total de 882 ni una ejecución sobre M.

T11-D volvió a contar directamente los XML de
`.codex-work/t11b11-evidence/verified-reports.zip`: 65 suites y 882/0/0/0.
SHA-256 del ZIP: `ec3c4df576ed95e990b55d857d53a52374dd1e6596587bf9e2fbc1794f10a302`.
Se reconfirmó el JAR archivado:
`cd2bb6cc3ff58e6d8c887f465f6c20815a6186e9b3a52961a53fdf3699676c2e`;
sus 14 SQL coinciden con las fuentes, normalizando solo CRLF/LF.
Se cotejaron los siete nombres/resultados del log R11, SHA-256
`00ba70a6a910003f1c6e3cfd69b01194107502ddc7c447d6e7595c3b273150fb`.
Estas son comprobaciones de archivos existentes, no ejecuciones de aplicación.

La [cobertura E10](t11b10/coverage.json) conserva 113 fuentes y 782/782 claves
ficticias, ninguna omitida. La [validación E11](t11b11/validation.json) y
[recuperación E11](t11b11/recovery.json) acreditan 128 tablas y 75 secuencias:
firmas de tablas exactas; nueve secuencias avanzan por prelogging WAL, sin
retroceso/reutilización y con `is_called` conservado. No se exige igualdad de
`last_value`, no se usa `setval` y no se renumeran intentos. R11 conserva su
recuperación independiente en `new-recovery-checks.json`; ninguna se reejecutó.

## 3. Matriz conjunta de contratos y dependencias

Todas las referencias de código de esta matriz se contrastaron en **M/E11**;
E1–E10 y R11 añaden las comprobaciones históricas atribuidas en §2.

| Área conjunta | Contrato contrastado en código/SQL/prueba | Resultado y límite de adopción |
| --- | --- | --- |
| Identidad, membresías y roles | `J/security/AccountSessionService.java:authenticate/revalidate`, `J/entity/Role.java`; `S/t11b1-v1.sql`, `S/t11b5-v1.sql:t11b5_platform_role_grants/t11b5_academic_codes`, `S/t11b7-v1.sql:t11b7_claims`; `T/InstitutionPersistenceDbTest.java`, `T/IdentityPreparationDbTest.java`, `T/GlobalAccessIdentifierDbTest.java` (E1/E5/E7) | Cuenta global estable, membresía única por colegio y grants separados. ADMIN_INSTITUCION no es ADMINISTRADOR. Sesiones/rol legacy siguen siendo autoridad actual; ACTIVE/PREPARED persistidos no constituyen permisos T12. Código F05 y login son contratos distintos |
| Clase, matrícula, docencia y biblioteca | `S/t11b2-v2.sql`, `S/t11b6-v1.sql:t11b6_teaching_slots/t11b6_resource_grants`; `J/service/AcademicMaintenanceService.java:replaceResponsible`, `DelegationMaintenanceService.java:requiredActor`; `T/DelegationPersistenceDbTest.java:replacementPreservesIndependentCoTeachersAuthorsEnrollmentAndAccounts/samePersonCannotHoldOverlappingFunctionsInEitherDirection` (E2/E6) | B6 añade CO_DOCENTE separado sin cambiar el CHECK RESPONSABLE B2; reserva común arbitra funciones/solapamientos. FKs y procedencia administrativa tipadas. Reemplazo no transfiere autor. Grants EDIT_DRAFT/PUBLISH/ASSIGN independientes; acceso efectivo y flujo T13 pendientes |
| Recursos, revisiones, entregas y T05/F09 | `S/t11b3-v1.sql:t11b3_account_attempt_budgets/t11b3_review_closed`, `S/t11b8-v1.sql:t11b8_budget_guard/t11b8_attempt_guard`; `J/service/EvaluationService.java:startAttempt`, `EvaluationAccessLock.java`, `AcademicRevisionMaintenanceService.java:attachLegacy/reserveFixtureAttempt`; `T/AcademicRevisionDbTest.java:legacyBudgetsRemainObservationsAndLegacyActiveLocksAreAuthoritative/severalClassesAndRevisionsConsumeOneF09Budget` (E3/E8) | LEGACY enlaza su raíz B3 exacta, cuenta todas las filas y no admite presupuesto NEW. F09 solo crea raíz distinta por creación académica explícita; fixture_attempts no es motor vivo. Revisión/entrega no reinicia saldo, activo, número, plazo o cierre. T12 conserva una sola autoridad y prueba el corte |
| Pizarras, actividad y auditoría | `S/t11b4-v2.sql:event_parent/reference_guard`, `S/t11b10-v1.sql:t11p05_fact_parent/t11p05_validate/t11b10_current`; `T/BoardAuditParentTransitionDbTest.java`, `T/ContextReconciliationDbTest.java:typedUsageAndAuditRequireSameCurrentParentAndPreserveOriginalFacts` (E4/E10) | Padre tipado exige mismo contexto vigente, incluso ADMIN sin boardId; matrícula no fabrica participante/autor. B10 corrige aditivamente vínculo B4 erróneo, conserva el original y deja STALE hechos dependientes si cambia el padre. No adopta queries ni STOMP |
| Código académico e identificadores | `S/t11b5-v1.sql:t11b5_academic_codes/t11b5_binding_observations`, `S/t11b7-v1.sql:t11b7_identifier_guard/t11b7_username_complete`; `T/GlobalAccessIdentifierDbTest.java`; `J/service/AuthService.java`, `J/security/AuthenticationLimits.java` (E5/E7) | Reserva escolar permanente por institución/cuenta; username global anterior reservado entre tipos, email reutilizable solo tras revocación/prueba nueva/revisión. B5 REVIEW_ASCII no cambia la igualdad B7 ni login legacy. T12 coordina resolver y límites T04; T14 verifica externamente |
| Medición, cuotas y F08 | `S/t11b9-v1.sql:t11b9_measure/t11b9_observation`, `S/t11b10-v1.sql:t11p05_privilege/t11p05_fresh/t11p05_measure`; `J/service/InstitutionStorageMaintenanceService.java:fresh`, `ContextReconciliationMaintenanceService.java:approve`; `T/InstitutionStorageDbTest.java:storedCopiesCountAndClassReferencesDoNotMultiplyPayload`, `T/ContextReconciliationDbTest.java:resourceCorrectionCannotTransferOldSchoolLibraryAuthority` (E9/E10) | 67 fuentes de bytes no sustituyen censo de 113. Medición sucesora no sobrescribe B9; referencias no multiplican payload y copias/historia sí cobran. Dimensiones ACCOUNT/SYSTEM/institucional/CONTROL no se suman. Dos aprobadores F08 distintos sobre fotografía exacta; corrección de recurso no traslada grants B6. `live_authoritative=false` y `access_authorized=false` |
| Lotes, checkpoints y recuperación | `J/service/TransitionBatchMaintenanceService.java:internal/command`; `S/t11b11-v1.sql:t11p06_execute/t11p06_command/t11p06_after`; `T/TransitionBatchDbTest.java:42–184`, `T/TransitionWalRecoveryFixture.java:exercise/assertRecovered/coverage` (E11/R11) | Una unidad = agregado B10 íntegro, checkpoint/negocio/medición/cuota/SUCCESS mismo XID. Epoch y lock transaccional, consulta tras coordinar ante commit desconocido, STALE durable. Respaldo físico + WAL incluye escrituras sintéticas ajenas a P06; objetivo alcanzado, replay/restart sin duplicación. No recuperación real ni archivos externos |
| Cadena, certificadores y conservación | Catorce archivos `S/t11b*.sql`; `S/t11b11-v1.sql` contiene íntegro el bloque `DO $b10$…END $b10$` de B10; `T/TransitionBatchDbTest.java:successorRepeatsWithoutTouchingFrozenLedgersAndRejectsDrift`; búsqueda de dependencias en `J/` y `J/config/SecurityConfig.java` (E11) | B11 sucede a B10 sin reescribir trece SQL históricos/ledgers. B6 normaliza exclusivamente su trigger nuevo al certificar B2 y lo firma en B6. `t11p05_*`/`t11p06_*` evitan patrón B1; control t06 separado conserva censo B10. Mantenedores sin consumidores externos, salvo composición interna B9→B8. No arranque/DDL automático ni nuevas autoridades |

Cadena de schema nuevo: B1 v1 → B2 v1/v2 → B3 v1/v2 → B4 v1/v2 → B5 v1 →
B6 v1 → B7 v1 → B8 v1 → B9 v1 → B10 v1 → **B11 v1**. En un predecesor
certificado se aplica únicamente el sucesor faltante, por archivo transaccional.
**B11 es el certificador vigente e incorpora B10 congelado**. El rechazo de un
certificador antiguo que no admite un sucesor es deliberado; no justifica
reescribir fingerprints, omitir guards o reparar con Hibernate.

El ensayo P06 admite 1–100 propuestas por plan, un agregado de hasta 256 PK/
2 MiB por transacción y un fixture completo de hasta 20 000 filas/64 MiB JSON/
256 MiB físicos entre ambos schemas; límites reducibles. CONTROL P06 tiene
100 000 filas/32 MiB independientes. Servicio: 60 s de transacción, 55 s de
statement y 3 s de adquisición. Preflight y comprobación posterior revierten
el agregado entero si excede el sobre. **B10 continúa escaneando el fixture
completo** y los locks serializan sus escritores; no se acredita paginación,
coste constante, memoria máxima de 64 MiB o backfill arbitrario.

Este límite no es un bloqueo nuevo de preparación A09/P06: el protocolo y la
recuperación exigidos están ensayados bajo un sobre explícito. Ampliarlo a datos
reales requiere ventana, cobertura y medición operativas, ya asignadas a
P08/P12 y T12. No se transforma esa tarea en otro requisito de etapa 3.

## 4. Balance P01–P06

“Cerrado” en esta tabla significa exclusivamente preparación persistente/ensayo
revisado. No significa capacidad externa disponible. No queda implementación
de P01–P06 necesaria para el dictamen; sus adopciones posteriores sí quedan abiertas.

| Criterio de preparación | Parte demostrada y evidencia archivo/SHA | Límites y trabajo restante / responsable |
| --- | --- | --- |
| P01 — cerrado | Relaciones, facultades, vigencias, reservas e historia B6; `S/t11b6-v1.sql`, `T/DelegationPersistenceDbTest.java`, E6; contraste conjunto E11 con corrección B10 de grants antiguos | **T12/P09:** autorización efectiva por institución/clase/facultad, F04; **T13/P10:** sustitución/co-docencia externa. No calificación por administrador solo ni por grant de biblioteca |
| P02 — cerrado | Ciclo USERNAME/EMAIL, prueba ficticia vinculada, revisión/revocación/reutilización y claims entre tipos; `S/t11b7-v1.sql`, `T/GlobalAccessIdentifierDbTest.java`, E7; código conservado E11 | **T12/P09:** adoptar resolver y coordinar todos sus lectores/writers con T02/T04; **T14/P11:** correo real/recuperación. Comparación exacta; no adoptar automáticamente PREPARED |
| P03 — cerrado | Borrador distinto/publicación inmutable/entrega exacta/historia y raíz F09; `S/t11b8-v1.sql`, `T/AcademicRevisionDbTest.java`, E8; conservación conjunta B10/P06 en E10/E11 | **T12/P09:** autorización y único presupuesto vivo con corte coordinado; **T13/P10:** flujos académicos. La simulación no acredita motor/calificación institucional reales |
| P04 — cerrado | Medición por PK/fuentes, exceso conservado, deltas reales, conciliación sin reparación y rollback; `S/t11b9-v1.sql`, `T/InstitutionStorageDbTest.java`, E9; sucesores E10/E11 | **T12/P09:** escritores coordinados/contador vigente; **operación/P12:** máximos aprobados, físico/WAL/rendimiento/SEC-13. Medición preparada no es saldo ni capacidad productiva |
| P05 — cerrado | Censo ficticio exhaustivo, aprobaciones F08 distintas, versiones aditivas y última proyección sin fallback; `S/t11b10-v1.sql`, `T/ContextReconciliationDbTest.java`, E10 y `t11b10/coverage.json`; consumo/raíces/historia conservados E11 | **Operación/P08 + responsables F08:** inventario/mapas reales y procedencia; **T12/P09:** adoptar contexto con permisos. Un run no garantiza cobertura continua; GLOBAL no es público |
| P06 — cerrado | Plan/checkpoint/reanudación/commit desconocido/caída y WAL posterior; `S/t11b11-v1.sql`, `T/TransitionBatchDbTest.java`, `T/TransitionWalRecoveryFixture.java`, E11/R11 | **T12 + operación/P08/P12:** escritores, almacenamiento externo, backup/WAL/RPO/RTO y ventana reales. Solo sobre ficticio acotado; no corte ni DDL escolar |

## 5. Balance A01–A17

Se mantienen los criterios originales de T11-A §7.1. **Ninguna fila declara
cumplido el criterio completo**: distingue parte persistente/fixture demostrada
de acceso, continuidad y operación. Referencias E* fijadas en §2; los mismos
archivos fueron contrastados en M/E11. Las tareas posteriores conservan sus
responsables originales, sin requerirlas para cerrar preparación.

| Criterio | Parte demostrada / evidencia archivo y SHA | Límite y restante con responsable |
| --- | --- | --- |
| A01 coincidencias — demostrado acotado | `T/AcademicPersistenceDbTest.java`, `IdentityPreparationDbTest.java`, `GlobalAccessIdentifierDbTest.java` (E2/E5/E7): códigos A/B/períodos y rechazo local/global entre tipos | **T12:** resolver/colisiones reales de acceso; **T14:** verificación externa; **P08/F08:** mapas reales. Código escolar no autentica |
| A02 membresías/roles — demostrado acotado | `T/InstitutionPersistenceDbTest.java`, `IdentityPreparationDbTest.java` (E1/E5); B6 conserva multirol y privilegio global independiente | **T12:** componer estados/ventanas/versiones y F04 con contexto del servidor. Grants reales F08/operación; no promoción por membresía |
| A03 integridad — preparación demostrada, criterio parcial | `S/t11b6-v1.sql`, `S/t11b8-v1.sql`, `T/DelegationPersistenceDbTest.java`, `AcademicRevisionDbTest.java` (E6/E8): cruces, fechas y carreras; `ContextReconciliationDbTest.java` (E10) enlaza padres/cuenta/episodio en corrección | **T12:** autorización bilateral de todos los writers/readers; **T13:** continuidad. Integridad SQL no es autorización de sesión |
| A04 cuenta — demostrado acotado | `T/InstitutionPersistenceDbTest.java:repeatExpansionPreservesLegacyAccountsSessionsAndNewAuthoritiesHaveNoEffect` (E1), `GlobalAccessIdentifierDbTest.java` (E7), 25 fuentes conservadas por STEP E11 | **T12:** cortar aprovisionamiento/edición legacy que liga username/código; **P08:** conservación en backfill real. No fusión de personas |
| A05 evidencia — demostrado para fuentes del fixture | `T/ResourceReconciliationDbTest.java`, `BoardActivityReconciliationDbTest.java` (E3/E4); `ContextReconciliationDbTest.java` (E10), `TransitionWalRecoveryFixture.java` (E11): 128 tablas exactas con resultados/bytes/autoría/historia | **P08/operación:** inventario y recuperación reales, históricos irrecuperables y almacenamiento externo. Un manifiesto de tablas no fabrica procedencia |
| A06 reemplazo — parte persistente demostrada | `T/AcademicPersistenceDbTest.java` (E2), `DelegationPersistenceDbTest.java:replacementPreservesIndependentCoTeachersAuthorsEnrollmentAndAccounts/replacementByExistingCoTeacherFailsAndRollsBackPredecessorHistory` (E6) | **T12:** facultades y revocación efectiva; **T13:** sustitución durante actividad. No acredita acceso saliente/reemplazo |
| A07 traslado/simultaneidad — parcial | `T/ResourceReconciliationDbTest.java:readmissionDoesNotDuplicateRecipientsOrRefundA16` (E3), `BoardActivityReconciliationDbTest.java:readmissionDoesNotRewriteOriginalParticipantEpisode` (E4), `AcademicRevisionDbTest.java` (E8) | **T13:** traslado con intento abierto, baja/readmisión y F03; **T12:** aislamiento entre pertenencias. No mueve hechos por perfil actual |
| A08 mapa/ambigüedad — preparación demostrada | `T/ContextReconciliationDbTest.java:completeIndependentPkCensusIncludesAccountsProfilesGroupsAndUnassignedAttempts/uncertainSuccessorAndStaleLatestNeverFallbackToEarlierValidVersion/b4WrongHistoricalLinkIsPreservedWhileBoardAndParticipantsGainNewVersion` (E10); cobertura 782/782 | **P08 + responsables F08:** censo/mapa real por PK y dos aprobaciones reales; **T12:** rechazo de contexto incierto. Ensayo no acredita colegio/permiso real |
| A09 idempotencia/recuperación — preparación demostrada | `T/TransitionBatchDbTest.java`, `TransitionWalRecoveryFixture.java` (E11/R11): caída, coordinación, checkpoint/SUCCESS mismo XID, fuentes/F08/antecedentes STALE, recuperación posterior y replay | **Operación/P08/P12 + T12:** ventana/escritores/almacenamiento/WAL/RPO/RTO reales. Sobre finito, censo no paginado; no recuperación de sistemas externos |
| A10 cuotas/auditoría — preparación demostrada | `T/InstitutionStorageDbTest.java` (E9), `ContextReconciliationDbTest.java:auditAndQuotaFailuresRollbackVersionsMeasurementsEvidenceAndSuccess` (E10), `TransitionBatchDbTest.java:auditFailureRollsBackBusinessMeasurementCheckpointAndSuccess/quotaFailureRollsBackBusinessCheckpointAndAudit` (E11) | **T12:** sincronización/corte y atribución al hecho; **operación/P12:** límites, permisos/retención/SEC-13. Contadores preparados no autoritativos; exceso e historia no se recortan |
| A11 compatibilidad cerrada — parcial | `J/config/SecurityConfig.java`, `J/security/AccountSessionService.java`, búsqueda de consumidores `J/` (M/E11); pruebas de autoridades legacy E1/E2 y rechazo de mantenedor E8/E11 | No endpoint nuevo ni autoridad desde esquema preparado. **T12/P09:** ámbito único legacy aprobado y rechazo integral de ambigüedad, REST/STOMP/archivos/queries/jobs. No activación por esta entrega |
| A12 regresión/revisión — parte técnica demostrada | E1–E11, R11 separados; XML/JAR y fuentes cotejados en T11-D. `T/TransitionBatchDbTest.java:successorRepeatsWithoutTouchingFrozenLedgersAndRejectsDrift` (E11) y pruebas de dos procesos | Revisión conjunta concluida; **validación/operación/P13:** Linux/JDK17/PostgreSQL Linux, SCA/runtime/VER-01. **T12/T13:** pruebas de sus flujos; no suite nueva atribuida a M/T11-D |
| A13 agotado — demostrado acotado | `T/ResourceReconciliationDbTest.java:exhaustedInactiveAndUnassignedAttemptsReserveOneBudgetA13` (E3); `ContextReconciliationDbTest.java:exhaustedAndActiveLegacyBudgetClosesNoWindowAndRefundsNoOpportunity` (E10); conservación WAL E11 | used=2/2, mismo consumo, saldo cero. **T12:** todos los inicios autorizados deben usar una autoridad común; no saldo desde snapshot |
| A14 entregas/raíz/carrera — demostrado acotado | `T/ResourceReconciliationDbTest.java:multipleAssignmentsAndTechnicalRevisionsShareRootA14/legacyLastOpportunityRaceWithPreparedDeliveriesStillHasOneWinner` (E3); F09/clases/revisiones y carrera `AcademicRevisionDbTest.java` (E8) | Último inicio legacy y cierre compartidos ensayados; F09 es simulación. **T12:** carrera/admisión/exposición institucionales y corte, sin repartir consumo por entrega |
| A15 activo previo — demostrado acotado | `T/ResourceReconciliationDbTest.java:previousActiveContextIsFixedAtStartAndUnassignedHasNoInventedEpisodeA15/consistentUnassignedActiveIsReservedWithoutInventingContext` (E3); E10/E11 preservan activo sin assignment y resultados | **T12:** finalización/vencimiento reales y bloqueo entre entregas; **T13:** baja/continuidad; **P08:** resolver conflicto real. No fabricar episodio ni liberar consumo |
| A16 readmisión/clases — demostrado acotado | `T/ResourceReconciliationDbTest.java:readmissionDoesNotDuplicateRecipientsOrRefundA16` (E3), `AcademicRevisionDbTest.java:readmissionKeepsOneRecipientHistoryAndConsumedBudget/twoClassesCompeteForLastF09OpportunityAcrossProcesses` (E8); corrección F09 E10 | No escenario externo integral de sustituto/readmisión/carrera. **T12:** gate bilateral; **T13:** continuidad. Membresía/clase/revisión no restablece saldo |
| A17 cierre/conciliación — demostrado acotado | `T/ResourceReconciliationDbTest.java:numberingConflictsAndTwoActivesRemainReservedA17` (E3), cierre y no reclasificación de raíz en `AcademicRevisionDbTest.java` (E8); correcciones/recuperación E10/E11 | **T12:** cierre/exposición T05 y corte; **T13:** F03 bajo las mismas condiciones; **P08:** conciliación real. Futuras/vacías/sin dueAt, nota no cerrada y conflicto no abren oportunidades |

## 6. Bloqueos y pendientes posteriores

**No hay bloqueos de preparación identificados en la base M.** No se propone
corrección mínima ni se implementan correcciones. Los límites anteriores son
explícitos y corresponden a contratos de ensayo o tareas posteriores ya asignadas.

| Trabajo pendiente | Responsable y puerta de salida |
| --- | --- |
| P07: atributos/renombrado académico/sedes e identificadores oficiales | Decisiones F01/F05 complementarias y flujos T13/T14; evolución persistente solo si una necesidad confirmada la requiere. No CRUD opcional como requisito nuevo de etapa 3 |
| P08: instituciones, responsables, censo/mapas reales y zona histórica | Operación con administradores de plataforma/destino F08 distintos, evidencia y ventana autorizadas. Mapas fuera de repositorio/PR/logs públicos; sin colegio por defecto |
| P09: principal/contexto, aislamiento integral, adopción/corte y una autoridad de intentos | T12, gates bilaterales T11-A §7.2 y contratos §7 de este cierre. Un contexto preparado no concede acceso |
| P10: sustitución/baja/traslado/readmisión y lectura propia F03 | T13 sobre aislamiento T12, respetando T05; capturas compartidas/suspensión requieren sus políticas complementarias |
| P11: altas/vinculación/invitaciones, correo/recuperación externa y menores | T14/F06; código académico, invitación y código de clase no son credenciales ni permisos directos |
| P12/P13: cuotas reales/medición sostenida, WAL/backup/almacenamiento externo/RPO/RTO, privilegios/TLS/reloj, retención, runtime/Linux/SCA | Operación/validación y T15–T17; **SEC-13 y VER-01 abiertos**. Retención apagada. El sobre ficticio no certifica capacidad o servicio |
| Privacidad, gobierno y requisitos operativos | Responsables de privacidad/operación conforme auditoría y T15–T17 antes del alcance real que corresponda; no se redactan textos legales ni se certifica cumplimiento por fixtures |

## 7. Contratos que T12 deberá adoptar y primer bloque acotado

T12 toma como entradas las relaciones/ventanas B1/B2/B6, identidad/códigos B5/B7,
revisiones/entregas B3/B8, contextos B4/B10 y mediciones/operaciones B9–B11.
Mantiene la separación de T11-C §6 para todos los writers actuales:

1. **Principal global y contexto por petición/conexión.** T02 valida cuenta/sesión/
   versión; la institución solicitada es un selector que el servidor comprueba.
   Componer institución/membresía/grants/clase/episodio/asignación, vigencias y
   versiones con tiempo servidor. Ausencia de DB o contradicción no autoriza.
   Revocación local corta A conservando B; global corta ambas. ADMIN_INSTITUCION
   no obtiene rutas globales ni poder unilateral de reset de cuenta compartida.
2. **Procedencia y autorización independientes.** Usar última resolución vigente
   B10 y padre tipado; UNRESOLVED/CONFLICT/STALE/omitido se rechazan, sin fallback
   B3/B4 o primera membresía. GLOBAL no implica público. DOCENTE vigente + clase
   asignada exige F04; veto de autocalificación y nota cerrada. Biblioteca requiere
   la facultad exacta B6 y su ámbito vigente, sin heredar grants del colegio anterior.
3. **Una sola autoridad de intentos.** Mientras existan escritores legacy, mandan
   evaluación/alumno y su lock T05/T10. B3 es observación; B8 fixture_attempts no
   sustituye ese motor. Para adopción, contar todas las filas por raíz/cuenta,
   conservar números/único activo/máximo/plazo/cierre y vínculo único; cero
   diferencias antes de corte coordinado de todos los escritores. Ni entrega,
   revisión, readmisión ni corrección crea otro saldo. Nueva evaluación F09 exige
   acto académico explícito, nunca clon técnico. Finalizar libera activo, no consumo.
4. **Escritura y lectura coherentes.** Publicación congela revisión y entrega exacta;
   negocio/versión/historia/deltas/cuotas/SUCCESS confirman juntos. Listados,
   counts/agregados, respuestas, revisión, archivos/capturas, uso/auditoría,
   exportaciones/jobs/cachés y STOMP entrante/saliente/pasivo usan el mismo ámbito.
   T06 y locks/revisión/afterCommit T03/T10 se conservan; métricas opcionales no
   sustituyen SUCCESS T08. Identidad B7 y límites T04 se adoptan coordinadamente,
   preservando cuenta y comparación acordada, sin código escolar como login desnudo.
5. **Transición y recuperación verificadas.** Mantener operaciones/checkpoints y
   guardas; ante resultado desconocido consultar evidencia confirmada después de
   coordinación, nunca inferir rollback desde ausencia mientras otro writer actúa.
   Inventariar escritores/almacenamiento real y WAL antes de corte; tras tenants
   activos, recuperación compatible con aislamiento o acceso deshabilitado, sin
   volver al binario global legacy ni restaurar un respaldo viejo sobre escrituras nuevas.

**Propuesta de T12-A: resolución y decisión interna de acceso a una entrega de
evaluación existente, exclusivamente con fixtures.** Acotar el primer bloque a
un servicio interno de consulta/decisión que reciba principal T02 y IDs solicitados,
resuelva institución/membresía/clase/entrega/revisión/cuenta y contexto B10 vigente,
y aplique facultades temporales/F04 y ocultación T05. No conectarlo a controlador,
login, STOMP, jobs ni otros consumidores; no cambiar el motor de intentos,
calificar, crear entregas o habilitar flujos externos. La decisión no devuelve
datos de otros ámbitos ni trata un presupuesto preparado como saldo vivo.

Salida verificable de ese bloque: pruebas A→B y B→A con cuenta común, roles
combinados, IDs/clase/institución manipulados, membresía suspendida/terminada,
grant vencido/revocado, falta de sesión/DB, contexto incierto o padre STALE y
revisión no publicada. Incluir ADMIN_INSTITUCION solo, DOCENTE sin asignación,
intento propio y nota cerrada; ninguna decisión revela corrección bloqueada por
otra asignación futura/vacía/sin dueAt. Las pruebas comprueban también ausencia
de nuevas filas de intento, consumo, historia o SUCCESS de negocio. Se mantiene
el writer legacy como única autoridad hasta otro bloque de adopción coordinada.
Esta propuesta delimita el siguiente trabajo; **T11-D no lo ejecuta**.

## 8. Verificación documental y conservación

No se ejecutaron suites, builds, DDL, backfill ni comprobaciones ficticias nuevas:
las incertidumbres se resolvieron con fuentes y evidencia archivada. Verificación
local reproducible: `.codex-work/t11d-evidence/verify.py`, `before.json` y
`verification.json`, fuera del PR. Coteja XML/JAR/SQL, árbol/código E11/R11,
certificador B10 congelado, archivos protegidos y demás worktrees.

Manifiesto inicial de **377 archivos**: documentos/JSON previos pertinentes,
documentos de Taller, frontend y revisiones locales. Se reconfirman los **43
archivos protegidos** históricos y **16 E2E** sin diferencias; los 14 SQL
actuales se conservan. Auditoría original SHA-256:
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`.
Frontend conserva `feature/validaciones-datos-institucionales`,
`e88589635a8269c50ee0f53fccf157f0d0985034` y su `?? e2e-selenium/` previo.
Los otros **24 worktrees** conservan HEAD, rama y estado. El diff autorizado
contiene únicamente este cierre y las matrices/conclusiones vigentes T11-A/T11-C;
criterios, decisiones y conclusiones históricas permanecen íntegros.

Entrega mediante commit y PR documental **en borrador hacia develop**, adjunta
al chat y detenida para revisión. Sin merge, implementación funcional/T12,
despliegue, datos escolares, DDL/backfill real, rotaciones o activación
multiinstitucional. El dictamen favorable de preparación no cierra P08, aislamiento
efectivo, SEC-13, VER-01, privacidad ni requisitos de operación real.
