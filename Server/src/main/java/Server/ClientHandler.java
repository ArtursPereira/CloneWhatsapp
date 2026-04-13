package Server;

import Entity.*;
import com.google.gson.Gson;
import com.seunome.Packet;
import jakarta.persistence.*;
import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private PrintWriter out;
    private String phone; // telefone do cliente desta thread

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            String line;
            while ((line = in.readLine()) != null) {
                Packet packet = ServerMain.gson.fromJson(line, Packet.class);
                handlePacket(packet);
            }

        } catch (IOException e) {
            System.out.println("Cliente desconectado: " + phone);
        } finally {
            // Remove do mapa ao desconectar
            if (phone != null) ServerMain.onlineUsers.remove(phone);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePacket(Packet packet) {
        switch (packet.getType()) {
            case REGISTER   -> handleRegister(packet);
            case MESSAGE    -> handleMessage(packet);
            case ACK_READ   -> handleAckRead(packet);
            default         -> System.out.println("Tipo desconhecido: " + packet.getType());
        }
    }

    private void handleRegister(Packet packet) {
        this.phone = packet.getFrom();
        EntityManager em = DatabaseManager.getEntityManager();

        // Salva usuário se não existir
        User existing = em.find(User.class, phone);
        if (existing == null) {
            em.getTransaction().begin();
            em.persist(new User(phone, packet.getName(), packet.getNickname()));
            em.getTransaction().commit();
        }
        em.close();

        // Adiciona ao mapa de online
        ServerMain.onlineUsers.put(phone, out);
        System.out.println("Usuário registrado: " + phone);

        // Entrega mensagens pendentes
        deliverPendingMessages();
    }

    private void handleMessage(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();

        User sender   = em.find(User.class, packet.getFrom());
        User receiver = em.find(User.class, packet.getTo());

        // Salva mensagem no banco como ENVIADA
        Message msg = new Message(sender, receiver, packet.getContent(), LocalDateTime.now());
        em.getTransaction().begin();
        em.persist(msg);
        em.getTransaction().commit();

        // Tenta entregar se destinatário está online
        PrintWriter receiverOut = ServerMain.onlineUsers.get(packet.getTo());
        if (receiverOut != null) {
            packet.setMessageId(String.valueOf(msg.getId()));
            packet.setStatus("ENTREGUE");
            receiverOut.println(ServerMain.gson.toJson(packet));

            // Verifica se o envio realmente funcionou
            if (receiverOut.checkError()) {
                // Socket estava morto, remove do mapa e mantém como ENVIADA
                ServerMain.onlineUsers.remove(packet.getTo());
                System.out.println("Destinatário desconectado, mensagem ficará como ENVIADA");
            } else {
                // Entregou de verdade
                em.getTransaction().begin();
                msg.setStatus(MessageStatus.ENTREGUE);
                em.getTransaction().commit();

                Packet ack = new Packet();
                ack.setType(Packet.Type.ACK_DELIVERED);
                ack.setMessageId(String.valueOf(msg.getId()));
                ack.setStatus("ENTREGUE");
                out.println(ServerMain.gson.toJson(ack));
            }
        } else {
            System.out.println("Destinatário offline, mensagem salva como ENVIADA");
        }
        em.close();
    }

    private void handleAckRead(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();
        Message msg = em.find(Message.class, Long.parseLong(packet.getMessageId()));

        if (msg != null) {
            em.getTransaction().begin();
            msg.setStatus(MessageStatus.LIDA);
            em.getTransaction().commit();

            // Notifica remetente que foi lida
            PrintWriter senderOut = ServerMain.onlineUsers.get(msg.getSender().getPhone());
            if (senderOut != null) {
                Packet ack = new Packet();
                ack.setType(Packet.Type.ACK_READ);
                ack.setMessageId(packet.getMessageId());
                ack.setStatus("LIDA");
                senderOut.println(ServerMain.gson.toJson(ack));
            }
        }
        em.close();
    }

    private void deliverPendingMessages() {
        EntityManager em = DatabaseManager.getEntityManager();

        List<Message> pending = em.createQuery(
                        "SELECT m FROM Message m WHERE m.receiver.phone = :phone AND m.status = :status",
                        Message.class)
                .setParameter("phone", phone)
                .setParameter("status", MessageStatus.ENVIADA)
                .getResultList();

        System.out.println("Pendentes para " + phone + ": " + pending.size());

        for (Message msg : pending) {
            // 1. Envia o Packet pelo socket primeiro
            Packet p = new Packet();
            p.setType(Packet.Type.MESSAGE);
            p.setFrom(msg.getSender().getPhone());
            p.setContent(msg.getContent());
            p.setMessageId(String.valueOf(msg.getId()));
            p.setStatus("ENTREGUE");
            out.println(ServerMain.gson.toJson(p)); // ← envia pro cliente

            // 2. Só então atualiza o status no banco
            em.getTransaction().begin();
            Message managed = em.find(Message.class, msg.getId());
            managed.setStatus(MessageStatus.ENTREGUE);
            em.getTransaction().commit();
        }
        em.close();
    }
}