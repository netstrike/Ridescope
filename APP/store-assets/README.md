# RideScope store assets

Asset grafici per Google Play Console.

- `ridescope-store-icon-512.png`: icona app store 512x512 PNG 32-bit con alpha.
- `ridescope-feature-graphic-1024x500.png`: feature graphic 1024x500 PNG senza alpha.

Rigenerazione:

```powershell
powershell -ExecutionPolicy Bypass -File .\store-assets\generate-store-assets.ps1
```

I requisiti Play Console correnti usati per questi file sono:
- app icon: 512px x 512px, PNG 32-bit con alpha, massimo 1024KB.
- feature graphic: 1024px x 500px, JPEG o PNG 24-bit senza alpha.
