package Entity;

import jakarta.persistence.*;

public class DatabaseManager {

    private static final EntityManagerFactory emf =
            Persistence.createEntityManagerFactory("whatsapp-pu");

    public static EntityManager getEntityManager() {
        return emf.createEntityManager();
    }

    public static void close() {
        emf.close();
    }
}