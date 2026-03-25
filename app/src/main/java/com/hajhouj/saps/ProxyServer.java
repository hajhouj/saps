package com.hajhouj.saps;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ProxyServer {
    private static final int BUFFER_SIZE = 65536; // 64KB buffer for better throughput
    private static final int THREAD_POOL_SIZE = 100;
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds
    private static final int CONNECT_TIMEOUT = 10000; // 10 seconds
    
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private boolean isRunning = false;
    private int port;
    private OnLogListener logListener;

    public interface OnLogListener {
        void onLog(String message);
    }

    public ProxyServer(int port) {
        this.port = port;
        // Use cached thread pool for better handling of many concurrent connections
        this.threadPool = new ThreadPoolExecutor(
            10, THREAD_POOL_SIZE,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "ProxyWorker-" + (++count));
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void setLogListener(OnLogListener listener) {
        this.logListener = listener;
    }

    private void log(String message) {
        if (logListener != null) {
            logListener.onLog(message);
        }
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        isRunning = true;
        log("Proxy server started on port " + port);

        while (isRunning) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for lower latency
                clientSocket.setKeepAlive(true);
                clientSocket.setSoTimeout(SOCKET_TIMEOUT);
                clientSocket.setReceiveBufferSize(BUFFER_SIZE);
                clientSocket.setSendBufferSize(BUFFER_SIZE);
                threadPool.execute(new ClientHandler(clientSocket));
            } catch (IOException e) {
                if (isRunning) {
                    log("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log("Error stopping server: " + e.getMessage());
        }
        threadPool.shutdown();
        log("Proxy server stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getPort() {
        return port;
    }

    private class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (
                InputStream clientIn = clientSocket.getInputStream();
                OutputStream clientOut = clientSocket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn))
            ) {
                String requestLine = reader.readLine();
                if (requestLine == null) return;

                log("Request: " + requestLine);

                String[] parts = requestLine.split(" ");
                if (parts.length < 3) return;

                String method = parts[0];
                String url = parts[1];
                String version = parts[2];

                if (method.equalsIgnoreCase("CONNECT")) {
                    handleHttpsTunnel(url, clientIn, clientOut);
                } else {
                    handleHttpRequest(method, url, version, reader, clientIn, clientOut);
                }
            } catch (IOException e) {
                log("Client handler error: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        private void handleHttpsTunnel(String hostPort, InputStream clientIn, OutputStream clientOut) throws IOException {
            String[] parts = hostPort.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;

            final Socket targetSocket = new Socket();
            try {
                targetSocket.setTcpNoDelay(true);
                targetSocket.setKeepAlive(true);
                targetSocket.setSoTimeout(SOCKET_TIMEOUT);
                targetSocket.setReceiveBufferSize(BUFFER_SIZE);
                targetSocket.setSendBufferSize(BUFFER_SIZE);
                targetSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
                
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                clientOut.flush();

                // Use a shared executor for tunnel threads instead of creating new ones
                CountDownLatch latch = new CountDownLatch(2);
                final OutputStream targetOut = targetSocket.getOutputStream();
                final InputStream targetIn = targetSocket.getInputStream();
                
                threadPool.execute(() -> {
                    try {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = clientIn.read(buffer)) != -1) {
                            targetOut.write(buffer, 0, read);
                            targetOut.flush();
                        }
                    } catch (IOException e) {
                        // Connection closed
                    } finally {
                        latch.countDown();
                    }
                });

                threadPool.execute(() -> {
                    try {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = targetIn.read(buffer)) != -1) {
                            clientOut.write(buffer, 0, read);
                            clientOut.flush();
                        }
                    } catch (IOException e) {
                        // Connection closed
                    } finally {
                        latch.countDown();
                    }
                });

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                log("HTTPS tunnel error: " + e.getMessage());
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
            } finally {
                try {
                    targetSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        private void handleHttpRequest(String method, String url, String version,
                                       BufferedReader reader, InputStream clientIn,
                                       OutputStream clientOut) throws IOException {
            URL targetUrl = new URL(url);
            String host = targetUrl.getHost();
            int port = targetUrl.getPort() == -1 ? 80 : targetUrl.getPort();

            StringBuilder headerBuilder = new StringBuilder();
            headerBuilder.append(method).append(" ").append(targetUrl.getFile()).append(" ").append(version).append("\r\n");

            String line;
            int requestContentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lowerLine = line.toLowerCase();
                if (lowerLine.startsWith("proxy-connection:")) {
                    continue;
                }
                if (lowerLine.startsWith("content-length:")) {
                    requestContentLength = Integer.parseInt(line.split(":")[1].trim());
                }
                headerBuilder.append(line).append("\r\n");
            }
            headerBuilder.append("\r\n");

            final Socket targetSocket = new Socket();
            try {
                targetSocket.setTcpNoDelay(true);
                targetSocket.setKeepAlive(true);
                targetSocket.setSoTimeout(SOCKET_TIMEOUT);
                targetSocket.setReceiveBufferSize(BUFFER_SIZE);
                targetSocket.setSendBufferSize(BUFFER_SIZE);
                targetSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
                
                final OutputStream targetOut = targetSocket.getOutputStream();
                final InputStream targetIn = targetSocket.getInputStream();

                // Send request headers
                targetOut.write(headerBuilder.toString().getBytes());

                // Forward request body if present
                if (requestContentLength > 0) {
                    byte[] bodyBuffer = new byte[Math.min(requestContentLength, BUFFER_SIZE)];
                    int totalRead = 0;
                    while (totalRead < requestContentLength) {
                        int toRead = Math.min(bodyBuffer.length, requestContentLength - totalRead);
                        int read = clientIn.read(bodyBuffer, 0, toRead);
                        if (read == -1) break;
                        targetOut.write(bodyBuffer, 0, read);
                        totalRead += read;
                    }
                }
                targetOut.flush();

                // Read and forward response headers
                BufferedInputStream bufferedTargetIn = new BufferedInputStream(targetIn, BUFFER_SIZE);
                ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
                int b;
                boolean headerComplete = false;
                while ((b = bufferedTargetIn.read()) != -1) {
                    headerBuffer.write(b);
                    byte[] bytes = headerBuffer.toByteArray();
                    int len = bytes.length;
                    if (len >= 4 && bytes[len-4] == '\r' && bytes[len-3] == '\n' 
                        && bytes[len-2] == '\r' && bytes[len-1] == '\n') {
                        headerComplete = true;
                        break;
                    }
                }
                
                if (!headerComplete) {
                    throw new IOException("Incomplete response headers");
                }

                // Send response headers to client
                clientOut.write(headerBuffer.toByteArray());
                clientOut.flush();

                // Parse response headers to determine body handling
                String responseHeaders = new String(headerBuffer.toByteArray(), "ISO-8859-1");
                String[] headerLines = responseHeaders.split("\r\n");
                
                int responseContentLength = -1;
                boolean chunked = false;
                boolean hasBody = true;
                
                for (String headerLine : headerLines) {
                    String lowerHeader = headerLine.toLowerCase();
                    if (lowerHeader.startsWith("content-length:")) {
                        try {
                            responseContentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                        } catch (NumberFormatException e) {
                            // Ignore invalid content-length
                        }
                    }
                    if (lowerHeader.startsWith("transfer-encoding:") && lowerHeader.contains("chunked")) {
                        chunked = true;
                    }
                    if (lowerHeader.startsWith("connection:") && lowerHeader.contains("close")) {
                        // Connection will be closed, read until EOF
                        responseContentLength = -1;
                    }
                }

                // Check for responses that shouldn't have bodies
                if (headerLines.length > 0) {
                    String statusLine = headerLines[0];
                    if (statusLine.contains(" 204 ") || statusLine.contains(" 304 ") 
                        || method.equalsIgnoreCase("HEAD")) {
                        hasBody = false;
                    }
                }

                if (!hasBody) {
                    return;
                }

                // Forward response body
                if (chunked) {
                    // Handle chunked transfer encoding
                    forwardChunkedBody(bufferedTargetIn, clientOut);
                } else if (responseContentLength >= 0) {
                    // Known content length
                    forwardFixedLengthBody(bufferedTargetIn, clientOut, responseContentLength);
                } else {
                    // Unknown length, read until EOF
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = bufferedTargetIn.read(buffer)) != -1) {
                        clientOut.write(buffer, 0, read);
                        clientOut.flush();
                    }
                }
            } catch (IOException e) {
                log("HTTP request error: " + e.getMessage());
                String errorResponse = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n";
                clientOut.write(errorResponse.getBytes());
            } finally {
                try {
                    targetSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        private void forwardChunkedBody(InputStream in, OutputStream out) throws IOException {
            while (true) {
                // Read chunk size line
                ByteArrayOutputStream chunkHeader = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1) {
                    chunkHeader.write(b);
                    byte[] bytes = chunkHeader.toByteArray();
                    int len = bytes.length;
                    if (len >= 2 && bytes[len-2] == '\r' && bytes[len-1] == '\n') {
                        break;
                    }
                }
                
                // Parse chunk size
                String chunkSizeStr = new String(chunkHeader.toByteArray(), "ISO-8859-1").trim();
                int chunkSize;
                try {
                    chunkSize = Integer.parseInt(chunkSizeStr.split(";")[0].trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid chunk size: " + chunkSizeStr);
                }
                
                // Write chunk header
                out.write(chunkHeader.toByteArray());
                out.flush();
                
                if (chunkSize == 0) {
                    // Last chunk - read trailing headers (if any) and final CRLF
                    ByteArrayOutputStream trailer = new ByteArrayOutputStream();
                    while ((b = in.read()) != -1) {
                        trailer.write(b);
                        byte[] bytes = trailer.toByteArray();
                        int len = bytes.length;
                        if (len >= 4 && bytes[len-4] == '\r' && bytes[len-3] == '\n' 
                            && bytes[len-2] == '\r' && bytes[len-1] == '\n') {
                            break;
                        }
                        if (len >= 2 && bytes[len-2] == '\r' && bytes[len-1] == '\n' && len == 2) {
                            // No trailers, just final CRLF
                            break;
                        }
                    }
                    out.write(trailer.toByteArray());
                    out.flush();
                    break;
                }
                
                // Forward chunk data
                byte[] buffer = new byte[Math.min(chunkSize, BUFFER_SIZE)];
                int remaining = chunkSize;
                while (remaining > 0) {
                    int toRead = Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, toRead);
                    if (read == -1) throw new IOException("Unexpected EOF in chunked body");
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                out.flush();
                
                // Read and forward chunk trailing CRLF
                byte[] crlf = new byte[2];
                if (in.read(crlf) == 2) {
                    out.write(crlf);
                    out.flush();
                }
            }
        }

        private void forwardFixedLengthBody(InputStream in, OutputStream out, int contentLength) throws IOException {
            byte[] buffer = new byte[Math.min(contentLength, BUFFER_SIZE)];
            int remaining = contentLength;
            while (remaining > 0) {
                int toRead = Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read == -1) break;
                out.write(buffer, 0, read);
                out.flush();
                remaining -= read;
            }
        }
    }
}
