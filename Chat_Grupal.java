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
    // Nuevo: historial de mensajes (mensajes ya formateados)
    private final List<String> history;

    public Chat_Grupal(String nombre) {
        this.nombre = nombre;
        this.miembros = new ArrayList<>();
        this.history = new ArrayList<>();
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

    // Nuevo: eliminar miembro
    public synchronized void eliminarMiembro(Usuario u) {
        if (u == null) return;
        if (miembros.remove(u)) {
            // mantener consistencia en el usuario
            u.removerChat(this);
        }
    }

    public synchronized List<Usuario> getMiembros() {
        return new ArrayList<>(miembros);
    }

    @Override
    public String toString() {
        return "Chat_Grupal{name='" + nombre + "', miembros=" + miembros.size() + "}";
    }

    // Devuelve lista de nombres (útil para enviar al cliente)
    public synchronized List<String> listarNombresMiembros() {
        List<String> names = new ArrayList<>();
        for (Usuario u : miembros) {
            names.add(u.getNombre());
        }
        return names;
    }

    // Nuevo: añadir mensaje al historial (mensajes ya formateados sin \n)
    public synchronized void addMessage(String msg) {
        if (msg == null) return;
        history.add(msg);
    }

    // Nuevo: obtener copia del historial
    public synchronized List<String> getHistory() {
        return new ArrayList<>(history);
    }
}
