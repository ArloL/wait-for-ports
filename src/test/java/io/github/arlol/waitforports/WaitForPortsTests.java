package io.github.arlol.waitforports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

public class WaitForPortsTests {

	private static final String[] NO_ARGS = {};

	@TempDir
	Path tempDir;

	private PrintStream originalOut;
	private PrintStream originalErr;
	private ByteArrayOutputStream capturedOut;
	private ByteArrayOutputStream capturedErr;

	@BeforeEach
	void redirectStdOut() {
		originalOut = System.out;
		originalErr = System.err;
		capturedOut = new ByteArrayOutputStream();
		capturedErr = new ByteArrayOutputStream();
		System.setOut(
				new PrintStream(capturedOut, true, StandardCharsets.UTF_8)
		);
		System.setErr(
				new PrintStream(capturedErr, true, StandardCharsets.UTF_8)
		);
	}

	@AfterEach
	void restoreStdOut() {
		System.setOut(originalOut);
		System.setErr(originalErr);
	}

	private String output() {
		return capturedOut.toString(StandardCharsets.UTF_8);
	}

	private String errorOutput() {
		return capturedErr.toString(StandardCharsets.UTF_8);
	}

	private void runMain(String... args) {
		assertTimeoutPreemptively(
				Duration.ofSeconds(30),
				() -> WaitForPorts.main(args)
		);
	}

