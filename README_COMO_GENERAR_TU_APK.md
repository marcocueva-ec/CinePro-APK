# 🎬 CinePro Camera PRO (Competencia de Blackmagic Camera para Android)

Este proyecto nativo en **Kotlin + Camera2 API** implementa exactamente la interfaz profesional del diseño y desbloquea el máximo potencial físico del sensor de tu teléfono:

## 🚀 Características de Sensor Incluidas en el Código
1. **Modo Open Gate Real (`chooseOptimalVideoSize`)**: Consulta la matriz activa del sensor (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`) y selecciona la resolución máxima completa en relación 4:3 / 3:2 sin el recorte tradicional 16:9.
2. **Perfil CineLog de 32 Puntos (`buildCineLogTonemapCurve`)**: Inyecta una curva de mapeo tonal logarítmica directa en el pipeline del ISP (`TONEMAP_MODE_CONTRAST_CURVE`) para levantar sombras y proteger altas luces, lista para etalonar en DaVinci Resolve.
3. **Bypass de Procesado Artificial (`ISP Bypass`)**:
   - `EDGE_MODE_OFF`: Elimina por completo los bordes blancos y la sobrenitidez digital típica de celular.
   - `NOISE_REDUCTION_MODE_OFF`: Evita el efecto "piel de plástico/acuarela" conservando el grano orgánico del sensor.
4. **Grabación HEVC (H.265) a 150 Mbps + Audio `UNPROCESSED`**: Máxima tasa de bits en `Movies/CinePro_Log` y captura de micrófono sin compresión automática de ganancia (AGC).
5. **Controles Manuales con Diales en Arco**:
   - **[FOCUS]**: Dial continuo desde macro hasta infinito con indicador en metros y modo `AF-C`.
   - **[ISO]**: Control directo de `SENSOR_SENSITIVITY` (50 a 6400) con alerta roja en ISOs altos.
   - **[SHUTTER]**: Velocidad en nanosegundos (`SENSOR_EXPOSURE_TIME`) con pasos de regla de 180° (`1/48s`, `1/50s`, `1/60s`, etc.).
   - **[WB]**: Cálculo matemático de matriz `RggbChannelVector` de 2500K a 10000K.
   - **[IRIS]**: Control de `LENS_APERTURE` y dial curvo de zoom en el borde derecho.

---

## 🛠️ Cómo obtener el archivo `.apk` instalable en 3 minutos (Sin instalar nada en tu PC)

1. Crea un repositorio nuevo en **[GitHub.com](https://github.com/new)** (puede ser privado o público).
2. Sube el contenido de esta carpeta al repositorio (arrastrando todos los archivos).
3. Ve a la pestaña **"Actions"** arriba en tu repositorio de GitHub.
4. Verás que el flujo **"Compilar CinePro Camera APK"** se ejecuta automáticamente (tarda unos 2 minutos).
5. Cuando aparezca el check verde ✅, entra ahí y abajo en **"Artifacts"** haz clic en **`CinePro-Camera-PRO-APK`** para descargar tu `.apk` listo para instalar en Android.
