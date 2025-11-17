import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class Cliente_B {
	private static final String HOST = "localhost";
	private static final int PORT = 5000;

	public static void main(String[] args) {
		try (SocketChannel client = SocketChannel.open()) {
			client.connect(new InetSocketAddress(HOST, PORT));
			System.out.println("Conectado al servidor " + safeRemoteAddress(client));
			System.out.println("Escribe una opción (1-4) o 'salir'. El servidor mostrará el menú al conectarte.");

			// Leer mensaje(s) inicial(es) del servidor (bloqueante hasta recibir datos)
			readAndPrintLines(client);

			// Enviar datos desde la consola y leer respuesta después de cada envío
			BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
			String line;
			while ((line = console.readLine()) != null) {
				String toSend = line + "\n";
				ByteBuffer out = ByteBuffer.wrap(toSend.getBytes(StandardCharsets.UTF_8));
				while (out.hasRemaining()) {
					client.write(out);
				}

				// Leer la(s) respuesta(s) del servidor para la línea enviada (bloqueante)
				readAndPrintLines(client);

				if ("salir".equalsIgnoreCase(line.trim()) || "4".equals(line.trim())) {
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

	private static void readAndPrintLines(SocketChannel client) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(2048);
		StringBuilder sb = new StringBuilder();
		// Leer al menos una línea completa (bloqueante)
		while (true) {
			buf.clear();
			int r = client.read(buf);
			if (r == -1) {
				System.out.println("Servidor cerró la conexión.");
				throw new IOException("Conexión cerrada por servidor");
			} else if (r > 0) {
				buf.flip();
				sb.append(StandardCharsets.UTF_8.decode(buf).toString());
				int idx;
				boolean printed = false;
				while ((idx = sb.indexOf("\n")) != -1) {
					String line = sb.substring(0, idx).trim();
					sb.delete(0, idx + 1);
					System.out.println("Servidor: " + line);
					printed = true;
				}
				if (printed) break; // al menos una línea completa recibida
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