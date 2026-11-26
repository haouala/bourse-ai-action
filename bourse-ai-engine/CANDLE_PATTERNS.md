# Figures de bougies et explications Android

`GET /api/v1/charts/{symbol}?timeframe=15m|30m|1h|1d&limit=250` conserve ses champs
existants et ajoute `patternAnalysis`. Les données proviennent des bougies clôturées
déjà importées : aucun nouvel appel fournisseur, changement de schéma ou coût IA.

## Règles `OHLC_PATTERNS_V1`

Les seuils suivants sont des conventions explicites de cette implémentation, pas une
validation de performance ni une définition universelle. Source pédagogique sur les
formes : [IG Academy — Candlestick patterns](https://www.ig.com/en/ig-academy/the-basics-of-technical-analysis/candlestick-patterns).

| Code | Condition |
| --- | --- |
| `DOJI` | Amplitude haut−bas positive ; corps ≤ 10 % de cette amplitude. Neutre, sans confirmation directionnelle. |
| `HAMMER` | Quatre clôtures précédentes strictement décroissantes ; corps > 10 % et ≤ 33 % de l’amplitude ; mèche basse ≥ 2 × corps ; mèche haute ≤ 0,5 × corps. |
| `BULLISH_ENGULFING` | Même contexte de baisse ; corps précédent baissier hors doji ; corps actuel haussier, strictement plus grand, recouvrant le corps précédent. |
| `BEARISH_ENGULFING` | Conditions symétriques après quatre clôtures précédentes strictement croissantes. |

Le contexte utilise trois variations entre quatre clôtures **antérieures** à la
figure. Les mèches ne sont pas utilisées pour le recouvrement des corps. Les bougies
sans amplitude, invalides, ou encore ouvertes ne produisent aucune figure.
Les calculs utilisent `BigDecimal`, y compris pour les petites valeurs crypto.

Seules les figures des 12 dernières bougies retournées sont publiées. Il faut au moins
cinq bougies pour une figure directionnelle et 21 pour comparer son volume. Avec un
`limit` plus court, le contexte disponible est réduit. `NO_DATA` distingue l’absence
de bougies ; une liste vide avec `AVAILABLE` signifie qu’aucune règle n’a reconnu de
figure sur la fenêtre disponible. L’interface rappelle le nombre de bougies inspectées.

## Continuité et confirmation

Toutes les bougies du contexte doivent être valides, de la même source et
consécutives. En intrajournalier, l’ouverture doit suivre exactement la clôture
précédente ; les contextes traversant une nuit ou une interruption sont donc exclus
par prudence. En journalier, les dates UTC doivent suivre les séances du calendrier
vérifié : week-ends et jours fériés sont autorisés, une séance manquante ou une
couverture inconnue bloque le contexte. La crypto exige des jours consécutifs.

Une figure orientée est initialement `AWAITING_NEXT_BAR`. Seule la bougie clôturée
**immédiatement suivante** peut produire `PRICE_CONFIRMED` : clôture au-dessus du
plus haut pour la hausse, sous le plus bas pour la baisse. Sinon `NOT_CONFIRMED`.
Le champ `confirmationAt` donne l’heure à laquelle cette observation est connue.
Si la source change ou qu’une bougie manque, `UNAVAILABLE`. Un doji est
`NOT_APPLICABLE`. Une bougie ultérieure favorable ne réécrit jamais le résultat
de cette règle. Une confirmation de prix ne signifie pas confirmation par le volume.

`volumeRatio` compare le volume de la figure à la moyenne des **20 bougies
précédentes**, sans inclure la figure. Sources, continuité et volumes strictement
positifs sont requis. À partir de 1,5, `ABOVE_USUAL` ; sinon `NOT_ELEVATED`.
Sans comparaison possible : ratio `null`, statut `UNAVAILABLE`. Ce repère simple
n’ajuste pas le profil horaire du volume ; l’aide Android l’indique.

## Android

L’écran d’un actif montre une carte « Figures de bougies », sa date et l’intervalle,
une explication simple, la condition de confirmation et le volume séparément. Trois
figures récentes sont affichées d’abord ; les autres peuvent être dépliées. Les
détections restent historiques, à la date affichée, même si les imports sont anciens.

L’icône d’information ouvre une définition, un exemple et les limites. Les titres,
champs financiers et formulaires partagent le lexique : OHLC, RSI, MACD, SMA,
volume, volatilité, drawdown, corrélation, budget de perte, ML, Brier, etc. Le lexique
recherchable est disponible dans Accueil et Profil. Les fenêtres se ferment avec
« Compris » et préservent leur ouverture lors d’une recréation de l’écran.

Un serveur ancien ou un rapport de figures incompatible conserve les graphiques et
affiche explicitement « Détection indisponible ». Aucune détection locale de secours.
Le mode démo contient un exemple **fictif** de marteau, distinct des données serveur.

Le détecteur de bougies reste une fonction pure. Ses sorties sont maintenant utilisées
par l’[analyse technique enrichie](TECHNICAL_ANALYSIS.md), les scores V2 et les variables
candidates du ML. L’application distingue les contributions brutes des points
effectivement appliqués selon l’horizon. Les alertes et ordres ne sont pas modifiés.
L’apport prédictif réel n’est pas validé par ces tests fonctionnels.

## Vérifications

Les tests backend couvrent contexte, figures opposées, doji, absence de négociation,
confirmation sans anticipation, trous de données, changement de source, volumes,
petites valeurs, fenêtre de 12, réponse HTTP et exclusion d’une bougie ouverte.
Les tests calendaires couvrent jours fériés, week-ends, séance manquante et crypto.
Les tests Android couvrent le contrat optionnel, les dates incohérentes, les aides
accessibles, la recréation, une police agrandie et la recherche dans le lexique.
