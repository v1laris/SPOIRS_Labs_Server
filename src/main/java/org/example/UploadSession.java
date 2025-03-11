package org.example;

public class UploadSession {
    private final String clientId;      // Идентификатор клиента (например, socket.getRemoteSocketAddress())
    private final String fileName;      // Имя файла
    private final long expectedFileSize;// Ожидаемый размер файла
    private long bytesReceived;         // Количество уже полученных байт
    private final String expectedHash;  // Вычисленная хеш-сумма файла (SHA-256)

    public UploadSession(String clientId, String fileName, long expectedFileSize, long bytesReceived, String expectedHash) {
        this.clientId = clientId;
        this.fileName = fileName;
        this.expectedFileSize = expectedFileSize;
        this.bytesReceived = bytesReceived;
        this.expectedHash = expectedHash;
    }

    public String getClientId() {
        return clientId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getExpectedFileSize() {
        return expectedFileSize;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public String getExpectedHash() {
        return expectedHash;
    }

    public void addBytesReceived(long count) {
        this.bytesReceived += count;
    }
}
