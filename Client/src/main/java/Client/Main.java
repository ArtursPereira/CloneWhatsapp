package Client;

import com.seunome.Packet;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Main {

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        Queue<String> pendingAckIds = new ConcurrentLinkedQueue<>();

        // 1. ConnectionManager recebe um callback para tratar packets recebidos
        ConnectionManager conn = new ConnectionManager(packet -> {
            switch (packet.getType()) {
                case LOGIN_SUCCESS, REGISTER_SUCCESS ->
                        System.out.println("✅ Autenticado com sucesso.");

                case LOGIN_FAIL ->
                        System.out.println("❌ Login recusado.");

                case MESSAGE -> {
                    System.out.println("\n[" + packet.getFrom() + "]: " + packet.getContent());
                    pendingAckIds.add(packet.getMessageId());
                }
                case ACK_DELIVERED ->
                        System.out.println("✓ Entregue - msg " + packet.getMessageId());

                case ACK_READ ->
                        System.out.println("✓✓ Lida - msg " + packet.getMessageId());

                case HISTORY_RESPONSE ->
                        System.out.println("[" + packet.getFrom() + "→" + packet.getTo() + "]: "
                                + packet.getContent() + " (" + packet.getStatus() + ")");

                default -> {}
            }
        });

        // 2. Coleta credenciais ANTES de tentar conectar
        System.out.println("LOGIN ou REGISTER?");
        String cmd = scanner.nextLine().toUpperCase();

        System.out.print("Telefone: "); String phone    = scanner.nextLine();
        System.out.print("Password: "); String password = scanner.nextLine();

        Packet.Type authType = cmd.equals("REGISTER") ? Packet.Type.REGISTER : Packet.Type.LOGIN;

        if (authType == Packet.Type.REGISTER) {
            // Para registro poderíamos guardar name/nickname também,
            // mas simplificando aqui para o exemplo
        }

        conn.setCredentials(phone, password, authType);

        // 3. Conecta — bloqueia até conseguir (com backoff)
        conn.connectWithRetry();

        // 4. Loop de input do usuário — funciona normalmente
        while (scanner.hasNextLine()) {
            // Envia ACKs pendentes
            String ackId;
            while ((ackId = pendingAckIds.poll()) != null) {
                Packet ack = new Packet();
                ack.setType(Packet.Type.ACK_READ);
                ack.setMessageId(ackId);
                conn.send(ack); // usa o ConnectionManager, não o out direto
            }

            String input = scanner.nextLine();

            if (input.startsWith("hist:")) {
                Packet hist = new Packet();
                hist.setType(Packet.Type.HISTORY_REQUEST);
                hist.setFrom(phone);
                hist.setTo(input.split(":", 2)[1]);
                conn.send(hist);

            } else {
                String[] parts = input.split(":", 2);
                if (parts.length == 2) {
                    Packet msg = new Packet();
                    msg.setType(Packet.Type.MESSAGE);
                    msg.setFrom(phone);
                    msg.setTo(parts[0]);
                    msg.setContent(parts[1]);
                    conn.send(msg); // ← se offline, enfileira automaticamente
                }
            }
        }

        conn.shutdown();
    }
}