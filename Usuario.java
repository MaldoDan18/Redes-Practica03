/* 
 * Usuario.java
 * Representa a un usuario en el sistema de chat.
 * Sus datos básicos y chats asociados.
 * El usuario puede participar en múltiples chats, tanto grupales como privados.
 * Puede salir y entrar a chat grupales según su preferencia.
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Usuario {
	private int id;
	private String nombre;
	private boolean active;
	private final List<Chat_Grupal> chatsAsociados;

	public Usuario(String nombre, int id) {
		this.nombre = nombre;
		this.id = id;
		this.active = true;
		this.chatsAsociados = new ArrayList<>();
	}

	public int getId() {
		return id;
	}

    public String getNombre() {
        return nombre;
    }   

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public synchronized void agregarChat(Chat_Grupal chat) {
        if (chat != null && !chatsAsociados.contains(chat)) {
            chatsAsociados.add(chat);
        }
    }

    public synchronized List<Chat_Grupal> getChatsAsociados() {
        return new ArrayList<>(chatsAsociados);
    }

    @Override
    public String toString() {
        return "Usuario{id=" + id + ", nombre='" + nombre + "', active=" + active + "}";
    }

    // Igualdad por id (único)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Usuario)) return false;
        Usuario usuario = (Usuario) o;
        return id == usuario.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
