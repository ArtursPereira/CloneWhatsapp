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
        EntityManager em = DatabaseManager.getEntityManager(); // cria uma variavel para usar comandos do bd

        // Salva usuário se não existir
        User existing = em.find(User.class, phone); // busca o cliente no banco de dados
        if (existing == null) { // Verifica se não existe no BD, se não existir ele registra
            em.getTransaction().begin(); // Começando uma transação
            em.persist(new User(phone, packet.getName(), packet.getNickname())); // fazendo a operação de salvar
            em.getTransaction().commit(); // termina a transação se nada no meio ocorrer
        }
        em.close(); // fecha a conexão com o banco de dados

        ServerMain.onlineUsers.put(phone, out); // Loga o amigo
        System.out.println("Usuário registrado: " + phone); // envia uma mensagem avisando que o amigo está online
        deliverPendingMessages(); // Entrega mensagens pendentes
        notifyPendingReadAcks(); // Verifica se tem mensagens pendentes
    }

    private void handleMessage(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager(); // variavel para usar comandos do bd

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
                // É uma confirmação para quem envia a msg, n pra quem recebe
                Packet ack = new Packet(); // cria um novo packet
                ack.setType(Packet.Type.ACK_DELIVERED); // o ack delivery seria a confirmação do whatsapp
                ack.setMessageId(String.valueOf(msg.getId())); // Associa ao id da msg
                ack.setStatus("ENTREGUE"); // seta os status pra entregue
                out.println(ServerMain.gson.toJson(ack)); // serializa para JSON
            }
        } else { // notifica o remetende que o destinatario está desconectado
            System.out.println("Destinatário offline, mensagem salva como ENVIADA");
        }
        em.close();
    }

    private void handleAckRead(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager(); // variavel para mexer no bd
        Message msg = em.find(Message.class, Long.parseLong(packet.getMessageId()));
        // Verifica mensagens pelo mensageID no packet

        if (msg != null) {
            em.getTransaction().begin();
            msg.setStatus(MessageStatus.LIDA); // coloca o status da mensagem como lida
            em.getTransaction().commit();
            PrintWriter senderOut = ServerMain.onlineUsers.get(msg.getSender().getPhone());
            // procura o socket do usuario para achar o número do remetende
            if (senderOut != null) { // Avisa ao remetente que a msg foi enviada
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
        EntityManager em = DatabaseManager.getEntityManager();// cria uma variavel para usar o bd

        // cria uma lista de mensagens com mensagens do status enviado entre o que envia e o que recebe
        List<Message> pending = em.createQuery(
                        "SELECT m FROM Message m WHERE m.receiver.phone = :phone AND m.status = :status",
                        Message.class)
                .setParameter("phone", phone) // telefone
                .setParameter("status", MessageStatus.ENVIADA) // status enviado
                .getResultList(); // pegar os resultados

        System.out.println("Pendentes para " + phone + ": " + pending.size()); // mensagens pendentes

        for (Message msg : pending) { // itera sobre as mensagens que n foram entregues
            // 1. Envia o Packet pelo socket primeiro
            Packet p = new Packet();
            p.setType(Packet.Type.MESSAGE); // define o packet como menssage
            p.setFrom(msg.getSender().getPhone()); // quem enviou
            p.setContent(msg.getContent()); // conteudo
            p.setMessageId(String.valueOf(msg.getId())); // id da msg
            p.setStatus("ENTREGUE"); // status pra entregue
            out.println(ServerMain.gson.toJson(p)); // ← envia pro cliente

            // atualiza os status no banco
            em.getTransaction().begin();
            Message managed = em.find(Message.class, msg.getId());
            managed.setStatus(MessageStatus.ENTREGUE);
            em.getTransaction().commit();
        }
        em.close();
    }

    private void notifyPendingReadAcks() {
        // essa função é usada quando o destinatario fica ofline depois de enviar uma mensagem
        EntityManager em = DatabaseManager.getEntityManager(); // variavel do bd
        //Guarda as mensagens que foram lidas pelo destinatario
        List<Message> readMsgs = em.createQuery(
                "SELECT m FROM Message m WHERE m.sender.phone = :phone AND m.status = :status",
                Message.class)
                .setParameter("phone", phone)
                .setParameter("status", MessageStatus.LIDA)
                .getResultList(); // pega as mensagens que foram lidas pelo destinatario

        for (Message msg : readMsgs) {// itera as mensagens
            Packet ack = new Packet(); // cria um packet
            ack.setType(Packet.Type.ACK_READ); // define o tipo do packet
            ack.setMessageId(String.valueOf(msg.getId())); // pega o id da mensagem
            ack.setStatus("LIDA"); // coloca os estados como lida
            out.println(ServerMain.gson.toJson(ack)); // serializa
        }
        em.close(); // fecha o bd
    }



    private void handleHistoryRequest(Packet packet) {
        EntityManager em = DatabaseManager.getEntityManager();
        // busca as mensagens no banco de dados trocadas entre dois usuários
        List<Message> history = em.createQuery("SELECT m FROM Message m WHERE " +
                        "(m.sender.phone = :user1 AND m.receiver.phone = :user2) OR  " +
                        "(m.sender.phone = :user2 AND m.receiver.phone = :user1) " +
                        "ORDER BY m.timestamp ASC ",
                 Message.class)
                .setParameter("user1", phone)
                .setParameter("user2", packet.getTo())
                .getResultList();

        for(Message message: history){ // itera sobre as mensagens
            Packet p = new Packet(); // cria um packet
            p.setType(Packet.Type.HISTORY_RESPONSE); // tipo historico
            p.setFrom(message.getSender().getPhone());
            p.setTo(message.getReceiver().getPhone());
            p.setContent(message.getContent());
            p.setMessageId(String.valueOf(message.getId()));
            p.setStatus(message.getStatus().name());
            out.println(ServerMain.gson.toJson(p));
            // Preenche as informações e envia para o  usuário que pediu o histórico
        }
        em.close();
    }

}