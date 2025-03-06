package org.example;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import static org.example.Constants.FILES_DIR;
import static org.example.Constants.PORT;

public class TCPServer {
    private static final Map<String, Long> uploadProgress = new HashMap<>(); // Хранит прогресс загрузок

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
//                clientSocket.setSoTimeout(30000); // 30 секунд

                System.out.println("Клиент подключился: " + clientSocket.getInetAddress());

                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}




//
//import java.io.*;
//import java.net.*;
//import java.time.LocalTime;
//
//public class TCPServer {
//    static final String FILES_DIR = "server_files"; // Директория для файлов
//
//    private static final int PORT = 12345;
//
//    public static void main(String[] args) {
//        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
//            System.out.println("Сервер запущен на порту " + PORT);
//
//            // Сервер работает бесконечно, обрабатывая клиентов
//            while (true) {
//                Socket clientSocket = serverSocket.accept(); // Принимаем подключение
//                System.out.println("Клиент подключился: " + clientSocket.getInetAddress());
//
//                // Создаем новый поток для обработки клиента
//                new ClientHandler(clientSocket).start();
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
//}
//
//
//
