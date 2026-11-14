# Sources européennes et provenance — Bourse AI

## Règles
- **Ne jamais fusionner silencieusement** les cotations de plusieurs fournisseurs : `market_candles` est la série canonique (la première importée pour une date) ; `provider_price_observations` garde les observations EODHD avec source, symbole, devise et ajustement.
- `asset_provider_symbols` relie l'identifiant interne à son code chez chaque fournisseur. Pour la production, ajouter ISIN et MIC après vérification des instruments (même ticker sur plusieurs places).
- Ne pas calculer les rendements avec des prix ajustés et bruts mélangés. Les splits, devises et calendriers de marché nécessitent une politique d'ajustement avant comparaison.
- `created_time` est la première insertion, `updated_time` est la dernière modification. `open_time` est la date de la bougie et non son import.
- GDELT `seendate` = date de détection, **pas nécessairement date de publication** ; nous ne l'enregistrons pas comme `published_time` non confirmé.

## Fournisseurs
- **Twelve Data** : gratuit, clé obligatoire, surtout US en Basic ; test manuel de chaque action européenne avant intégration.
- **EODHD Sandbox** : gratuit avec clé, 20 appels/jour, **1 an maximum** d'historique EOD, marchés mondiaux/européens sous réserve du symbole ; pas de fondamentaux gratuits. `POST /api/v1/providers/eodhd/sync/BNP.PA?providerSymbol=BNP.PA&currency=EUR&name=BNP%20Paribas` : exemple de syntaxe seulement, le code exact doit être validé chez EODHD (souvent extension de place comme `.PA`).
- **SEC EDGAR** : gratuit, rapports officiels des émetteurs déclarants US ; pas pour toutes les sociétés UE.
- **GDELT** : gratuit sans token, actualités internationales, recherche d'entreprise par raison sociale ; vérifier le texte et les droits de réutilisation.
- **FMP** : profil si accessible avec la formule gratuite, accès et symboles limités.
- **Émetteurs/AMF/Financial Conduct Authority/Companies House/portails nationaux OAM** : publications réglementaires européennes et UK, gratuites pour lecture mais pas d'API homogène ; connecteurs spécifiques à construire après validation des conditions.

## Limite de la gratuité
Avec EODHD Sandbox, récupérer 100 actions européennes prend **au minimum cinq jours** de quota (20 appels/jour) et ne donne qu'un an de prix, pas cinq ans. Les cinq ans gratuits pour l'ensemble des marchés européens ne sont **pas garantis**.

## Android / prochaine phase
Accueil Android : grille de cartes de symboles, dernières données horodatées et provenance ; clic = JSON complet (`GET /api/v1/investment/explain/{symbol}` et données associées) ; bouton « Analyser » = explication en langage naturel des formules, news, sources et limites. Le backend actuel fournit une explication déterministe **sans modèle IA connecté**. Prévoir une API LLM ou un modèle local, explicable et à coûts maîtrisés.
