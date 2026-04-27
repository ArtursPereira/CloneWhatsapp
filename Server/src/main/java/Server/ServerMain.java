package Server;
import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServerMain {

    public static final int PORT = 5000;
    public static final Gson gson = new Gson();

    // Mapa de usuários online: telefone → stream de saída
    public static final ConcurrentHashMap<String, PrintWriter> onlineUsers
            = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();// Verifica se tem alguma thread disponivel, se n tiver cria outra
        ServerSocket serverSocket = new ServerSocket(PORT); // Cria um socket para o client

        System.out.println("Servidor rodando na porta " + PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept(); // espera um cliente conectar
            System.out.println("Novo cliente conectado: " + clientSocket.getInetAddress());
            pool.submit(new ClientHandler(clientSocket)); // avança a fila, para esperar outra conexão de client
        }
    }
}