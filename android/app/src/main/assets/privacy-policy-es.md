<!-- Spanish translation of privacy-policy.md (the English original, docs/privacy-policy.md, is the source of truth). Legal text: it must be reviewed by a qualified translator and legal counsel before release, and updated in the same change as the English original. Keep the same sections and bullets (PrivacyPolicyTest checks the structure). -->
# Política de privacidad de Dak

**Versión 1, vigente desde (fecha del primer lanzamiento público).** La versión más reciente está en [el sitio web de Dak](https://dak.example/privacy). La app muestra este mismo texto sin conexión en Ajustes → Privacidad → Política de privacidad.

## En resumen

- Dak es una app de mensajería de texto (SMS y MMS). Lee, clasifica y guarda tus mensajes **en tu teléfono**.
- En la versión gratuita, **no se envía nada sobre tus mensajes a Dak ni a nadie más**, salvo los mensajes que tú decides enviar a través de tu red móvil.
- Algunas funciones opcionales enviarían ciertos datos fuera de tu teléfono (se indican más abajo). Están **desactivadas hasta que tú las activas**, y cada una te pide permiso primero con una pantalla que explica exactamente qué se envía, adónde y por qué. Puedes volver a desactivar cada una en cualquier momento.
- Dak **no tiene anuncios, ni analíticas, ni servicios de informes de errores**. No vendemos ni compartimos tus datos.
- Puedes **exportar** o **eliminar** todo lo que Dak guarda desde Ajustes → Privacidad.

## Quiénes somos

Dak ("nosotros") es responsable de esta app. Contacto: privacy@dak.example (provisional hasta que los datos de la empresa sean definitivos). Si estás en la India, este es también el contacto de nuestro responsable de reclamaciones; si estás en la UE o el Reino Unido, el de nuestro contacto de protección de datos.

## Lo que Dak gestiona en tu teléfono

Como tu app de SMS predeterminada, Dak trabaja con lo siguiente. Todo ello se queda en tu teléfono, salvo que una sección de más abajo diga lo contrario.

- **Tus mensajes de texto y con imágenes.** Android los guarda en el almacén de mensajes compartido del teléfono. Dak lee ese almacén para mostrar tus conversaciones, escribe en él los mensajes nuevos y mantiene su propio índice cifrado de ellos para buscar y clasificar con rapidez.
- **Lo que Dak deduce de tus mensajes:** la categoría de cada mensaje (por ejemplo OTP, banco o spam), etiquetas, contraseñas de un solo uso, cuentas bancarias y tarjetas y un registro de transacciones (la Libreta), avisos de estafa y nombres de remitentes. Todo esto está en el índice cifrado de Dak.
- **Lo que creas en Dak:** ajustes, reglas de automatización, mensajes programados, búsquedas guardadas, listas de difusión, agrupaciones de remitentes y tu papelera (los mensajes que eliminaste, que se guardan poco tiempo para que puedas restaurarlos).
- **Registros que Dak guarda para ti:** un historial de ejecuciones de automatizaciones (qué envió cada regla, a quién, o por qué no lo hizo) y un registro de actividad de las acciones automáticas y destructivas, para que puedas comprobar qué ocurrió.
- **Contactos:** nombres y fotos, para mostrar de quién es un mensaje. Dak no sube ni modifica tus contactos.
- **Datos del teléfono y de la SIM:** qué SIM tienes y sus números, para enviar desde la SIM correcta.
- **Tus registros de consentimiento:** cuándo permitiste o retiraste cada función opcional de más abajo, y qué versión de su explicación viste.

El índice y las bases de datos de Dak están cifrados, con claves guardadas en el almacenamiento seguro de claves de tu teléfono (Android Keystore). También puedes bloquear Dak con tu huella, tu cara o un PIN.

## Lo que sale de tu teléfono

### Lo que haces tú mismo

Esto va adonde tú lo envías. Dak no recibe ninguna copia.

- **Los mensajes que envías** pasan por tu red móvil, como cualquier SMS o MMS. Los mensajes con imágenes se envían y reciben a través del servicio MMS de tu operador usando datos móviles.
- **Los reenvíos, lo que compartes y las respuestas** que haces, incluidas las reglas de automatización que configuras para reenviar mensajes por SMS a una persona que eliges, las respuestas automáticas y el reenvío a WhatsApp con "un toque" (Dak abre WhatsApp con el texto ya escrito; tú pulsas enviar).
- **Las denuncias de spam al 1909** (India): Dak prepara el texto de la denuncia; tú lo revisas y lo envías como SMS.
- **Tu ubicación**, solo cuando tocas "compartir ubicación" en un mensaje que estás escribiendo. Dak la convierte en un enlace de mapa dentro de ese mensaje. No se guarda ni se envía a ningún otro sitio.
- **Las exportaciones y copias de seguridad** a un lugar que eliges (una carpeta de tu teléfono o una app de almacenamiento como Google Drive o Dropbox). Las copias de seguridad se cifran en tu teléfono con tu frase de contraseña antes de guardarse; nadie, ni nosotros ni tu proveedor de almacenamiento, puede leerlas sin tu frase de contraseña o tu código de recuperación. Las exportaciones ("Exportar mis datos", "Exportar mensajes") no están cifradas, porque su objetivo es que puedas leerlas tú y otras apps; guárdalas en un lugar seguro.
- **Los enlaces** que tocas se abren en tu navegador.

### Funciones opcionales que envían datos a un servidor (desactivadas por defecto)

Cada una de ellas te pide permiso primero, en una pantalla que muestra qué se envía, a quién, por qué y durante cuánto tiempo. Desactívalas en Ajustes → Privacidad → Datos que salen de tu teléfono; al desactivar una, se detiene de inmediato.

**Estado actual:** ninguna de ellas está activa en la versión actual de Dak. Se describen aquí para que sepas qué esperar; cuando una esté disponible, verás su pantalla de permiso antes de que se envíe nada.

- **Clasificación en la nube Jev.** Cuando el clasificador de Dak en el teléfono no puede clasificar un mensaje entrante nuevo, Jev puede enviar el ID del remitente comercial (un encabezado como "VM-HDFCBK" o un código corto) y una copia enmascarada del mensaje, con los números, importes, números de tarjeta, enlaces, direcciones de correo y probables nombres sustituidos por marcadores, al servicio de clasificación de Dak para una segunda opinión. El servicio devuelve una categoría y no conserva el texto. Está limitado a un número mensual que tú fijas. Los mensajes de personas (números de teléfono) nunca se envían, ni tampoco sus números.
- **Webhooks** (premium). Una regla de automatización que creas puede enviar el remitente, un ID interno del mensaje y el texto del mensaje (o tu plantilla de él) a una dirección web que introduces. Esa dirección te pertenece a ti o a un servicio que elegiste; ellos deciden cuánto tiempo lo conservan.
- **Retransmisión web y de escritorio** (premium). Los mensajes que decides retransmitir se cifran en tu teléfono con una clave que solo compartes con tu ordenador vinculado, y pasan por el servidor de retransmisión de Dak, que no puede leerlos. Los elementos cifrados se eliminan de la retransmisión en cuanto se entregan, y como máximo a los 7 días.
- **Búsqueda asistida por IA** (premium). Solo la pregunta que escribes (por ejemplo "cuánto gasté en Swiggy el mes pasado") se envía a un servicio de modelo de lenguaje, que la convierte en filtros de búsqueda. Tus mensajes no se envían; la búsqueda se hace en tu teléfono.

Si compras premium, Google Play gestiona el pago; Dak solo recibe la confirmación de a qué tienes derecho, no tus datos de pago.

### Lo que Dak nunca hace

- La app no incluye código de publicidad, analíticas, seguimiento ni informes de errores.
- No vendemos, alquilamos ni intercambiamos datos personales, y no usamos tus mensajes para entrenar modelos de IA.

## Permisos y por qué Dak los pide

- **SMS y MMS (enviar, recibir, leer):** para ser tu app de mensajería predeterminada. Android solo deja que Dak los pida después de que la elijas como app de SMS predeterminada.
- **Notificaciones:** para avisarte de mensajes nuevos y de OTP.
- **Contactos:** para mostrar nombres y fotos en lugar de números.
- **Estado del teléfono y números de teléfono:** para conocer tus SIM, enviar desde la correcta y etiquetar los mensajes por SIM.
- **Ubicación aproximada:** solo cuando compartes tu ubicación en un mensaje.
- **Internet y estado de la red:** solo se usan para descargar y enviar mensajes con imágenes (MMS) a través de la conexión de datos móviles de tu operador. La versión gratuita no contiene código que envíe datos a un servidor.
- **Ejecutar al inicio, alarmas exactas, servicio en primer plano, mantener activo, vibración:** para entregar a tiempo los mensajes programados, terminar de enviar o descargar un mensaje con la pantalla apagada y avisarte.
- **Lista de apps instaladas:** para reconocer cuándo una app de tu teléfono usó un OTP (comprobación de SMS Retriever). Esta comprobación se hace en tu teléfono; la lista no se envía a ningún sitio.

Dak no pide directamente a Android que lo excluya de la optimización de batería. Si los mensajes te llegan tarde, Dak te explica cómo cambiarlo tú mismo en los ajustes de batería de tu teléfono.

## Cuánto tiempo se conservan los datos

- **Los mensajes** se quedan en el almacén de mensajes de tu teléfono hasta que los eliminas.
- **Papelera:** los OTP eliminados se borran definitivamente al cabo de 1 día y los demás mensajes eliminados al cabo de 30 días (puedes vaciarla antes).
- **Registro de actividad:** 90 días.
- **Historial de ejecuciones de automatizaciones:** un año, y como mínimo las 5.000 entradas más recientes.
- **Historial de búsqueda:** las 50 búsquedas más recientes.
- **Todo lo demás que Dak guarda** (índice, ajustes, reglas, registro de transacciones, registros de consentimiento) se queda en tu teléfono hasta que lo eliminas, usas "Eliminar mis datos de Dak", borras el almacenamiento de la app o desinstalas Dak.
- **Las copias de seguridad** se quedan en la carpeta que elegiste hasta que las eliminas allí.
- **Servidores:** las funciones opcionales de más arriba solo conservan datos durante los plazos indicados en sus pantallas de permiso.

## Tus derechos y opciones

Vivas donde vivas, puedes:

- **Ver tus datos y obtener una copia:** Ajustes → Privacidad → Exportar mis datos de Dak guarda tus ajustes, reglas, historial de ejecuciones, registro de actividad, cuentas y registro de transacciones, etiquetas y registros de consentimiento en un archivo que eliges. Para exportar también tus mensajes, usa Ajustes → Copia de seguridad, datos y privacidad → Exportar.
- **Corregirlos:** edita o elimina reglas, etiquetas, cuentas y ajustes en la app.
- **Eliminarlos:** Ajustes → Privacidad → Eliminar mis datos de Dak borra todo lo que Dak guarda en tu teléfono. Tus SMS y MMS se quedan en el almacén de mensajes del teléfono, porque otras apps lo comparten; elimínalos en Dak o en los ajustes de mensajería de tu teléfono si quieres que desaparezcan.
- **Retirar tu consentimiento** para cualquier función opcional, con la misma facilidad con que lo diste: Ajustes → Privacidad → Datos que salen de tu teléfono.
- **Presentar una reclamación:** contacta primero con nosotros (datos más arriba). En la India puedes acudir después a la Junta de Protección de Datos de la India (Data Protection Board of India); en la UE o el Reino Unido, a tu autoridad de protección de datos.

Como Dak guarda los datos en tu teléfono, normalmente no conservamos nada sobre ti de nuestro lado. Si has usado una función opcional de servidor y quieres que confirmemos o eliminemos algo guardado allí, contacta con nosotros.

## Menores

Dak no está dirigida a menores de 13 años. Las funciones opcionales de servidor de más arriba son solo para personas de 18 años o más, y sus pantallas de permiso te piden que lo confirmes.

## Cambios en esta política

Si cambiamos lo que Dak hace con tus datos, actualizaremos esta política y su número de versión, y mostraremos la nueva versión en la app. Si una función opcional empieza a enviar datos distintos, se te volverá a pedir permiso antes de que lo haga.
