# Redes-Practica03
En esta práctica se implementa una conexión cliente-servidor usando sockets de datagrama e hilos para resemblar un chat como el de una aplicación.

# Idea general

- El servidor hará como si fuera la aplicación en sí misma, mientras que los clientes serán los usuarios que se conectan a la aplicación.
- El servidor contiene registros, los chats y practicamente es el punto de conección.
- Una clase extra por crear se llamará Chat.java y contendrá los mensajes y los usuarios que participan en el chat. Y también se tendrá una clase Usuario.java que contendrá la información de cada usuario.

# Estructura de archivos
- Chat.java: Clase que representa un chat con sus mensajes y usuarios.
- Usuario.java: Clase que representa un usuario con su información. 
- Servidor.java: Clase que implementa el servidor de la aplicación.
- Cliente.java: Clase que implementa el cliente de la aplicación.
- README.md: Archivo de documentación del proyecto.

# Funcionalidades requeridas para la práctica
- El servidor debe ser capaz de manejar múltiples clientes simultáneamente utilizando hilos.
- Los clientes deben poder enviar y recibir mensajes a través del servidor.
- El servidor debe mantener un registro de los chats y los usuarios conectados.
- El servidor almacena chats grupales con múltiples usuarios, donde se pueden enviar mensajes a todos los participantes del chat, videos, stickers, imágenes, etc.
- Los clientes deben poder unirse a chats existentes o crear nuevos chats.
- Los clientes pueden enviar mensajes privados a otros usuarios y estos también se almacenan en el servidor.

# Requisitos
- Java Development Kit (JDK) 8 o superior.  

# Instrucciones para compilar y ejecutar

