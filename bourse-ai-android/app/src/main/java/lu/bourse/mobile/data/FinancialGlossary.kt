package lu.bourse.mobile.data

/** Shared plain-French definitions for contextual help throughout the app. */
enum class FinancialTerm(val title: String, val definition: String, val example: String, val limitation: String = "") {
    CANDLE("Bougie et OHLC", "Une bougie résume une période : O = ouverture, H = plus haut, B = plus bas, C = clôture. Le corps relie ouverture et clôture ; les mèches montrent les extrêmes.", "En 1 h : ouverture 100 €, plus haut 103 €, plus bas 98 €, clôture 102 €.", "Ces prix décrivent le passé. Une bougie verte ne garantit pas la prochaine hausse."),
    PATTERNS("Figures de bougies", "Des règles recherchent des formes dans les prix des bougies clôturées : marteau, doji et engloutissement. L’application affiche les détections des 12 dernières bougies de l’intervalle choisi.", "Un marteau après une baisse peut signaler une reprise possible des achats.", "Une figure est un indice descriptif, pas une probabilité de gain. Elle n’ajoute aucun point au score et ne modifie pas le modèle ML."),
    HAMMER("Marteau", "Petit corps près du haut et longue mèche en bas : les acheteurs ont récupéré une grande partie de la baisse de cette période. Ici, il est recherché après quatre clôtures précédentes en baisse.", "Ouverture 100 €, plus bas 94 €, plus haut 101,20 €, clôture 101 €.", "Un rebond reste possible, jamais certain. La même forme après une hausse n’est pas appelée marteau haussier ici."),
    DOJI("Doji", "L’ouverture et la clôture sont très proches : le prix termine presque à son point de départ. Cela peut traduire une hésitation entre acheteurs et vendeurs.", "Ouverture 100 €, plus haut 103 €, plus bas 97 €, clôture 100,10 €.", "Un doji seul n’indique pas une direction. Une bougie sans aucune variation est ignorée."),
    BULLISH_ENGULFING("Engloutissement haussier", "Après une baisse, une bougie qui monte possède un corps plus grand qui recouvre le corps de la bougie précédente, qui baissait. Les mèches ne sont pas utilisées pour ce recouvrement.", "Première bougie : de 105 à 101 €. Suivante : de 100 à 106 €.", "Cela suggère un retour des acheteurs, sans garantir une hausse à venir."),
    BEARISH_ENGULFING("Engloutissement baissier", "Après une hausse, une bougie qui baisse possède un corps plus grand qui recouvre le corps de la bougie précédente, qui montait.", "Première bougie : de 95 à 99 €. Suivante : de 100 à 94 €.", "Cela suggère une pression des vendeurs, sans garantir une baisse à venir."),
    CONFIRMATION("Confirmation par le prix", "Pour une figure orientée à la hausse, la bougie clôturée suivante doit finir au-dessus du plus haut de la figure. Pour une figure orientée à la baisse, elle doit finir sous son plus bas.", "Marteau avec un plus haut à 101,20 € : clôture suivante à 102 € = condition observée.", "Une confirmation par le prix n’est pas une certitude. Le volume est évalué séparément. Une bougie manquante ou un changement de fournisseur rend la confirmation indisponible."),
    VOLUME("Volume échangé", "Quantité de titres ou d’unités échangés pendant la période, selon le fournisseur. Un volume élevé indique davantage d’activité, sans donner à lui seul une direction.", "200 000 titres contre une moyenne de 100 000 sur les 20 bougies précédentes : volume multiplié par 2.", "Ici, « supérieur à l’habitude » signifie au moins 1,5 fois cette moyenne. Sans 20 volumes positifs, consécutifs et de même source, la comparaison reste indisponible. En intrajournalier, l’heure de séance peut influencer le volume."),
    SMA("Moyenne mobile · SMA 20", "Moyenne des 20 dernières clôtures. Elle lisse les variations pour rendre la tendance plus lisible.", "Sur un graphique 1 h, elle utilise 20 bougies d’une heure ; en 1 jour, 20 bougies journalières.", "Elle suit le passé avec retard et ne prédit pas le prochain prix."),
    RSI("RSI · force des mouvements", "Indicateur de 0 à 100 qui compare les hausses et les baisses récentes. Ici, il porte sur 14 périodes. Les repères usuels sont 30 et 70.", "Un RSI de 75 indique que les hausses récentes dominent fortement les baisses.", "Un RSI élevé n’impose pas de vendre, et un RSI bas n’impose pas d’acheter. Ces situations peuvent durer."),
    MACD("MACD · évolution de la tendance", "Écart entre deux moyennes qui donnent plus de poids aux prix récents, sur 12 et 26 périodes. La ligne de signal lisse cet écart sur 9 périodes. L’histogramme montre la différence entre les deux lignes.", "Quand la MACD passe au-dessus de sa ligne de signal, la dynamique récente s’améliore selon cet indicateur.", "Un croisement peut être trompeur, surtout lorsque le marché change peu de direction."),
    SCORES("Scores d’opportunité, de risque et de fiabilité", "L’opportunité résume les éléments favorables. Le risque résume les facteurs de perte potentielle : 100 signifie plus de risque. La fiabilité décrit la qualité des données et des preuves disponibles.", "Opportunité 78/100 ne signifie pas 78 % de chances de gagner.", "Ces scores sont expérimentaux et ne garantissent aucun rendement."),
    VOLATILITY("Volatilité", "Mesure de l’ampleur des variations du prix. Une forte volatilité signifie des mouvements plus importants, à la hausse comme à la baisse. « Annualisée » signifie ramenée à une échelle annuelle par une formule.", "Un prix passant souvent de 100 à 110 puis à 90 varie davantage qu’un prix restant entre 99 et 101.", "La volatilité historique ne donne pas la direction future."),
    DRAWDOWN("Drawdown · recul depuis un sommet", "Baisse mesurée depuis un sommet précédent sur la période étudiée.", "Le prix passe de 100 à 120 puis à 90 € : le recul depuis le sommet de 120 € est de 25 %.", "Le recul mesuré dépend de la période et des observations disponibles."),
    SUPPORT("Support et résistance", "Un support est une zone où une baisse a déjà rencontré des achats. Une résistance est une zone où une hausse a déjà rencontré des ventes.", "Le prix rebondit plusieurs fois près de 90 € : cette zone peut être étudiée comme support.", "Ces zones peuvent être franchies. L’application ne les déduit pas automatiquement des figures de ce module."),
    CORRELATION("Corrélation historique", "Mesure de la façon dont deux séries de rendements ont évolué ensemble, de −1 à +1. Près de +1 : mouvements souvent dans le même sens ; près de −1 : souvent opposés.", "Deux actions peuvent souvent monter et baisser ensemble sans que l’une cause les mouvements de l’autre.", "Une corrélation passée peut changer. Elle ne prouve pas une contagion ni un lien de cause à effet."),
    RETURN("Rendement", "Variation de valeur rapportée au montant de départ. Selon le calcul, les frais, dividendes et effets de change peuvent être inclus ou exclus.", "Un prix passe de 100 à 110 € : hausse de 10 % avant frais, dividendes et change.", "Un rendement passé ne prédit pas le rendement futur."),
    FX("FX · taux de change", "Taux utilisé pour convertir une devise dans une autre, par exemple des dollars en euros.", "Une action peut monter en dollars tandis que sa valeur en euros progresse moins si le dollar baisse.", "Un taux ancien peut rendre la valorisation du portefeuille inexacte."),
    LOSS_BUDGET("Budget de perte", "Montant de perte maximale que vous avez fixé pour l’ensemble du portefeuille. Le budget restant mesure la marge avant cette limite.", "Limite de 500 € et perte mesurée de 120 € : budget restant de 380 €.", "C’est un suivi, pas une garantie de protection. Les prix peuvent changer et aucun ordre automatique n’est transmis."),
    SCENARIO("Scénario de prix", "Hypothèse calculée pour une échéance, avec des valeurs défavorable, centrale et favorable.", "Un scénario à 30 jours affiche des possibilités à cette date, sans prédire tous les prix intermédiaires.", "La fourchette historique n’est pas un intervalle de confiance et ne garantit pas que le prix restera entre ses bornes."),
    ML("Machine learning · ML", "Méthode qui apprend des relations statistiques sur des données passées, puis doit être évaluée sur des données non utilisées pour l’entraînement.", "Comparer un modèle à une référence simple sur une période de test permet de mesurer son apport.", "Les résultats de recherche ne prouvent pas une performance future. Un test sur données fictives ne valide pas le marché réel."),
    BRIER("Score de Brier", "Mesure de l’écart entre les probabilités estimées et les résultats observés. Pour la même cible et le même jeu de test, un score plus faible est meilleur.", "Comparer le Brier du modèle à celui d’une probabilité constante sur les mêmes observations.", "Ce score ne mesure pas directement les gains en euros."),
    BASELINE("Référence · baseline", "Méthode simple utilisée pour vérifier si un modèle apporte quelque chose de plus.", "Comparer une estimation entraînée à une probabilité constante apprise sur les données passées.", "La comparaison doit porter sur les mêmes dates et la même cible."),
    SEASONALITY("Saisonnalité", "Répétitions statistiques observées selon le mois, le trimestre ou l’année.", "Comparer les rendements moyens des mois de décembre sur plusieurs années.", "Un petit nombre d’années peut donner une impression trompeuse. Ces statistiques n’ajoutent aucun point au score actuel."),
    BOTTLENECK("AI bottleneck · goulot d’étranglement", "Ressource ou capacité limitée qui peut ralentir le développement de l’intelligence artificielle : puces, énergie ou équipements, par exemple.", "Une pénurie d’équipements peut accroître la demande auprès de certains fournisseurs.", "Une forte demande ne garantit ni bénéfices ni hausse du cours. Il faut aussi examiner le prix payé et les risques."),
    TARGET_2R("Objectif illustratif · 2R", "R désigne l’écart de prix entre l’entrée et la protection illustrative. Un objectif 2R place l’objectif à deux fois cet écart, au-dessus de l’entrée pour une position achetée.", "Entrée 100 €, protection 95 € : R = 5 € et objectif 2R = 110 €.", "C’est une illustration avant frais et change, pas une promesse de gain ni un ordre placé."),
    VALUATION("Valorisation du portefeuille", "Valeur calculée à partir des positions, des prix disponibles, des liquidités et des taux de change nécessaires.", "Deux actions à 100 € et 50 € de liquidités donnent 250 € de valeur si aucune autre position n’intervient.", "Une valorisation peut être partielle si un cours ou un taux de change manque."),
    CASH("Liquidités", "Argent disponible dans le portefeuille, qui n’est pas actuellement investi dans les positions suivies.", "Capital de 1 000 €, puis achat de 300 € avec 2 € de frais : 698 € restent disponibles.", "Le montant suivi dans l’application dépend des opérations que vous avez enregistrées."),
    POSITION("Position et prix de revient", "Une position est la quantité d’un actif détenue. Le prix de revient décrit le coût d’acquisition ; une moyenne regroupe plusieurs achats selon la méthode de calcul.", "Acheter 2 actions à 100 € puis 2 à 120 € donne un prix moyen de 110 € avant frais.", "Une position ouverte est encore détenue. Une position clôturée a été entièrement vendue."),
    FEES("Frais", "Coûts liés à un achat, une vente ou une conversion de devise. Ils réduisent le résultat net.", "Acheter à 100 € puis vendre à 110 €, avec 1 € de frais à chaque opération, laisse 8 € de gain.", "Les simulations ne couvrent que les coûts effectivement pris en compte par leur calcul."),
    HORIZON("Horizon", "Durée sur laquelle une analyse ou une position est étudiée. Une séance est un jour d’ouverture du marché ; elle ne correspond pas toujours à un jour du calendrier.", "21 séances représentent environ un mois de bourse, selon les jours fériés.", "Des analyses sur 15 minutes et sur un an peuvent donner des indications différentes."),
    REGIME("Régime de marché", "Description de l’état récent du marché, par exemple une tendance à la hausse, à la baisse ou une période de fortes variations.", "Une série de cours peut être classée comme volatile si ses variations récentes sont importantes.", "C’est une classification du passé disponible, pas une annonce du prochain mouvement."),
    WEIGHT("Pondération des composantes", "Importance attribuée à chaque élément dans le calcul d’un score.", "Une amplitude technique plus élevée permet aux indicateurs de prix d’influencer davantage le score.", "Un poids plus élevé ne rend pas un signal plus fiable ; les choix doivent être évalués sur des données distinctes."),
    MARGIN("Marge financière", "Part du chiffre d’affaires restant après les coûts définis par le type de marge utilisé. Une marge nette prend en compte l’ensemble des charges du résultat net.", "Résultat net de 10 € pour 100 € de chiffre d’affaires : marge nette de 10 %.", "Il faut comparer le même type de marge et des périodes comparables."),
}

