package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;

public class SimpleTCPProxy {

    private final int localPort;
    private final String remoteHost;
    private final int remotePort;
    private volatile boolean criticalError = false;
    private Exception criticalException = null;
    private volatile ServerSocket serverSocket;

    // 连接测试超时时间（毫秒）
    private static final int CONNECTION_TEST_TIMEOUT = 5000;

    public SimpleTCPProxy(int localPort, String remoteHost, int remotePort) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public void start() throws IOException {
        criticalError = false;
        criticalException = null;

        try {
            serverSocket = new ServerSocket(localPort);
            System.out.println("Proxy listening on port " + localPort + " ...");

            // 启动前先测试远程连接
            testRemoteConnection();

            while (!criticalError) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                    System.out.println("Client connected from: " + clientSocket.getRemoteSocketAddress());
                } catch (SocketException e) {
                    if (criticalError) {
                        throw new CriticalIOException("Proxy stopped due to critical error: " +
                                criticalException.getMessage(), criticalException);
                    }
                    throw e;
                }

                new Thread(() -> {
                    try {
                        handleClientConnection(clientSocket);
                    } catch (IOException e) {
                        System.err.println("Client connection error: " + e.getMessage());
                    }
                }).start();
            }
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing server socket: " + e.getMessage());
                }
                serverSocket = null;
            }
        }
    }

    /**
     * 测试远程服务器是否可达
     */
    private void testRemoteConnection() throws IOException {
        try (Socket testSocket = new Socket()) {
            testSocket.connect(new InetSocketAddress(remoteHost, remotePort), CONNECTION_TEST_TIMEOUT);
            System.out.println("Remote server test passed: " + remoteHost + ":" + remotePort);
        } catch (SocketTimeoutException e) {
            handleCriticalError(new ConnectException("Connection to " + remoteHost + ":" + remotePort +
                    " timed out after " + CONNECTION_TEST_TIMEOUT + "ms"));
            throw new CriticalIOException("Initial connection test failed", e);
        } catch (UnknownHostException | ConnectException e) {
            handleCriticalError(e);
            throw new CriticalIOException("Initial connection test failed", e);
        }
    }

    private Socket connectToRemote() throws IOException {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(remoteHost, remotePort), CONNECTION_TEST_TIMEOUT);
            return socket;
        } catch (UnknownHostException | ConnectException | SocketTimeoutException e) {
            handleCriticalError(e);
            throw e;
        }
    }

    private void handleCriticalError(Exception e) {
        synchronized (this) {
            if (!criticalError) {
                criticalError = true;
                criticalException = e;
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException ex) {
                        // 忽略关闭异常
                    }
                }
            }
        }
    }

    private void handleClientConnection(Socket clientSocket) throws IOException {
        try {
            Socket remoteSocket = connectToRemote();
            System.out.println("Connected to remote server: " + remoteHost + ":" + remotePort);

            Thread clientToRemote = new Thread(() -> transferData(clientSocket, remoteSocket));
            Thread remoteToClient = new Thread(() -> transferData(remoteSocket, clientSocket));

            clientToRemote.start();
            remoteToClient.start();

            clientToRemote.join();
            remoteToClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            safeCloseSocket(clientSocket);
        }
    }

    private void transferData(Socket source, Socket destination) {
        try (InputStream sourceInput = source.getInputStream();
             OutputStream destinationOutput = destination.getOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = sourceInput.read(buffer)) != -1) {
                destinationOutput.write(buffer, 0, bytesRead);
                destinationOutput.flush();
            }
            destination.shutdownOutput();
        } catch (IOException e) {
            System.out.println("Data transfer exception: " + e.getMessage());
        } finally {
            safeCloseSocket(source);
            safeCloseSocket(destination);
        }
    }

    private static void safeCloseSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }

    private static class CriticalIOException extends IOException {
        public CriticalIOException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static void main(String[] args) throws IOException {
        SimpleTCPProxy proxy = new SimpleTCPProxy(9005, "192.168.18.138", 9004);
        proxy.start();
    }
}