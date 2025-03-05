package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalTime;
import java.util.StringTokenizer;

class ClientHandler extends Thread {
    private Socket socket;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("Получено: " + line);
                String[] parts = line.split(" ", 2); // Разбиваем строку на 2 части
                if (parts[0].equalsIgnoreCase("ECHO")) {
                    String message = parts.length > 1 ? parts[1] : "Should be argument!!!";
                    writer.println(message);
                }
                else if (line.equalsIgnoreCase("TIME")) {
                    writer.println(LocalTime.now());
                } else if (line.equalsIgnoreCase("CLOSE")) {
                    writer.println("Сервер закрывает соединение.");
                    break; // Завершаем соединение
                } else {
                    writer.println("Неизвестная команда");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}