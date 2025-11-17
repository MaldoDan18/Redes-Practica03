/*
 * List_usuario.java
 * Contiene los usuarios registrados en el sistema del servidor.
 * Cada usuario tiene un nombre único y puede estar asociado a múltiples chats.
 * Cada chat puede ser grupal o privado.
*/

import java.util.List;
import java.util.ArrayList;

public class List_usuario {

    private List<Usuario> usuarios;

    public List_usuario() {
        this.usuarios = new ArrayList<>();
    }

    public void agregarUsuario(Usuario usuario) {
        this.usuarios.add(usuario);
    }

    public List<Usuario> getUsuarios() {
        return usuarios;
    }

    //Borrar la lista de usuarios una vez que el servidor se apague

    public void clearUsuarios() {
        this.usuarios.clear();
    }

    //Listar usuarios activos
    public List<Usuario> listarUsuariosActivos() {
        return new ArrayList<>(this.usuarios);
    }

    public int contarUsuarios() {
        return this.usuarios.size();
    }

}
