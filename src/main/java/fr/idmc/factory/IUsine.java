package fr.idmc.factory;

import bernard_flou.Fabricateur.Lunette;
import bernard_flou.Fabricateur.TypeLunette;

import java.util.List;
import java.util.Map;

/**
 * Interface de l'Usine de fabrication de lunettes.
 */
public interface IUsine {
    /**
     * @param typesLunettes map associant chaque type de lunette à sa quantité
     * @return la liste des lunettes fabriquées, chacune avec son type et son numéro de série
     * @throws ProductionException si une erreur survient pendant la fabrication
     */
    List<Lunette> produire(final Map<TypeLunette, Integer> typesLunettes);
}