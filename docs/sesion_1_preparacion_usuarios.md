# Sesión 1 — Preparación del Módulo de Usuarios

**Fecha:** 2026-05-22  
**Rama activa:** `feature/users-domain-setup`  
**Objetivo:** Revisar el estado actual del backend y preparar la estructura para desarrollar el módulo de usuarios, roles y autenticación.

---

## 1. Estado Actual del Backend

El proyecto es una API REST construida con **Spring Boot 4.0.6** y **Java 17**. Actualmente contiene un motor químico funcional que genera fórmulas de compuestos (óxidos, hidróxidos, ácidos, sales y oxisales). El motor no depende de base de datos ni de seguridad real.

### Paquetes existentes

```
com.morales.chemicallab
├── config/
│   └── SecurityConfig.java          ← seguridad en modo desarrollo (permisivo)
├── controller/
│   ├── HealthController.java        ← GET /api/health (público)
│   └── ChemicalEngineController.java← POST /api/chemistry/** (público)
├── dto/
│   ├── AcidRequest.java
│   ├── CompoundResponse.java
│   ├── ElementCompoundRequest.java
│   ├── OxisaltRequest.java
│   └── SaltRequest.java
├── exception/
│   └── GlobalExceptionHandler.java  ← maneja IllegalArgumentException y validaciones
└── service/
    └── ChemicalEngineService.java   ← lógica del motor químico, sin base de datos
```

### Archivos de configuración

| Archivo | Estado |
|---|---|
| `pom.xml` | Completo para el módulo actual; dependencias principales presentes |
| `application.properties` | Configuración PostgreSQL válida; falta optimización menor |
| `data.sql` | Archivo vacío (reservado para datos semilla) |

---

## 2. Dependencias Verificadas

### Dependencias presentes (producción)

| Dependencia | Artifact | Estado |
|---|---|---|
| Spring Web MVC | `spring-boot-starter-webmvc` | ✅ Presente |
| Spring Data JPA | `spring-boot-starter-data-jpa` | ✅ Presente |
| Spring Security | `spring-boot-starter-security` | ✅ Presente |
| PostgreSQL Driver | `postgresql` (runtime) | ✅ Presente |
| Validation (Bean Validation) | `spring-boot-starter-validation` | ✅ Presente |
| Lombok | `org.projectlombok:lombok` | ✅ Presente |
| DevTools | `spring-boot-devtools` (runtime) | ✅ Presente |
| Actuator | `spring-boot-starter-actuator` | ✅ Presente |
| SpringDoc OpenAPI | `springdoc-openapi-starter-webmvc-ui:3.0.2` | ✅ Presente |

### Dependencias de prueba

| Artifact | Observación |
|---|---|
| `spring-boot-starter-data-jpa-test` | Nomenclatura no estándar en Spring Boot 3.x; posiblemente válida en 4.x |
| `spring-boot-starter-security-test` | Ídem — equivalente a `spring-security-test` en versiones anteriores |
| `spring-boot-starter-validation-test` | Ídem |
| `spring-boot-starter-webmvc-test` | Ídem |

> **Acción recomendada:** Si al ejecutar `./mvnw test` alguna de estas dependencias de prueba no resuelve, reemplazarlas por `spring-boot-starter-test` (estándar) más `spring-security-test`.

### Dependencias que se agregarán en sesiones futuras

| Dependencia | Cuándo | Motivo |
|---|---|---|
| `jjwt-api`, `jjwt-impl`, `jjwt-jackson` (io.jsonwebtoken) | Sesión JWT | Generación y validación de tokens JWT |

No se agrega ninguna dependencia en esta sesión. Las existentes son suficientes para implementar usuarios, roles y autenticación básica (sin JWT).

---

## 3. Análisis de Archivos Críticos

### SecurityConfig.java — Estado actual

```java
// Configuración de desarrollo — no apta para producción
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/health").permitAll()
    .requestMatchers("/api/chemistry/**").permitAll()
    .anyRequest().authenticated()   // requiere autenticación pero...
)
.formLogin(form -> form.disable())  // ...el login por formulario está deshabilitado
.httpBasic(httpBasic -> httpBasic.disable()) // ...y el básico también
```

**Diagnóstico:** Es una configuración provisional para desarrollo. Cualquier endpoint fuera de `/api/health` y `/api/chemistry/**` retorna 401/403 sin ninguna forma de autenticarse. Falta:
- Bean `PasswordEncoder` (BCryptPasswordEncoder)
- Bean `UserDetailsService` (necesita entidad User)
- Configuración para el endpoint de login (`/api/auth/login`)
- Soporte para sesión stateless (requerido antes de JWT)

**Acción en Sesión 2:** Agregar `PasswordEncoder` bean. No modificar aún la cadena de filtros hasta tener `UserDetailsService`.

