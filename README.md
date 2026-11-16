# Radar de Licitaciones (uso interno, privado)

App Android que vigila las licitaciones de la DGCP y avisa con notificaciones del celular.

## Seguridad
- Repositorio **privado**: el código y las versiones solo los ves tú.
- La app se conecta **únicamente** a la API pública de datos abiertos de la DGCP. No usa servidor, GitHub ni WhatsApp.
- La llave de firma está guardada cifrada en los secretos de este repositorio y se borra del equipo de GitHub al terminar cada construcción.
- Nadie puede instalar una versión encima de la de tu papá sin esa llave.

## Cómo funciona
- Android la despierta a la hora del resumen, 2:30 pm y 9:30 pm (JobScheduler). Revisa unos segundos y se apaga.
- Todo se guarda dentro de la app (`estado.json`) y entra en la copia automática de Google del teléfono.
- Si algo falla, se lo dice a tu papá en la app (Ajustes › Autovigilancia › Ver diagnóstico).

## Sacar una versión nueva
1. Cualquier cambio en el código que llegue a `main` construye el APK firmado.
2. Entra a **Releases** de este repositorio y descarga `radar.apk`.
3. Mándaselo por WhatsApp; él lo abre y toca **Instalar**. Se pone encima y no pierde nada.

## Secretos (Settings › Secrets and variables › Actions)
- `KEYSTORE_B64`: la llave de firma en base64.
- `KEYSTORE_PASS`: su contraseña.

**Guarda `radar.jks` y su contraseña en dos lugares.** Si se pierde, no se puede actualizar y hay que desinstalar y reinstalar.
