package org.example;

import java.io.*;
import java.net.Socket;
import java.time.LocalTime;
import java.util.List;

import static org.example.Constants.FILES_DIR;

public class ClientHandler extends Thread {
    private final Socket socket;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            String input;
            while ((input = reader.readLine()) != null) {
                System.out.println("Получено: " + input);
                List<String> command = List.of(input.split(" ", 2));

                if (command.getFirst().equalsIgnoreCase("ECHO")) {
                    processEchoCommand(writer, command);
                } else if (input.equalsIgnoreCase("TIME")) {
                    writer.println(LocalTime.now());
                } else if (input.equalsIgnoreCase("CLOSE")) {
                    writer.println("Сервер закрывает соединение.");
                    break;
                } else if (command.getFirst().equalsIgnoreCase("UPLOAD")) {
                    processUploadCommand(command, reader, socket.getInputStream(), writer);
                } else if (command.getFirst().equalsIgnoreCase("DOWNLOAD")) {
                    processDownloadCommand(command, writer, socket.getOutputStream());
                } else {
                    writer.println("Неизвестная команда");
                }
            }
        } catch (IOException e) {
            System.out.println("Соединение с клиентом потеряно.");
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void processDownloadCommand(List<String> command, PrintWriter writer, OutputStream outputStream) {
        if (command.size() < 2) {
            writer.println("ERROR: Invalid DOWNLOAD command format.");
            return;
        }

        String fileName = command.get(1);
        File file = new File(FILES_DIR, fileName);

        if (!file.exists() || !file.isFile()) {
            writer.println("ERROR: File not found.");
            return;
        }

        writer.println("READY");
        writer.println(file.length());
        writer.flush();

        try {
            sendFile(outputStream, file);
            writer.println("DONE"); // Подтверждение завершения передачи файла
            writer.flush();
            System.out.println("Файл отправлен: " + fileName);
        } catch (IOException e) {
            System.out.println("Ошибка при отправке файла: " + e.getMessage());
        }
    }



    private static void sendFile(OutputStream outputStream, File file) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        }
    }

    private static void processUploadCommand(List<String> command, BufferedReader reader, InputStream inputStream, PrintWriter writer) {
        if (command.size() < 2) {
            writer.println("ERROR: Invalid UPLOAD command format.");
            writer.flush();
            return;
        }

        String fileName = command.get(1);
        File saveFile = new File(FILES_DIR, fileName);

        writer.println("READY");
        writer.flush(); // Отправляем ответ немедленно

        try {
            long fileSize = Long.parseLong(reader.readLine());

            try (FileOutputStream fileOutputStream = new FileOutputStream(saveFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalRead = 0;

                while (totalRead < fileSize && (bytesRead = inputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
                System.out.println("Success upload");
            }
        } catch (IOException | NumberFormatException e) {
            System.out.println("error");
            writer.println("ERROR: " + e.getMessage());
            writer.flush();
        }
    }

    // todo Разобраться с размером
    private long readFileSize(BufferedReader reader) {
        String sizeResponse;
        try {
            sizeResponse = reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }

        if (sizeResponse != null && sizeResponse.startsWith("FILE_SIZE")) {
            System.out.println(sizeResponse);
            return Long.parseLong(sizeResponse.split(" ")[1]);
        } else {
            System.out.println("Ошибка: сервер не отправил размер файла!");
            return -1;
        }
    }

    private void processEchoCommand(PrintWriter writer, List<String> command) {
        String message = command.size() > 1 ? command.get(1) : "Should be argument!!!";
        writer.println(message);
    }

}