### GlobalExceptionHandler.java — Estado actual

Maneja dos tipos de errores:
- `IllegalArgumentException` → usado por el motor químico
- `MethodArgumentNotValidException` → errores de Bean Validation (`@Valid`)

**Diagnóstico:** Sirve directamente para el módulo de usuarios. Se extiende en Sesión 2 con:
- `EntityNotFoundException` → cuando no se encuentra un usuario
- `UsernameNotFoundException` → credenciales inválidas en login
- `DataIntegrityViolationException` → violación de unicidad (correo o código duplicado)
- Handler genérico 500 como red de seguridad

### application.properties — Estado actual

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/lab_quimico_db
spring.datasource.username=postgres
spring.datasource.password=admin
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jackson.time-zone=America/Lima
```

**Diagnóstico:** Configuración válida para desarrollo. Observaciones:
- `ddl-auto=update` es adecuado para desarrollo; cambiarlo a `validate` o `none` en producción.
- Falta `spring.jpa.open-in-view=false` (evita el anti-patrón Open Session in View en APIs REST).
- La zona horaria `America/Lima` es correcta para el contexto del proyecto.
- Contraseña en texto plano es aceptable solo en entorno local.

---

## 4. Estructura de Paquetes Recomendada

La siguiente estructura está preparada en esta sesión (paquetes vacíos marcados con `.gitkeep`):

```
com.morales.chemicallab
├── config/
│   └── SecurityConfig.java              ← existente; se amplía en Sesión 2
├── controller/
│   ├── HealthController.java            ← existente
│   ├── ChemicalEngineController.java    ← existente
│   └── [AuthController.java]            ← Sesión 2: POST /api/auth/login, /cambiar-contrasena
│   └── [UserController.java]            ← Sesión 3: CRUD usuarios (docente/admin)
├── dto/
│   ├── [request] — DTOs de entrada para usuarios y auth
│   └── [response] — DTOs de salida para usuarios y auth
├── entity/                              ← CREADO (vacío)
│   └── [Usuario.java]                   ← Sesión 2
│   └── [Rol.java]                       ← Sesión 2
├── exception/
│   └── GlobalExceptionHandler.java      ← existente; se amplía en Sesión 2
│   └── [excepciones personalizadas]     ← Sesión 2
├── repository/                          ← CREADO (vacío)
│   └── [UsuarioRepository.java]         ← Sesión 2
├── security/                            ← CREADO (vacío)
│   └── [UserDetailsServiceImpl.java]    ← Sesión 2
│   └── [JwtUtil.java]                   ← Sesión JWT
│   └── [JwtFilter.java]                 ← Sesión JWT
└── service/
    └── ChemicalEngineService.java        ← existente
    └── [UsuarioService.java]             ← Sesión 3
