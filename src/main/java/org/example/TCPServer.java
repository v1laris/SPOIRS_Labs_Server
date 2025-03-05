package org.example;

import java.io.*;
import java.net.*;
import java.time.LocalTime;

public class TCPServer {
    private static final int PORT = 12345;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Сервер запущен на порту " + PORT);

            // Сервер работает бесконечно, обрабатывая клиентов
            while (true) {
                Socket clientSocket = serverSocket.accept(); // Принимаем подключение
                System.out.println("Клиент подключился: " + clientSocket.getInetAddress());

                // Создаем новый поток для обработки клиента
                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Класс для обработки клиента в отдельном потоке

}
