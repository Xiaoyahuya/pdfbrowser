package dev.pdfbrowser.service;

import dev.pdfbrowser.config.NasMountProperties;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SocksTcpRelayManagerTest {
    @Test
    void relaysTcpThroughNoAuthSocks5Proxy() throws Exception {
        try (ServerSocket fakeProxy = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> proxyTask = CompletableFuture.runAsync(() -> serveOneSocksConnection(fakeProxy));
            NasMountProperties properties = new NasMountProperties();
            properties.setSocksProxyHost("127.0.0.1");
            properties.setSocksProxyPort(fakeProxy.getLocalPort());
            SocksTcpRelayManager manager = new SocksTcpRelayManager(properties);
            try {
                int relayPort = manager.start(
                        "12345678-1234-1234-1234-123456789abc",
                        "192.168.1.20",
                        445);
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), relayPort), 2000);
                    client.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    assertThat(client.getInputStream().readNBytes(4))
                            .isEqualTo("ping".getBytes(StandardCharsets.UTF_8));
                }
                proxyTask.get(3, TimeUnit.SECONDS);
            } finally {
                manager.shutdown();
            }
        }
    }

    private void serveOneSocksConnection(ServerSocket server) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(2000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            assertThat(input.readNBytes(3)).containsExactly(0x05, 0x01, 0x00);
            output.write(new byte[] { 0x05, 0x00 });
            output.flush();

            byte[] request = input.readNBytes(4);
            assertThat(request[0]).isEqualTo((byte) 0x05);
            int addressLength = request[3] == 0x01 ? 4 : 16;
            input.readNBytes(addressLength + 2);
            output.write(new byte[] { 0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0 });
            output.flush();

            byte[] payload = input.readNBytes(4);
            output.write(payload);
            output.flush();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
