# RW ModTranslate

Android app for translating [Rusted Warfare](https://store.steampowered.com/app/647960/Rusted_Warfare__RTS/) mod files. Processes `.rwmod` and `.zip` archives, translates text fields while preserving code structure and variable placeholders.

## Features

- Translates mod config files (`.ini`, `.template`, `.txt`) in bulk
- Preserves dynamic variables (`${...}`, `%{...}`) and escape sequences
- Two translation modes:
  - **Add** — keeps original text, adds translated fields with language suffix (e.g. `displayText_en`)
  - **Replace** — replaces original text, stores original in suffixed field
- Parallel file processing using all available cores
- Supports 15 languages including English, Chinese, Russian, Ukrainian, Japanese, and more

## Tech Stack

- **Android** (Kotlin) with Material Design
- **Retrofit 2.9** — Google Translate API integration
- **Min SDK 24**, Target SDK 34

## Usage

1. Open the app, select source and target languages
2. Pick a `.zip` or `.rwmod` file
3. Choose Add or Replace mode
4. Translated file is saved with `_translated` suffix

## License

MIT
