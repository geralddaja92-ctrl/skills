# Pulizia S24

App Android per **Samsung Galaxy S24 Ultra** (funziona su qualsiasi telefono con Android 10 o
successivo) che aiuta a trovare e rimuovere virus, app spia, adware e file inutili.

L'app **non usa internet**: non chiede il permesso `INTERNET`, quindi nessun dato lascia il telefono.

## Cosa fa

### 1. Scansione app
Analizza ogni app installata e assegna un punteggio di rischio in base ai segnali tipici di malware:

| Segnale | Perché è pericoloso |
|---|---|
| Installata fuori da Play Store / Galaxy Store | È il canale principale dei virus Android |
| Servizio di accessibilità attivo | Legge lo schermo e tocca al posto tuo (trojan bancari) |
| Amministratore del dispositivo | Può bloccare il telefono e impedire la disinstallazione |
| Icona nascosta | Tecnica tipica di spyware e stalkerware |
| Nome di pacchetto che imita Android/Google/Samsung | App che si spaccia per sistema |
| Nome ingannevole ("System Update", "Flash Player"…) | Truffa per ottenere permessi |
| Accesso SMS senza essere l'app SMS | Furto dei codici OTP della banca |
| Microfono + fotocamera + posizione in background | Possibile app spia |
| Finestre sopra le altre app | Adware e finte schermate di login |
| Permesso "Installa app sconosciute" | Può scaricare altri malware |

Per ogni app: **Disinstalla**, **Info app**, scorciatoie per togliere accessibilità e
amministratore, e **Verifica su VirusTotal** (calcola l'impronta SHA-256 dell'APK e apre la
pagina nel browser: il file non viene caricato).

### 2. Pulizia memoria (richiede "Accesso a tutti i file")
- File APK dimenticati (spesso l'origine dei virus)
- File temporanei e log, miniature in cache
- Cartelle lasciate da app disinstallate (`Android/media`)
- Cartelle vuote
- Duplicati (confronto SHA-256) e file oltre 100 MB — **non** preselezionati

Prima di eliminare viene chiesta conferma.

### 3. Controlli del dispositivo
Blocco schermo, età della patch di sicurezza, debug USB, segni di root, oltre a scorciatoie per
Play Protect, **Blocco automatico (Auto Blocker)** Samsung, **Assistenza dispositivo** e per
bloccare le notifiche spam di Samsung Internet / Chrome.

## Limiti onesti

- Android isola ogni app: **nessuna app può cancellare da sola un'altra app**, neanche gli
  antivirus commerciali. Pulizia S24 trova le app sospette e apre la finestra di disinstallazione
  di sistema, che confermi tu.
- L'analisi è euristica e offline (nessun database di firme). Un punteggio alto non significa per
  forza virus: controlla prima di disinstallare app che riconosci.
- La cache delle altre app si può svuotare solo da Impostazioni o da Assistenza dispositivo.
- Se il telefono resta compromesso (root non voluto, amministratore che non si rimuove), la
  soluzione sicura è il ripristino ai dati di fabbrica dopo un backup.

## Installare l'app sul telefono

### Opzione A — APK compilato da GitHub Actions
1. Su GitHub apri **Actions › Build Pulizia S24 APK** e l'ultima esecuzione riuscita.
2. Scarica l'artefatto **PuliziaS24-apk** (zip) e estrai `app-debug.apk`.
3. Copia l'APK sul telefono e aprilo. Consenti temporaneamente "Installa app sconosciute" per il
   file manager; se è attivo il **Blocco automatico** Samsung, disattivalo solo per
   l'installazione e riattivalo subito dopo.

### Opzione B — Android Studio
1. Apri la cartella `s24-cleaner` in Android Studio.
2. Attiva il debug USB sul telefono, collegalo e premi **Run**.

Da riga di comando: `./gradlew assembleDebug` (serve l'Android SDK con la piattaforma 35).

## Struttura

```
app/src/main/kotlin/it/pulizias24/app/
├── MainActivity.kt        navigazione a tre schede (Compose + Material 3)
├── MainViewModel.kt       stato e lavori in background
├── scan/AppScanner.kt     regole di rischio per le app installate
├── clean/JunkScanner.kt   ricerca ed eliminazione di file inutili
├── device/DeviceChecks.kt controlli di sicurezza e scorciatoie alle impostazioni
└── ui/                    schermate
```
