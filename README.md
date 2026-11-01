# Volume Boost

App Android minimale per aumentare il volume di tutto il telefono fino a +20 dB, con un riquadro nelle Impostazioni rapide per accenderla e spegnerla.

## Installazione
1. Apri la pagina **Releases** di questo repository dal telefono.
2. Scarica il file `VolumeBoost-x.y.z.apk` dell'ultima versione.
3. Aprilo e consenti l'installazione da questa fonte quando Android lo chiede.
4. Abbassa le Impostazioni rapide, tocca la matita e trascina **Volume Boost** tra i riquadri attivi.

Gli aggiornamenti si installano dall'app: all'apertura controlla da sola, oppure tocca la scritta con la versione in basso.

## Come si pubblica una nuova versione
1. Modifica il codice in `app/src/main/`.
2. Scrivi le novità in `NOVITA.md`.
3. Aumenta il numero nel file `VERSION` (es. da `1.0.0` a `1.0.1`) e fai push su `main`: GitHub Actions compila, firma e pubblica l'APK nelle Releases.

La firma usa i secret `KEYSTORE_BASE64` e `KEYSTORE_PASSWORD` (stessa chiave di TrainUP). Per usare una chiave diversa: secret `KEY_ALIAS` e variabile `FIRMA_SHA256` con l'impronta SHA-256 del certificato. La chiave non è nel repository: senza la stessa chiave gli aggiornamenti non si installano sopra la versione esistente.

## Limiti
L'effetto agisce sull'uscita audio globale di Android, una funzione che Google considera superata: sulla maggior parte dei telefoni funziona, su alcuni modelli o con alcune app potrebbe non avere effetto.
