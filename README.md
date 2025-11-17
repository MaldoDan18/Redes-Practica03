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

# Requisitos
- Java Development Kit (JDK) 8 o superior.  