import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.CancelledKeyException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.Queue;
import java.util.LinkedList;
import java.util.List;

public class Servidor_B {

	// Puerto del servidor
	private static final int PORT = 5000;

	// Estado global del servidor
	private static final List_usuario listaUsuarios = new List_usuario();
	private static final List<Chat_Grupal> chats = new LinkedList<>();
	private static final Map<SocketChannel, Usuario> socketToUsuario = new HashMap<>();
	private static int nextUserId = 1000; // id incremental para usuarios clientes

	// Inicializadores de prueba (usuarios y chat grupal)
	static {
		// usuarios de prueba
		Usuario a = new Usuario("Alice", 1);
		Usuario b = new Usuario("Bob", 2);
		Usuario r = new Usuario("Reu", 3);
		listaUsuarios.agregarUsuario(a);
		listaUsuarios.agregarUsuario(b);
		listaUsuarios.agregarUsuario(r);

		// chat grupal de prueba y asociación
		Chat_Grupal prueba = new Chat_Grupal("Sala-Prueba");
		prueba.agregarMiembro(a);
		prueba.agregarMiembro(b);
		chats.add(prueba);
	}

	public static void main(String[] args) throws IOException {
		ServerSocketChannel serverChannel = ServerSocketChannel.open();

		// Intentar permitir reutilizar la dirección si está en TIME_WAIT
		try {
			serverChannel.socket().setReuseAddress(true);
		} catch (Exception ignore) {
			// Si por alguna razón no se puede, seguimos igual
		}

		// Bind con manejo de puerto en uso
		try {
			serverChannel.bind(new InetSocketAddress(PORT));
		} catch (java.net.BindException be) {
			System.err.println("No se puede enlazar en el puerto " + PORT + ": " + be.getMessage());
			System.err.println("Comprueba si otro proceso ya está usando el puerto o cambia el puerto en la configuración.");
			serverChannel.close();
			return;
		}

		serverChannel.configureBlocking(false);

		Selector selector = Selector.open();
		serverChannel.register(selector, SelectionKey.OP_ACCEPT);

		System.out.println("Servidor escuchando en puerto " + PORT);

		// Estado por canal: buffer de lectura (StringBuilder) y cola de escritura (ByteBuffer)
		Map<SocketChannel, StringBuilder> readMap = new HashMap<>();
		Map<SocketChannel, Queue<ByteBuffer>> writeMap = new HashMap<>();
		Charset charset = StandardCharsets.UTF_8;
		ByteBuffer tmp = ByteBuffer.allocate(2048);

		while (true) {
			selector.select();
			Iterator<SelectionKey> it = selector.selectedKeys().iterator();
			while (it.hasNext()) {
				SelectionKey key = it.next();
				it.remove();

				if (key.isAcceptable()) {
					ServerSocketChannel srv = (ServerSocketChannel) key.channel();
					SocketChannel client = srv.accept();
					if (client != null) {
						client.configureBlocking(false);
						client.register(selector, SelectionKey.OP_READ);
						readMap.put(client, new StringBuilder());
						writeMap.put(client, new LinkedList<>());

						// Crear usuario para este cliente y añadir a la lista global
						int id = nextUserId++;
						String defaultName = "user-" + id;
						Usuario nuevo = new Usuario(defaultName, id);
						// asegurarse de ids no colisionen (simple): usar nextUserId y luego incrementarlo
						listaUsuarios.agregarUsuario(nuevo);
						socketToUsuario.put(client, nuevo);

						System.out.println("Conexión aceptada desde " + safeRemoteAddress(client) + " -> creado usuario " + nuevo.getNombre());

						// Enviar mensaje de bienvenida y menú
						StringBuilder welcome = new StringBuilder();
						welcome.append("Bienvenido al servidor. Tu usuario: ").append(nuevo.getNombre()).append("\n");
						welcome.append(menuTexto());
						enqueueWrite(client, writeMap, ByteBuffer.wrap(welcome.toString().getBytes(charset)));
						SelectionKey ck = client.keyFor(selector);
						if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
					}
				}

				if (key.isReadable()) {
					SocketChannel ch = (SocketChannel) key.channel();
					tmp.clear();
					int read;
					try {
						read = ch.read(tmp);
					} catch (IOException e) {
						read = -1;
					}
					if (read == -1) {
						cleanupChannel(ch, key, readMap, writeMap);
						continue;
					} else if (read > 0) {
						tmp.flip();
						String data = charset.decode(tmp).toString();
						StringBuilder sb = readMap.get(ch);
						if (sb == null) sb = new StringBuilder();
						sb.append(data);
						int idx;
						while ((idx = sb.indexOf("\n")) != -1) {
							String line = sb.substring(0, idx).trim();
							sb.delete(0, idx + 1);
							System.out.println("[" + safeRemoteAddress(ch) + "] " + line);

							// obtener usuario asociado al socket
							Usuario usuario = socketToUsuario.get(ch);

							// Procesar comandos simples
							if ("salir".equalsIgnoreCase(line) || "4".equals(line)) {
								enqueueWrite(ch, writeMap, ByteBuffer.wrap("Adios!\n".getBytes(charset)));
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								enqueueWrite(ch, writeMap, ByteBuffer.allocate(0)); // marcador de cierre
								SelectionKey k = ch.keyFor(selector);
								if (k != null) k.attach(Boolean.TRUE); // marcar para cerrar después de escribir
								break;
							} else if ("1".equals(line)) {
								// listar usuarios
								List<String> nombres = listaUsuarios.listarNombres();
								if (nombres.isEmpty()) {
									enqueueWrite(ch, writeMap, ByteBuffer.wrap("No hay usuarios.\n".getBytes(charset)));
								} else {
									StringBuilder out = new StringBuilder();
									out.append("Usuarios:\n");
									for (String n : nombres) {
										out.append("- ").append(n).append("\n");
									}
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));
								}
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
							} else if ("2".equals(line)) {
								// listar chats
								if (chats.isEmpty()) {
									enqueueWrite(ch, writeMap, ByteBuffer.wrap("No hay chats disponibles.\n".getBytes(charset)));
								} else {
									StringBuilder out = new StringBuilder();
									out.append("Chats:\n");
									for (Chat_Grupal c : chats) {
										out.append("- ").append(c.getNombre()).append(" (miembros: ").append(c.getMiembros().size()).append(")\n");
									}
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));
								}
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
							} else if ("3".equals(line)) {
								// Unirse al primer chat de prueba si existe
								if (!chats.isEmpty()) {
									Chat_Grupal target = chats.get(0);
									if (usuario != null) {
										target.agregarMiembro(usuario);
										StringBuilder out = new StringBuilder();
										out.append("Te has unido a ").append(target.getNombre()).append("\nMiembros:\n");
										for (String n : target.listarNombresMiembros()) {
											out.append("- ").append(n).append("\n");
										}
										enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));
									} else {
										enqueueWrite(ch, writeMap, ByteBuffer.wrap("Usuario no registrado en servidor.\n".getBytes(charset)));
									}
								} else {
									enqueueWrite(ch, writeMap, ByteBuffer.wrap("No hay chats a los que unirse.\n".getBytes(charset)));
								}
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
							} else {
								// eco simple y volver a enviar menú
								String resp = "Recibido: " + line + "\n" + menuTexto();
								enqueueWrite(ch, writeMap, ByteBuffer.wrap(resp.getBytes(charset)));
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
							}
						}
						readMap.put(ch, sb);
					}
				}

				if (key.isWritable()) {
					SocketChannel ch = (SocketChannel) key.channel();
					Queue<ByteBuffer> q = writeMap.get(ch);
					boolean closeAfter = Boolean.TRUE.equals(key.attachment());
					try {
						while (q != null && !q.isEmpty()) {
							ByteBuffer buf = q.peek();
							// marker: zero-length buffer indicates cierre pedido
							if (buf.remaining() == 0) {
								q.poll();
								closeAfter = true;
								continue;
							}
							ch.write(buf);
							if (buf.hasRemaining()) {
								// socket no puede aceptar más ahora
								break;
							} else {
								q.poll();
							}
						}
					} catch (IOException e) {
						cleanupChannel(ch, key, readMap, writeMap);
						continue;
					}
					if (q == null || q.isEmpty()) {
						// quitar flag de escritura
						try {
							key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
						} catch (CancelledKeyException ignored) {}
					}
					if (closeAfter) {
						cleanupChannel(ch, key, readMap, writeMap);
					}
				}
			}
		}
	}

	private static String menuTexto() {
		StringBuilder m = new StringBuilder();
		m.append("Menu:\n");
		m.append("1 - Listar usuarios\n");
		m.append("2 - Listar chats\n");
		m.append("3 - Unirse al chat de prueba\n");
		m.append("4 - Salir\n");
		m.append("Escribe una opción:\n");
		return m.toString();
	}

	private static void enqueueWrite(SocketChannel ch, Map<SocketChannel, Queue<ByteBuffer>> writeMap, ByteBuffer buf) {
		Queue<ByteBuffer> q = writeMap.get(ch);
		if (q == null) {
			q = new LinkedList<>();
			writeMap.put(ch, q);
		}
		q.add(buf);
	}

	private static void cleanupChannel(SocketChannel ch, SelectionKey key, Map<SocketChannel, StringBuilder> readMap, Map<SocketChannel, Queue<ByteBuffer>> writeMap) {
		try {
			System.out.println("Cerrando canal: " + safeRemoteAddress(ch));
			if (key != null) key.cancel();
			readMap.remove(ch);
			writeMap.remove(ch);
			socketToUsuario.remove(ch);
			ch.close();
		} catch (IOException e) {
			// ignore
		}
	}

	private static String safeRemoteAddress(SocketChannel ch) {
		try {
			return ch.getRemoteAddress().toString();
		} catch (IOException e) {
			return "desconocido";
		}
	}
}