```

---

## 5. Decisiones Técnicas para el Módulo de Usuarios

### Roles del sistema

| Rol | Descripción |
|---|---|
| `ESTUDIANTE` | Accede con código generado + contraseña temporal |
| `DOCENTE` | Accede con usuario o correo + contraseña |
| `ADMINISTRADOR` | Accede con usuario o correo + contraseña; gestiona docentes |

### Modelo de autenticación (sin JWT por ahora)

- Autenticación mediante `UserDetailsService` + `PasswordEncoder` (BCrypt).
- Los endpoints de login retornarán el usuario autenticado y su rol (sin token por ahora).
- Se implementará cambio de contraseña obligatorio en el primer inicio de sesión (campo `primerAcceso: boolean` en la entidad `Usuario`).

### Diseño de la entidad Usuario

```
Usuario
├── id (Long, PK, autoincremental)
├── codigoOUsuario (String, único, no nulo)  ← código para estudiantes, usuario para docentes/admin
├── correo (String, único, nullable)          ← solo docentes y administradores
├── contrasena (String, no nulo)              ← cifrada con BCrypt
├── nombre (String)
├── apellido (String)
├── rol (Enum: ESTUDIANTE, DOCENTE, ADMINISTRADOR)
├── activo (boolean, default true)
├── primerAcceso (boolean, default true)      ← obliga cambio de contraseña
├── creadoEn (LocalDateTime)
└── actualizadoEn (LocalDateTime)
```

### Convenciones de código

- Comentarios en español cuando sean necesarios.
- Usar `record` de Java para DTOs de entrada/salida (ya establecido en el motor químico).
- Usar Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) en entidades JPA.
- Endpoints bajo `/api/auth/**` para autenticación.
- Endpoints bajo `/api/usuarios/**` para gestión de usuarios.

### Estrategia de permisos (SecurityConfig)

Antes de JWT, los endpoints de login estarán en `permitAll()`. Los endpoints de gestión estarán protegidos con `hasRole(...)`. La implementación stateless completa se hará cuando se integre JWT.

---

## 6. Coexistencia con el Motor Químico

El motor químico existente (`/api/chemistry/**`) es completamente independiente:
- No usa base de datos.
- No tiene entidades JPA.
- Su endpoint está mapeado a `permitAll()` en `SecurityConfig`.
- Los DTOs del motor químico no colisionan con los del módulo de usuarios.

**Conclusión:** El módulo de usuarios puede desarrollarse en paralelo sin afectar el motor químico.

---

## 7. Riesgos Técnicos Detectados

| # | Riesgo | Severidad | Mitigación |
|---|---|---|---|
| 1 | Las dependencias de prueba usan nomenclatura no estándar (`spring-boot-starter-*-test`) | Media | Ejecutar `./mvnw test` para validar que resuelven antes de escribir tests |
| 2 | `SecurityConfig` deshabilita login pero tiene `anyRequest().authenticated()` | Alta | Agregar el bean `PasswordEncoder` en Sesión 2 antes de cualquier prueba de autenticación |
| 3 | `ddl-auto=update` puede generar migraciones silenciosas en producción | Media | Planificar migración a Flyway o Liquibase antes del despliegue |
| 4 | Contraseña de base de datos en `application.properties` (texto plano) | Media | Usar variables de entorno o Spring Profiles para producción |
| 5 | Sin handler genérico 500 en `GlobalExceptionHandler` | Baja | Agregar en Sesión 2 junto con las excepciones personalizadas |
| 6 | `spring.jpa.open-in-view` no configurado (default `true`) | Baja | Agregar `spring.jpa.open-in-view=false` en Sesión 2 |

---

## 8. Archivos que se Crearán en las Siguientes Sesiones

### Sesión 2 — Entidades, repositorio y seguridad base

| Archivo | Tipo | Descripción |
|---|---|---|
| `entity/Usuario.java` | Entidad JPA | Modelo de usuario con roles y auditoría |
| `entity/Rol.java` | Enum | `ESTUDIANTE`, `DOCENTE`, `ADMINISTRADOR` |
| `repository/UsuarioRepository.java` | Interface JPA | Consultas por código, correo y rol |
| `security/UserDetailsServiceImpl.java` | Service | Carga usuario desde BD para Spring Security |
| `dto/LoginRequest.java` | Record DTO | Entrada para el login |
| `dto/LoginResponse.java` | Record DTO | Respuesta del login (usuario + rol) |
| `dto/CambiarContrasenaRequest.java` | Record DTO | Entrada para cambio de contraseña |
| `controller/AuthController.java` | Controller | `POST /api/auth/login`, `POST /api/auth/cambiar-contrasena` |
| `exception/UsuarioNoEncontradoException.java` | Exception | Lanzada cuando no existe el usuario |

### Sesión 3 — Gestión de usuarios (CRUD)

| Archivo | Tipo | Descripción |
|---|---|---|
| `dto/CrearUsuarioRequest.java` | Record DTO | Entrada para crear estudiante o docente |
| `dto/ActualizarUsuarioRequest.java` | Record DTO | Entrada para editar datos de usuario |
| `dto/UsuarioResponse.java` | Record DTO | Respuesta con datos del usuario (sin contraseña) |
| `service/UsuarioService.java` | Service | Lógica de negocio CRUD de usuarios |
| `controller/UserController.java` | Controller | `POST/GET/PUT/PATCH /api/usuarios/**` |

### Sesión JWT (futura)

| Archivo | Tipo | Descripción |
|---|---|---|
| `security/JwtUtil.java` | Utilidad | Generación y validación de JWT |
| `security/JwtAuthFilter.java` | Filter | Intercepta solicitudes y valida el token |
| `dto/LoginResponse.java` | (actualizar) | Incluir el campo `token` |

---

## 9. Checklist para Continuar con la Sesión 2

- [ ] Verificar que la base de datos `lab_quimico_db` existe en PostgreSQL local
- [ ] Ejecutar `./mvnw spring-boot:run` y confirmar que el backend inicia sin errores
- [ ] Verificar `GET http://localhost:8080/api/health` retorna `{"status": "OK"}`
- [ ] Confirmar que las dependencias de prueba resuelven con `./mvnw test`
- [ ] Revisar este documento antes de iniciar la Sesión 2
- [ ] Crear entidad `Usuario` con el diseño definido en la sección 5
- [ ] Crear enum `Rol`
- [ ] Agregar bean `PasswordEncoder` en `SecurityConfig`
- [ ] Agregar `spring.jpa.open-in-view=false` en `application.properties`
- [ ] Extender `GlobalExceptionHandler` con excepciones de usuarios

---

*Documento generado en la Sesión 1. Actualizar al inicio de cada sesión con los cambios realizados.*
