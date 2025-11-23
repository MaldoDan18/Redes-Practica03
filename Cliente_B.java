import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.io.FileOutputStream;

public class Cliente_B {
	private static final String HOST = "localhost";
	private static final int PORT = 5000;

	public static void main(String[] args) {
		try (SocketChannel client = SocketChannel.open()) {
			client.connect(new InetSocketAddress(HOST, PORT));
			System.out.println("Conectado al servidor " + safeRemoteAddress(client));
			System.out.println("Escribe una opción (1-6) o 'salir'. (5 o 'salir' cierra la conexión). Puedes usar /sendfile <ruta> o /priv <id> <msg>");

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
				
				// 1. Detección y codificación de archivos
				if (line.trim().toLowerCase().startsWith("/sendfile ")) {
					
					String filePath = line.trim().substring(10).trim();
					line = processAndEncodeFile(filePath); 
					
					if (line.startsWith("ERROR")) {
						System.err.println("Error al procesar archivo: " + line.substring(6));
						continue; // No enviamos nada si hay error
					}
					
					System.out.println("DEBUG: Preparando para enviar archivo codificado. Tamaño de mensaje: " + line.length() + " caracteres.");
				}
				
				// 2. Mensaje de debug simple
				System.out.println("DEBUG: Enviando entrada -> " + line.trim());
				
				// 3. Enviar al servidor
				String toSend = line + "\n";
				ByteBuffer out = ByteBuffer.wrap(toSend.getBytes(StandardCharsets.UTF_8));
				while (out.hasRemaining()) {
					client.write(out);
				}

				// 4. Cerrar cliente si es necesario
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
    
   //Métodos de soporte para el cliente

	// Metodo para leer, codificar y generar el protocolo /file
	private static String processAndEncodeFile(String filePath) {
		try {
			File file = new File(filePath);
			if (!file.exists()) {
				return "ERROR: Archivo no encontrado en la ruta: " + filePath;
			}
			if (file.length() > 2097152) { // Limitar a 2MB
				return "ERROR: El archivo es demasiado grande (Máx. 2MB).";
			}
			
			// 1. Leer bytes del archivo
			byte[] fileBytes = Files.readAllBytes(file.toPath());
			
			// 2. Codificar los bytes a Base64
			String base64Content = Base64.getEncoder().encodeToString(fileBytes);
			
			// 3. Devolver la línea de protocolo para el Servidor: /file <nombre> <contenido>
			return "/file " + file.getName() + " " + base64Content;
			
		} catch (IOException e) {
			return "ERROR: Falló la lectura/codificación: " + e.getMessage();
		}
	}

	// Nuevo: hilo lector que imprime cada línea recibida y la encola
	private static Thread startReaderThread(SocketChannel client, BlockingQueue<String> incoming) {
		Thread t = new Thread(() -> {
			ByteBuffer buf = ByteBuffer.allocate(4096); // Aumentar buffer
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
							
							
							// LÓGICA DE RECEPCIÓN Y DECODIFICACIÓN DE ARCHIVOS 
							
							if (line.startsWith("FILE_INCOMING|")) {
								
								// 1. Descomponer el protocolo: FILE_INCOMING|<sender>|<name>|<base64>
								String[] parts = line.substring(14).split("\\|", 3);
								if (parts.length == 3) {
									String senderName = parts[0];
									String fileName = parts[1];
									String base64Content = parts[2].trim().replaceAll("\\s", "").replaceAll("[^a-zA-Z0-9+/=]", "");
									
									// 2. Decodificar Base64 a bytes
									byte[] fileBytes = Base64.getDecoder().decode(base64Content);
									
									// 3. Guardar el archivo
									try (FileOutputStream fos = new FileOutputStream("RECEIVED_" + fileName)) {
										fos.write(fileBytes);
										System.out.println("\n--- ARCHIVO RECIBIDO ---");
										System.out.println("De: " + senderName);
										System.out.println("Guardado como: RECEIVED_" + fileName + " (Tamaño: " + fileBytes.length / 1024 + " KB)");
										System.out.println("------------------------\nEscribe entrada ->");
										
									} catch (IOException e) {
										System.err.println("Error al guardar archivo: " + e.getMessage());
									}
								}
								
							} else {
								// Mensajes de chat normales y comandos del servidor
								System.out.println("Servidor: " + line);
							}
							// ==========================================================
							
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

	// Método auxiliar para direcciones
	private static String safeRemoteAddress(SocketChannel ch) {
		try {
			return ch.getRemoteAddress().toString();
		} catch (IOException e) {
			return "desconocido";
		}
	}
}
