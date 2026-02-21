# AGENTS.md

## Project
Android TV app: fullscreen loop player for local images (MVP), later controlled remotely.

## Tech constraints
- Kotlin only.
- Single-activity MVP.
- No third-party image loading libs (no Coil/Glide) in MVP.
- Prefer Jetpack Compose if already enabled; otherwise simple XML ImageView is fine.
- Must be stable on Android TV (no touch assumptions).

## Performance & safety
- Do not decode full-resolution bitmaps on the UI thread.
- Use BitmapFactory with sampling for large images.
- Avoid memory leaks; stop timers on onStop, resume on onStart.

## Workflow rules
- Never commit secrets or tokens.
- Work on a feature branch named: codex/<short-task-name>
- Open a PR with:
    - Summary
    - What changed
    - How to test (commands)