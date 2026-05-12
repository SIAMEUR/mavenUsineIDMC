package fr.idmc.factory;

import bernard_flou.Fabricateur;
import bernard_flou.Fabricateur.Lunette;
import bernard_flou.Fabricateur.TypeLunette;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Usine implements IUsine {

    private static final Logger logger = LoggerFactory.getLogger(Usine.class);

    private final Fabricateur fabricateur;
    private final int capacite;
    private final ExecutorService pool;
    private final List<Demande> fileAttente = new ArrayList<>();
    private final Thread coordinateur;

    public Usine(Fabricateur fabricateur) {
        this.fabricateur = fabricateur;
        this.capacite    = fabricateur.getCapacity();
        this.pool        = Executors.newFixedThreadPool(capacite);
        logger.info("Usine démarré avec capacite : {}", capacite);

        coordinateur = new Thread(this::boucleCoordinateur, "Coordinateur");
        coordinateur.setDaemon(true);
        coordinateur.start();
    }

    /**
     * @param typesLunettes la commande sous forme de Map
     * @return la liste des lunettes produites
     */
    @Override
    public List<Lunette> produire(Map<TypeLunette, Integer> typesLunettes) {
        List<TypeLunette> listeTypes = aplatir(typesLunettes);
        logger.info("Nouvelle commande reçue : {} lunettes  {}", listeTypes.size(), typesLunettes);

        Demande demande = new Demande(listeTypes);

        // on dépose la demande et on réveille le coordinateur
        synchronized (fileAttente) {
            fileAttente.add(demande);
            fileAttente.notifyAll();
        }

        List<Lunette> resultat = demande.attendreResultat();
        logger.info("Commande terminée : {} lunettes reçues.", resultat.size());
        return resultat;
    }

    private void boucleCoordinateur() {
        logger.info("Coordinateur démarré.");

        while (!Thread.currentThread().isInterrupted()) {
            List<Demande> demandesATraiter;

            synchronized (fileAttente) {
                while (fileAttente.isEmpty()) {
                    try {
                        fileAttente.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                demandesATraiter = new ArrayList<>(fileAttente);
                fileAttente.clear();
            }

            logger.info("Coordinateur : {} demande à traiter.", demandesATraiter.size());
            traiterDemandes(demandesATraiter);
        }
    }

    /**
     * Regroupe toutes les demandes en slots puis les découpe en cycles selon la capacité du Fabricateur
     * @param demandes la liste des demandes à traiter
     */
    private void traiterDemandes(List<Demande> demandes) {
        List<Slot> tousLesSlots = new ArrayList<>();
        for (Demande d : demandes) {
            for (TypeLunette type : d.getTypes()) {
                tousLesSlots.add(new Slot(type, d));
            }
        }

        List<List<Slot>> cycles = decouper(tousLesSlots, capacite);
        logger.info("Coordinateur : {} cycle à lancer.", cycles.size());

        for (List<Slot> cycle : cycles) {
            executerCycle(cycle);
        }
    }

    /**
     * Execute un cycle de fabrication :
     * configure le Fabricateur, lance un Callable par lunette,
     * attend les Future et distribue les lunettes à chaque demande
     * @param cycle la liste des slots à traiter dans ce cycle
     */
    private void executerCycle(List<Slot> cycle) {
        int taille = cycle.size();
        logger.info("Démarrage d'un cycle : {} lunette(s).", taille);

        TypeLunette[] types = cycle.stream()
                .map(Slot::getType)
                .toArray(TypeLunette[]::new);
        try {
            fabricateur.configurer(types);
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.error("Erreur lors de configurer() : {}", e.getMessage());
            cycle.stream().map(Slot::getDemande).distinct()
                    .forEach(d -> d.signalerErreur(new ProductionException("Erreur configurer()", e)));
            return;
        }

        List<Future<LunetteEtDemande>> futures = new ArrayList<>();
        for (Slot slot : cycle) {
            Callable<LunetteEtDemande> tache = () -> {
                Lunette lunette = fabricateur.fabriquer(slot.getType());
                return new LunetteEtDemande(lunette, slot.getDemande());
            };
            futures.add(pool.submit(tache));
        }

        for (Future<LunetteEtDemande> future : futures) {
            try {
                LunetteEtDemande resultat = future.get();
                resultat.demande().ajouterLunette(resultat.lunette());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Cycle interrompu.");
                return;
            } catch (ExecutionException e) {
                logger.error("Erreur lors de fabriquer() : {}", e.getCause().getMessage());
                cycle.stream().map(Slot::getDemande).distinct()
                        .forEach(d -> d.signalerErreur(
                                new ProductionException("Erreur fabriquer()", e.getCause())));
                return;
            }
        }
        logger.info("Cycle terminé : {} lunettes produites.", taille);
    }

    /**
     * extriare que les types dans chaque map (type:quantité)
     * Exemple : {BlaBlaBla:2,Bananaaaa:1} en {BlaBlaBla,Bananaaaa}
     * @param map la commande sous forme de Map
     * @return la liste aplatie
     */
    private List<TypeLunette> aplatir(Map<TypeLunette, Integer> map) {
        List<TypeLunette> liste = new ArrayList<>();
        map.forEach((type, qnt) -> {
            for (int i = 0; i < qnt; i++) {
                liste.add(type);
            }
        });
        return liste;
    }

    /**
     * Découpe une selon la capacité
     * Exemple : {BlaBlaBla,Bananaaaa} avec capacité de 1 -> {BlaBlaBla}, {Bananaaaa}
     * @param liste  la liste a decouper
     * @param capacité la taille max de chaque sous liste
     * @param <T>    le type des élements
     * @return la liste des sous listes
     */
    private <T> List<List<T>> decouper(List<T> liste, int capacité) {
        List<List<T>> resultat = new ArrayList<>();
        for (int i = 0; i < liste.size(); i += capacité) {
            resultat.add(new ArrayList<>(liste.subList(i, Math.min(i + capacité, liste.size()))));
        }
        return resultat;
    }

    //Association entre un type de lunette à produire et la demande cliente qui l'a soumis
    //c'est notre façon pour comment savoir à qui appartient la commande
    private static class Slot {
        private final TypeLunette type;
        private final Demande demande;

        Slot(TypeLunette type, Demande demande) {
            this.type    = type;
            this.demande = demande;
        }

        TypeLunette getType()    { return type; }
        Demande     getDemande() { return demande; }
    }

    //pour savoir à qui la commande
    /**
     * @param lunette
     * @param demande
     */
    private record LunetteEtDemande(Lunette lunette, Demande demande) {}

    private static class Demande {

        private final List<TypeLunette> types;
        private final List<Lunette> lunettesRecues;
        private final int quantiteTotale;
        private ProductionException erreur = null;
        private boolean termine = false;

        Demande(List<TypeLunette> types) {
            this.types          = types;
            this.quantiteTotale = types.size();
            this.lunettesRecues = new ArrayList<>();
        }

        List<TypeLunette> getTypes() { return types; }

        /**
         * @param lunette la lunette peoduite
         */
        synchronized void ajouterLunette(Lunette lunette) {
            lunettesRecues.add(lunette);
            if (lunettesRecues.size() >= quantiteTotale) {
                termine = true;
                notifyAll();
            }
        }

        /**
         * @param e l'exception
         */
        synchronized void signalerErreur(ProductionException e) {
            this.erreur  = e;
            this.termine = true;
            notifyAll();
        }

        /**
         * @return la liste des lunettes produites
         * @throws ProductionException ProductionException
         */
        synchronized List<Lunette> attendreResultat() {
            while (!termine) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProductionException("Attente interrompue", e);
                }
            }
            if (erreur != null) throw erreur;
            return new ArrayList<>(lunettesRecues);
        }
    }
}