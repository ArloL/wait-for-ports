package io.github.arlol.waitforports;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

public class WaitForPortsTests {

	private PrintStream originalOut;
	private ByteArrayOutputStream capturedOut;

	@BeforeEach
	void redirectStdOut() {
		originalOut = System.out;
		capturedOut = new ByteArrayOutputStream();
		System.setOut(
				new PrintStream(capturedOut, true, StandardCharsets.UTF_8)
		);
	}

	@AfterEach
	void restoreStdOut() {
		System.setOut(originalOut);
	}

	private String output() {
		return capturedOut.toString(StandardCharsets.UTF_8);
	}

	private void runMain(String... args) {
		assertTimeoutPreemptively(
				Duration.ofSeconds(30),
				() -> WaitForPorts.main(args)
		);
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

}
