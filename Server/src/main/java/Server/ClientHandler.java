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

    private final Socket socket; //conexão TCP com o cliente
    private PrintWriter out; // stream para escrever pro cliente
    private String phone; // telefone do cliente desta thread

    public ClientHandler(Socket socket) {
        this.socket = socket;
    } // construtor, da socket do cliente

    @Override
    public void run() { // loop inicial
        try {
            BufferedReader in = new BufferedReader( // stream para ler o cliente
                    new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true); // stream para escrever pro cliente

            String line;
            while ((line = in.readLine()) != null) { // ler linha por linha até o JSON acabar
                Packet packet = ServerMain.gson.fromJson(line, Packet.class); // converte JSON para o object packet
                handlePacket(packet); // processa o packet
            }

        } catch (IOException e) {
            System.out.println("Cliente desconectado: " + phone);
        } finally {
            // Remove do mapa ao desconectar
            if (phone != null) ServerMain.onlineUsers.remove(phone); // Remove o user
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePacket(Packet packet) { // função que recebe os pacotes e define o que vai ser feito
        //dependendo  do caso escolhido
        switch (packet.getType()) {
            case REGISTER   -> handleRegister(packet);
            case MESSAGE    -> handleMessage(packet);
            case ACK_READ   -> handleAckRead(packet);
            case HISTORY_REQUEST ->  handleHistoryRequest(packet);
            default         -> System.out.println("Tipo desconhecido: " + packet.getType());
        }
    }

    private void handleRegister(Packet packet) { // case REGISTER
        this.phone = packet.getFrom(); // pega o número de telefone informado
        EntityManager em = DatabaseManager.getEntityManager();

        // Salva usuário se não existir
        User existing = em.find(User.class, phone);
        if (existing == null) { // Verifica se não existe no BD, se não existir ele registra
            em.getTransaction().begin(); // Começando uma transação
            em.persist(new User(phone, packet.getName(), packet.getNickname())); // fazendo a operação de salvar
            em.getTransaction().commit(); // termina a transação se nada no meio ocorrer
        }
        em.close(); // sai do case

        ServerMain.onlineUsers.put(phone, out); // Loga o amigo
        System.out.println("Usuário registrado: " + phone); // envia uma mensagem avisando que o amigo está online
        deliverPendingMessages(); // Entrega mensagens pendentes
    }
    //TODO alterar a lógica de envio para funcionar corretamente,
    // os status de lido, entregue e enviada não estão corretos
    // Ajeitar essa função e as que contribuem para esse resultado
    private void handleMessage(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();

        User sender   = em.find(User.class, packet.getFrom()); // pega os dados de quem envia
        User receiver = em.find(User.class, packet.getTo()); // pega os dados de quem recebe

        // Salva mensagem no banco como ENVIADA
        Message msg = new Message(sender, receiver, packet.getContent(), LocalDateTime.now());
        em.getTransaction().begin(); // começa transação
        em.persist(msg); // Guarda a mensagem no BD
        em.getTransaction().commit(); // termina transação

        // Tenta entregar se destinatário está online
        PrintWriter receiverOut = ServerMain.onlineUsers.get(packet.getTo());
        if (receiverOut != null) { // se ele estiver online
            packet.setMessageId(String.valueOf(msg.getId())); // envia a msg
            packet.setStatus("ENTREGUE"); // coloca como entregue
            receiverOut.println(ServerMain.gson.toJson(packet)); // converte json para packet

            // Verifica se o envio realmente funcionou
            if (receiverOut.checkError()) {
                // Socket estava morto, remove do mapa e mantém como ENVIADA
                ServerMain.onlineUsers.remove(packet.getTo());
                System.out.println("Destinatário desconectado, mensagem ficará como ENVIADA");
            } else {
                // Entregou de verdade
                em.getTransaction().begin(); // transação
                msg.setStatus(MessageStatus.ENTREGUE); // muda o status para entregue
                em.getTransaction().commit(); // termina

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
            //TODO: Concertar essa função pra retornar lida quando o amigo se reconectar
            // Notifica remetente que foi lida (Não funciona)
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

    /*
    TODO criar a função para enviar o histórico entre dois usuários,
    Ideia inical é pegar o número de quem solicitou o histórico
     e pedir o número do outro usuário que ele deseja*/

    private void handleHistoryRequest(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();

        List<Message> history = em.createQuery("SELECT m FROM Message m WHERE " +
                        "(m.sender.phone = :user1 AND m.receiver.phone = :user2) OR  " +
                        "(m.sender.phone = :user2 AND m.receiver.phone = :user1) " +
                        "ORDER BY m.timestamp ASC ",
                 Message.class)
                .setParameter("user1", phone)
                .setParameter("user2", packet.getTo())
                .getResultList();

        for(Message message: history){
            Packet p = new Packet();
            p.setType(Packet.Type.HISTORY_RESPONSE);
            p.setFrom(message.getSender().getPhone());
            p.setTo(message.getReceiver().getPhone());
            p.setContent(message.getContent());
            p.setMessageId(String.valueOf(message.getId()));
            p.setStatus(message.getStatus().name());
            out.println(ServerMain.gson.toJson(p));

        }
    }

}