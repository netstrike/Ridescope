# Gauge PFD Airbus - note di ricostruzione

## Scopo
Questa nota descrive come e stato costruito il gauge PFD Airbus-like usato nella pagina `Telemetria`, in modo da poterlo ricreare da zero senza dover reinterpretare tutto `TelemetryDashboardWidget.kt`.

File sorgente attuale:
- `app/src/main/java/com/example/ridescope/ui/home/TelemetryDashboardWidget.kt`

Funzioni principali coinvolte:
- `drawAirbusPrimaryFlightDisplay(...)`
- `drawAirbusTape(...)`
- `drawAccelVariometer(...)`

## Riferimenti visivi usati
Riferimento generale PFD:
- https://en.wikipedia.org/wiki/Primary_flight_display

Riferimento visuale Airbus A320:
- https://commons.wikimedia.org/wiki/File:Primary_flight_display_of_an_A320_during_cruise.jpg

Obiettivo grafico:
- orizzonte artificiale centrale dominante
- speed tape verticale a sinistra
- altitude tape verticale a destra
- scala roll arcuata in alto, dentro l'orizzonte artificiale
- indicatore verticale laterale tipo variometro

## Vincoli dati reali dell'app
Il gauge usa solo dati realmente disponibili in RideScope:
- `rollDeg`
- `pitchDeg`
- `gpsSpeedKmh`
- `gpsAltitudeMeters`
- `longitudinalAccelG`

Scelte esplicite:
- il variometro non rappresenta la velocita verticale reale
- la colonna tipo variometro e stata riutilizzata per `accelerazione / decelerazione`
- non vengono mostrati heading, FMA, AP/FD, baro, ILS, track, bugs autopilot

## Posizionamento del modulo
Il blocco PFD occupa questo rettangolo modello:
- `AirbusPfdLeftModel = 12f`
- `AirbusPfdTopModel = 396f`
- `AirbusPfdRightModel = 308f`
- `AirbusPfdBottomModel = 652f`

Questo rettangolo viene convertito in pixel dentro `OriginalTelemetryGauge(...)`.

## Struttura interna del layout
Dentro `drawAirbusPrimaryFlightDisplay(...)` il rettangolo viene suddiviso in 4 aree:

1. `speedTapeRect`
- nastro verticale sinistro

2. `attitudeFrameRect`
- area principale del PFD

3. `altitudeTapeRect`
- nastro verticale destro

4. `accelVarioRect`
- colonna laterale destra usata come variometro per accel/decel

Il viewport effettivo dell'orizzonte artificiale e:
- `attitudeViewport`
- parte sotto-superiore del frame, con angoli arrotondati

## Ordine di disegno corretto
L'ordine e importante. Se viene cambiato, il bank arc puo sparire dietro cielo/terra.

Ordine attuale:

1. cornice esterna nera del PFD
2. speed tape sinistro
3. altitude tape destro
4. variometro accel/decel
5. definizione di `attitudeViewport`
6. clipping del viewport
7. disegno cielo / terra ruotati con `roll` e traslati con `pitch`
8. pitch ladder dentro il viewport
9. linea orizzonte
10. scala roll arcuata
11. marker giallo dinamico del roll
12. bordo del viewport
13. simbolo aereo centrale

Nota critica:
- la scala `roll` va disegnata dopo il layer cielo/terra
- se viene disegnata prima del `clipPath` o sotto il background dell'orizzonte, puo risultare invisibile

## Come e costruito l'orizzonte artificiale
`attitudeViewport` viene usato come maschera arrotondata.

Dentro il clip:
- si applica `rotate(degrees = -clampedRoll, pivot = attitudeViewport.center)`
- si calcola `horizonY` con uno spostamento verticale proporzionale a `pitch`
- sopra `horizonY` viene disegnato il cielo
- sotto `horizonY` viene disegnato il terreno
- al centro viene disegnata la linea dell'orizzonte

Pitch mapping attuale:
- `clampedPitch = pitchDeg.coerceIn(-25f, 25f)`
- `pitchPixelsPerDeg = attitudeViewport.height / 44f`

Questo produce un pitch leggibile senza far uscire troppo rapidamente il ladder dal viewport.

## Come e costruita la pitch ladder
La ladder usa marcatori ogni 5 gradi:
- step da `-20` a `20`
- linee maggiori ogni 10 gradi
- numeri solo sui major tick

Regole attuali:
- linee major piu lunghe
- linee minor piu corte
- etichette ai lati del ladder
- testo volutamente piu grande delle prime versioni per migliorare leggibilita

