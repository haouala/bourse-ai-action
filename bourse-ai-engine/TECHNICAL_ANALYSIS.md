# Analyse technique, scores et ML — V1/V2

## Périmètre

`GET /api/v1/technical-analysis/{symbol}?timeframe=1d` retourne les données datées,
les structures, 18 variables techniques et les contributions brutes. Les périodes
acceptées utilisent le calendrier partagé. Le mobile propose 15 min, 30 min, 1 h et
1 jour. Aucun appel fournisseur supplémentaire ni migration de schéma n’est nécessaire.

Les méthodes retenues sont courantes et calculables ; leur popularité ne démontre
pas leur capacité à produire un rendement. Elliott reste une lecture exploratoire
simplifiée, pas un comptage universel ou une prévision certaine.

## Données et disponibilité

`ClosedMarketHistory` est partagé par graphiques, contrôle de qualité, scénarios,
scores et explications. Il exclut les bougies encore ouvertes et futures, résout les
clôtures en une requête par lot et conserve les dates de source. Les vérifications
de qualité portent sur les 10 000 dernières bougies clôturées au maximum.

L’analyse technique utilise au plus 250 bougies. Un OHLC invalide, une interruption
ou un changement de fournisseur réinitialise son contexte. En journalier, les jours
fériés et week-ends vérifiés sont autorisés ; une séance manquante ne l’est pas.
En intrajournalier, le contexte ne traverse pas une interruption de cotation.

Les scores utilisent uniquement une analyse à jour, avec calendrier vérifié, au
moins cinq bougies continues et la même date de référence que le scénario.
Une série ancienne, suspendue ou trop courte garde ses preuves mais ajoute zéro point.

## Structures et indicateurs

| Élément | Convention de cette implémentation |
| --- | --- |
| Marteau, doji, engloutissements | Règles décimales [décrites ici](CANDLE_PATTERNS.md). Variables sur la dernière bougie ; confirmation sur la précédente connue à la clôture suivante. |
| Pivots de structure | Extrême strict des clôtures avec deux bougies de part et d’autre. `time` est la date du pivot ; `availableAt` celle de sa confirmation deux bougies plus tard. |
| Triple creux/sommet | Cinq pivots alternés ; extrêmes comparables à 2 %, rebonds d’au moins 2 %, mouvement antérieur d’au moins 3 %. La cassure doit être connue après confirmation du dernier pivot. |
| Triple en formation | Affiché, sans contribution directionnelle. Invalidation si le prix dépasse le plancher/plafond de plus de 1 %. |
| Cassure triple observée | Clôture au-delà des deux niveaux intermédiaires ; prise en compte jusqu’à trois bougies après la première cassure. |
| Elliott 1–5 | Six pivots : vague 2 ne dépasse pas l’origine, vague 3 dépasse 1, vague 4 ne recouvre pas 1, vague 5 dépasse 3, vague 3 n’est pas la plus courte des vagues 1/3/5. Pas de points favorables pour cette seule impulsion complète. |
| Elliott A–B–C | Neuf pivots après l’impulsion : B reste sous 5, C dépasse A sans dépasser l’origine, avec règles symétriques en baisse. Signal exploratoire récent, pas de cible future inventée. |
| Donchian 20 | Clôture comparée au plus haut/bas des 20 bougies précédentes, bougie actuelle exclue. |
| ATR 14 | Moyenne **simple** des 14 amplitudes vraies, divisée par la clôture. Variante explicite, sans lissage de Wilder. |
| Bollinger 20 | Moyenne de 20 clôtures ±2 écarts-types de population. Position normalisée à ±1 aux bandes ; largeur rapportée à la moyenne. |
| Tendance SMA 20/50 | Ratio moyenne 20 / moyenne 50 −1. C’est un état de tendance, pas nécessairement un nouveau croisement. |

Le choix des pivots courants peut évoluer avec de nouvelles données. Les variables
ML sont toujours recalculées sur le **préfixe disponible à chaque date**, jamais
en attribuant rétrospectivement un pivot au jour de son extrême. Les seuils, le
comptage strict et les fenêtres de récence sont des conventions expérimentales.

## Points dans les scores

`TECHNICAL_SCORE_V2` enrichit les scores opportunité/risque existants.

| Famille | Opportunité brute | Risque brut |
| --- | --- | --- |
| Figures de bougies | Haussière +2 ; baissière −2 ; confirmation ±3 ; total borné à ±3 | Doji +1 ; baissière +2 ; confirmation baissière +3 ; total plafonné à 3 |
| Donchian | +2 / −2 selon la cassure | +2 pour cassure baissière |
| Triple confirmé | +3 / −3 | +3 pour triple sommet confirmé |
| Elliott A–B–C compatible | +1 / −1 | +1 pour la configuration baissière |
| SMA 20/50 | +2 / −2 | +1 si moyenne courte sous la longue |
| ATR ≥3 % | 0 | +2 |
| Clôture hors bandes de Bollinger | 0 | +1 |

