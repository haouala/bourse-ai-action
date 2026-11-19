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
- [Schémas et dictionnaire des 19 tables et 179 colonnes](bourse-ai-engine/docs/DATABASE.md)
- [Résultats des 37 tests et de l’essai après redémarrage](bourse-ai-engine/docs/VALIDATION.md)

Ce lot ajoute le suivi configurable de 100 actions et 30 cryptos, les intervalles 15m/30m/1h/1d et la comptabilité des positions avec alertes enregistrées. Le front Android, le push et la validation du fournisseur réel restent à faire. La planification est désactivée par défaut. Le document d’analyse Word/PDF n’est pas modifié.

