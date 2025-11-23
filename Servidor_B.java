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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

	// Estados interactivos por cliente
	private static final Map<SocketChannel, String> pendingState = new HashMap<>();
	private static final Map<SocketChannel, String> pendingTemp = new HashMap<>();
	private static final String S_IDLE = "IDLE";
	private static final String S_AWAIT_CREATE_IDS = "AWAIT_CREATE_IDS";
	private static final String S_AWAIT_CREATE_NAME = "AWAIT_CREATE_NAME";
	private static final String S_AWAIT_ENTER_CHAT = "AWAIT_ENTER_CHAT";
	private static final String S_AWAIT_LEAVE_CHAT = "AWAIT_LEAVE_CHAT";
	private static final String S_IN_CHAT = "IN_CHAT";

	// Map de id usuario -> socket (si está conectado)
	private static final Map<Integer, SocketChannel> userIdToSocket = new HashMap<>();

	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

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
						listaUsuarios.agregarUsuario(nuevo);
						socketToUsuario.put(client, nuevo);
						userIdToSocket.put(id, client);
						pendingState.put(client, S_IDLE);

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
							String state = pendingState.getOrDefault(ch, S_IDLE);

							// Flujos interactivos primera prioridad
							// Si está en modo de chat activo, tratamos las líneas como mensajes de chat
							if (S_IN_CHAT.equals(state)) {
								// 1. Lógica de Mensaje Privado (/priv)
								if (line.toLowerCase().startsWith("/priv ")) {
									String[] parts = line.substring(6).trim().split("\\s+", 2);
									if (parts.length == 2) {
										try {
											int destId = Integer.parseInt(parts[0]);
											String privMsg = parts[1];
											
											// Encontrar el SocketChannel del destinatario usando el ID
											SocketChannel destCh = userIdToSocket.get(destId);
											Usuario destUser = listaUsuarios.getUsuarioPorId(destId);
											
											if (destCh != null && destCh.isOpen() && destUser != null) {
												// Mensaje que verá el DESTINATARIO
												String time = LocalTime.now().format(TIME_FMT);
												String msgForDest = "[PRIVADO de " + usuario.getNombre() + " - " + time + "] : " + privMsg;
												
												// Enviar al DESTINATARIO
												enqueueWrite(destCh, writeMap, ByteBuffer.wrap((msgForDest + "\n").getBytes(charset)));
												SelectionKey sk = destCh.keyFor(selector);
												if (sk != null) sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
												
												// Mensaje de confirmación que verá el EMISOR
												String confirmation = "[PRIVADO a " + destUser.getNombre() + "] Enviado.";
												enqueueWrite(ch, writeMap, ByteBuffer.wrap((confirmation + "\n").getBytes(charset)));
											} else {
												enqueueWrite(ch, writeMap, ByteBuffer.wrap(("Error: ID " + destId + " no encontrado o no conectado.\n").getBytes(charset)));
											}
											
										} catch (NumberFormatException e) {
											enqueueWrite(ch, writeMap, ByteBuffer.wrap("Error: Formato incorrecto. Uso: /priv <ID> <mensaje>\n".getBytes(charset)));
										}
									} else {
										enqueueWrite(ch, writeMap, ByteBuffer.wrap("Error: Uso: /priv <ID> <mensaje>\n".getBytes(charset)));
									}
								}
								
								// 2. Lógica de Envío de Archivo (/file)
								else if (line.toLowerCase().startsWith("/file ")) {
									String fileDataLine = line.substring(6).trim();
									String[] parts = fileDataLine.split("\\s+", 2);

									if (parts.length == 2) {
										String fileName = parts[0].trim();
										String base64Content = parts[1].trim();
										
										// PROTOCOLO DE RECEPCIÓN: FILE_INCOMING|<sender>|<name>|<base64>
										String protocolMsg = "FILE_INCOMING|" + usuario.getNombre() + "|" + fileName + "|" + base64Content; 

										// Difundir el mensaje de archivo a TODOS los miembros del chat.
										String chatName = pendingTemp.get(ch);
										if (chatName != null) {
											Chat_Grupal current = null;
											for (Chat_Grupal c : chats) if (c.getNombre().equalsIgnoreCase(chatName)) { current = c; break; }
											if (current != null) {
												// Notificar a todos los miembros del chat 
												for (Usuario member : current.getMiembros()) {
													SocketChannel dest = userIdToSocket.get(member.getId());
													if (dest != null && dest.isOpen()) {
														// Enviamos el mensaje de protocolo de recepción (FILE_INCOMING)
														enqueueWrite(dest, writeMap, ByteBuffer.wrap((protocolMsg + "\n").getBytes(charset)));
														SelectionKey sk = dest.keyFor(selector);
														if (sk != null) sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
													}
												}
											} else {
												enqueueWrite(ch, writeMap, ByteBuffer.wrap(("Error: No estás en un chat activo.\n").getBytes(charset)));
											}
										}
									} else {
										enqueueWrite(ch, writeMap, ByteBuffer.wrap("Error: Uso: /file <nombre_archivo> <contenido_base64>\n".getBytes(charset)));
									}
								}
								
								// 3. Lógica para volver al menú (MEN0)
								else if ("MEN0".equalsIgnoreCase(line.trim())) {
									pendingState.put(ch, S_IDLE);
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(("Saliendo del chat. " + menuTexto()).getBytes(charset)));
								} else {
									// Lógica de difusión Grupal (Mensaje normal)
									String chatName = pendingTemp.get(ch);
									if (chatName != null) {
										Chat_Grupal current = null;
										for (Chat_Grupal c : chats) if (c.getNombre().equalsIgnoreCase(chatName)) { current = c; break; }
										if (current != null && usuario != null) {
											String time = LocalTime.now().format(TIME_FMT);
											String msg = usuario.getNombre() + " - [" + time + "] : " + line;
											// Guardar en historial (sin \n)
											current.addMessage(msg);
											// difundir a todos los miembros conectados (incluye emisor)
											for (Usuario member : current.getMiembros()) {
												SocketChannel dest = userIdToSocket.get(member.getId());
												if (dest != null && dest.isOpen()) {
													enqueueWrite(dest, writeMap, ByteBuffer.wrap((msg + "\n").getBytes(charset)));
													SelectionKey sk = dest.keyFor(selector);
													if (sk != null) sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
												}
											}
										} else {
											enqueueWrite(ch, writeMap, ByteBuffer.wrap(("Chat no disponible: " + chatName + "\n").getBytes(charset)));
											pendingState.put(ch, S_IDLE);
										}
									} else {
										enqueueWrite(ch, writeMap, ByteBuffer.wrap("No hay chat activo.\n".getBytes(charset)));
										pendingState.put(ch, S_IDLE);
									}
								}
								SelectionKey ck0 = ch.keyFor(selector);
								if (ck0 != null) ck0.interestOps(ck0.interestOps() | SelectionKey.OP_WRITE);
								continue;
							}

							if (S_AWAIT_CREATE_IDS.equals(state)) {
								// línea recibida: IDs separados por comas
								pendingTemp.put(ch, line);
								enqueueWrite(ch, writeMap, ByteBuffer.wrap("Escribe nombre del chat grupal:\n".getBytes(charset)));
								pendingState.put(ch, S_AWAIT_CREATE_NAME);
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								continue;
							} else if (S_AWAIT_CREATE_NAME.equals(state)) {
								// línea recibida: nombre del chat
								String idsLine = pendingTemp.remove(ch);
								String chatName = line;
								Chat_Grupal nuevoChat = new Chat_Grupal(chatName);
								if (idsLine != null && !idsLine.trim().isEmpty()) {
									String[] parts = idsLine.split("[,\\s]+");
									for (String p : parts) {
										try {
											int uid = Integer.parseInt(p.trim());
											Usuario u = listaUsuarios.getUsuarioPorId(uid);
											if (u != null) {
												nuevoChat.agregarMiembro(u);
											}
										} catch (NumberFormatException ignore) {}
									}
								}
								// Asegurar al menos agregar al creador
								if (usuario != null) nuevoChat.agregarMiembro(usuario);
								chats.add(nuevoChat);

								// Notificar historial (nuevo chat vacío) y notificaciones de unión
								// Crear notificación de unión del creador
								String joinNotif = (usuario != null ? usuario.getNombre() : "usuario") + " se unió al chat";
								// Guardar en historial
								nuevoChat.addMessage(joinNotif);
								// Enviar notificación a miembros conectados
								for (Usuario member : nuevoChat.getMiembros()) {
									SocketChannel dest = userIdToSocket.get(member.getId());
									if (dest != null && dest.isOpen()) {
										enqueueWrite(dest, writeMap, ByteBuffer.wrap((joinNotif + "\n").getBytes(charset)));
										SelectionKey sk = dest.keyFor(selector);
										if (sk != null) sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
									}
								}

								StringBuilder out = new StringBuilder();
								out.append("Chat creado: ").append(chatName).append("\nMiembros:\n");
								for (String n : nuevoChat.listarNombresMiembros()) {
									out.append("- ").append(n).append("\n");
								}
								out.append("Entrando al chat. Para volver al menu escribe MEN0\n");
								enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));
								// entrar automáticamente al chat creado
								pendingState.put(ch, S_IN_CHAT);
								pendingTemp.put(ch, chatName);
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								continue;
							} else if (S_AWAIT_ENTER_CHAT.equals(state)) {
								// Entrar a chat por nombre
								String chatName = line;
								Chat_Grupal target = null;
								for (Chat_Grupal c : chats) if (c.getNombre().equalsIgnoreCase(chatName)) { target = c; break; }
								if (target != null && usuario != null) {
									target.agregarMiembro(usuario);

									// 1) Enviar inmediatamente el historial al cliente que entra
									List<String> history = target.getHistory();
									if (!history.isEmpty()) {
										for (String h : history) {
											enqueueWrite(ch, writeMap, ByteBuffer.wrap((h + "\n").getBytes(charset)));
										}
									}

									// 2) Enviar estado de presencia (debug) al cliente que entra
									for (Usuario m : target.getMiembros()) {
										boolean online = (userIdToSocket.get(m.getId()) != null && userIdToSocket.get(m.getId()).isOpen());
										String pres = "DEBUG: " + m.getNombre() + (online ? " está en linea" : " está desconectado");
										enqueueWrite(ch, writeMap, ByteBuffer.wrap((pres + "\n").getBytes(charset)));
									}

									// 3) Notificar a todos que este usuario se unió y guardar en historial
									String joinNotif = usuario.getNombre() + " se unió al chat";
									target.addMessage(joinNotif);
									for (Usuario member : target.getMiembros()) {
										SocketChannel dest = userIdToSocket.get(member.getId());
										if (dest != null && dest.isOpen()) {
											enqueueWrite(dest, writeMap, ByteBuffer.wrap((joinNotif + "\n").getBytes(charset)));
											SelectionKey sk = dest.keyFor(selector);
											if (sk != null) sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
										}
									}

									// 4) Informar al que entró y poner estado IN_CHAT
									StringBuilder out = new StringBuilder();
									out.append("Te has unido a ").append(target.getNombre()).append("\nMiembros:\n");
									for (String n : target.listarNombresMiembros()) out.append("- ").append(n).append("\n");
									out.append("Entrando al chat. Para volver al menu escribe MEN0\n");
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));

									// poner estado IN_CHAT
									pendingState.put(ch, S_IN_CHAT);
									pendingTemp.put(ch, target.getNombre());
								} else {
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(("Chat no encontrado: " + chatName + "\n").getBytes(charset)));
									// sólo volver a IDLE si no se encontró el chat
									pendingState.put(ch, S_IDLE);
								}
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								continue;
							} else if (S_AWAIT_LEAVE_CHAT.equals(state)) {
								// Salir de chat por nombre
								String chatName = line;
								Chat_Grupal target = null;
								for (Chat_Grupal c : chats) if (c.getNombre().equalsIgnoreCase(chatName)) { target = c; break; }
								if (target != null && usuario != null) {
									target.eliminarMiembro(usuario);
									String leaveNotif = usuario.getNombre() + " ha salido del chat";
									// guardar en historial y notificar
									target.addMessage(leaveNotif);
									for (Usuario member : target.getMiembros()) {
										SocketChannel dest = userIdToSocket.get(member.getId());
										if (dest != null && dest.isOpen()) {
											enqueueWrite(dest, writeMap, ByteBuffer.wrap((leaveNotif + "\n").getBytes(charset)));
											SelectionKey sk = dest.keyFor(selector);
											if (sk != null) sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
										}
									}
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(("Has salido de " + target.getNombre() + "\n").getBytes(charset)));
								} else {
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(("Chat no encontrado: " + chatName + "\n").getBytes(charset)));
								}
								pendingState.put(ch, S_IDLE);
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								continue;
							}

							// Procesar comandos simples cuando está en IDLE
							if ("salir".equalsIgnoreCase(line) || "5".equals(line)) {
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
									out.append("Usuarios (id - nombre):\n");
									for (Usuario u : listaUsuarios.listarUsuariosActivos()) {
										out.append(u.getId()).append(" - ").append(u.getNombre()).append("\n");
									}
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));
								}
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
							} else if ("2".equals(line)) {
								// listar chats disponibles
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
								// Crear chat grupal: enviar lista de usuarios y pedir IDs
								StringBuilder out = new StringBuilder();
								out.append("Crear chat - lista de usuarios (id - nombre):\n");
								for (Usuario u : listaUsuarios.listarUsuariosActivos()) {
									out.append(u.getId()).append(" - ").append(u.getNombre()).append("\n");
								}
								out.append("Escribe los IDs separados por comas (puedes incluirte):\n");
								enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));
								pendingState.put(ch, S_AWAIT_CREATE_IDS);
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								continue;
							} else if ("4".equals(line)) {
								// Listar los chats a los que pertenece y pedir nombre para entrar
								if (usuario == null) {
									enqueueWrite(ch, writeMap, ByteBuffer.wrap("Usuario no registrado.\n".getBytes(charset)));
								} else {
									List<Chat_Grupal> mine = usuario.getChatsAsociados();
									StringBuilder out = new StringBuilder();
									out.append("Tus chats:\n");
									for (Chat_Grupal c : mine) {
										out.append("- ").append(c.getNombre()).append("\n");
									}
									out.append("Si quieres entrar a un chat (o crearlo con opción 3), escribe el nombre del chat ahora:\n");
									enqueueWrite(ch, writeMap, ByteBuffer.wrap(out.toString().getBytes(charset)));
									pendingState.put(ch, S_AWAIT_ENTER_CHAT);
								}
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								continue;
							} else if ("6".equals(line)) {
								// Salir de un chat grupal: pedir nombre
								enqueueWrite(ch, writeMap, ByteBuffer.wrap("Escribe el nombre del chat del que quieres salir:\n".getBytes(charset)));
								pendingState.put(ch, S_AWAIT_LEAVE_CHAT);
								SelectionKey ck = ch.keyFor(selector);
								if (ck != null) ck.interestOps(ck.interestOps() | SelectionKey.OP_WRITE);
								continue;
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
		m.append("3 - Crear chat grupal\n");
		m.append("4 - Listar mis chats y entrar\n");
		m.append("6 - Salir de chat grupal\n");
		m.append("5 - Salir\n");
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
			Usuario u = socketToUsuario.remove(ch);
			if (u != null) {
				// marcar como desconectado en el registro global
				u.setActive(false);
				userIdToSocket.remove(u.getId());
				// Notificar a los chats de este usuario que está desconectado (debug)
				for (Chat_Grupal c : u.getChatsAsociados()) {
					String off = "DEBUG: " + u.getNombre() + " se ha desconectado";
					c.addMessage(off);
					for (Usuario member : c.getMiembros()) {
						SocketChannel dest = userIdToSocket.get(member.getId());
						if (dest != null && dest.isOpen()) {
							enqueueWrite(dest, writeMap, ByteBuffer.wrap((off + "\n").getBytes(StandardCharsets.UTF_8)));
							SelectionKey sk = dest.keyFor(key.selector());
							if (sk != null) sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
						}
					}
				}
			}
			pendingState.remove(ch);
			pendingTemp.remove(ch);
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
