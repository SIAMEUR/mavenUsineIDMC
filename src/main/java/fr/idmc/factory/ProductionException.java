package fr.idmc.factory;

/**Exception levée lorsqu'une erreur survient pendant la fabrication des lunettes.*/
public class ProductionException extends RuntimeException {

    /**
     * @param message description de l'erreur
     */
    public ProductionException(String message) {
        super(message);
    }

    /**
     * @param message description de l'erreur
     * @param cause  exception d'origine
     */
    public ProductionException(String message, Throwable cause) {
        super(message, cause);
    }

}
