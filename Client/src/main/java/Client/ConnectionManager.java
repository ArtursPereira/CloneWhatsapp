package Client;

import com.google.gson.Gson;
import com.seunome.Packet;

import java.io.*;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ConnectionManager {

    private static final String HOST = "localhost";
    private static final int PORT = 5000;
    private static final int MAX_BACKOFF_MS = 30_000; // teto de 30s entre tentativas

    private final Gson gson = new Gson();

    // Credenciais guardadas para reautenticar após reconexão
    private String phone;
    private String password;
    private Packet.Type authType; // LOGIN ou REGISTER

    // Fila de mensagens que não foram confirmadas pelo servidor
    // (persistência em memória — pode evoluir para disco)
    private final Deque<Packet> outboundQueue = new ArrayDeque<>();

    private Socket socket;
    private PrintWriter out;
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private final AtomicBoolean running  = new AtomicBoolean(true);

    // Callback: o Main.java registra aqui o que fazer com cada Packet recebido
    private final Consumer<Packet> onPacketReceived;

    public ConnectionManager(Consumer<Packet> onPacketReceived) {
        this.onPacketReceived = onPacketReceived;
    }

    // ---------------------------------------------------------------
    // API pública
    // ---------------------------------------------------------------

    public void setCredentials(String phone, String password, Packet.Type authType) {
        this.phone    = phone;
        this.password = password;
        this.authType = authType;
    }

    /** Envia um packet — se offline, enfileira para envio posterior */
    public synchronized void send(Packet packet) {
        if (isOnline()) {
            out.println(gson.toJson(packet));
            if (out.checkError()) {
                // Escrita falhou — servidor caiu nesse exato momento
                outboundQueue.addFirst(packet); // devolve para frente da fila
                triggerReconnect();
            }
        } else {
            outboundQueue.add(packet);
            System.out.println("[OFFLINE] Mensagem enfileirada. Total na fila: " + outboundQueue.size());
        }
    }

    public boolean isOnline() {
        return socket != null && socket.isConnected() && !socket.isClosed() && loggedIn.get();
    }

    public void shutdown() {
        running.set(false);
        closeSocket();
    }

    // ---------------------------------------------------------------
    // Conexão inicial — chamada pelo Main
    // ---------------------------------------------------------------

    /**
     * Tenta conectar em loop com backoff exponencial.
     * Bloqueia até conseguir conectar pela primeira vez.
     */
    public void connectWithRetry() {
        int delayMs = 1000;

        while (running.get()) {
            try {
                System.out.println("Conectando ao servidor...");
                socket = new Socket(HOST, PORT);
                out    = new PrintWriter(socket.getOutputStream(), true);

                startReaderThread();
                authenticate(); // envia LOGIN ou REGISTER automaticamente

                System.out.println("Conectado!");
                return; // saiu do loop — conexão estabelecida

            } catch (IOException e) {
                System.out.println("Servidor indisponível. Tentando novamente em " + delayMs / 1000 + "s...");
                sleep(delayMs);
                delayMs = Math.min(delayMs * 2, MAX_BACKOFF_MS); // backoff exponencial
            }
        }
    }

    // ---------------------------------------------------------------
    // Reconexão em background
    // ---------------------------------------------------------------

    private void triggerReconnect() {
        loggedIn.set(false);
        closeSocket();

        // Roda em thread separada para não travar a thread de leitura nem a do usuário
        Thread reconnectThread = new Thread(() -> {
            int delayMs = 1000;
            while (running.get() && !loggedIn.get()) {
                try {
                    sleep(delayMs);
                    System.out.println("\n[RECONECTANDO] Tentando reconectar...");

                    socket = new Socket(HOST, PORT);
                    out    = new PrintWriter(socket.getOutputStream(), true);

                    startReaderThread();
                    authenticate();

                    // Aguarda confirmação do login antes de drenar a fila
                    int waited = 0;
                    while (!loggedIn.get() && waited < 5000) {
                        sleep(100);
                        waited += 100;
                    }

                    if (loggedIn.get()) {
                        drainOutboundQueue();
                        return; // reconectou com sucesso
                    }

                } catch (IOException e) {
                    System.out.println("[RECONECTANDO] Servidor ainda indisponível. Próxima tentativa em "
                            + delayMs / 1000 + "s...");
                    delayMs = Math.min(delayMs * 2, MAX_BACKOFF_MS);
                    closeSocket();
                }
            }
        }, "reconnect-thread");

        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    // ---------------------------------------------------------------
    // Thread de leitura
    // ---------------------------------------------------------------

    private void startReaderThread() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        Thread reader = new Thread(() -> {
            try {
                String line;
                while (running.get() && (line = in.readLine()) != null) {
                    Packet p = gson.fromJson(line, Packet.class);

                    // Intercepta LOGIN_SUCCESS/REGISTER_SUCCESS aqui
                    if (p.getType() == Packet.Type.LOGIN_SUCCESS
                            || p.getType() == Packet.Type.REGISTER_SUCCESS) {
                        loggedIn.set(true);
                    }

                    // Passa TODOS os packets para o Main tratar a UI
                    onPacketReceived.accept(p);
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.out.println("\n[CONEXÃO PERDIDA] Servidor caiu. Reconectando...");
                    triggerReconnect(); // ← aqui está a tolerância a falhas
                }
            }
        }, "reader-thread");

        reader.setDaemon(true);
        reader.start();
    }

    // ---------------------------------------------------------------
    // Helpers internos
    // ---------------------------------------------------------------

    private void authenticate() {
        Packet auth = new Packet();
        auth.setType(authType);
        auth.setFrom(phone);
        auth.setPassword(password);
        out.println(gson.toJson(auth));
    }

    /** Drena a fila de mensagens pendentes após reconexão bem-sucedida */
    private synchronized void drainOutboundQueue() {
        System.out.println("[RECONECTADO] Reenviando " + outboundQueue.size() + " mensagem(ns) da fila...");
        while (!outboundQueue.isEmpty()) {
            Packet p = outboundQueue.peek();
            out.println(gson.toJson(p));
            if (out.checkError()) {
                System.out.println("[FILA] Falha ao reenviar. Abortando drain.");
                triggerReconnect();
                return;
            }
            outboundQueue.poll(); // só remove após envio bem-sucedido
        }
    }

    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}