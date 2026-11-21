# Validation du 21 novembre 2026

Les résultats locaux ci-dessous correspondent aux suites de cette livraison. Les workflows GitHub Actions « Information worker and backend validation » et « Python ML batch validation » reproduisent ces contrôles ; consulter la PR 4 pour leur statut sur le dernier commit.

| Vérification locale | Résultat |
|---|---|
| Backend Java 21, Maven 3.9.9, H2 | 125 tests, zéro échec, erreur ou test ignoré |
| Même suite sur PostgreSQL 17.7 | 125 tests, zéro échec, erreur ou test ignoré |
| Worker Python 3.12 | 31 tests, zéro échec ou erreur |
| Batch ML avec PostgreSQL | 45 tests, zéro échec, erreur ou test ignoré |
| JAR Java et worker HTTP sur H2 fichier | Réussi, persistance après redémarrage |
| JAR Java et worker HTTP sur PostgreSQL | Réussi, persistance après redémarrage |
| Schéma PostgreSQL après V11 | 48 tables métier, 410 colonnes, hors Flyway |

Les parcours HTTP utilisent de vrais processus Java et Python, mais des publications et réponses IA synthétiques. Deux publications d'un même événement produisent deux contenus (extrait et transcription), une contribution approuvée et un seul appel IA simulé grâce au cache. Les contrôles couvrent aussi les citations invalides, symboles inconnus, refus IA, budgets, reprises de tâches, baux expirés, accès non authentifiés, approbation/retrait, corrections d'articles, contradictions et séparation du mode ML cours seuls. Les bases de validation sont jetables ; la base de l'utilisateur n'est pas modifiée.

Les métriques locales sont conservées dans [validation.json](validation.json). Un test de contradiction a également reproduit puis validé la correction du cas où deux interprétations opposées portent sur un contenu strictement identique : la déduplication ne masque plus le conflit. La CI utilise ses propres bases temporaires et publie les rapports comme artefacts GitHub.

## Vérifications des sources publiques

Le [rapport de disponibilité](public-source-validation.json) contient les URL, nombres d'entrées et empreintes observés, sans recopier les articles. Les 14 flux ont répondu HTTP 200 et leur XML a été lu. Deux transcriptions ont été extraites sur les pages officielles Richmond Fed et RBC ; pour RBC, la correspondance avec le titre de l'épisode a été vérifiée. Un article BCE a fourni un extrait de 17 991 caractères. Les tentatives d'articles Fed et Bank of England ont été bloquées ou refusées, bien que leurs flux soient accessibles.

Ces sondes passent par le proxy de sortie de l'environnement et utilisent le parseur de production. Elles ne valident pas le transport TLS direct avec DNS épinglé du worker sur le réseau de déploiement. La disponibilité et les formats d'un éditeur peuvent changer. L'accès à un flux ne garantit ni l'article intégral ni une transcription pour tous les épisodes. Les replis `SUMMARY_ONLY` et les erreurs d'extraction restent nécessaires.

## Limites restant à valider

- Aucun appel OpenAI payant n'a été réalisé : accès réel au modèle, qualité des interprétations et coût effectif restent à mesurer avec une clé de projet.
- Le regroupement sémantique d'événements différents entre éditeurs exige encore une revue ; plusieurs éditeurs ne prouvent pas des confirmations indépendantes.
- Les fichiers audio de plus de 25 Mo nécessitent une transcription éditeur ou restent en résumé ; le découpage audio n'est pas implémenté.
- Le budget local borne appels et volume d'entrée, pas une facture monétaire ; plusieurs machines nécessitent un compteur partagé.
- Le batch ML reste synthétique. Adaptateur aux historiques réels, disponibilité point-in-time et validation financière indépendante restent à développer.
- Aucune performance de trading n'est validée et aucun ordre de courtage n'est envoyé. Android et ses interfaces de revue ne font pas partie de ces modifications.
