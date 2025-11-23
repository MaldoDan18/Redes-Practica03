/*
 * List_usuario.java
 * Contiene los usuarios registrados en el sistema del servidor.
 * Cada usuario tiene un nombre único y puede estar asociado a múltiples chats.
 * Cada chat puede ser grupal o privado.
*/

import java.util.List;
import java.util.ArrayList;

public class List_usuario {

    private final List<Usuario> usuarios;

    public List_usuario() {
        this.usuarios = new ArrayList<>();
    }

    // Evita duplicados por id o nombre
    public synchronized void agregarUsuario(Usuario usuario) {
        if (usuario == null) return;
        for (Usuario u : usuarios) {
            if (u.getId() == usuario.getId() || u.getNombre().equalsIgnoreCase(usuario.getNombre())) {
                return; // ya existe
            }
        }
        this.usuarios.add(usuario);
    }

    public synchronized List<Usuario> getUsuarios() {
        return new ArrayList<>(usuarios);
    }

    //Borrar la lista de usuarios una vez que el servidor se apague
    public synchronized void clearUsuarios() {
        this.usuarios.clear();
    }

    //Listar usuarios activos (devuelve copia)
    public synchronized List<Usuario> listarUsuariosActivos() {
        return new ArrayList<>(this.usuarios);
    }

    public synchronized int contarUsuarios() {
        return this.usuarios.size();
    }

    // Obtener nombres para enviar al cliente
    public synchronized List<String> listarNombres() {
        List<String> names = new ArrayList<>();
        for (Usuario u : usuarios) {
            names.add(u.getNombre());
        }
        return names;
    }

    // Nuevo: buscar usuario por id
    public synchronized Usuario getUsuarioPorId(int id) {
        for (Usuario u : usuarios) {
            if (u.getId() == id) return u;
        }
        return null;
    }
}

