package org.example;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

public class ClientState {
    enum State {
        DEFAULT,
        UPLOAD_WAITING_SIZE,
        UPLOAD_WAITING_HASH,
        UPLOAD_RECEIVING_DATA,
        DOWNLOAD_SENDING_SIZE,
        DOWNLOAD_WAITING_RESPONSE,
        DOWNLOAD_SENDING_DATA
    }

    SocketChannel channel;
    String clientId;
    ByteBuffer readBuffer = ByteBuffer.allocate(4096);
    StringBuilder lineBuilder = new StringBuilder();
    Queue<ByteBuffer> writeQueue = new LinkedList<>();
    ByteBuffer currentWriteBuffer;
    State state = State.DEFAULT;

    // Для загрузки
    String uploadFileName;
    FileOutputStream uploadStream;
    long uploadExpectedSize;
    long uploadBytesReceived;
    String uploadExpectedHash;

    // Для скачивания
    String downloadFileName;
    FileInputStream downloadStream;
    long downloadFileSize;
    long downloadBytesSent;

    ClientState(SocketChannel channel) throws IOException {
        this.channel = channel;
        this.clientId = channel.getRemoteAddress().toString();
    }
}
