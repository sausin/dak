<!-- French translation of privacy-policy.md (the English original, docs/privacy-policy.md, is the source of truth). Legal text: it must be reviewed by a qualified translator and legal counsel before release, and updated in the same change as the English original. Keep the same sections and bullets (PrivacyPolicyTest checks the structure). -->
# Règles de confidentialité de Dak

**Version 1, en vigueur à compter du (date de la première publication).** La dernière version se trouve sur [le site web de Dak](https://dak.example/privacy). L’application affiche ce même texte hors connexion dans Paramètres → Confidentialité → Règles de confidentialité.

## L’essentiel

- Dak est une application de messagerie texte (SMS et MMS). Elle lit, classe et stocke vos messages **sur votre téléphone**.
- Dans la version gratuite, **rien concernant vos messages n’est envoyé à Dak ni à qui que ce soit**, à l’exception des messages que vous choisissez d’envoyer via votre réseau mobile.
- Quelques fonctionnalités facultatives enverraient certaines données hors de votre téléphone (voir ci-dessous). Elles sont **désactivées tant que vous ne les activez pas**, et chacune vous demande d’abord votre autorisation sur un écran qui indique précisément ce qui est envoyé, où et pourquoi. Vous pouvez désactiver chacune d’elles à tout moment.
- Dak ne contient **ni publicité, ni outil d’analyse, ni service de rapports de plantage**. Nous ne vendons ni ne partageons vos données.
- Vous pouvez **exporter** ou **supprimer** tout ce que Dak stocke, depuis Paramètres → Confidentialité.

## Qui sommes-nous

Dak (« nous ») est responsable de cette application. Contact : privacy@dak.example (adresse provisoire jusqu’à ce que les coordonnées de l’entreprise soient définitives). Si vous êtes en Inde, c’est aussi le contact de notre responsable des réclamations ; si vous êtes dans l’UE ou au Royaume-Uni, celui de notre contact pour la protection des données.

## Ce que Dak traite sur votre téléphone

En tant qu’application SMS par défaut, Dak utilise les éléments suivants. Tout cela reste sur votre téléphone, sauf indication contraire dans une section ci-dessous.

- **Vos messages texte et illustrés.** Android les conserve dans la mémoire de messages partagée du téléphone. Dak lit cette mémoire pour afficher vos conversations, y écrit les nouveaux messages et en tient son propre index chiffré pour rechercher et classer rapidement.
- **Ce que Dak déduit de vos messages :** la catégorie de chaque message (par exemple OTP, banque ou spam), les libellés, les mots de passe à usage unique, les comptes bancaires et cartes ainsi qu’un registre des transactions (le Livret), les alertes d’arnaque et les noms des expéditeurs. Tout cela se trouve dans l’index chiffré de Dak.
- **Ce que vous créez dans Dak :** paramètres, règles d’automatisation, messages programmés, recherches enregistrées, listes de diffusion, regroupements d’expéditeurs et votre corbeille (les messages que vous avez supprimés, conservés peu de temps pour que vous puissiez les restaurer).
- **Les journaux que Dak tient pour vous :** un historique des exécutions d’automatisations (ce que chaque règle a envoyé, à qui, ou pourquoi elle ne l’a pas fait) et un journal d’activité des actions automatiques et destructrices, pour que vous puissiez vérifier ce qui s’est passé.
- **Contacts :** noms et photos, pour afficher l’auteur d’un message. Dak n’envoie pas et ne modifie pas vos contacts.
- **Informations sur le téléphone et la SIM :** les SIM dont vous disposez et leurs numéros, pour envoyer depuis la bonne SIM.
- **Vos enregistrements de consentement :** quand vous avez autorisé ou retiré chaque fonctionnalité facultative ci-dessous, et quelle version de son explication vous avez vue.

L’index et les bases de données de Dak sont chiffrés, avec des clés conservées dans le stockage sécurisé des clés de votre téléphone (Android Keystore). Vous pouvez aussi verrouiller Dak avec votre empreinte digitale, votre visage ou un code PIN.

## Ce qui quitte votre téléphone

### Ce que vous faites vous-même

Ces données vont là où vous les envoyez. Dak n’en reçoit aucune copie.

- **Les messages que vous envoyez** passent par votre réseau mobile, comme tout SMS ou MMS. Les messages illustrés sont envoyés et reçus via le service MMS de votre opérateur, en utilisant les données mobiles.
- **Les transferts, partages et réponses** que vous effectuez, y compris les règles d’automatisation que vous configurez pour transférer des messages par SMS à une personne de votre choix, les réponses automatiques et le transfert vers WhatsApp « en un geste » (Dak ouvre WhatsApp avec le texte prérempli ; c’est vous qui appuyez sur Envoyer).
- **Les signalements de spam au 1909** (Inde) : Dak prépare le texte de la plainte ; vous le vérifiez et l’envoyez par SMS.
- **Votre position**, uniquement lorsque vous appuyez sur « partager la position » dans un message que vous rédigez. Dak la transforme en lien de carte dans ce message. Elle n’est ni conservée ni envoyée ailleurs.
- **Les exportations et sauvegardes** vers un emplacement de votre choix (un dossier de votre téléphone, ou une application de stockage comme Google Drive ou Dropbox). Les sauvegardes sont chiffrées sur votre téléphone avec votre phrase secrète avant d’être enregistrées ; personne, ni nous ni votre fournisseur de stockage, ne peut les lire sans votre phrase secrète ou votre code de récupération. Les exportations (« Exporter mes données », « Exporter les messages ») ne sont pas chiffrées, car elles sont destinées à être lisibles par vous et par d’autres applications ; conservez-les en lieu sûr.
- **Les liens** sur lesquels vous appuyez s’ouvrent dans votre navigateur.

### Fonctionnalités facultatives qui envoient des données à un serveur (désactivées par défaut)

Chacune d’elles vous demande d’abord votre autorisation, sur un écran qui indique ce qui est envoyé, à qui, pourquoi et pour combien de temps. Désactivez-les dans Paramètres → Confidentialité → Données qui quittent votre téléphone ; la désactivation d’une fonctionnalité l’arrête immédiatement.

**État actuel :** aucune de ces fonctionnalités n’est active dans la version actuelle de Dak. Elles sont décrites ici pour que vous sachiez à quoi vous attendre ; lorsque l’une d’elles deviendra disponible, vous verrez son écran d’autorisation avant tout envoi.

- **Classement dans le cloud Jev.** Lorsque le classement de Dak sur le téléphone ne parvient pas à trier un nouveau message entrant, Jev peut envoyer l’identifiant de l’expéditeur professionnel (un en-tête comme « VM-HDFCBK » ou un code court) et une copie masquée du message, dans laquelle les nombres, montants, numéros de carte, liens, adresses e-mail et noms probables sont remplacés par des marqueurs, au service de classement de Dak pour un deuxième avis. Le service renvoie une catégorie et ne conserve pas le texte. Le nombre d’envois est limité à un plafond mensuel que vous fixez. Les messages de personnes (numéros de téléphone) ne sont jamais envoyés, pas plus que leurs numéros.
- **Webhooks** (premium). Une règle d’automatisation que vous créez peut envoyer l’expéditeur, un identifiant interne du message et le texte du message (ou votre modèle de celui-ci) à une adresse web que vous saisissez. Cette adresse vous appartient ou appartient à un service que vous avez choisi ; c’est lui qui décide de la durée de conservation.
- **Relais web et ordinateur** (premium). Les messages que vous choisissez de relayer sont chiffrés sur votre téléphone avec une clé partagée uniquement avec votre ordinateur associé, et transitent par le serveur relais de Dak, qui ne peut pas les lire. Les éléments chiffrés sont supprimés du relais dès leur distribution, et au plus tard après 7 jours.
- **Recherche assistée par IA** (premium). Seule la question que vous saisissez (par exemple « combien ai-je dépensé sur Swiggy le mois dernier ») est envoyée à un service de modèle de langage, qui la transforme en filtres de recherche. Vos messages ne sont pas envoyés ; la recherche s’effectue sur votre téléphone.

Si vous achetez la version premium, Google Play gère le paiement ; Dak reçoit uniquement la confirmation de ce à quoi vous avez droit, pas vos informations de paiement.

### Ce que Dak ne fait jamais

- L’application ne contient aucun code de publicité, d’analyse, de suivi ou de rapports de plantage.
- Nous ne vendons, ne louons ni n’échangeons de données personnelles, et nous n’utilisons pas vos messages pour entraîner des modèles d’IA.

## Autorisations et raisons pour lesquelles Dak les demande

- **SMS et MMS (envoyer, recevoir, lire) :** pour être votre application de messagerie par défaut. Android ne permet à Dak de les demander qu’après que vous l’avez choisie comme application SMS par défaut.
- **Notifications :** pour vous informer des nouveaux messages et des OTP.
- **Contacts :** pour afficher des noms et des photos au lieu de numéros.
- **État du téléphone et numéros de téléphone :** pour connaître vos SIM, envoyer depuis la bonne et identifier les messages par SIM.
- **Position approximative :** uniquement lorsque vous partagez votre position dans un message.
- **Internet et état du réseau :** utilisés uniquement pour télécharger et envoyer des messages illustrés (MMS) via la connexion de données mobiles de votre opérateur. La version gratuite ne contient aucun code qui envoie des données à un serveur.
- **Lancement au démarrage, alarmes exactes, service de premier plan, maintien de l’activité, vibreur :** pour envoyer les messages programmés à l’heure, terminer l’envoi ou le téléchargement d’un message écran éteint, et vous alerter.
- **Liste des applications installées :** pour reconnaître lorsqu’un OTP a été utilisé par une application de votre téléphone (vérification SMS Retriever). Cette vérification a lieu sur votre téléphone ; la liste n’est envoyée nulle part.

Dak ne demande pas directement à Android d’être exclue de l’optimisation de la batterie. Si les messages arrivent en retard sur votre téléphone, Dak vous explique comment modifier cela vous-même dans les paramètres de batterie de votre téléphone.

## Durée de conservation des données

- **Les messages** restent dans la mémoire de messages de votre téléphone jusqu’à ce que vous les supprimiez.
- **Corbeille :** les OTP supprimés sont effacés définitivement après 1 jour, les autres messages supprimés après 30 jours (vous pouvez la vider plus tôt).
- **Journal d’activité :** 90 jours.
- **Historique des exécutions d’automatisations :** un an, et au moins les 5 000 entrées les plus récentes.
- **Historique de recherche :** les 50 dernières recherches.
- **Tout ce que Dak stocke par ailleurs** (index, paramètres, règles, registre des transactions, enregistrements de consentement) reste sur votre téléphone jusqu’à ce que vous le supprimiez, utilisiez « Supprimer mes données Dak », effaciez le stockage de l’application ou désinstalliez Dak.
- **Les sauvegardes** restent dans le dossier que vous avez choisi jusqu’à ce que vous les y supprimiez.
- **Serveurs :** les fonctionnalités facultatives ci-dessus ne conservent les données que pendant les durées indiquées sur leurs écrans d’autorisation.

## Vos droits et vos choix

Où que vous viviez, vous pouvez :

- **Consulter vos données et en obtenir une copie :** Paramètres → Confidentialité → Exporter mes données Dak enregistre vos paramètres, règles, historique des exécutions, journal d’activité, comptes et registre des transactions, libellés et enregistrements de consentement dans un fichier de votre choix. Pour exporter aussi vos messages, utilisez Paramètres → Sauvegarde, données et confidentialité → Exporter.
- **Les corriger :** modifiez ou supprimez les règles, libellés, comptes et paramètres dans l’application.
- **Les supprimer :** Paramètres → Confidentialité → Supprimer mes données Dak efface tout ce que Dak stocke sur votre téléphone. Vos SMS et MMS restent dans la mémoire de messages du téléphone, car d’autres applications la partagent ; supprimez-les dans Dak ou dans les paramètres de messagerie de votre téléphone si vous voulez qu’ils disparaissent.
- **Retirer votre consentement** à toute fonctionnalité facultative, aussi facilement que vous l’avez donné : Paramètres → Confidentialité → Données qui quittent votre téléphone.
- **Déposer une réclamation :** contactez-nous d’abord (coordonnées ci-dessus). En Inde, vous pouvez ensuite saisir le Data Protection Board of India ; dans l’UE ou au Royaume-Uni, votre autorité de protection des données.

Comme Dak conserve les données sur votre téléphone, nous ne détenons généralement rien vous concernant de notre côté. Si vous avez utilisé une fonctionnalité facultative de serveur et souhaitez que nous confirmions ou supprimions des données qui y sont conservées, contactez-nous.

## Enfants

Dak ne s’adresse pas aux enfants de moins de 13 ans. Les fonctionnalités facultatives de serveur ci-dessus sont réservées aux personnes âgées de 18 ans ou plus, et leurs écrans d’autorisation vous demandent de le confirmer.

## Modifications de ces règles

Si nous modifions ce que Dak fait de vos données, nous mettrons à jour ces règles et leur numéro de version, et afficherons la nouvelle version dans l’application. Si une fonctionnalité facultative se met à envoyer des données différentes, votre autorisation vous sera à nouveau demandée avant tout envoi.
