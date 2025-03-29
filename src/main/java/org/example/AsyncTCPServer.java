package org.example;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.example.Constants.FILES_DIR;
import static org.example.Constants.PORT;

public class AsyncTCPServer {
    public static final Map<String, UploadSession> uploadSessions = new ConcurrentHashMap<>();

    public static final Map<String, Object> downloadLocks = new ConcurrentHashMap<>();
    public static final Map<SelectionKey, ClientState> clientMap = new HashMap<>();

    public static void main(String[] args) throws Exception {
        // Создание директории для файлов
        File dir = new File(FILES_DIR);
        if (!dir.exists()) {
            dir.mkdir();
        }

        // Создание серверного канала
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(PORT));
        serverChannel.configureBlocking(false);

        // Создание селектора
        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        // Карта для хранения состояния клиентов

        // Вывод IP-адреса сервера
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                    System.out.println("Серверный IP-адрес: " + address.getHostAddress());
                }
            }
        }
        System.out.println("Сервер запущен на порту " + PORT);

        // Основной цикл
        while (true) {
            selector.select();
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();

                if (key.isAcceptable()) {
                    acceptConnection(selector, serverChannel, clientMap);
                } else if (key.isReadable()) {
                    readFromChannel(key, clientMap);
                } else if (key.isWritable()) {
                    writeToChannel(key, clientMap);
                }
            }
        }
    }

    private static void acceptConnection(Selector selector, ServerSocketChannel serverChannel,
                                         Map<SelectionKey, ClientState> clientMap) throws IOException {
        SocketChannel sc = serverChannel.accept();
        sc.configureBlocking(false);
        SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ);
        ClientState state = new ClientState(sc);
        clientMap.put(clientKey, state);
        clientKey.attach(state); // Прикрепляем состояние к ключу
        System.out.println("Клиент подключился: " + sc.getRemoteAddress());
    }

    private static void readFromChannel(SelectionKey key, Map<SelectionKey, ClientState> clientMap) throws Exception {
        ClientState state = clientMap.get(key);
        if (state == null) return;

        try {
            int bytesRead = state.channel.read(state.readBuffer);
            if (bytesRead == -1) {
                // Клиент закрыл соединение корректно
                handleClientDisconnect(key, clientMap);
            } else {
                // Обрабатываем полученные данные
                processInput(key, state);
                updateInterestOps(key, state);
            }
        } catch (IOException e) {
            if (e.getMessage().contains("Connection reset")) {
                // Клиент неожиданно разорвал соединение
                System.out.println("Клиент неожиданно разорвал соединение: " + state.channel.getRemoteAddress());
                handleClientDisconnect(key, clientMap);
            } else {
                // Другие ошибки передаем дальше
                throw e;
            }
        }
    }

    private static void writeToChannel(SelectionKey key, Map<SelectionKey, ClientState> clientMap) throws IOException, InterruptedException {
        //Thread.sleep(3);
        ClientState state = clientMap.get(key);
        if (state == null) return;

        try {
            if (state.currentWriteBuffer == null) {
                if (!state.writeQueue.isEmpty()) {
                    state.currentWriteBuffer = state.writeQueue.poll();
                } else if (state.state == ClientState.State.DOWNLOAD_SENDING_DATA
                        && state.downloadStream != null
                        && state.downloadBytesSent < state.downloadFileSize) {

                    byte[] chunk = new byte[4096];
                    int bytesRead = state.downloadStream.read(chunk);

                    if (bytesRead > 0) {
                        state.currentWriteBuffer = ByteBuffer.wrap(chunk, 0, bytesRead);
                        state.downloadBytesSent += bytesRead;
                    }
//                     else if (bytesRead == -1) {
//                        sendString(key, "DONE");
//                        resetDownloadState(state);
//                    }
                }
            }

            if (state.currentWriteBuffer != null) {
                int written = state.channel.write(state.currentWriteBuffer);
                if (written == -1) {
                    throw new IOException("Client disconnected");
                }

                if (!state.currentWriteBuffer.hasRemaining()) {
                    state.currentWriteBuffer = null;
                    if (state.downloadBytesSent >= state.downloadFileSize && state.downloadBytesSent != 0 && state.downloadFileSize != 0) {
                        resetDownloadState(state);
                    }
                }
            }
        } catch (IOException e) {
            resetDownloadState(state);
            throw e;
        }

        updateInterestOps(key, state);
    }

    private static void resetDownloadState(ClientState state) throws IOException {
        if (state.downloadStream != null) {
            state.downloadStream.close();
            state.downloadStream = null;
        }
        if (state.downloadFileName != null) {
            downloadLocks.remove(state.downloadFileName);
        }
        state.state = ClientState.State.DEFAULT;
        state.downloadFileName = null;
        state.downloadFileSize = 0;
        state.downloadBytesSent = 0;
    }

    private static void handleClientDisconnect(SelectionKey key, Map<SelectionKey, ClientState> clientMap) throws IOException {
        ClientState state = clientMap.get(key);
        if (state != null) {
            System.out.println("Клиент отключился: " + state.channel.getRemoteAddress());
            if (state.state != ClientState.State.UPLOAD_RECEIVING_DATA) {
                String sessionKey = state.clientId + "_" + state.uploadFileName;
                uploadSessions.remove(sessionKey);
            }
            state.channel.close();
            clientMap.remove(key);
        }
    }

    private static void sendString(SelectionKey key, String message) {
        if (!key.isValid()) {
            return; // Ключ аннулирован, ничего не делаем
        }
        ClientState state = (ClientState) key.attachment();
        ByteBuffer buffer = ByteBuffer.wrap((message + "\r\n").getBytes());
        System.out.println("Отправлено клиенту: " + message);
        state.writeQueue.add(buffer);
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    private static void updateInterestOps(SelectionKey key, ClientState state) {
        if (!key.isValid()) {
            return; // Ключ аннулирован, ничего не делаем
        }
        int ops = SelectionKey.OP_READ;
        if (!state.writeQueue.isEmpty() || state.currentWriteBuffer != null ||
                (state.state == ClientState.State.DOWNLOAD_SENDING_DATA && state.downloadBytesSent < state.downloadFileSize)) {
            ops |= SelectionKey.OP_WRITE;
        }
        key.interestOps(ops);
    }

    private static void processInput(SelectionKey key, ClientState state) throws Exception {
        //System.out.println("[DEBUG] Current state: " + state.state); // Логирование
        switch (state.state) {
            case DEFAULT:
                processCommand(key, state);
                break;
            case UPLOAD_WAITING_SIZE:
                String sizeLine = readLine(state);
                //readLine(state);
                if (sizeLine != null) {
                    state.uploadExpectedSize = Long.parseLong(sizeLine);
                    state.state = ClientState.State.UPLOAD_WAITING_HASH;
                }
                break;
            case UPLOAD_WAITING_HASH:
                String hashLine = readLine(state);
                if (hashLine != null) {
                    state.uploadExpectedHash = hashLine;
                    processUploadStart(key, state);
                }
                break;
            case UPLOAD_RECEIVING_DATA:
                processUploadData(state, key);
                break;
            case DOWNLOAD_SENDING_SIZE:
                state.state = ClientState.State.DOWNLOAD_WAITING_RESPONSE;
                sendString(key, String.valueOf(state.downloadFileSize));
                break;
            case DOWNLOAD_WAITING_RESPONSE:
                String response = readLine(state);
                if (response != null) {
                    handleDownloadResponse(key, state, response);
                }
                break;
            default:
                break;
        }
    }

    private static String readLine(ClientState state) {
        state.readBuffer.flip();
        while (state.readBuffer.hasRemaining()) {
            byte b = state.readBuffer.get();
            if (b == '\n') {
                String line = state.lineBuilder.toString().trim();
                state.lineBuilder.setLength(0);
                state.readBuffer.compact();
                return line;
            } else {
                state.lineBuilder.append((char) b);
            }
        }
        state.readBuffer.compact();
        return null;
    }

    private static void processCommand(SelectionKey key, ClientState state) throws IOException, InterruptedException {
        String line = readLine(state);
        if (line == null) return;

        List<String> command = List.of(line.split(" ", 2));
        String cmd = command.get(0).toUpperCase();

        System.out.println("Processing command:" + line);

        switch (cmd) {
            case "ECHO":
                String message = command.size() > 1 ? command.get(1) : "Should be argument!!!";
                sendString(key, message);
                break;
            case "TIME":
                String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss dd:MM:yyyy"));
                sendString(key, time);
                break;
            case "CLOSE":
                sendString(key, "Сервер закрывает соединение.");
                key.cancel(); // Явно аннулируем ключ
                state.channel.close();
                clientMap.remove(key); // Удаляем клиента из clientMap                break;
            case "UPLOAD":
                if (command.size() < 2) {
                    sendString(key, "ERROR: Invalid UPLOAD command format.");
                } else {
                    state.uploadFileName = null;
                    state.uploadExpectedSize = 0;
                    state.uploadExpectedHash = null;
                    state.uploadBytesReceived = 0;
                    state.uploadFileName = command.get(1);
                    sendString(key, "READY");
                    state.state = ClientState.State.UPLOAD_WAITING_SIZE;
                    key.interestOps(SelectionKey.OP_READ);
                }
                break;
            case "DOWNLOAD":
                processDownloadCommand(key, state, command);
                break;
            default:
                sendString(key, "Неизвестная команда");
                break;
        }
    }

    private static void processUploadStart(SelectionKey key, ClientState state) throws Exception {
        File saveFile = new File(FILES_DIR, state.uploadFileName);
        String sessionKey = state.clientId + "_" + state.uploadFileName;
        UploadSession session = uploadSessions.get(sessionKey);
        boolean appendMode = false;

        if (saveFile.exists() && saveFile.length() == state.uploadExpectedSize) {
            sendString(key, "FILE_CONFLICT");
            state.uploadFileName = generateCopyName(state.uploadFileName);
            saveFile = new File(FILES_DIR, state.uploadFileName);
            sessionKey = state.clientId + "_" + state.uploadFileName;
        } else {
            sendString(key, "NO_CONFLICT");
        }

        if (session != null && session.getExpectedFileSize() == state.uploadExpectedSize &&
                session.getExpectedHash().equals(state.uploadExpectedHash)) {
            appendMode = true;
            state.uploadBytesReceived = session.getBytesReceived();
        } else {
            if (session != null) uploadSessions.remove(sessionKey);
            if (saveFile.exists()) saveFile.delete();
            session = new UploadSession(state.clientId, state.uploadFileName, state.uploadExpectedSize, 0, state.uploadExpectedHash);
            uploadSessions.put(sessionKey, session);
            state.uploadBytesReceived = 0;
        }

        state.uploadStream = new FileOutputStream(saveFile, appendMode);
        sendString(key, String.valueOf(state.uploadBytesReceived));
        state.state = ClientState.State.UPLOAD_RECEIVING_DATA;
    }

    private static void processUploadData(ClientState state, SelectionKey key) throws IOException {
        state.readBuffer.flip();
        int bytesAvailable = state.readBuffer.remaining();
        if (bytesAvailable > 0) {
            long bytesToWrite = Math.min(bytesAvailable, state.uploadExpectedSize - state.uploadBytesReceived);
            byte[] data = new byte[(int) bytesToWrite];
            state.readBuffer.get(data);
            state.uploadStream.write(data);
            state.uploadBytesReceived += bytesToWrite;

            String sessionKey = state.clientId + "_" + state.uploadFileName;
            UploadSession session = uploadSessions.get(sessionKey);
            if (session != null) session.addBytesReceived(bytesToWrite);

            if (state.uploadBytesReceived >= state.uploadExpectedSize) {
                state.uploadStream.close();
                state.uploadStream = null;
                uploadSessions.remove(sessionKey);
                System.out.println("DONE");
                //sendString(key, "DONE");
                state.state = ClientState.State.DEFAULT;
                state.uploadFileName = null;
                state.uploadExpectedSize = 0;
                state.uploadExpectedHash = null;
                state.uploadBytesReceived = 0;
            }
        }
        state.readBuffer.compact();
    }

    private static void processDownloadCommand(SelectionKey key, ClientState state, List<String> command) throws IOException {
        if (command.size() < 2) {
            sendString(key, "ERROR: Invalid DOWNLOAD command format.");
            return;
        }
        String fileName = command.get(1);
        File file = new File(FILES_DIR, fileName);
        if (!file.exists() || !file.isFile()) {
            sendString(key, "ERROR: File not found.");
            return;
        }
        if (downloadLocks.putIfAbsent(fileName, new Object()) != null) {
            sendString(key, "LOCKED");
            state.state = ClientState.State.DEFAULT;
            return;
        }
        state.downloadFileName = fileName;
        state.downloadFileSize = file.length();
        state.downloadBytesSent = 0;
        state.downloadStream = new FileInputStream(file);
        sendString(key, "READY");
        sendString(key, String.valueOf(state.downloadFileSize));
        state.state = ClientState.State.DOWNLOAD_WAITING_RESPONSE;
    }

    private static void handleDownloadResponse(SelectionKey key, ClientState state, String response) throws IOException {
        if (response == null) return;

        if (response.startsWith("RESUME")) {
            long resumePoint = Long.parseLong(response.split(" ")[1]);
            if (resumePoint >= state.downloadFileSize) {
                sendString(key, "ERROR: Invalid resume point");
                state.state = ClientState.State.DEFAULT;
                return;
            }
            state.downloadBytesSent = resumePoint;
            state.downloadStream.skip(resumePoint);
            state.state = ClientState.State.DOWNLOAD_SENDING_DATA;
        } else if (response.equals("START")) {
            state.downloadBytesSent = 0;
            state.state = ClientState.State.DOWNLOAD_SENDING_DATA;
        } else if (response.equals("ABORT")) {
            state.downloadStream.close();
            state.state = ClientState.State.DEFAULT;
            sendString(key, "DOWNLOAD ABORTED");
        } else {
            sendString(key, "ERROR: Unknown command");
            state.state = ClientState.State.DEFAULT;
        }

        if (state.state == ClientState.State.DOWNLOAD_SENDING_DATA) {
            key.interestOps(SelectionKey.OP_WRITE);
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


