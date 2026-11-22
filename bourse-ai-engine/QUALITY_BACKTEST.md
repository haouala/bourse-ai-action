# Lots 1 et 2 — Qualité des données et backtesting

Branche de livraison : `feature/data-quality-historical-backtesting`.

## Lot 1 : Data Quality Engine

`GET /api/v1/quality/NVDA?timeframe=1d`

Produit `status` (`PASS`, `REVIEW`, `BLOCKED`), nombre de bougies,
nombre d'observations indépendantes et liste des anomalies datées.

Contrôles : OHLCV incohérents, historique trop court, données trop anciennes,
variation journalière > 40 % (possible split), écarts > 2 % entre fournisseurs,
devise incohérente, ajustement de prix non harmonisé et absence de corroboration.
Ces seuils sont des **alertes heuristiques**, non une validation des prix du marché.
Les fermetures de marchés/jours fériés ne sont pas encore prises en charge.
Une observation du même fournisseur n'est pas comptée comme confirmation indépendante.
Les séries ajustées et non ajustées ne doivent pas être comparées directement.

## Lot 2 : Historical Backtesting

`GET /api/v1/backtests/NVDA?shortWindow=10&longWindow=30&feeBps=10`

Stratégie initiale et vérifiable : moyenne mobile courte > moyenne longue =
position longue, sinon liquidités. Le signal est calculé **sur les clôtures
précédentes seulement** et exécuté à la clôture suivante. Un coût forfaitaire
(`feeBps`, points de base) est prélevé à chaque entrée/sortie.
Le rapport renvoie la courbe chronologique, le capital final, le nombre
de transactions, le rendement, le benchmark achat-conservation et le drawdown maximal.

**Limites obligatoires** : les prix de clôture suivants sont une hypothèse
d'exécution, sans estimation du spread, de l'impact de marché ni du slippage ;
pas de dividendes, fiscalité ou change, pas de short. Les courbes canoniques
peuvent contenir des splits non ajustés. Le benchmark ne déduit pas de frais.
Les tests hors échantillon, la validation walk-forward et le simulateur
d'exécution réaliste restent des modules distincts de la feuille de route.
Une stratégie qui a bien fonctionné dans le passé n'est pas une promesse de gain.

## Exécution locale

Depuis `bourse-ai-engine` :

```bash
mvn clean test
mvn spring-boot:run
curl 'http://localhost:8081/api/v1/quality/NVDA?timeframe=1d'
curl 'http://localhost:8081/api/v1/backtests/NVDA?shortWindow=10&longWindow=30&feeBps=10'
```

Les deux API sont en lecture seule et ne consomment pas de quotas fournisseurs.
La configuration du port dépend du profil actif.
Les endpoints doivent être protégés avant toute mise en production.
