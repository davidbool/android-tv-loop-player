# Android TV Loop Player

MVP Android TV app that plays a fullscreen loop of images from local storage.

## Current scope
- Single Activity
- Fullscreen image viewer
- Will later load images from:
  /Android/data/<package>/files/advision_demo

## Build
./gradlew assembleDebug

## Optional playlist.json
Place `playlist.json` in:
`/Android/data/<package>/files/advision_demo/playlist.json`

Sample content:

```json
[
  { "file": "img1.jpg", "durationMs": 3000 },
  { "file": "img2.png" },
  { "file": "img3.jpg", "durationMs": 8000 }
]
```
