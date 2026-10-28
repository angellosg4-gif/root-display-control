# Root Display Control — Android nativo

Aplicación Android de código abierto para ajustar la resolución y la densidad DPI del dispositivo mediante `wm size` y `wm density`, ejecutados desde un gestor de superusuario (`su`).

## Compilar localmente

1. Abre esta carpeta (`android-native`) en Android Studio.
2. Instala Android SDK Platform 35 y usa JDK 17.
3. Espera a que Gradle sincronice y compila `app` con **Build > Make Project**.
4. Instala la app en un dispositivo Android con root para probar los comandos. Al aplicar el primer perfil, el gestor root pedirá autorización.

También se puede compilar con Gradle 8.9 instalado ejecutando `gradle :app:assembleDebug` desde esta carpeta. El APK de depuración aparece en `app/build/outputs/apk/debug/app-debug.apk`.

## Compilar y descargar con GitHub Actions

El repositorio incluye el flujo `Build Root Display Control APK`. Para generar un APK sin instalar Android Studio:

1. Sube los cambios y el flujo `.github/workflows/build-root-display-control-apk.yml` a un repositorio de GitHub.
2. En GitHub, abre **Actions > Build Root Display Control APK > Run workflow**. También se inicia automáticamente al subir cambios a esta carpeta.
3. Cuando termine la ejecución correctamente, descarga **root-display-control-debug-apk** desde la sección **Artifacts** de esa ejecución.

El artefacto contiene `root-display-control-debug.apk` y se conserva durante 14 días. Es una compilación de depuración; no está firmada para distribución en Google Play.

## Estructura

```text
android-native/
  app/src/main/
    AndroidManifest.xml
    java/com/rootdisplaycontrol/MainActivity.java
    res/layout/activity_main.xml
    res/drawable/
    res/values/
```

## Seguridad y restauración

- No solicita permisos Android de red ni utiliza servicios externos.
- Solo ejecuta comandos `wm size`, `wm density` y sus variantes `reset`; no acepta comandos de shell escritos por el usuario.
- Limita la resolución a 320–10000 px por lado y la densidad a 72–1000 DPI.
- Guarda los valores activos originales antes del primer cambio. **Restore previous display** recupera esos valores, incluso si ya existía una anulación antes de abrir la app.
- Los cambios se aplican al dispositivo completo. Úsala solo en dispositivos propios donde tengas autorización para usar root.

## Vista Expo

El artefacto Expo incluido en la raíz sirve como vista previa de la interfaz. Expo Go no puede ejecutar comandos `su`; las operaciones reales de root están en este proyecto Android Java/XML.

## Licencia

MIT. Consulta `LICENSE`.
