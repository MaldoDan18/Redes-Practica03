import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class Cliente {
	private static final String HOST = "localhost";
	private static final int PORT = 5000;

	public static void main(String[] args) {
		try (SocketChannel client = SocketChannel.open()) {
			client.connect(new InetSocketAddress(HOST, PORT));
			System.out.println("Conectado al servidor " + safeRemoteAddress(client));
			System.out.println("El servidor espera que escribas 'salir' para desconectar.");

			// Hilo lector de respuestas del servidor
			Thread reader = new Thread(() -> {
				ByteBuffer buf = ByteBuffer.allocate(2048);
				StringBuilder sb = new StringBuilder();
				try {
					while (true) {
						buf.clear();
						int r = client.read(buf);
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
							}
						}
					}
				} catch (IOException e) {
					System.err.println("Error lectura servidor: " + e.getMessage());
				}
			}, "reader");
			reader.setDaemon(true);
			reader.start();

			// Enviar datos desde la consola
			BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
			String line;
			while ((line = console.readLine()) != null) {
				String toSend = line + "\n";
				ByteBuffer out = ByteBuffer.wrap(toSend.getBytes(StandardCharsets.UTF_8));
				while (out.hasRemaining()) {
					client.write(out);
				}
				if ("salir".equalsIgnoreCase(line.trim())) {
					System.out.println("Has solicitado salir. Cerrando conexión...");
					break;
				}
			}

			client.close();
			System.out.println("Conexión cerrada.");
		} catch (IOException e) {
			System.err.println("No se pudo conectar: " + e.getMessage());
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