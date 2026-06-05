package Server;

import Entity.*;
import com.google.gson.Gson;
import com.seunome.Packet;
import jakarta.persistence.*;
import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private PrintWriter out;
    private String phone;
    private String sessionToken;

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
            if (phone != null) ServerMain.onlineUsers.remove(phone);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePacket(Packet packet) {
        switch (packet.getType()) {
            case REGISTER        -> handleRegister(packet);
            case LOGIN           -> handleLogin(packet);
            case MESSAGE         -> handleMessage(packet);
            case ACK_DELIVERED   -> handleAckDelivered(packet); //adicionado
            case ACK_READ        -> handleAckRead(packet);
            case HISTORY_REQUEST -> handleHistoryRequest(packet);
            default -> System.out.println("Tipo desconhecido: " + packet.getType());
        }
    }

    private void handleRegister(Packet packet) {
        String requestedPhone = packet.getFrom();
        EntityManager em = DatabaseManager.getEntityManager();
        User existing = em.find(User.class, requestedPhone);

        Packet response = new Packet();

        if (existing == null) {
            em.getTransaction().begin();
            em.persist(new User(requestedPhone, packet.getName(), packet.getNickname(), packet.getPassword()));
            em.getTransaction().commit();
            this.phone = requestedPhone;
            ServerMain.onlineUsers.put(phone, out);
            response.setType(Packet.Type.REGISTER_SUCCESS);
            System.out.println("Usuário registrado: " + phone);
        } else if (existing!=null){
            response.setType(Packet.Type.REGISTER_FAIL);
            System.out.println("O registro falhou, você já tem conta no sistema.");
        }

        out.println(ServerMain.gson.toJson(response));
        em.close();
    }

    public void invalidateSession() {
        Packet kickMessage = new Packet();
        kickMessage.setType(Packet.Type.LOGIN_FAIL);
        out.println(ServerMain.gson.toJson(kickMessage));
        out.flush();
        // Força desconexão
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleLogin(Packet packet) {
        String requestedPhone = packet.getFrom();
        EntityManager em = DatabaseManager.getEntityManager();
        Packet response = new Packet();

        try {
            User user = em.find(User.class, requestedPhone);

            if (user != null && user.getPassword().equals(packet.getPassword())) {

                // Bloqueia se já houver sessão ativa para esse usuário
                if (ServerMain.onlineUsers.containsKey(requestedPhone)) {
                    response.setType(Packet.Type.LOGIN_FAIL);
                    out.println(ServerMain.gson.toJson(response));
                    closeConnection();
                    return;
                }

                // Gera novo token e persiste
                em.getTransaction().begin();
                String newSessionToken = UUID.randomUUID().toString();
                user.setSessionToken(newSessionToken);
                em.merge(user);
                em.getTransaction().commit();

                this.phone = requestedPhone;
                this.sessionToken = newSessionToken;
                ServerMain.onlineUsers.put(phone, out); // usa o mapa original

                response.setType(Packet.Type.LOGIN_SUCCESS);
                out.println(ServerMain.gson.toJson(response));

                deliverPendingMessages();
                notifyPendingReadAcks();

            } else {
                response.setType(Packet.Type.LOGIN_FAIL);
                out.println(ServerMain.gson.toJson(response));

            }

        } catch (Exception e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            response.setType(Packet.Type.LOGIN_FAIL);
            out.println(ServerMain.gson.toJson(response));
        } finally {
            em.close();
        }
    }

    private void closeConnection() {
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();

        User sender   = em.find(User.class, packet.getFrom());
        User receiver = em.find(User.class, packet.getTo());

        Message msg = new Message(sender, receiver, packet.getContent(), LocalDateTime.now());
        em.getTransaction().begin();
        em.persist(msg);
        em.getTransaction().commit();

        PrintWriter receiverOut = ServerMain.onlineUsers.get(packet.getTo());
        if (receiverOut != null) {
            packet.setMessageId(String.valueOf(msg.getId()));
            packet.setStatus("ENVIADA"); //mudança de "entregue" para "enviada"
            receiverOut.println(ServerMain.gson.toJson(packet));

            if (receiverOut.checkError()) {
                ServerMain.onlineUsers.remove(packet.getTo());
                System.out.println("Destinatário desconectado, mensagem continuará como ENVIADA");
            } else {
                System.out.println("Mensagem enviada ao socket do destinatário, aguardando ACK_DELIVERED");
                /*
                em.getTransaction().begin();
                msg.setStatus(MessageStatus.ENTREGUE);
                em.getTransaction().commit();

                Packet ack = new Packet();
                ack.setType(Packet.Type.ACK_DELIVERED);
                ack.setMessageId(String.valueOf(msg.getId()));
                ack.setStatus("ENTREGUE");
                out.println(ServerMain.gson.toJson(ack));
                 */
            }
        } else {
            System.out.println("Destinatário offline, mensagem salva como ENVIADA");
        }
        em.close();
    }

    //adicionado
    private void handleAckDelivered(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();
        Message msg = em.find(Message.class, Long.parseLong(packet.getMessageId()));

        if (msg != null && msg.getStatus() == MessageStatus.ENVIADA) {
            em.getTransaction().begin();
            msg.setStatus(MessageStatus.ENTREGUE);
            em.getTransaction().commit();

            PrintWriter senderOut = ServerMain.onlineUsers.get(msg.getSender().getPhone());
            if (senderOut != null) {
                Packet ack = new Packet();
                ack.setType(Packet.Type.ACK_DELIVERED);
                ack.setMessageId(packet.getMessageId());
                ack.setStatus("ENTREGUE");
                senderOut.println(ServerMain.gson.toJson(ack));
            }
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
            Packet p = new Packet();
            p.setType(Packet.Type.MESSAGE);
            p.setFrom(msg.getSender().getPhone());
            p.setTo(msg.getReceiver().getPhone());
            p.setContent(msg.getContent());
            p.setMessageId(String.valueOf(msg.getId()));
            p.setStatus("ENVIADA"); //mudança de "entregue" para "enviada"
            out.println(ServerMain.gson.toJson(p));

            System.out.println("Mensagem pendente reenviada ao cliente, aguardando ACK_DELIVERED");

            /*
            em.getTransaction().begin();
            Message managed = em.find(Message.class, msg.getId());
            managed.setStatus(MessageStatus.ENTREGUE);
            em.getTransaction().commit();
             */
        }
        em.close();
    }

    private void notifyPendingReadAcks() {
        EntityManager em = DatabaseManager.getEntityManager();

        List<Message> readMsgs = em.createQuery(
                        "SELECT m FROM Message m WHERE m.sender.phone = :phone AND m.status = :status",
                        Message.class)
                .setParameter("phone", phone)
                .setParameter("status", MessageStatus.LIDA)
                .getResultList();

        for (Message msg : readMsgs) {
            Packet ack = new Packet();
            ack.setType(Packet.Type.ACK_READ);
            ack.setMessageId(String.valueOf(msg.getId()));
            ack.setStatus("LIDA");
            out.println(ServerMain.gson.toJson(ack));
        }
        em.close();
    }

    private void handleHistoryRequest(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();

        List<Message> history = em.createQuery(
                        "SELECT m FROM Message m WHERE " +
                                "(m.sender.phone = :user1 AND m.receiver.phone = :user2) OR " +
                                "(m.sender.phone = :user2 AND m.receiver.phone = :user1) " +
                                "ORDER BY m.timestamp ASC",
                        Message.class)
                .setParameter("user1", phone)
                .setParameter("user2", packet.getTo())
                .getResultList();

        for (Message message : history) {
            Packet p = new Packet();
            p.setType(Packet.Type.HISTORY_RESPONSE);
            p.setFrom(message.getSender().getPhone());
            p.setTo(message.getReceiver().getPhone());
            p.setContent(message.getContent());
            p.setMessageId(String.valueOf(message.getId()));
            p.setStatus(message.getStatus().name());
            out.println(ServerMain.gson.toJson(p));
        }
        em.close();
    }
}