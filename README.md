# ChemicalLab-Backend
API REST del laboratorio químico digital interactivo, desarrollada con Java, Spring Boot, Spring Security y PostgreSQL.

## Ejecución local

Requisitos: Java 17+, PostgreSQL en local con una base de datos `lab_quimico_db`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

En Windows: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`.
Solo `dev` ofrece los valores locales `localhost:5432/lab_quimico_db`, usuario
`postgres` / contraseña `admin` y una clave JWT pública de desarrollo. Use únicamente
una base de desarrollo sin datos del colegio. Puede sobrescribirlos por variables externas.
Sin perfil explícito se aplica `prod` y el arranque exige JWT y conexión DB externos.
No combine perfiles; los únicos admitidos son `dev`, `test` y `prod`.

- Endpoint de health: `GET http://localhost:8080/api/health`
- No se crean cuentas por defecto. `APP_DEMO_ENABLED=true` habilita `docente` y
  `EST0001` únicamente en `dev`/`test`; producción bloquea las demos incluso con esa opción.
- El primer administrador requiere `APP_BOOTSTRAP_ADMIN_ENABLED=true`, un usuario y
  una contraseña externos; no hay administrador ni contraseña predeterminados.
- Perfiles, variables, bootstrap de un uso, rotación, inventario de cuentas existentes y
  pruebas aisladas: [configuración segura](docs/configuracion-segura.md).

## Motor químico

Formación de compuestos (óxidos, hidróxidos, ácidos, sales binarias y oxisales) y sus
endpoints de catálogo, con ejemplos de petición, en
[`docs/MOTOR_QUIMICO.md`](./docs/MOTOR_QUIMICO.md).

## Despliegue

Pasos completos para desplegar en Render (backend + PostgreSQL) y Vercel (frontend) en
[`DEPLOY.md`](./DEPLOY.md).
