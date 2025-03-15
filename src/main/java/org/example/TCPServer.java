package org.example;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.example.Constants.FILES_DIR;
import static org.example.Constants.PORT;

public class TCPServer {
    public static final Map<String, UploadSession> uploadSessions = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        File dir = new File(FILES_DIR);
        if (!dir.exists()) {
            dir.mkdir();
        }

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    // Фильтруем loopback-адреса (127.0.0.1) и проверяем, что это IPv4
                    if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                        System.out.println("Серверный IP-адрес: " + address.getHostAddress());
                    }
                }
            }
            System.out.println("Сервер запущен на порту " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setKeepAlive(true);
                System.out.println("Клиент подключился: " + clientSocket.getRemoteSocketAddress());
                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}