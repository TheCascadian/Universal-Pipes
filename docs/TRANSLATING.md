# Translating Universal Pipes

All text lives in `src/main/resources/assets/universal_pipes/lang/`. The reference file is `en_us.json`.

## Adding a locale

1. Copy `en_us.json` to the new locale file name, in lower case, for example `de_de.json`. Minecraft locale codes are listed on the Minecraft Wiki under "Language".
2. Keep every key exactly as it is. Translate only the values.
3. Save the file as UTF-8.
4. Run `python3 tools/check_lang.py`. It reports missing keys, extra keys and format specifier mismatches against `en_us.json`, and exits with a non-zero status if it finds any.

Untranslated keys fall back to English in game, but the checker still reports them as missing. Remove no keys.

## What must not be translated

- Keys (the text left of the colon).
- Format specifiers such as `%s`, `%d` and `%1$s`. Each value must contain the same specifiers as the English value. Positional specifiers (`%1$s`, `%2$s`, `%3$s`) may be reordered if the target grammar needs it, but none may be added, dropped or renumbered. Write a literal percent sign as `%%`.
- Command names and arguments: `/upipes`, `stats`, `profile`, `rebuild`.
- Filter expression sigils and keywords inside values, for example `#tag`, `@namespace`, `~name`, `?enchanted`, `?damaged`, `&`, `|`, `!`. Expressions are parsed from what the player types, so the text describing them must show the real symbols.
- Config option names such as `tier_feature_gating`, namespaced ids such as `universal_pipes:pipe`, and unit abbreviations that name real quantities (`mB`, `FE`).

## Testing

1. Run `python3 tools/check_lang.py` and fix every reported problem.
2. Start the client with `./gradlew runClient`, set the language under Options, Language, and open the pipe screen (right-click an extract face with an empty hand), the Look view, the status line, the configuration screen (Mods, Universal Pipes, Config) and an item tooltip.
3. Look for text that overflows its button or label. Shorten the translation if it does.
