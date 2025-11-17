/* 
 * Chat.java
 * 
 * Integra un listado de usuarios y funcionalidades de chat.
 * El chat grupal permite a los usuarios enviar mensajes a todos los miembros conectados.
 * El chat privado permite a los usuarios enviar mensajes directos a otros usuarios específicos.
 */

import java.util.ArrayList;
import java.util.List;

public class Chat_Grupal {

    private final String nombre;
    private final List<Usuario> miembros;

    public Chat_Grupal(String nombre) {
        this.nombre = nombre;
        this.miembros = new ArrayList<>();
    }

    public String getNombre() {
        return nombre;
    }

    public synchronized void agregarMiembro(Usuario u) {
        if (u != null && !miembros.contains(u)) {
            miembros.add(u);
            u.agregarChat(this);
        }
    }

    public synchronized List<Usuario> getMiembros() {
        return new ArrayList<>(miembros);
    }

    @Override
    public String toString() {
        return "Chat_Grupal{name='" + nombre + "', miembros=" + miembros.size() + "}";
    }
}
