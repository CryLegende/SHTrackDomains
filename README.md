<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHTrackDomains

*Statistiques de connexion par domaine*

<img src="https://img.shields.io/badge/Velocity-3.5.1-f2a900?style=for-the-badge" alt="Velocity 3.5.1"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Proxy-3aa655?style=for-the-badge" alt="SkyHand Proxy">

</div>

---

## Présentation

Statistiques de connexion par domaine

Utilisé sur : **Proxy**.

## Fonctionnement

- `data.yml` : compteur de connexions par adresse (`/shtd list`, `/shtd show <adresse>`), comme en 2.0.
- Depuis la 3.0, chaque connexion est aussi écrite dans MariaDB (base `Global`) :
  - `sh_acq_players` : une ligne par pseudo, avec l'adresse et la **source de sa première connexion**,
    `new_player = 1` s'il n'avait pas de compte AuthMe (ou un compte de moins de `nouveau-joueur-heures`),
    connexions et jours distincts ;
  - `sh_acq_logins` : une ligne par connexion.
- `<code>.skyhand.fr` sans entrée dans `sources` donne la source `<code>` : adresses de créateurs et de campagnes.
  Liste des créateurs, page de statistiques et cadeau de première connexion : plugin `skyhandsite` du site.

Commandes (permission `shtrackdomains.list`) :

| Commande | Effet |
|---|---|
| `/shtd sources [jours]` | nouveaux joueurs par source (venus, inscrits, revenus un 2e jour, vus 3 jours+) et connexions par source |
| `/shtd joueur <pseudo>` | source et historique d'un joueur |

Le client Minecraft envoie la **cible** d'un enregistrement SRV, pas l'adresse tapée : deux adresses
qui partagent une cible SRV ne se distinguent plus. Chaque adresse publique a donc sa propre cible.

## Configuration

`config.yml` (créé au premier démarrage, chaque clé y est documentée) :
`domaine`, `sources` (adresse → nom de source) et `base-de-donnees`
(`actif`, `url`, `utilisateur`, `mot-de-passe`, `table-authme`, `nouveau-joueur-heures`).
Tables : `schema.sql` dans `/skyhand/_staging-acquisition/`. L'utilisateur MariaDB `shtrackdomains`
n'a que `SELECT/INSERT/UPDATE` sur ces tables et `SELECT (username, regdate)` sur `authme`.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
