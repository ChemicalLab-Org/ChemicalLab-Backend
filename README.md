# ChemicalLab-Backend
API REST del laboratorio químico digital interactivo, desarrollada con Java, Spring Boot, Spring Security y PostgreSQL.

## Ejecución local

Requisitos: Java 17+, PostgreSQL en local con una base de datos `lab_quimico_db`.

```bash
./mvnw spring-boot:run
```

La configuración por defecto (en `application.properties`) apunta a `localhost:5432` con
usuario `postgres` / `admin`. Todos los valores se pueden sobrescribir por variable de
entorno (ver tabla en `DEPLOY.md`).

- Endpoint de health: `GET http://localhost:8080/api/health`
- Usuarios iniciales de demo creados por el seeder: `admin`, `docente`, `EST0001`
  (contraseñas configurables vía `ADMIN_INITIAL_PASSWORD`, `TEACHER_INITIAL_PASSWORD`,
  `STUDENT_INITIAL_PASSWORD`).

## Despliegue

Pasos completos para desplegar en Render (backend + PostgreSQL) y Vercel (frontend) en
[`DEPLOY.md`](./DEPLOY.md).
