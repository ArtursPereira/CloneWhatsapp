package Client;

import com.google.gson.Gson;
import com.seunome.Packet;

import java.io.*;
import java.net.Socket;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    private static final String HOST = "localhost";
    private static final int PORT = 5000;
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        Socket socket = new Socket(HOST, PORT);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        Scanner scanner = new Scanner(System.in);
        Queue<String> pendingAckIds = new ConcurrentLinkedQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean loggedIn = new AtomicBoolean(false);

        new Thread(() -> {
            try {
                String line;
                while (running.get() && (line = in.readLine()) != null) {
                    Packet p = gson.fromJson(line, Packet.class);

                    switch (p.getType()) {
                        case LOGIN_SUCCESS -> {
                            loggedIn.set(true);
                            System.out.println("Login realizado com sucesso.");
                        }

                        case LOGIN_FAIL -> {
                            System.out.println("Login recusado. Usuário já está conectado ou credenciais inválidas.");
                            running.set(false);
                            try {
                                socket.close();
                            } catch (IOException ignored) {}
                            return;
                        }

                        case REGISTER_SUCCESS -> {
                            loggedIn.set(true);
                            System.out.println("Registro realizado com sucesso.");
                        }

                        case REGISTER_FAIL -> {
                            System.out.println("Registro falhou.");
                            running.set(false);
                            try {
                                socket.close();
                            } catch (IOException ignored) {}
                            return;
                        }

                        case MESSAGE -> {
                            System.out.println("\n[" + p.getFrom() + "]: " + p.getContent());

                            //adicionado
                            Packet deliveredAck = new Packet();
                            deliveredAck.setType(Packet.Type.ACK_DELIVERED);
                            deliveredAck.setMessageId(p.getMessageId());
                            out.println(gson.toJson(deliveredAck));

                            System.out.println("(pressione Enter para marcar como lida)");
                            pendingAckIds.add(p.getMessageId());
                        }

                        case ACK_DELIVERED -> System.out.println("✓ Entregue - mensagem " + p.getMessageId());

                        case ACK_READ -> System.out.println("✓✓ Lida - mensagem " + p.getMessageId());

                        case HISTORY_RESPONSE -> System.out.println("[" + p.getFrom() + " → " + p.getTo() + "]: "
                                + p.getContent() + " (" + p.getStatus() + ")");

                        default -> System.out.println("Pacote recebido: " + p.getType());
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.out.println("Desconectado do servidor.");
                }
            } finally {
                running.set(false);
            }
        }).start();

        System.out.println("Digite o comando:");
        System.out.println("LOGIN");
        System.out.println("REGISTER");
        String comando = scanner.nextLine().toUpperCase();

        String phone = null;
        Packet packet = new Packet();

        switch (comando) {
            case "LOGIN" -> {
                System.out.print("Telefone: ");
                phone = scanner.nextLine();
                System.out.print("Password: ");
                String password = scanner.nextLine();

                packet.setType(Packet.Type.LOGIN);
                packet.setFrom(phone);
                packet.setPassword(password);
                out.println(gson.toJson(packet));
            }

            case "REGISTER" -> {
                System.out.print("Telefone: ");
                phone = scanner.nextLine();
                System.out.print("Nome: ");
                String name = scanner.nextLine();
                System.out.print("Apelido: ");
                String nickname = scanner.nextLine();
                System.out.print("Password: ");
                String password = scanner.nextLine();

                packet.setType(Packet.Type.REGISTER);
                packet.setFrom(phone);
                packet.setName(name);
                packet.setNickname(nickname);
                packet.setPassword(password);
                out.println(gson.toJson(packet));
            }

            default -> {
                System.out.println("Comando inválido.");
                socket.close();
                return;
            }
        }

        while (running.get() && !loggedIn.get()) {
            Thread.sleep(100);
        }

        if (!running.get()) {
            return;
        }

        while (running.get() && scanner.hasNextLine()) {
            String pendingId;
            while ((pendingId = pendingAckIds.poll()) != null) {
                Packet ack = new Packet();
                ack.setType(Packet.Type.ACK_READ);
                ack.setMessageId(pendingId);
                out.println(gson.toJson(ack));
            }

            String input = scanner.nextLine();

            if (!running.get()) {
                break;
            }

            if (input.startsWith("hist:")) {
                String target = input.split(":", 2)[1];
                Packet hist = new Packet();
                hist.setType(Packet.Type.HISTORY_REQUEST);
                hist.setFrom(phone);
                hist.setTo(target);
                out.println(gson.toJson(hist));
            } else {
                String[] parts = input.split(":", 2);
                if (parts.length == 2) {
                    Packet msg = new Packet();
                    msg.setType(Packet.Type.MESSAGE);
                    msg.setFrom(phone);
                    msg.setTo(parts[0]);
                    msg.setContent(parts[1]);
                    out.println(gson.toJson(msg));
                } else {
                    System.out.println("Formato: telefone:mensagem ou hist:telefone");
                }
            }
        }

        socket.close();
    }
}