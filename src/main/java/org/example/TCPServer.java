package org.example;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
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