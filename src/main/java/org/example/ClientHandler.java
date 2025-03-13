package org.example;

import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import me.tongfei.progressbar.ProgressBar;

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
                    writer.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss dd:MM:yyyy")));
                } else if (input.equalsIgnoreCase("CLOSE")) {
                    writer.println("Сервер закрывает соединение.");
                    break;
                } else if (command.getFirst().equalsIgnoreCase("UPLOAD")) {
                    processUploadCommand(command, reader, socket.getInputStream(), writer);
                } else if (command.getFirst().equalsIgnoreCase("DOWNLOAD")) {
                    processDownloadCommand(command, writer, reader, socket.getOutputStream());
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

    public static void processDownloadCommand(List<String> command, PrintWriter writer,
                                              BufferedReader reader, OutputStream outputStream) {
        if (command.size() < 2) {
            writer.println("ERROR: Invalid DOWNLOAD command format.");
            return;
        }
        String fileName = command.get(1);
        File file = new File(Constants.FILES_DIR, fileName);
        if (!file.exists() || !file.isFile()) {
            writer.println("ERROR: File not found.");
            return;
        }
        writer.println("READY");
        try {
            long fileSize = file.length();
            writer.println(fileSize);
            String response = reader.readLine();
            if (response.startsWith("RESUME")) {
                long existingFileSize = Long.parseLong(response.split(" ")[1]);
                sendFile(outputStream, file, existingFileSize);
            } else if (response.startsWith("START")) {
                sendFile(outputStream, file, 0);
            } else if (response.startsWith("ABORT")) {
                System.out.println("Скачивание отменено клиентом для файла: " + fileName);
                return;
            } else {
                writer.println("ERROR: Unknown resume option.");
                return;
            }
            writer.println("DONE");
            writer.flush();
            System.out.println("Файл отправлен: " + fileName);
        } catch (IOException e) {
            System.out.println("Ошибка при отправке файла: " + e.getMessage());
        }
    }

    private static void sendFile(OutputStream outputStream, File file, long offset) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             // Инициализируем прогресс-бар с полным размером файла
             ProgressBar progressBar = new ProgressBar("Отправка", file.length())) {
            // Пропускаем уже отправленные байты и обновляем прогресс
            fis.skip(offset);
            progressBar.stepTo(offset);
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesSent = offset;
            long startTime = System.currentTimeMillis();
            while ((bytesRead = fis.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;
                progressBar.stepBy(bytesRead);
            }
            long elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime > 0) {
                long bitRate = (totalBytesSent * 8) / elapsedTime;
                System.out.printf("Битрейт: %.2f Kbps\n", bitRate / 1000.0);
            }
            outputStream.flush();
        }
    }




    private void processUploadCommand(
            List<String> command,
            BufferedReader reader,
            InputStream inputStream,
            PrintWriter writer
    ) {
        if (command.size() < 2) {
            writer.println("ERROR: Invalid UPLOAD command format.");
            writer.flush();
            return;
        }

        String fileName = command.get(1);
        File saveFile = new File(FILES_DIR, fileName);

        writer.println("READY");
        writer.flush();
        boolean appendMode = false;

        try {
            long fileSize = Long.parseLong(reader.readLine());
            String expectedHash = reader.readLine();

            long saveFileLen = saveFile.length();
            System.out.printf("Существующая длина файла: %d", saveFileLen);
            System.out.printf("Клиентская длина файла: %d", fileSize);

            if (saveFile.exists()  && saveFile.length() == fileSize) {
                String existingHash = computeFileHash(saveFile);
                System.out.println("Хеш-сумма файла: " + existingHash);
                if (existingHash.equals(expectedHash)) {
                    writer.println("FILE_EXISTS");
                    writer.flush();
                    String clientResponse = reader.readLine(); // Ожидаем ответ: REUPLOAD или SKIP
                    if ("REUPLOAD".equalsIgnoreCase(clientResponse)) {
                        String newName = generateCopyName(fileName);
                        writer.println(newName);
                        writer.flush();
                        fileName = newName;
                        saveFile = new File(FILES_DIR, fileName);
                    } else {
                        return;
                    }
                } else {
                    // Файл с таким именем существует, но другой
                    writer.println("FILE_CONFLICT");
                    writer.flush();
                    String newName = reader.readLine();
                    if (newName != null && !newName.trim().isEmpty()) {
                        fileName = newName.trim();
                    } else {
                        fileName = generateCopyName(fileName);
                    }
                    saveFile = new File(FILES_DIR, fileName);
                }
            } else {
                // Если файла нет, продолжаем как обычно
                writer.println("NO_CONFLICT");
                writer.flush();
            }

            String clientId = socket.getInetAddress() != null//.getRemoteSocketAddress() != null
                    ? socket.getInetAddress().toString()
                    : "unknown";
            String sessionKey = clientId + "_" + fileName;

            UploadSession session = TCPServer.uploadSessions.get(sessionKey);

            if (session != null) {
                if (session.getExpectedFileSize() != fileSize || !session.getExpectedHash().equals(expectedHash)) {
                    System.out.println("Новый файл с тем же именем от клиента " + clientId + ". Сброс старой сессии.");
                    TCPServer.uploadSessions.remove(sessionKey);
                    if (saveFile.exists()) {
                        saveFile.delete();
                    }
                    long alreadyReceived = saveFile.exists() ? saveFile.length() : 0;
                    session = new UploadSession(clientId, fileName, fileSize, alreadyReceived, expectedHash);
                    TCPServer.uploadSessions.put(sessionKey, session);
                } else {
                    appendMode = true;
                    System.out.println("Продолжаем загрузку файла " + fileName + " от клиента " + clientId + " с " + session.getBytesReceived() + " байт.");
                }
            } else {
                session = new UploadSession(clientId, fileName, fileSize, 0, expectedHash);
                TCPServer.uploadSessions.put(sessionKey, session);
            }

            writer.println(session.getBytesReceived());

            try (FileOutputStream fileOutputStream = new FileOutputStream(saveFile, appendMode);
                 ProgressBar progressBar = new ProgressBar("Загрузка", session.getExpectedFileSize())
            ) {
                progressBar.stepTo(session.getBytesReceived());

                byte[] buffer = new byte[4096];
                int bytesRead;
                long bytesReceived = 0;

                long startTime = System.currentTimeMillis();
                while (session.getBytesReceived() < fileSize && (bytesRead = inputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                    session.addBytesReceived(bytesRead);
                    bytesReceived += bytesRead;
                    progressBar.stepBy(bytesRead);
                }
                System.out.println(); // перевод строки после завершения
                long elapsedTime = System.currentTimeMillis() - startTime;
                double bitRate = (double) (bytesReceived * 8) / elapsedTime / 1000.0;
                System.out.printf("Битрейт: %.2f Mbps\n", bitRate);
            }
            if (session.getExpectedFileSize() == fileSize) {
                TCPServer.uploadSessions.remove(sessionKey);
                writer.println("DONE");
                System.out.println("Файл успешно загружен: " + fileName + " от клиента " + clientId);
            } else {
                writer.println("ERROR: File size mismatch.");
                System.out.println("Ошибка: неверный размер файла " + fileName + " от клиента " + clientId);
            }
        } catch (IOException | NumberFormatException e) {
            System.out.println("Ошибка при загрузке файла: " + e.getMessage());
            writer.println("ERROR: " + e.getMessage());
            writer.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String computeFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void processEchoCommand(PrintWriter writer, List<String> command) {
        String message = command.size() > 1 ? command.get(1) : "Should be argument!!!";
        writer.println(message);
    }

    // Генерация нового имени файла вида: имя(1).расширение, имя(2).расширение, ...
    private static String generateCopyName(String originalName) {
        int dotIndex = originalName.lastIndexOf('.');
        String baseName = (dotIndex == -1) ? originalName : originalName.substring(0, dotIndex);
        String extension = (dotIndex == -1) ? "" : originalName.substring(dotIndex);
        int copyIndex = 1;
        String newName;
        do {
            newName = baseName + "(" + copyIndex + ")" + extension;
            copyIndex++;
        } while (new File(FILES_DIR, newName).exists());
        return newName;
    }
}