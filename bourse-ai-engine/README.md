
## Alpaca Basic · bougies US de 15 min

Créer des identifiants **Paper Trading** Alpaca et renseigner
`ALPACA_API_KEY_ID`, `ALPACA_API_SECRET_KEY` et `ALPACA_ENABLED=true` dans
l'environnement du backend. Redémarrer le backend pour appliquer la migrations
V25 et V26 et charger ces paramètres. Le calendrier Paper est utilisé en lecture seule.

L'import sélectionne les actions et ETF USD de la watchlist US active. Une
correspondance `asset_provider_symbols.provider='ALPACA'` est prioritaire sur
le symbole local. Les requêtes demandent le flux consolidé SIP non ajusté.
Les timestamps proviennent du calendrier de séance de New York et la clôture
doit dater d'au moins 15 minutes. Seules les bougies de la séance régulière
sont retenues, y compris lors des clôtures anticipées.

Le passage récent reparcourt sept jours calendaires chaque quart d'heure.
Le passage historique part de sept ans en arrière par fenêtres de sept jours,
avec reprise par actif après un redémarrage. Chaque fenêtre historique est
parcourue une fois avec nouvelle tentative si elle échoue. Une absence chez
le fournisseur, y compris avant la première cotation ou pendant une suspension,
reste un créneau sans bougie. La profondeur réellement fournie varie par titre.

Les observations Alpaca sont conservées séparément. Les valeurs OHLC divergentes
sont comptées et les bougies déjà présentes gardent leur source et leur prix.
Une observation valide remplit un créneau absent. La série 15 min peut donc
contenir plusieurs fournisseurs identifiables par bougie. Le journal de chaque
lot est conservé dans `alpaca_import_runs` et la tuile Administration affiche
les derniers résultats et l'avancement de l'historique.


### Actions cotées depuis moins de sept ans

Après vérification de la première séance auprès de la bourse, renseignez
`assets.first_trading_date` pour le symbole concerné. Le rattrapage commence
au plus tardif entre cette date et la borne de sept ans. Les créneaux
antérieurs à la première séance sont exclus du contrôle de couverture.
Une date inconnue reste vide : la première bougie stockée peut masquer des
données manquantes et ne permet pas de déduire la première cotation.

```sql
UPDATE assets
SET first_trading_date = DATE 'AAAA-MM-JJ'
WHERE symbol = 'SYMBOLE';
```
