package Client;

import com.google.gson.Gson;
import com.seunome.Packet;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class Main {

    private static final String HOST = "localhost";
    private static final int PORT = 5000;
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        Socket socket = new Socket(HOST, PORT);
        PrintWriter out   = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        Scanner scanner   = new Scanner(System.in);

        // Thread para receber mensagens do servidor
        new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Packet p = gson.fromJson(line, Packet.class);

                    switch (p.getType()) {
                        case MESSAGE ->
                                System.out.println("\n[" + p.getFrom() + "]: " + p.getContent());

                        case ACK_DELIVERED ->
                                System.out.println("✓✓ Entregue - mensagem " + p.getMessageId());

                        case ACK_READ ->
                                System.out.println("✓✓ Lida - mensagem " + p.getMessageId());

                        default ->
                                System.out.println("Pacote recebido: " + p.getType());
                    }
                }
            } catch (IOException e) {
                System.out.println("Desconectado do servidor.");
            }
        }).start();

        // Registro


        System.out.print("Telefone: ");
        String phone = scanner.nextLine();
        System.out.print("Nome: ");
        String name = scanner.nextLine();
        System.out.print("Apelido: ");
        String nickname = scanner.nextLine();

        Packet register = new Packet();
        register.setType(Packet.Type.REGISTER);
        register.setFrom(phone);
        register.setName(name);
        register.setNickname(nickname);
        out.println(gson.toJson(register));

        System.out.println("Registrado! Digite 'telefone:mensagem' para enviar:");

        // Loop de envio
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine();
            String[] parts = input.split(":", 2);
            if (parts.length == 2) {
                Packet msg = new Packet();
                msg.setType(Packet.Type.MESSAGE);
                msg.setFrom(phone);
                msg.setTo(parts[0]);
                msg.setContent(parts[1]);
                out.println(gson.toJson(msg));
            } else {
                System.out.println("Formato: telefone:mensagem");
            }
        }
    }
}