Se serve aumentare leggibilita:
- aumentare `sizeSp` delle etichette pitch
- aumentare la distanza laterale delle etichette dal tratto

## Come e costruita la scala roll
La scala roll e pensata come arco interno al viewport dell'orizzonte artificiale.

Geometria attuale:
- raggio basato sulla larghezza del viewport: `attitudeViewport.width * 0.575f`
- centro dell'arco ancorato dentro il viewport, non sopra il frame
- sweep da `210f` a `330f`

Tacche:
- `-45, -30, -20, -10, 10, 20, 30, 45`
- etichette mostrate su `10, 20, 30, 45`

Marker dinamico:
- triangolo giallo
- angolo: `270f + clampedRoll.coerceIn(-45f, 45f)`
- deve seguire l'arco, non restare fisso in alto

Regola pratica:
- il marker va calcolato con `polar(...)` sullo stesso `bankScaleCenter` e sullo stesso `bankRadius` della scala

## Come sono costruiti i tape
I tape sinistro e destro sono volutamente semplificati.

`drawAirbusTape(...)` gestisce:
- asse verticale centrale bianco
- tacche major/minor
- etichette numeriche sui major tick
- finestra centrale valore
- triangolo laterale puntatore
- unita di misura sotto il tape

Scelte attuali:
- `speed` a sinistra con puntatore verso destra
- `altitude` a destra con puntatore verso sinistra
- valori mostrati in finestra nera con bordo bianco

Parametri usati:
- speed: `majorStep = 20f`, `minorStep = 10f`, `visibleSpan = 120f`
- altitude: `majorStep = 100f`, `minorStep = 50f`, `visibleSpan = 600f`

## Come e costruito il variometro accel/decel
`drawAccelVariometer(...)` riusa il linguaggio del VSI ma mostra `AY`.

Input:
- `accelG`
- `limitG`
- `LongitudinalAccelUnit`

Logica:
- valore normalizzato in `[-1, 1]`
- asta inclinata dal centro verso alto o basso
- verde quando accelera
- rosso quando decelera
- testo numerico del valore vicino alla punta
- unita sotto la colonna

Formatter usati:
- `formatLongitudinalAccelNumeric(...)`
- `formatLongitudinalAccelUnitLabel(...)`

## Elementi lasciati fuori volutamente
Per mantenere il gauge coerente con RideScope sono stati esclusi:
- annunciatori `AP/FD/A-THR`
- heading scale in basso
- bug di target speed/altitude
- baro setting
- indicatori ILS/VOR/NAV
- trend vettori
- flight path vector Airbus

## Procedura per ricrearlo da zero
1. Creare il rettangolo PFD principale nel modello canvas.
2. Dividere il rettangolo in `speed tape`, `attitude frame`, `altitude tape`, `accel/vario`.
3. Disegnare prima le colonne laterali.
4. Definire `attitudeViewport` con corner arrotondati.
5. Disegnare cielo e terra dentro un `clipPath`, ruotando con `roll` e traslando con `pitch`.
6. Disegnare la pitch ladder dentro il viewport.
7. Disegnare la linea dell'orizzonte.
8. Disegnare la scala roll sopra il background del viewport.
9. Disegnare il marker giallo del roll usando lo stesso centro/raggio dell'arco.
10. Disegnare il bordo del viewport e infine il simbolo aereo.

## Errori gia incontrati
1. Scala roll invisibile
- causa: arco disegnato sotto il layer dell'orizzonte artificiale
- fix: ridisegnarlo dopo cielo/terra

2. Marker giallo fermo
- causa: triangolo fisso anziche dinamico
- fix: usare `bankPointerAngle` derivato da `roll`

3. Arco roll fuori dal gauge
- causa: centro e raggio basati sul frame esterno
- fix: ancorare geometria a `attitudeViewport`

4. Testi pitch troppo piccoli
- causa: `sizeSp` iniziale troppo basso
- fix: aumentare la dimensione dei label major

## Validazione minima dopo modifiche
Dopo modifiche al gauge:
- eseguire `:app:compileDebugKotlin`
- deployare con `scripts/deploy-device.ps1`
- verificare visivamente sul device:
  - visibilita dell'arco roll
  - marker giallo che segue il roll
  - leggibilita ladder pitch
  - tape speed/altitude non sovrapposti
  - variometro accel/decel leggibile

## Stato attuale desiderato
Il PFD non deve essere una replica completa Airbus 1:1.
Deve invece essere:
- leggibile su smartphone
- coerente con il linguaggio Airbus
- costruito solo con dati realmente disponibili in RideScope
- facilmente ritoccabile agendo quasi solo su geometrie e `sizeSp`
