# Flux (nombre provisional)

Descargador de video y audio todo en uno para Android. Motor: yt-dlp + FFmpeg (youtubedl-android).
YouTube queda excluido por decisión de producto (`engine/SiteGuard.kt`).

## Cómo abrirlo
1. Android Studio (Koala o más nuevo) → *Open* → carpeta `Flux`.
2. Dejar que sincronice Gradle (genera el wrapper automáticamente).
3. Ejecutar en un dispositivo o emulador con Android 10+ (minSdk 29).

## Estructura
- `engine/Engine.kt` — análisis del enlace y descarga con yt-dlp/FFmpeg
- `engine/SiteGuard.kt` — validación de enlaces y sitios bloqueados
- `engine/Downloads.kt` — cola y estado de descargas · `DownloadService.kt` — descargas en segundo plano
- `adblock/` — motor de filtros y gestor de listas
- `browser/` — navegador seguro (controlador con la política de seguridad, pantalla, bloqueador)
- `engine/Storage.kt` — guarda en Descargas/Flux vía MediaStore
- `ui/` — pantalla principal (Compose), ViewModel, tema negro/naranja
- `logo.svg` — vista previa del logo; el ícono real está en `res/drawable/ic_launcher_foreground.xml`

## Fase 1 (esto)
Pegar o compartir enlace → analizar → elegir video (calidad) o solo audio (MP3/M4A/OPUS) → descargar con progreso, cancelar y abrir.

## Fase 2 (hecha)
Servicio en primer plano con notificación de progreso y botón "Cancelar todo", cola (2 descargas en paralelo), aviso al completar. Estado global en `engine/Downloads.kt`.

## Fase 3 (hecha): navegador seguro
`browser/` — WebView endurecido: solo HTTPS, certificados inválidos bloqueados, Navegación segura, popups/esquemas externos/redirecciones automáticas bloqueados, permisos denegados, cookies de terceros off, ejecutables bloqueados, bloqueo de anuncios y rastreadores, detección de video en la página y botón Descargar. Navegación y búsqueda con DuckDuckGo.

## Fase 4 (hecha): bloqueo serio + navegador completo
- `adblock/` — motor de filtros compatible con la sintaxis de EasyList/uBlock (reglas de red con índice por dominio y token, excepciones, opciones third-party / domain / tipo de recurso, `$important`) más filtrado cosmético (CSS que oculta huecos). `FilterManager` descarga y actualiza las listas (EasyList, EasyPrivacy, uBlock, badware, Peter Lowe, Fanboy, EasyList Español) cada 72 h o a demanda; hay una lista semilla para el primer arranque.
- Pausa de protección por sitio, contador de bloqueos, listas activables.
- Descargas: solo YouTube está bloqueado. Los archivos generales usan el gestor del sistema; los ejecutables piden confirmación.
- Pestañas, marcadores, historial, modo escritorio y botón de borrado total.

## Siguiente
- Progreso unificado video+audio (hoy el % se reinicia al pasar de una pista a otra)
- Fase 3: ajustes (solo Wi-Fi, límite de velocidad, carpeta), playlists, subtítulos, recorte antes de descargar
- Fase 4: bóveda privada, reproductor integrado, navegador con detección de videos
