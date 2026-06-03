package Client;

import com.google.gson.Gson;
import com.seunome.Packet;

import java.io.*;
import java.net.Socket;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Main {

    private static final String HOST = "localhost";
    private static final int PORT = 5000;
    private static final Gson gson = new Gson();
    private String  LoginorRegister;

    public static void main(String[] args) throws Exception {
        Socket socket = new Socket(HOST, PORT);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        Scanner scanner = new Scanner(System.in);
        Queue<String> pendingAckIds = new ConcurrentLinkedQueue<>();
        new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Packet p = gson.fromJson(line, Packet.class);
                    //mudança
                    switch (p.getType()) {
                        case MESSAGE -> {
                            System.out.println("\n[" + p.getFrom() + "]: " + p.getContent());
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
                System.out.println("Desconectado do servidor.");
            }
        }).start();
        System.out.println("Digite o comando:");
        System.out.println("LOGIN");
        System.out.println("REGISTER");
        String comando = scanner.nextLine().toUpperCase();
        String phone = null;
        Packet packet = new Packet();
        switch (comando) {


            case "LOGIN" ->{
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
                    return;
                }

        }
        //Mudança
        // Loop de envio
        while (scanner.hasNextLine()) {
            String pendingId;
            while ((pendingId = pendingAckIds.poll()) != null) {
                Packet ack = new Packet();
                ack.setType(Packet.Type.ACK_READ);
                ack.setMessageId(pendingId);
                out.println(gson.toJson(ack));
            }

            String input = scanner.nextLine();

            if (input.startsWith("hist:")) {
                // verifica hist: ANTES do split genérico
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
    }
}
