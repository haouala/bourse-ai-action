# Bourse AI

Backend Java 21 / Spring Boot dans `bourse-ai-engine`.

Voir [la configuration et les quotas](bourse-ai-engine/CONFIGURATION.md) pour le lancement, les clés privées, PostgreSQL et les tests.

```bash
cd bourse-ai-engine
mvn clean verify
mvn spring-boot:run
```

Port par défaut : 8081. Sans profil `postgres`, la base H2 est volatile ; utiliser PostgreSQL pour conserver les données et les compteurs entre redémarrages.

## Lot 3

- [Architecture, code et exemples d’API](bourse-ai-engine/docs/ARCHITECTURE.md)
- [Schémas et dictionnaire de la base de données](bourse-ai-engine/docs/DATABASE.md)
- [Résultats des tests et de l’essai après redémarrage](bourse-ai-engine/docs/VALIDATION.md)
- [Publications sociales, influence par sujet et scores par actif](bourse-ai-engine/docs/SOCIAL_SIGNALS.md)

Ce lot ajoute le suivi configurable de 100 actions et 30 cryptos, les intervalles 15m/30m/1h/1d et la comptabilité des positions avec alertes enregistrées. Le front Android, le push et la validation du fournisseur réel restent à faire. La planification est désactivée par défaut. Le document d’analyse Word/PDF n’est pas modifié.

L’extension sociale ajoute un registre des personnes, des événements dédupliqués, des évaluations par actif et des scores explicables. Un collecteur X facultatif est désactivé par défaut et limité par des réservations persistantes. La classification du texte et la confirmation des sources restent manuelles ; aucun achat de données ni appel réel n’a été effectué.

