# Validation du lot 3 et de l’extension sociale

Vérifications réalisées le 19 novembre 2026 sur une copie de `haouala/bourse-ai-action`, à partir du commit `58a415d`. Aucun test n’a modifié la base PostgreSQL du PC de l’utilisateur. Le document d’analyse Word/PDF reste inchangé.

## Construction et tests

Java 21, Maven 3.9.9, Spring Boot 3.5.7. `mvn clean verify` a réussi sur H2 ; les mêmes 53 tests ont ensuite réussi sur une instance PostgreSQL 17.7 isolée. Aucun test ignoré, aucune erreur ni échec.

| Suite | Tests | Résultat H2 | Résultat PostgreSQL 17 |
|---|---:|---|---|
| FinancialIndicatorsTest | 3 | Réussi | Réussi |
| ProviderFlowIntegrationTest | 8 | Réussi | Réussi |
| QuotaIntegrationTest | 6 | Réussi | Réussi |
| IntradayFlowIntegrationTest | 4 | Réussi | Réussi |
| PortfolioIntegrationTest | 12 | Réussi | Réussi |
| MonitoringApiIntegrationTest | 4 | Réussi | Réussi |
| SocialScoringTest | 3 | Réussi | Réussi |
| SocialSignalIntegrationTest | 8 | Réussi | Réussi |
| XCollectorIntegrationTest | 5 | Réussi | Réussi |
| **Total** | **53** | **53 réussis** | **53 réussis** |

Les fournisseurs sont simulés par un serveur HTTP local dans les tests. Ils valident les requêtes, la persistance, le comptage des quotas, les réponses incomplètes et les erreurs. Ils ne démontrent pas les droits d’un abonnement réel ni la disponibilité de chaque action ou crypto.

## Comportements couverts

- Bougies en cours exclues ; séries 15m et 30m analysées séparément ; graphique chronologique.
- Historique quotidien EODHD configurable et conservation du type CRYPTO ; date de publication GDELT conservée.
- File de tâches persistante et dédupliquée ; quota local contrôlé avant l’appel distant.
- Capacités de 100 actions et 30 cryptos ; horaires et changements d’heure pris en compte.
- Achats et ventes idempotents ; réutilisation incorrecte d’une clé refusée.
- Frais, ventes partielles, résultat réalisé et conversion de devise explicite.
- Rejet des ventes supérieures au stock et des achats dépassant la trésorerie ; deux achats concurrents ne peuvent pas dépenser la même somme.
- Prix manquants ou périmés signalés ; pas de limite de perte déclarée saine avec une valorisation incomplète.
- Dépassement global alerté une fois par épisode, avec réarmement après récupération.
- Position longue sans alerte MACD 15m ; position courte avec un seul avis de révision pour le même croisement.
- Effets opposés d’un événement sur deux actifs ; audit complet, dates et méthode conservés.
- Rejeu des publications et événements ; refus d’une modification silencieuse du texte original.
- Autorité liée au sujet et à la date ; périodes de profils qui se chevauchent refusées.
- Plafonds de confiance pour rumeurs et identités non confirmées ; décroissance temporelle et sens inconnu.
- Reprise d’une annonce sans multiplication de l’influence ni remise à zéro de son ancienneté.
- Alerte sociale unique par événement et position ; retrait annulant le score et produisant une correction.
- Collecteur X désactivé par défaut ; contrôle d’identité avant toute réservation et tout appel.
- Pagination X sans avancement prématuré du curseur ; erreurs et dates manquantes sans insertion partielle.
- Réservations de lectures conservées après un échec ; plafond mensuel bloquant avant l’appel HTTP.

## Essai de l’application empaquetée et redémarrage

L’application Spring Boot empaquetée a été lancée puis redémarrée sur une base PostgreSQL isolée. Toutes les données de l’essai sont synthétiques et ne représentent aucun cours réel.

| Mesure | Observation |
|---|---|
| Bougies 15m importées | 40 |
| Capital initial fictif | 1 000 EUR |
| Achat déclaré fictif | 10 unités à 100 EUR, frais nuls |
| Prix de valorisation fictif | 89 EUR |
| Limite de perte fictive | 100 EUR |
| Valeur du portefeuille | 890 EUR |
| Perte nette calculée | 110 EUR |
| État | LOSS_LIMIT_REACHED |
| Alertes enregistrées | 1 |
| Après redémarrage | 40 bougies, 1 position et 1 alerte toujours présentes |
| Appels à un fournisseur réel | 0 |
| Ordres transmis à un courtier | 0 |

L’essai de redémarrage ci-dessus concernait le lot 3 (V1 à V6). L’extension sociale a ensuite été validée par les suites H2 et PostgreSQL, sans nouvel essai de redémarrage empaqueté. Le dictionnaire `DATABASE.md` décrit maintenant les 28 tables et 251 colonnes extraites de PostgreSQL après application des migrations V1 à V7.

## Limites de cette validation

Les performances d’investissement, règles de protection et objectifs de prix ne sont pas validés par ces tests logiciels. Les clés réelles, frais de marché, flux de change automatiques, calendrier complet, historique intraday long, interface Android et notifications push restent à intégrer ou à vérifier. La planification est désactivée par défaut afin de ne pas consommer de crédits tant que le fournisseur et le budget ne sont pas configurés.

La collecte X n’a contacté qu’un serveur HTTP local simulé : ni droit d’accès réel ni facturation n’ont été vérifiés par un appel payant. Les textes de test sont synthétiques. La classification sociale et la vérification des preuves sont manuelles, les pondérations et demi-vies sont heuristiques. Aucun test logiciel ne démontre une prévision boursière rentable ou une relation causale entre une publication et un mouvement de prix.
