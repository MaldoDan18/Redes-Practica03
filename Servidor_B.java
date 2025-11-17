import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class Servidor_B {
	// Puerto del servidor
	private static final int PORT = 5000;

	public static void main(String[] args) throws IOException {
		ServerSocketChannel serverChannel = ServerSocketChannel.open();
		serverChannel.bind(new InetSocketAddress(PORT));
		serverChannel.configureBlocking(false);

		Selector acceptSelector = Selector.open();
		serverChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);

		System.out.println("Servidor escuchando en puerto " + PORT);

		while (true) {
			acceptSelector.select();
			Iterator<SelectionKey> it = acceptSelector.selectedKeys().iterator();
			while (it.hasNext()) {
				SelectionKey key = it.next();
				it.remove();
				if (key.isAcceptable()) {
					SocketChannel client = serverChannel.accept();
					if (client != null) {
						client.configureBlocking(false);
						System.out.println("Conexión aceptada desde " + safeRemoteAddress(client));
						// Lanzar hilo por cliente
						new Thread(new ClientHandler(client)).start();
					}
				}
			}
		}
	}

	// Handler por cliente que usa socket no bloqueante + selector propio
	private static class ClientHandler implements Runnable {
		private final SocketChannel client;
		private final Charset charset = StandardCharsets.UTF_8;

		ClientHandler(SocketChannel client) {
			this.client = client;
		}

		@Override
		public void run() {
			try (SocketChannel ch = this.client) {
				Selector sel = Selector.open();
				ch.register(sel, SelectionKey.OP_READ);

				// Enviar mensaje de bienvenida
				writeString(ch, "Bienvenido al servidor. Escribe 'salir' para desconectar.\n");

				ByteBuffer buf = ByteBuffer.allocate(2048);
				StringBuilder sb = new StringBuilder();
				boolean running = true;

				while (running && ch.isOpen()) {
					int ready = sel.select(); // bloquea hasta que haya datos (no bloquea el socket)
					if (ready == 0) {
						continue;
					}
					Iterator<SelectionKey> it = sel.selectedKeys().iterator();
					while (it.hasNext()) {
						SelectionKey key = it.next();
						it.remove();
						if (key.isReadable()) {
							buf.clear();
							int read = ch.read(buf);
							if (read == -1) {
								System.out.println("Cliente desconectado: " + safeRemoteAddress(ch));
								running = false;
								break;
							} else if (read > 0) {
								buf.flip();
								String data = charset.decode(buf).toString();
								sb.append(data);
								// Procesar líneas completas
								int idx;
								while ((idx = sb.indexOf("\n")) != -1) {
									String line = sb.substring(0, idx).trim();
									sb.delete(0, idx + 1);
									System.out.println("[" + safeRemoteAddress(ch) + "] " + line);
									if ("salir".equalsIgnoreCase(line)) {
										writeString(ch, "Adios!\n");
										running = false;
										break;
									} else {
										// eco simple
										writeString(ch, "Recibido: " + line + "\n");
									}
								}
							}
						}
					}
				}
				sel.close();
			} catch (IOException e) {
				System.err.println("Error en cliente: " + e.getMessage());
			}
		}

		private void writeString(SocketChannel ch, String msg) throws IOException {
			ByteBuffer out = ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8));
			while (out.hasRemaining()) {
				ch.write(out);
			}
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