	private static int closedPort() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			return serverSocket.getLocalPort();
		}
	}

	@Test
	void getVersionReportsArtifactTitleAndVersion() {
		String version = WaitForPorts.getVersion();
		assertTrue(
				version.startsWith("wait-for-ports version \""),
				() -> "Unexpected version string: " + version
		);
		assertTrue(
				version.endsWith("\""),
				() -> "Unexpected version string: " + version
		);
	}

	@Test
	void unsupportedSchemeIsReportedAndSkipped() {
		runMain("ftp://localhost");

		String output = output();
		assertTrue(
				output.contains("Testing ftp://localhost"),
				() -> "Expected endpoint to be tested, was: " + output
		);
		assertTrue(
				output.contains("ftp not supported"),
				() -> "Expected unsupported scheme message, was: " + output
		);
	}

	@Test
	void unsupportedSchemeCountsAsReadySoItIsNotRetried() throws Exception {
		assertTrue(WaitForPorts.isReady(URI.create("ftp://localhost")));
		assertEquals("ftp not supported" + System.lineSeparator(), output());
	}

	@Test
	void reachableTcpPortSucceeds() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			int port = serverSocket.getLocalPort();
			Thread server = new Thread(() -> {
				try (Socket socket = serverSocket.accept()) {
					socket.getOutputStream().write(42);
					socket.getOutputStream().flush();
					// Keep the connection open until the client has read.
					Thread.sleep(200);
				} catch (IOException | InterruptedException e) {
					// Ignore: the test asserts on the client output.
				}
			});
			server.setDaemon(true);
			server.start();

			runMain("tcp://localhost:" + port);

			String output = output();
			assertTrue(
					output.contains("Success"),
					() -> "Expected TCP success, was: " + output
			);
		}
	}

	@Test
	void tcpPortThatClosesWithoutSendingIsNotReady() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			int port = serverSocket.getLocalPort();
			Thread server = new Thread(() -> {
				try (Socket socket = serverSocket.accept()) {
					// Close right away, without writing a byte.
				} catch (IOException e) {
					// Ignore: the test asserts on the client output.
				}
			});
			server.setDaemon(true);
			server.start();

			assertFalse(
					WaitForPorts
							.isTcpReady(URI.create("tcp://localhost:" + port))
			);
			assertEquals("Disconnected" + System.lineSeparator(), output());
		}
	}

	@Test
	void httpEndpointWithExpectedStatusSucceeds() throws IOException {
		HttpServer server = HttpServer
				.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();

			runMain("http://localhost:" + port);

			String output = output();
			assertTrue(
					output.contains("Success"),
					() -> "Expected HTTP success, was: " + output
			);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void httpEndpointWithCustomExpectedStatusFromFragmentSucceeds()
			throws IOException {
		HttpServer server = HttpServer
				.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();

			runMain("http://localhost:" + port + "#404");

			String output = output();
			assertTrue(
					output.contains("Success"),
					() -> "Expected HTTP success for expected 404, was: "
							+ output
			);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void httpEndpointWithUnexpectedStatusIsNotReady() throws Exception {
		HttpServer server = HttpServer
				.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(503, -1);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();

			assertFalse(
					WaitForPorts
							.isHttpReady(URI.create("http://localhost:" + port))
			);
			assertEquals(
					"Status code is 503" + System.lineSeparator(),
					output()
			);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void expectedStatusCodeDefaultsTo200() {
		assertEquals(
				200,
				WaitForPorts.expectedStatusCode(URI.create("http://localhost"))
		);
		assertEquals(
				200,
				WaitForPorts.expectedStatusCode(URI.create("http://localhost#"))
		);
	}

	@Test
	void expectedStatusCodeComesFromTheFragment() {
		assertEquals(
				404,
				WaitForPorts
						.expectedStatusCode(URI.create("http://localhost#404"))
		);
	}

	@Test
	void unreachableEndpointIsReportedAndKeptForTheNextRound()
			throws Exception {
		URI uri = URI.create("tcp://localhost:" + closedPort());
		Collection<URI> endpoints = new HashSet<>(List.of(uri));

		WaitForPorts.probe(endpoints);

		assertEquals(Set.of(uri), endpoints);
		String prefix = "Testing " + uri + " : ";
		String output = output();
		assertTrue(
				output.startsWith(prefix),
				() -> "Expected endpoint to be tested, was: " + output
		);
		assertFalse(
				output.substring(prefix.length()).isBlank(),
				() -> "Expected a failure message, was: " + output
		);
	}

	@Test
	void urisComeFromArgumentsWhenGiven() {
		Path configFile = writeConfigFile("tcp://ignored:1");

		assertEquals(
				List.of("http://localhost:1", "tcp://localhost:2"),
				List.copyOf(
						WaitForPorts.uris(
								new String[] { "http://localhost:1",
										"tcp://localhost:2" },
								configFile
						)
				)
		);
	}

	@Test
	void urisComeFromTheConfigFileWhenThereAreNoArguments() {
		Path configFile = writeConfigFile(
				"tcp://localhost:1",
				"http://localhost:2"
		);

		assertEquals(
				List.of("tcp://localhost:1", "http://localhost:2"),
				List.copyOf(WaitForPorts.uris(NO_ARGS, configFile))
		);
	}

	@Test
	void urisFallBackToTheDefaultWithoutArgumentsOrConfigFile() {
		Path configFile = tempDir.resolve("missing");

		assertEquals(
				List.of("http://localhost:8080"),
				List.copyOf(WaitForPorts.uris(NO_ARGS, configFile))
		);
	}

	@Test
	void unreadableConfigFileFailsInsteadOfFallingBack() {
		Path configFile = tempDir;

		UncheckedIOException exception = assertThrows(
				UncheckedIOException.class,
				() -> WaitForPorts.uris(NO_ARGS, configFile)
		);
		assertInstanceOf(IOException.class, exception.getCause());
	}

	@Test
	void endpointsAreParsedAndDeduplicated() {
		Path configFile = tempDir.resolve("missing");

		assertEquals(
				Set.of(
						URI.create("tcp://localhost:1"),
						URI.create("http://localhost:2")
				),
				Set.copyOf(
						WaitForPorts.endpoints(
								new String[] { "tcp://localhost:1",
										"http://localhost:2",
										"tcp://localhost:1" },
								configFile
						)
				)
		);
	}

	@Test
	void mainRestoresTheInterruptStatusWhenCancelled() throws Exception {
		String uri = "tcp://localhost:" + closedPort();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread waiting = new Thread(() -> {
			WaitForPorts.main(new String[] { uri });
			interrupted.set(Thread.currentThread().isInterrupted());
		});
		waiting.setDaemon(true);
		waiting.start();

		Thread.sleep(200);
		waiting.interrupt();
		waiting.join(Duration.ofSeconds(10));

		assertFalse(waiting.isAlive(), "Expected the wait to be cancelled");
		assertTrue(interrupted.get(), "Expected the interrupt to be restored");
		assertEquals(
				"Interrupted while waiting for ports" + System.lineSeparator(),
				errorOutput()
		);
	}

	private Path writeConfigFile(String... lines) {
		try {
			// Explicit newlines: the fixture must not depend on the
			// platform line separator.
			return Files.writeString(
					tempDir.resolve(".wait-for-ports"),
					String.join("\n", lines) + "\n"
			);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
