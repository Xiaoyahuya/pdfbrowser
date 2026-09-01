package dev.pdfbrowser.service;

import dev.pdfbrowser.config.NasMountProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SocksTcpRelayManager {
    private static final Logger log = LoggerFactory.getLogger(SocksTcpRelayManager.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private final NasMountProperties properties;
    private final Map<String, Relay> relays = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "nas-socks-worker-" + THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public SocksTcpRelayManager(NasMountProperties properties) {
        this.properties = properties;
    }

    public synchronized int start(String id, String targetHost, int targetPort) {
        stop(id);
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
            Relay relay = new Relay(server, targetHost, targetPort);
            relays.put(id, relay);
            Thread acceptor = new Thread(() -> acceptLoop(id, relay), "nas-socks-accept-" + id.substring(0, 8));
            acceptor.setDaemon(true);
            relay.acceptor = acceptor;
            acceptor.start();
            return server.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建 NAS 的本机代理中继", exception);
        }
    }

    public synchronized void stop(String id) {
        Relay relay = relays.remove(id);
        if (relay == null) return;
        try { relay.server.close(); }
        catch (IOException ignored) { /* already closed */ }
        for (Socket socket : relay.sockets) {
            try { socket.close(); }
            catch (IOException ignored) { /* best effort */ }
        }
        relay.sockets.clear();
    }

    @PreDestroy
    void shutdown() {
        for (String id : Set.copyOf(relays.keySet())) stop(id);
        workers.shutdownNow();
    }

    private void acceptLoop(String id, Relay relay) {
        while (!relay.server.isClosed()) {
            try {
                Socket client = relay.server.accept();
                client.setTcpNoDelay(true);
                relay.sockets.add(client);
                workers.execute(() -> handle(relay, client));
            } catch (SocketException exception) {
                if (!relay.server.isClosed()) log.warn("NAS 中继 {} 接受连接失败", id);
                return;
            } catch (IOException exception) {
                log.warn("NAS 中继 {} 接受连接失败", id);
            }
        }
    }

    private void handle(Relay relay, Socket client) {
        Socket upstream = new Socket();
        relay.sockets.add(upstream);
        Future<?> upload = null;
        try {
            upstream.connect(new InetSocketAddress(properties.getSocksProxyHost(), properties.getSocksProxyPort()), 8000);
            upstream.setTcpNoDelay(true);
            negotiateSocks5(upstream, relay.targetHost, relay.targetPort);
            upload = workers.submit(() -> transfer(client, upstream, true));
            transfer(upstream, client, false);
        } catch (IOException exception) {
            log.debug("NAS SOCKS 中继连接结束: {}", exception.getMessage());
        } finally {
            if (upload != null) upload.cancel(true);
            close(client);
            close(upstream);
            relay.sockets.remove(client);
            relay.sockets.remove(upstream);
        }
    }

    private void negotiateSocks5(Socket proxy, String targetHost, int targetPort) throws IOException {
        InputStream input = proxy.getInputStream();
        OutputStream output = proxy.getOutputStream();
        output.write(new byte[] { 0x05, 0x01, 0x00 });
        output.flush();
        byte[] greeting = readExactly(input, 2);
        if (greeting[0] != 0x05 || greeting[1] != 0x00) {
            throw new IOException("Mihomo SOCKS5 不接受免认证连接");
        }

        byte[] address = InetAddress.getByName(targetHost).getAddress();
        byte addressType = address.length == 4 ? (byte) 0x01 : (byte) 0x04;
        byte[] request = new byte[4 + address.length + 2];
        request[0] = 0x05;
        request[1] = 0x01;
        request[2] = 0x00;
        request[3] = addressType;
        System.arraycopy(address, 0, request, 4, address.length);
        request[request.length - 2] = (byte) ((targetPort >>> 8) & 0xff);
        request[request.length - 1] = (byte) (targetPort & 0xff);
        output.write(request);
        output.flush();

        byte[] response = readExactly(input, 4);
        if (response[0] != 0x05 || response[1] != 0x00) {
            throw new IOException("Mihomo 无法连接目标 NAS，SOCKS5 状态码 " + (response[1] & 0xff));
        }
        int addressLength = switch (response[3]) {
            case 0x01 -> 4;
            case 0x04 -> 16;
            case 0x03 -> readExactly(input, 1)[0] & 0xff;
            default -> throw new IOException("Mihomo 返回了无效的 SOCKS5 地址类型");
        };
        readExactly(input, addressLength + 2);
    }

    private byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("SOCKS5 响应不完整");
        return bytes;
    }

    private void transfer(Socket source, Socket target, boolean shutdownOutput) {
        try {
            source.getInputStream().transferTo(target.getOutputStream());
            if (shutdownOutput && !target.isClosed()) target.shutdownOutput();
        } catch (IOException ignored) {
            // Closing either side is the normal way to stop the paired transfer.
        }
    }

    private void close(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { /* best effort */ }
    }

    private static final class Relay {
        private final ServerSocket server;
        private final String targetHost;
        private final int targetPort;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private Thread acceptor;

        private Relay(ServerSocket server, String targetHost, int targetPort) {
            this.server = server;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }
    }
}