fun financialHelpFor(label: String): FinancialTerm? {
    val s = label.lowercase(java.util.Locale.FRENCH)
    return when {
        "figures de bougies" in s -> FinancialTerm.PATTERNS
        "confirmation" in s -> FinancialTerm.CONFIRMATION
        "bougie" in s || "ohlc" in s || "clôture" in s -> FinancialTerm.CANDLE
        "volume" in s -> FinancialTerm.VOLUME
        "macd" in s -> FinancialTerm.MACD
        "rsi" in s -> FinancialTerm.RSI
        "moyenne mobile" in s || "sma" in s -> FinancialTerm.SMA
        "brier" in s -> FinancialTerm.BRIER
        "baseline" in s -> FinancialTerm.BASELINE
        "volatilité" in s -> FinancialTerm.VOLATILITY
        "drawdown" in s -> FinancialTerm.DRAWDOWN
        "corrélation" in s || "actions liées" in s -> FinancialTerm.CORRELATION
        "rendement" in s -> FinancialTerm.RETURN
        "budget de perte" in s -> FinancialTerm.LOSS_BUDGET
        "risque global" in s || "perte globale" in s || "perte maximale" in s -> FinancialTerm.LOSS_BUDGET
        "2r" in s -> FinancialTerm.TARGET_2R
        "scénario" in s -> FinancialTerm.SCENARIO
        "saisonnalité" in s -> FinancialTerm.SEASONALITY
        "régime" in s -> FinancialTerm.REGIME
        "liquidités" in s -> FinancialTerm.CASH
        "valorisation" in s || "valeur connue" in s -> FinancialTerm.VALUATION
        "prix de revient" in s || "position" in s -> FinancialTerm.POSITION
        "frais" in s -> FinancialTerm.FEES
        "horizon" in s -> FinancialTerm.HORIZON
        "amplitude" in s || "pondération" in s -> FinancialTerm.WEIGHT
        "marge" in s -> FinancialTerm.MARGIN
        "bottleneck" in s -> FinancialTerm.BOTTLENECK
        "machine learning" in s || Regex("\\bml\\b").containsMatchIn(s) -> FinancialTerm.ML
        "score" in s || "opportunité" in s || "fiabilité" in s || s.startsWith("risque") -> FinancialTerm.SCORES
        "fx" in s || "taux de change" in s -> FinancialTerm.FX
        else -> null
    }
}
