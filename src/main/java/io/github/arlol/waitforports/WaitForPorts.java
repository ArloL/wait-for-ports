package io.github.arlol.waitforports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public class WaitForPorts {

	private static final int TIMEOUT_MS = 10_000;
	private static final String SUCCESS = "Success";
	private static final Path CONFIG_FILE = Path.of(".wait-for-ports");
	private static final String DEFAULT_URI = "http://localhost:8080";

	public static void main(String[] args) {
		if (args.length == 1 && "--version".equals(args[0])) {
			System.out.println(getVersion());
			System.exit(0);
		}
		try {
			waitForEndpoints(endpoints(args, CONFIG_FILE));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Interrupted while waiting for ports");
		}
	}

	static Collection<URI> endpoints(String[] args, Path configFile) {
		return uris(args, configFile).stream()
				.map(URI::create)
				.collect(Collectors.toSet());
	}

	static Collection<String> uris(String[] args, Path configFile) {
		if (args.length > 0) {
			return Arrays.asList(args);
		}
		if (Files.isReadable(configFile)) {
			try {
				return Files.readAllLines(configFile);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return List.of(DEFAULT_URI);
	}

	// Probes every endpoint once per round until none are left, starting a
	// round every TIMEOUT_MS milliseconds.
	private static void waitForEndpoints(Collection<URI> endpoints)
			throws InterruptedException {
		while (!endpoints.isEmpty()) {
			long startTimeMillis = System.currentTimeMillis();
			probe(endpoints);
			long sleepTime = startTimeMillis + TIMEOUT_MS
					- System.currentTimeMillis();
			if (!endpoints.isEmpty() && sleepTime > 0) {
				Thread.sleep(sleepTime);
			}
		}
	}

	static void probe(Collection<URI> endpoints) throws InterruptedException {
		Iterator<URI> iterator = endpoints.iterator();
		while (iterator.hasNext()) {
			URI uri = iterator.next();
			System.out.print("Testing " + uri + " : ");
			try {
				if (isReady(uri)) {
					iterator.remove();
				}
			} catch (IOException e) {
				System.out.println(e.getMessage());
			}
		}
	}

	// True when the endpoint can be dropped: it answered as expected, or its
	// scheme is not supported.
	static boolean isReady(URI uri) throws IOException, InterruptedException {
		String scheme = uri.getScheme();
		if ("tcp".equals(scheme) || "telnet".equals(scheme)) {
			return isTcpReady(uri);
		}
		if ("http".equals(scheme) || "https".equals(scheme)) {
			return isHttpReady(uri);
		}
		System.out.println(scheme + " not supported");
		return true;
	}

	static boolean isTcpReady(URI uri) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(
					new InetSocketAddress(uri.getHost(), uri.getPort()),
					TIMEOUT_MS
			);
			socket.setSoTimeout(TIMEOUT_MS);
			if (socket.getInputStream().read() == -1) {
				System.out.println("Disconnected");
				return false;
			}
		} catch (SocketTimeoutException e) {
			// A server that accepts but stays silent is up.
		}
		System.out.println(SUCCESS);
		return true;
	}

	static boolean isHttpReady(URI uri)
			throws IOException, InterruptedException {
		// HTTP 1.1 since the fallback to 1.1 times out with yarn server
		// ¯\_(ツ)_/¯
		HttpRequest request = HttpRequest.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.uri(uri)
				.timeout(Duration.ofMillis(TIMEOUT_MS))
				.build();
		int actual = HttpClient.newBuilder()
				.build()
				.send(request, HttpResponse.BodyHandlers.discarding())
				.statusCode();
		if (actual != expectedStatusCode(uri)) {
			System.out.println("Status code is " + actual);
			return false;
		}
		System.out.println(SUCCESS);
		return true;
	}

	static int expectedStatusCode(URI uri) {
		String fragment = uri.getFragment();
		if (fragment != null && !fragment.isBlank()) {
			return Integer.parseInt(fragment);
		}
		return 200;
	}

	public static String getVersion() {
		try {
			Enumeration<URL> resources = WaitForPorts.class.getClassLoader()
					.getResources("META-INF/MANIFEST.MF");
			while (resources.hasMoreElements()) {
				URL url = resources.nextElement();
				Manifest manifest = new Manifest(url.openStream());
				if (isApplicableManifest(manifest)) {
					Attributes attr = manifest.getMainAttributes();
					return get(attr, "Implementation-Title") + " version \""
							+ get(attr, "Implementation-Version") + "\"";
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return "";
	}

	private static boolean isApplicableManifest(Manifest manifest) {
		Attributes attributes = manifest.getMainAttributes();
		return "wait-for-ports".equals(get(attributes, "Implementation-Title"));
	}

	private static Object get(Attributes attributes, String key) {
		return attributes.get(new Attributes.Name(key));
	}

}
