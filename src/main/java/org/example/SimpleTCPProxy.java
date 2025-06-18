package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class SimpleTCPProxy {

    private final int localPort;
    private final String remoteHost;
    private final int remotePort;

    public SimpleTCPProxy(int localPort, String remoteHost, int remotePort) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(localPort)) {
            System.out.println("Proxy listening on port " + localPort + " ...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected from: " + clientSocket.getRemoteSocketAddress());
                // 为每个客户端连接启动一个线程处理
                new Thread(() -> {
                    try {
                        // 连接到目标服务器
                        Socket remoteSocket = new Socket(remoteHost, remotePort);
                        // 启动两个线程分别处理两个方向的数据传输
                        Thread clientToRemote = new Thread(() -> transferData(clientSocket, remoteSocket));
                        Thread remoteToClient = new Thread(() -> transferData(remoteSocket, clientSocket));
                        clientToRemote.start();
                        remoteToClient.start();
                        // 等待两个线程结束
                        clientToRemote.join();
                        remoteToClient.join();
                        // 关闭连接
                        clientSocket.close();
                        remoteSocket.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            }
        }
    }

    private void transferData(Socket source, Socket destination) {
        try {
            InputStream sourceInput = source.getInputStream();
            OutputStream destinationOutput = destination.getOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = sourceInput.read(buffer)) != -1) {
                destinationOutput.write(buffer, 0, bytesRead);
                destinationOutput.flush();
            }
            // 关闭输出方向（通知对端）
            destination.shutdownOutput();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        // 示例：将本地的8080端口代理到远程的example.com的80端口
        SimpleTCPProxy proxy = new SimpleTCPProxy(9005, "192.168.18.138", 9004);
        proxy.start();
    }
}