Total technique borné à **±8 d’opportunité et +8 de risque**. Les horizons de
7/30/90/180 jours appliquent ensuite les facteurs 1/0,5/0,25/0,25, arrondis et bornes
0–100. Les champs `technicalOpportunityDelta` et `technicalRiskDelta` exposent la
différence **effectivement appliquée**, après saturation. Les détails par famille
restent bruts pour l’audit. Aucun gain de fiabilité n’est supposé.

L’analyse explicative crée `EXPLAINABLE_RULES_V2_TECHNICAL` : 40 % du budget technique
pour MACD, 40 % pour RSI, 20 % réservés à l’analyse technique enrichie. Cette réserve
est multipliée par le total d’opportunité brut /8. Les trois horizons continuent à
utiliser leurs séries 15 min /1 h /1 jour respectives. Les règles V1 restent
sélectionnables, avec leurs anciennes pondérations ; les snapshots antérieurs sont
immuables et les changements de version sont signalés.

Ces points sont des heuristiques actives autorisées, **pas des poids appris ni des
probabilités de gain**. Des indicateurs corrélés peuvent partager une information :
les plafonds limitent leur amplification, sans constituer une calibration statistique.

## ML : 22 variables et comparaison réservée

Le mode marché lit désormais open/high/low/close/volume de la série qualifiée
`SPLIT_ADJUSTED`, avec source et calendrier identifiés. Il garde ses quatre variables
de prix et ajoute les 18 variables de `TECHNICAL_FEATURES_V1` : figures, confirmations,
volume relatif, disponibilité, triples, Elliott, cassures, ATR, Bollinger et tendance.

Les valeurs manquantes restent `None` ; elles ne deviennent pas silencieusement des
figures absentes. La régression logistique apprend une imputation médiane et une
normalisation sur l’entraînement seulement. Le gradient boosting gère les valeurs
manquantes. Les ratios de volume ML sont plafonnés à 20. Les autres disponibilités
restent explicites. Aucune information, date de confirmation ou étiquette future
n’est incluse avant qu’elle soit connue.

Chaque découpage chronologique compare `PRICE_ONLY` à `TECHNICAL_ENRICHED` avec les
deux familles de modèles existantes. Le Brier de **validation** choisit le modèle et
les variables ; le test final ne sert pas à sélectionner. Les frontières sont
purgées des résultats qui débordent sur la période suivante. Le rapport publie
`selectedFeatureSet`, `featureComparison`, `features`, `candidateFeatures` et
`trainerVersion=market-classifiers-walk-forward-v2-technical`.

Un jeu sans OHLC disponibles garde le mode prix seuls. Un ancien rapport reste
lisible. Les empreintes V2 et la liste de variables empêchent la réutilisation d’un
ancien entraînement. Relancer `train-market` avec les mêmes options historiques
produit donc un nouveau calcul ; la prochaine exécution configurée utilise ce code.
Le mobile affiche les variables du modèle effectivement retenu et les Brier des
deux variantes quand ils existent. Il ne prétend pas qu’un ancien modèle a changé.

## Validation et limites

Le contrat [Java/Python](../contracts/technical-features-v1.json) vérifie les mêmes
scénarios : bougies, confirmations, triples, Elliott, petits contextes, trous et
indicateurs. Les tests vérifient aussi la date de disponibilité, l’absence de fuite
du test vers le choix des variables, la contribution aux scores, la compatibilité
V1, les snapshots, les bougies ouvertes, PostgreSQL, les APIs et l’interface Android.

Les tests de marché utilisent des cours **artificiels** dans le vrai schéma Java.
Ils prouvent le fonctionnement, pas une performance boursière réelle. Les biais
d’univers, historiques révisés, actions radiées, frais incomplets et glissement de
prix restent les limites déjà documentées du laboratoire ML. Aucun ordre n’est envoyé.

Références pédagogiques : [Elliott](https://www.tradingview.com/support/solutions/43000653212-elliott-wave-theory-and-tools/),
[triples](https://www.fidelity.com/bin-public/060_www_fidelity_com/documents/learning-center/Deck_Chart%20patterns.pdf),
[ATR](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/atr),
[Bollinger](https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/bollinger-bands),
[Donchian](https://www.tradingview.com/support/solutions/43000502253-donchian-channels-dc/).
