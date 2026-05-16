# Upload debug

Endpoint PHP per ricevere uno o piu file da un'app e salvarli in `./ridescope/web/debug`.

La password di autenticazione va configurata nella variabile ambiente `RIDESCOPE_UPLOAD_PASSWORD`.
Se la variabile non e configurata, lo script rifiuta gli upload.

## Come inviare i file

Invia una richiesta `POST` in `multipart/form-data` verso `ridescope.php`.

La password puo essere passata in uno di questi modi:

- header `X-Upload-Password`
- campo `password` nel form

I file possono essere inviati con qualsiasi nome campo supportato da `$_FILES`, inclusi array come `files[]`.

## Esempio `curl`

```bash
curl -X POST "https://tuo-server/ridescope.php" \
  -H "X-Upload-Password: la-password-impostata-nello-script" \
  -F "files[]=@/percorso/file1.txt" \
  -F "files[]=@/percorso/file2.log"
```

## Risposta

Lo script restituisce JSON con:

- elenco dei file salvati
- eventuali errori
