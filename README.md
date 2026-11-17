# Bourse AI

Backend Java 21 / Spring Boot dans `bourse-ai-engine`.

Voir [la configuration et les quotas](bourse-ai-engine/CONFIGURATION.md) pour le lancement, les clés privées, PostgreSQL et les tests.

```bash
cd bourse-ai-engine
mvn clean verify
mvn spring-boot:run
```

Port par défaut : 8081. Sans profil `postgres`, la base H2 est volatile ; utiliser PostgreSQL pour conserver les données et les compteurs entre redémarrages.
