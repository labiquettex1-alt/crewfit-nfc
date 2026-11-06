# Crewfit NFC

Appli Android perso de Jérôme (Mieux-être Cheveyo) pour programmer ses puces NFC.

## Ce qu'elle fait

- **Lire** : contenu de la puce, UID, type, mémoire utilisée, verrouillée ou non, ouvrir le lien, copier sur une autre puce.
- **Écrire** : lien, texte, fiche contact (vCard), Wi-Fi, effacer, verrouillage définitif (avec confirmation).
- **Raccourcis Crewfit** : Instagram, YouTube et fiche contact en un clic.
- **Mes puces** : historique de chaque puce, avec un nom que tu choisis.
- **Verrou** : certaines apps s'ouvrent seulement après le scan d'une puce clé (et de secours). Réglages protégés par la puce. Tout se reverrouille quand l'écran s'éteint.

Tout reste sur le téléphone, aucune connexion internet, aucune pub.

## Obtenir l'APK

Chaque envoi sur la branche `main` lance la compilation sur GitHub (onglet **Actions**).
Quand c'est vert, l'APK est dans l'onglet **Releases** : `CrewfitNFC.apk`.

## Installer sur le Pixel

1. Ouvre le lien de la release sur ton cell et télécharge `CrewfitNFC.apk`.
2. Ouvre le fichier. Android demande d'autoriser l'installation depuis ton navigateur : accepte.
3. Play Protect peut avertir que l'appli est inconnue : touche « Plus de détails » puis « Installer quand même ».

## Activer le verrou d'apps

1. Dans l'appli, onglet Verrou, touche **Accessibilité**, puis Applications téléchargées, Verrou Crewfit NFC, active.
2. Si c'est grisé (« Paramètre restreint ») : Infos de l'appli, ⋮ en haut à droite, « Autoriser les paramètres restreints », puis recommence.
3. Enregistre ta puce clé, puis coche les apps à verrouiller.

## Limites à connaître

- Le NFC ne lit rien écran éteint, c'est Android qui le bloque.
- Le verrou est une protection pratique, pas un coffre-fort : quelqu'un qui a ton cell déverrouillé peut désactiver le service d'accessibilité, et une puce peut être copiée. Pour du vraiment sensible, combine avec l'Espace privé (empreinte).
- Les mises à jour s'installent par-dessus sans perdre tes réglages (même clé de signature, `app/debug.keystore`).
