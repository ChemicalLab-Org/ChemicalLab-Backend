# Lista local de contraseñas comunes

`common-passwords.txt` contiene una lista suplementaria en español y el fichero
[10k-most-common.txt de SecLists](https://github.com/danielmiessler/SecLists/blob/master/Passwords/Common-Credentials/10k-most-common.txt),
descargado el 11 de octubre de 2026. Se conserva su licencia MIT adjunta.
Es un recurso público de pruebas de seguridad, no credenciales de usuarios.

SHA-256 del archivo incorporado (UTF-8):
`a9f17b251d090687e0df0d483c8771046323c24294a24b55c7ae7790f70257ef`.

No hay consultas de contraseñas a terceros. Para actualizar: revisar el origen y su
licencia, descargar únicamente la lista estática, revisar el diff, repetir
PasswordPolicyTest y publicar un artefacto coordinado entre instancias. No añadir
contraseñas reales observadas. La comparación de bloqueo ignora mayúsculas y
espacios externos; el hash y su verificación conservan exactamente la entrada.
