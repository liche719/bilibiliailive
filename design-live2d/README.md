# Paper Messenger Live2D Assets

This directory contains source artwork and intermediate assets used to build the Paper Messenger Live2D model.

- `source/`: approved flattened reference and reconstruction images.
- `layers/`: transparent, full-canvas component layers.
- `model/`: Cubism Editor project source when available.
- `exports/`: local export staging before assets are copied into `frontend/public/live2d/paper-messenger/`.

Rebuild the current layered PSD with:

```powershell
python .\tools\prepare_face_layers.py
.\.venv\Scripts\python.exe .\tools\build_psd.py
```

The generated `paper-messenger-live2d.psd` currently contains the featureless registered character base, independent left and right eyes, blush, nose, closed mouth, open mouth, and forehead mark. Cubism deformers handle the first body-motion pass; larger paper parts can be split further when a wider motion range is required.

The PSD uses PackBits/RLE layer data because Cubism Editor does not support ZIP-compressed layer data.

Generated images and model files must not contain credentials or API responses.
