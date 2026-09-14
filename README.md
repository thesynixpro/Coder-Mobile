# Coder Mobile

**Application:** Coder Mobile  
**Package:** `com.aprax.coderm`  
**Language:** Kotlin  
**UI:** Jetpack Compose / Material 3

## Included MVP

- Project-first onboarding with Open Project / Create Project / Recent Projects
- Android Storage Access Framework directory access with persisted URI permissions
- Project scanning and lightweight project-type detection
- Project-root source-of-truth file explorer
- File opening, editing and autosave
- Multiple editor tabs
- Dark / light / system theme cycle
- Touch coding toolbar
- Project file search
- HTML preview in a WebView with local-file access disabled
- AI provider store with Android Keystore-backed API key encryption
- OpenAI-compatible provider requests with custom base URL, model, API key and connection test
- Project-aware AI context limited to project metadata and active file content
- Terminal surface with command execution intentionally disabled in this build
- Problems surface and polished empty states
- Release R8 configuration

## Architecture

The app is intentionally split by responsibility:

`data/` contains project storage, SAF/file operations, provider persistence, secret storage, and HTTP AI access.

`ui/screens/` contains workspace navigation and feature surfaces.

`ui/editor/` contains the mobile editor surface.

`ui/components/` and `ui/theme/` form the shared design system.

`MainViewModel` owns workspace state and coordinates repositories rather than putting all logic into composables.

## Project source of truth

The selected tree URI is persisted and all file reads/writes use the Android `ContentResolver`/`DocumentFile` path. The app does not copy the project into an application-private mirror.

## Build environment

- Android Gradle Plugin: 9.4.0
- Kotlin: 2.4.20
- Compose BOM: 2026.08.00
- compileSdk / targetSdk: 36
- minSdk: 26
- Java: 21

The container used to prepare this artifact did not have Gradle or an Android SDK installed, and outbound dependency downloads were unavailable, so an end-to-end Gradle build was not executed here. Open the project in a compatible Android Studio installation and sync the project to resolve dependencies.

## Important scope notes

This artifact is a production-oriented **MVP foundation**, not a claim that every P1/P2 feature in the PRD is already implemented. In particular, advanced LSP-style diagnostics, true code-folding/tokenized syntax rendering, Git operations, real terminal process execution, advanced runtime/build orchestration, AI diff application/review, remote development, and collaboration remain follow-on work.
