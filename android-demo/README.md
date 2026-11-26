# Bourse AI — APK démo Android (100 % fictive)

Application native Java Android, utilisable sans réseau et sans backend. Elle contient cinq sociétés **imaginaires** avec préfixe DEMO-, des scores, une courbe dessinée localement, quatre horizons de scénarios inventés, un portefeuille fictif, et des alertes d'illustration. Il n'y a **aucune donnée boursière réelle**, aucune autorisation Internet et aucun accès à un courtier.

## Construction

GitHub Actions : onglet **Actions → Android Demo APK**, choisir le run terminé → **Artifacts → bourse-ai-demo-apk**. Télécharger le ZIP, extraire `app-debug.apk`, puis installer ce fichier sur Android avec l'autorisation d'installer depuis la source utilisée. Il s'agit d'une APK de débogage, signée avec une clé de développement éphémère, et non d'une release Play Store.

Sur un environnement équipé d'Android SDK 35, JDK 17 et Gradle 8.9 :

```bash
gradle -p android-demo :app:assembleDebug
```

Fichier de sortie : `android-demo/app/build/outputs/apk/debug/app-debug.apk`.

## Portée

Onglets : **Marchés, Prévisions, Portefeuille, Alertes**. Aucun endpoint de production utilisé. Les prévisions chiffrées et les courbes sont **des fixtures pour démonstration UI** et non des sorties du moteur de prévision. Le raccordement authentifié aux API, les préférences, les véritables graphiques et le suivi de comptes sont du travail distinct. Le package d'application est `lu.bourse.ai.demo.debug`.
