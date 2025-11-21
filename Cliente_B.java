import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Cliente_B {
	private static final String HOST = "localhost";
	private static final int PORT = 5000;

	public static void main(String[] args) {
		try (SocketChannel client = SocketChannel.open()) {
			client.connect(new InetSocketAddress(HOST, PORT));
			System.out.println("Conectado al servidor " + safeRemoteAddress(client));
			System.out.println("Escribe una opción (1-6) o 'salir'. (5 o 'salir' cierra la conexión). El servidor mostrará el menú al conectarte.");

			// Nuevo: cola para recibir líneas desde el hilo lector
			BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
			Thread reader = startReaderThread(client, incoming);
			// Esperar y mostrar bienvenida/menu inicial enviado por el servidor
			collectUntilMatch(incoming, new String[] { "Escribe una opción" }, 5000);

			// debug print (opcional)
			System.out.println("DEBUG: recibido welcome (ver líneas anteriores)");

			BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
			String line;
			while ((line = console.readLine()) != null) {
				String opt = line.trim();
				switch (opt.toLowerCase()) {
					case "1":
						System.out.println("DEBUG: Opción seleccionada -> 1 (Listar usuarios)");
						break;
					case "2":
						System.out.println("DEBUG: Opción seleccionada -> 2 (Listar chats)");
						break;
					case "3":
						System.out.println("DEBUG: Opción seleccionada -> 3 (Crear chat grupal)");
						break;
					case "4":
						System.out.println("DEBUG: Opción seleccionada -> 4 (Listar mis chats / Entrar)");
						break;
					case "6":
						System.out.println("DEBUG: Opción seleccionada -> 6 (Salir de chat grupal)");
						break;
					case "5":
						System.out.println("DEBUG: Opción seleccionada -> 5 (Salir del servidor)");
						break;
					case "salir":
						System.out.println("DEBUG: Comando textual -> salir (cerrar cliente)");
						break;
					default:
						if ("men0".equalsIgnoreCase(opt)) {
							System.out.println("DEBUG: Comando de chat -> MEN0 (volver al menú)");
						} else {
							System.out.println("DEBUG: Mensaje libre/entrada -> " + opt);
						}
						break;
				}

				// enviar al servidor
				String toSend = line + "\n";
				ByteBuffer out = ByteBuffer.wrap(toSend.getBytes(StandardCharsets.UTF_8));
				while (out.hasRemaining()) {
					client.write(out);
				}

				// Después de enviar una opción, recoger líneas entrantes durante un tiempo
				// para detectar si el servidor indica que entramos al chat o muestra el menú.
				String collected = collectUntilMatch(incoming, new String[] {
						"Entrando al chat", "te has unido", "chat creado", "Escribe una opción"
				}, 5000);

				// Si detectamos que entramos al chat, cambiar a modo chat inmediatamente.
				if (collected != null && (collected.toLowerCase().contains("entrando al chat")
						|| collected.toLowerCase().contains("te has unido")
						|| collected.toLowerCase().contains("chat creado"))) {
					System.out.println("DEBUG: entrando en modo CHAT. Escribe MEN0 para salir del chat.");
					// modo chat: el hilo lector seguirá imprimiendo broadcasts en tiempo real.
					while ((line = console.readLine()) != null) {
						System.out.println("DEBUG[chat] enviando -> " + line);
						String toSendMsg = line + "\n";
						ByteBuffer outMsg = ByteBuffer.wrap(toSendMsg.getBytes(StandardCharsets.UTF_8));
						while (outMsg.hasRemaining()) client.write(outMsg);
						// si es MEN0, esperar a que servidor reenvíe el menú y salir del modo chat
						if ("MEN0".equalsIgnoreCase(line.trim())) {
							collectUntilMatch(incoming, new String[] { "Escribe una opción" }, 5000);
							System.out.println("DEBUG: volvemos al menu (ver líneas anteriores)");
							break;
						}
						// no se bloquea para leer broadcasts: reader thread los imprimirá automáticamente
					}
				}

				// cerrar cliente sólo si envía 5 o 'salir'
				if ("salir".equalsIgnoreCase(line.trim()) || "5".equals(line.trim())) {
					System.out.println("Has solicitado salir. Cerrando conexión...");
					break;
				}
			}

			// ordenada: parar lector e cerrar socket
			reader.interrupt();
			try { reader.join(500); } catch (InterruptedException ignored) {}
			client.close();
			System.out.println("Conexión cerrada.");
		} catch (IOException e) {
			System.err.println("No se pudo conectar: " + e.getMessage());
		}
	}

	// Nuevo: hilo lector que imprime cada línea recibida y la encola
	private static Thread startReaderThread(SocketChannel client, BlockingQueue<String> incoming) {
		Thread t = new Thread(() -> {
			ByteBuffer buf = ByteBuffer.allocate(2048);
			StringBuilder sb = new StringBuilder();
			try {
				while (!Thread.currentThread().isInterrupted()) {
					buf.clear();
					int r = client.read(buf); // blocking read
					if (r == -1) {
						System.out.println("Servidor cerró la conexión.");
						break;
					} else if (r > 0) {
						buf.flip();
						sb.append(StandardCharsets.UTF_8.decode(buf).toString());
						int idx;
						while ((idx = sb.indexOf("\n")) != -1) {
							String line = sb.substring(0, idx).trim();
							sb.delete(0, idx + 1);
							System.out.println("Servidor: " + line);
							// intentar encolar sin bloquear indefinidamente
							incoming.offer(line);
						}
					}
				}
			} catch (IOException e) {
				// si ocurre error de E/S, terminar hilo
			}
		}, "cliente-reader");
		t.setDaemon(true);
		t.start();
		return t;
	}

	// Nuevo: recoge líneas desde la cola hasta encontrar alguna que coincida con algún patrón
	// o hasta timeout (ms). Devuelve las líneas concatenadas que se hayan colectado (puede ser "")
	private static String collectUntilMatch(BlockingQueue<String> incoming, String[] patterns, long timeoutMs) {
		StringBuilder sb = new StringBuilder();
		long deadline = System.currentTimeMillis() + timeoutMs;
		try {
			while (System.currentTimeMillis() < deadline) {
				long wait = Math.max(1, deadline - System.currentTimeMillis());
				String line = incoming.poll(wait, TimeUnit.MILLISECONDS);
				if (line == null) continue;
				sb.append(line).append("\n");
				// buscar patrones
				String low = line.toLowerCase();
				for (String p : patterns) {
					if (low.contains(p.toLowerCase())) {
						return sb.toString();
					}
				}
			}
		} catch (InterruptedException ignored) {}
		return sb.length() > 0 ? sb.toString() : null;
	}

	private static String safeRemoteAddress(SocketChannel ch) {
		try {
			return ch.getRemoteAddress().toString();
		} catch (IOException e) {
			return "desconocido";
		}
	}
}
