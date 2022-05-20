package io.github.arlol.waitforports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class WaitForPorts {

	private static final int TIMEOUT_MS = 10_000;

	public static void main(String[] args) {
		try {
			Path configFile = Paths.get(".wait-for-ports");
			Collection<String> uris = List.of("http://localhost:8080");
			if (args.length > 0) {
				uris = Arrays.asList(args);
			} else if (Files.isReadable(configFile)) {
				try {
					uris = Files.readAllLines(configFile);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			Collection<URI> endpoints = uris.stream().map(URI::create).collect(Collectors.toSet());
			while (!endpoints.isEmpty()) {
				Iterator<URI> iterator = endpoints.iterator();
				long startTimeMillis = System.currentTimeMillis();
				while (iterator.hasNext()) {
					URI uri = iterator.next();
					System.out.print("Testing " + uri + " : ");
					try {
						if ("tcp".equals(uri.getScheme()) || "telnet".equals(uri.getScheme())) {
							try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
								socket.setSoTimeout(TIMEOUT_MS);
								if (socket.getInputStream().read() != -1) {
									System.out.println("Success");
									iterator.remove();
								} else {
									System.out.println("Disconnected");
								}
							} catch (SocketTimeoutException e) {
								System.out.println("Success");
								iterator.remove();
							}
						} else if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
							// HTTP 1.1 since the fallback to 1.1 times out with yarn server ¯\_(ツ)_/¯
							HttpRequest request = HttpRequest.newBuilder().version(HttpClient.Version.HTTP_1_1).uri(uri)
									.timeout(Duration.ofMillis(TIMEOUT_MS)).build();
							int expected = 200;
							if (uri.getFragment() != null && !uri.getFragment().isBlank()) {
								expected = Integer.parseInt(uri.getFragment());
							}
							int actual = HttpClient.newBuilder().build()
									.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
							if (actual == expected) {
								System.out.println("Success");
								iterator.remove();
							} else {
								System.out.println("Status code is " + actual);
							}
						} else {
							System.out.println(uri.getScheme() + " not supported");
							iterator.remove();
						}
					} catch (IOException e) {
						System.out.println(e.getMessage());
					}
				}
				long sleepTime = startTimeMillis + TIMEOUT_MS - System.currentTimeMillis();
				if (!endpoints.isEmpty() && sleepTime > 0) {
					Thread.sleep(sleepTime);
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
