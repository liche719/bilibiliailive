# Paper Messenger Live2D assets

The Overlay loads the exported Cubism 5 Paper Messenger model from this directory. If Cubism Core or any required model asset is unavailable, the existing transparent PNG character remains visible as a safe fallback.

Runtime layout:

```text
public/live2d/
├─ live2dcubismcore.min.js
└─ paper-messenger/
   ├─ paper-messenger.moc3
   ├─ paper-messenger.model3.json
   ├─ paper-messenger.cdi3.json
   ├─ model-state-map.json
   ├─ paper-messenger.2048/
   │  └─ texture_00.png
   ├─ motions/
   │  ├─ idle.motion3.json
   │  ├─ received.motion3.json
   │  ├─ thinking.motion3.json
   │  ├─ speaking.motion3.json
   │  ├─ error.motion3.json
   │  └─ reconnecting.motion3.json
   └─ expressions/
      ├─ normal.exp3.json
      ├─ happy.exp3.json
      ├─ focused.exp3.json
      ├─ confused.exp3.json
      └─ offline.exp3.json
```

`live2dcubismcore.min.js` must come from the official Live2D Cubism SDK for Web and remains subject to the Live2D license accepted by the project owner. The project does not download or redistribute that proprietary runtime automatically.

`src/Live2DPaperMessenger.tsx` dynamically loads PixiJS and the Cubism adapter, maps Overlay events to the six model states, keeps scaling stable across browser sizes, honors reduced-motion preferences, and falls back to the PNG character if initialization fails.

The current Cubism project exports separate eye, blush, open-mouth, closed-mouth, nose, forehead-mark, and body drawables, but its standard parameter IDs do not contain keyed mesh deformation. The runtime therefore animates those exported Live2D drawables directly after each Cubism update: eye meshes squash and shift, mouth drawables cross-fade while speaking, blush opacity follows emotion, and the whole model supplies restrained body movement. Ear and wing deformation still requires splitting those shapes out of `character_base_no_face` and binding them in Cubism Editor.
