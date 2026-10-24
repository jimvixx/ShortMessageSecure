# l10n_sync Manual

This tool manages Android localization workflow:

- sync → synchronize base and translations
- translate → auto-translate missing/stale entries
- report → inspect translation state
- unused-keys → finds resource keys with no usages in the project
- remove-key → remove a specific resource key from all locale files

All commands follow a consistent structure:
- Description
- Core logic
- Behavior
- Examples
- Options


# l10n_sync.py

`l10n_sync.py` is a localization management tool for Android projects with optional DeepL integration.

It is designed for projects where:

* `res/values/strings.xml` (English) is the **single source of truth**
* Localizations are **partial and evolving**
* English text changes must **invalidate existing translations**
* XML comments in the base file must be preserved and synchronized to localized files
* Automatic translation via DeepL API is required
* Terminology consistency is enforced via glossaries

Originally designed for the **SMSecure / ShortMessageSecure** project, but usable in any Android app.

---

## Features

* Synchronizes Android localization resources across languages
* Supports:

  * `<string>`
  * `<plurals>`
  * `<string-array>`
* Tracks translation state per entry:

  * `missing`
  * `stale`
  * `ok`
  * `copied`
  * `skipped`
* Preserves and synchronizes **all XML comments that immediately precede a resource entry**
* Removes comment lines in localized files that are not present before the matching base entry
* Safe automatic translation using DeepL API
* DeepL glossary support:
  * per-language glossaries
  * default fallback glossary
* CI-friendly deterministic workflow
* No IDE or Android Studio plugins required
* Includes resource cleanup helpers:
  * `unused-keys`
  * `remove-key`

---

## Requirements

### Python

* Python **3.9+**

### Python packages

```bash
pip install requests lxml
```

* `requests` — required for DeepL API
* `lxml` — strongly recommended, because comment-preserving XML synchronization depends on it

Without `lxml`, translations still work, but XML comments cannot be reliably preserved or synchronized.

---

## Project Structure (Example)

```text
app/
└── src/
    └── main/
        └── res/
            ├── values/
            │   └── strings.xml
            ├── values-de/
            ├── values-fr/
            ├── values-ru/
            └── ...
glossaries/
langs.txt
l10n_sync.py
.smsecure-l10n/
```

By default, the tool stores its state in:

```text
<git-project-root>/.smsecure-l10n/state.json
```

The project root is discovered by walking upward from `--res` until `.git` is found.

You can override the state location with:

```bash
--state-dir /path/to/project-root
```

## Branch-aware state v2

`.smsecure-l10n/state.json` is local tool state for translation hashes,
statuses, and glossary tracking. It remains in `.gitignore` because it is
working-copy data: it changes during synchronization, contains local history
and DeepL glossary metadata, and must not be merged as application source.

The state file uses one independent state object per Git branch:

```json
{
  "version": 2,
  "branches": {
    "main": {
      "base": {"string:example": "base-hash"},
      "langs": {"ru": {"string:example": {"status": "ok"}}},
      "glossaries": {}
    },
    "feature/example": {
      "created_from": "main",
      "base": {"string:example": "base-hash"},
      "langs": {"ru": {"string:example": {"status": "stale"}}},
      "glossaries": {}
    }
  }
}
```

`sync`, `report`, and `translate` select the current branch automatically.
Branch names are stored exactly, including `/`. Existing branch entries are
loaded without changing other branches. A newly selected branch is deep-copied
from its parent, so later status and hash changes remain independent.

For a v2 state file, parent selection is deliberately conservative:

1. If the current branch already exists in `branches`, its state is used.
2. Otherwise, the tool accepts a parent only when the current branch reflog has
   an unambiguous `branch: Created from BRANCH` entry and `BRANCH` already has
   state.
3. If Git cannot establish that parent, the command stops with an actionable
   error. It never silently assumes `main` or the previously active branch.
4. Use `--from-branch BRANCH` to select an existing state branch explicitly.

Git reflogs can expire, and checkout history does not always preserve the
original branch relationship. Therefore merge-base information is not treated
as proof of origin. A failed parent lookup does not write a new branch entry or
modify any existing branch.

With no branch name available, detached HEAD is represented as
`detached/<short-commit>`. A detached branch with existing state can be read
normally; a new detached branch must use `--from-branch` once a v2 state file
already exists. If no state file exists at all, a non-`main` state-changing run
stops without creating a file; initialize state on `main` first.

### Legacy v1 migration

When an older single-branch `state.json` has no `version: 2`/`branches`
structure, the first state-changing `sync` or `translate` run migrates it:

* On `main`, the complete old object becomes `branches.main`.
* On any other branch, the complete old object is deep-copied into both
  `branches.main` and `branches.<current-branch>`; the feature entry records
  `created_from: "main"`.
* Existing hashes, statuses, glossary data, and other legacy fields are kept.
* The original bytes are saved as `state.json.legacy-backup`; numbered suffixes
  are used instead of overwriting an earlier backup.

`report` is read-only: it can inspect legacy data and reports that migration
was not written. State-changing commands save through a temporary file in the
same directory followed by `os.replace`; serialization or replacement failure
leaves the previous state file in place and cleans the temporary file.

### Recommended SMSecure workflow

Initialize or migrate state on `main` before creating a feature branch:

```bash
python3 tools/l10n/l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file tools/l10n/langs.txt \
  --glossary-file tools/l10n/glossary.txt
```

Create and use the MMS branch. Its reflog normally identifies `main`:

```bash
git switch -c feature/mms-support
python3 tools/l10n/l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file tools/l10n/langs.txt
```

Create a second branch from the feature branch. Its state is copied from
`feature/mms-support` when the reflog records that origin:

```bash
git switch -c feature/mms-support-ui feature/mms-support
python3 tools/l10n/l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file tools/l10n/langs.txt
```

If the origin is ambiguous, initialize explicitly without changing another
branch:

```bash
python3 tools/l10n/l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file tools/l10n/langs.txt \
  --from-branch feature/mms-support
```

Switching branches changes only which branch object is selected. After merging
a feature's source changes into `main`, switch to `main` and run `sync` again;
the main branch's localization history remains separate and is reconciled with
the merged resources. The ignored state file is not part of the Git merge.

### Recovery

The tool does not automatically rewrite an existing branch's history. If a
new branch was initialized from the wrong source, first copy
`.smsecure-l10n/state.json` to a safe location, remove only that branch object
from the working copy, and rerun `sync --from-branch` with the correct existing
state branch. Never remove the source branch object. If a migration went wrong,
restore the non-overwritten `state.json.legacy-backup` (or another private
copy) before running a state-changing command. `report` can be used to inspect
the result without rewriting state.

### Pre-commit integration

`.githooks/pre-commit` runs `sync` and then `report` for the current checkout,
requiring zero `missing` and `stale` entries. It uses the same automatic branch
selection and keeps `.smsecure-l10n/state.json` ignored. On a newly created
branch whose parent is ambiguous, run `sync --from-branch` once before
committing so the branch entry exists; subsequent hook runs reuse it.

---

## XML Comments

All XML comments that immediately precede a resource entry in the base file are treated as that entry's comment block.

Example in base file (`values/strings.xml`):

```xml
<!-- settings -->
<string name="title_short">Settings</string>
```

Behavior:

* Copied to all locales for the same resource
* Kept in sync with the base file

---

## Non-Translatable Entries

Entries marked as:

```xml
<string name="example" translatable="false">Do not translate</string>
```

are:

* Never translated

---

## Missing Translations

For translatable resources:

* if a localized translation does not exist, the entry is marked as `missing`
* missing entries are **not created automatically**
* empty translated resources are removed from localized files during sync

This means a localized file contains only:

* actual translations

---

## Language Selection

Languages can be provided in two ways.

### Inline

```bash
--langs de,fr,ru,tr
```

### From file

```bash
--langs-file langs.txt
```

Example `langs.txt`:

```text
# Main UI languages
de
fr
ru
tr
uk
```

Both methods can be combined.

---

## Commands Overview

```text
l10n_sync.py <command> [options]
```

Available commands:

* `sync`
* `report`
* `translate`
* `glossary sync`
* `unused-keys`
* `remove-key`

---

## Command: sync

### Description

Synchronizes localized `strings.xml` files with the base English file and updates translation state.

### What it does

* Sorts the base `strings.xml` before synchronization: sections are ordered
  alphabetically by their comment marker, resources inside each section are
  ordered by name, and plural quantity order
* Keeps XML comments immediately preceding each resource synchronized with base
* Keeps existing translator-provided translations
* Adds new base keys to `state.json`
* Marks translations for new base keys as `missing`
* Marks existing translations as `stale` when the base text changes
* Marks translations as `ok` when a translator adds a new translation
* Marks translations as `ok` when a translator updates an existing translation
* Leaves all other statuses unchanged
* Always removes obsolete top-level locale entries that no longer exist in base
* Also removes obsolete translation state entries from `state.json`
* Updates `state.json`

### State logic

For translatable resources, the command follows these rules:

* if a new base resource is added, a state entry is created and the translation status becomes `missing`
* if the base text changes, the base hash is updated and all existing translations for that key become `stale`
* if a translator adds a missing translation, its translation hash is stored and the status becomes `ok`
* if a translator changes an existing translation, its translation hash is updated and the status becomes `ok`
* if the translation already had status `ok` and the translator edits it again, the status stays `ok` and only the translation hash changes
* if nothing changed for a key, its status is preserved

For non-translatable resources:

* the locale entry is removed
* the status is stored as `skipped`

### Forced status options

You can force status assignment for all existing translations:

```bash
--mark-all-stale
```

Sets all existing translatable translations to `stale`.

```bash
--mark-all-plurals-stale
```

Sets all existing translatable plurals translations to status `stale`.

```bash
--mark-all-ok
```

Sets all existing translatable translations to `ok`.

These options are mutually exclusive.

### Obsolete keys removal

* top-level locale entries missing in the base file are always removed
* obsolete keys are always removed from `state.json`

Exception:

* extra quantity items inside an existing `<plurals>` entry are preserved, because some locales may require more plural forms than the base file

### State location

By default, state is stored under the Git project root:

```text
<git-project-root>/.smsecure-l10n/state.json
```

The project root is discovered by walking upward from `--res` until `.git` is found.

To override:

```bash
--state-dir /path/to/project-root
```

### Examples

Basic sync:

```bash
python3 l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file langs.txt
```

Force all existing translations to `stale`:

```bash
python3 l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file langs.txt \
  --mark-all-stale
```

Force all existing translations to `ok`:

```bash
python3 l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file langs.txt \
  --mark-all-ok
```

Use a custom state location:

```bash
python3 l10n_sync.py sync \
  --res app/src/main/res \
  --langs-file langs.txt \
  --state-dir .
```

### Options

* `--res`  
  Path to Android `res/` directory. Required.

* `--base`  
  Base values folder. Default: `values`.

* `--langs`  
  Comma- or space-separated Android language codes to process.

* `--langs-file`  
  Path to a file with language codes. Can be combined with `--langs`.

* `--state-dir`  
  Override the project root used for `.smsecure-l10n/state.json`.

* `--from-branch`
  Select an existing state branch as the source when the current Git branch has no state.

* `--mark-all-stale`  
  Force all existing translatable translations to status `stale`.

* `--mark-all-plurals-stale`  
  Force all existing translatable plurals translations to status `stale`.

* `--mark-all-ok`  
  Force all existing translatable translations to status `ok`.

---

## Command: report

### Description

Shows translation status from `state.json` without modifying files.

By default, it reports:

* `missing`
* `stale`

### Examples

```bash
python3 l10n_sync.py report   --res app/src/main/res   --status missing,stale
```

You can also request other statuses, for example:

```bash
python3 l10n_sync.py report   --res app/src/main/res   --status missing,stale,ok
```

### Options

* `--res`  
  Path to Android `res/` directory. Required.

* `--langs`  
  Comma- or space-separated Android language codes to process.

* `--langs-file`  
  Path to a file with language codes. Can be combined with `--langs`.

* `--state-dir`  
  Override the project root used for `.smsecure-l10n/state.json`.

* `--from-branch`
  Select an existing state branch to inspect when the current Git branch has no state.

* `--status`  
  Comma-separated statuses: missing,stale,ok,copied,skipped.

---

## Command: translate

### Description

Automatically translates entries that require translation using the DeepL API.

### Core logic

The translation workflow is strictly based on `state.json`:

- `ok` → NOT translated (already valid)
- `missing` → translated
- `stale` → translated

After translation:

- translation is written/updated into XML
- `translation_hash` is updated
- status becomes **`ok`**

### What it does

* Reads current state from `state.json`
* Selects only entries with status `missing` or `stale`
* Sends base (English) text to DeepL
* Writes translated values into localized `strings.xml`
* Updates:
  * `translation_hash`
  * `status = ok`
  * timestamps

### Context-aware translation

The command sends additional **context** to DeepL to improve translation quality.

Context includes:

* resource name (converted to human-readable form)
* XML comments from base `strings.xml`
* for `<plurals>`:
  * all plural variants (one, few, many, etc.)
  * current plural category
* for `<string-array>`:
  * index and preview of all items

This significantly improves:

* plural correctness (especially for Slavic languages)
* short UI text translation
* ambiguous phrases

### Placeholder protection

Before sending text to DeepL:

* `%1$d`, `%1$s` and other format tokens are protected
* `<xliff:g>` tags are preserved
* generic XML tags are protected

After translation, all placeholders are restored.

### Important behavior

* Entries with status `ok` are never retranslated
* Translator-edited translations remain untouched
* If base text changes → `sync` marks entries `stale`, then `translate` updates them
* Works for:
  * `<string>`
  * `<plurals>`
  * `<string-array>`
* Respects:
  * `translatable="false"`

### Default behavior

By default, only:

missing,stale

are translated.

### Examples

Run this commands or create .env file with such content:
```bash
# Required
export DEEPL_API_KEY="your_key:fx"

# Optional (auto-detected if not set)
export DEEPL_BASE_URL="https://api-free.deepl.com"
```

Basic usage:
```bash
python3 l10n_sync.py translate \
  --res app/src/main/res \
  --langs-file langs.txt
```

Translate only missing:

```bash
python3 l10n_sync.py translate \
  --res app/src/main/res \
  --langs-file langs.txt \
  --status missing
```

Translate specific languages:

```bash
python3 l10n_sync.py translate \
  --res app/src/main/res \
  --langs ru,uk,cs
```

Use glossary:

```bash
python3 l10n_sync.py translate \
  --res app/src/main/res \
  --langs-file langs.txt \
  --glossary-file glossary.txt \
  --glossary-sync
```

### Options

* `--res`  
  Path to Android `res/` directory  

* `--base`  
  Base values folder  

* `--langs`  
  Language list  

* `--langs-file`  
  Language file  

* `--status`  
  Which statuses to translate (default: missing,stale)  

* `--state-dir`
  Override the project root used for `.smsecure-l10n/state.json`.

* `--from-branch`
  Select an existing state branch as the source when the current Git branch has no state.

* `--deepl-key`  
  DeepL API key  

* `--deepl-base-url`  
  Override DeepL endpoint  

* `--sleep-ms`  
  Delay between batches  

* `--sync`  
  Run `sync` command before and after translate  

* `--glossary-sync`  
  Synchronizes glossary files from the repository to DeepL and stores a local snapshot in `state.json` before translating.

  Used to keep your DeepL glossaries aligned with the glossary files stored in your project. It is helpful when you want translation terminology to stay consistent across automatic translations.
Typical use cases:

  * force product names, feature names, and UI terms to always translate the same way
  * keep a per-language terminology file in Git
  * review what changed before uploading a new glossary
  * recreate glossaries in DeepL after editing glossary files

* `--glossary-file`  
  Glossary file

* `--verbose`  
  Print source and translated text for each item

### Glossary File Format
* Support sections
* Section [common] applies to all languages
* Sections [lang:xx] overrides entries from [common] for that language
* A line may be written as:
  *  source `<TAB>` target
  *  source = target
  *  source => target
  *  source,target
  *  source
*  If only "source" is provided, target is assumed to be identical to source

DeepL note:
* A language may support translation but not glossary creation for EN -> target
* In that case glossary sync is skipped for that language
* Translation still continues without glossary
* Support is rechecked on future runs

```text
[common]
HEX
SMS
MMS
PIN
PUK
Signal Protocol = Signal Protocol

[lang:cs]
message thread = vlákno zpráv
```

---

## Command: unused-keys

Finds resource keys defined in base `values/strings.xml` that have **no strict usages** in the project and optionally offers interactive deletion.

### Rules

* Scans almost all text files under `project_root`, excluding caches, build outputs, and binaries
* Ignores `res/values*/` XML files as usage sources because definitions are not usages
* Distinguishes:

  * **strict hits** — Java, Kotlin, XML, Gradle, KTS, properties and similar source/config files
  * **soft hits** — Markdown, TXT, and other weak-signal text files
* A key is considered a deletion candidate when **strict hits == 0**
* Soft mentions are reported as warnings, but do not automatically block deletion

### Interactive mode

Interactive mode is enabled by default.

For each candidate key, the command:

* shows the candidate key and resource kinds
* shows soft-hit examples if present
* shows locale XML files where the key is defined
* asks for an action:

  * delete
  * skip
  * quit

Action input is validated. Invalid input is reprompted and is not treated as skip.

### Safety semantics

* `--apply` enables actual file modifications
* Without `--apply`, the command is dry-run
* In non-interactive mode, the command never deletes files, even if `--apply` is passed
* `--yes` skips the extra yes/no confirmation after choosing delete, but still requires the action prompt
* At the end, the command prints a summary with deleted, skipped, and total candidate counts

### Example

```bash
python3 l10n_sync.py unused-keys   --res app/src/main/res   --project-root .
```

### Example with apply

```bash
python3 l10n_sync.py unused-keys   --res app/src/main/res   --project-root .   --apply
```

### Example list-only mode

```bash
python3 l10n_sync.py unused-keys   --res app/src/main/res   --project-root .   --no-interactive   --limit 200
```

### Options

* `--res` — path to Android `res/` directory
* `--project-root` — project root for usage scan (default: current working directory)
* `--apply` — enable actual deletion in interactive mode
* `--yes` — skip final delete confirmation after action `delete`
* `--interactive` — interactive mode (default)
* `--no-interactive` — non-interactive list-only mode
* `--limit` — max list items printed in non-interactive mode

---

## Command: remove-key

Interactive command to remove a specific resource key from all locale `strings*.xml` files.

### What it does

For the selected key, the command:

* finds all matching definitions in locale files
* scans project sources for usages
* warns if usage hits are found
* removes the key from all matching locale XML files when deletion is confirmed

### Behavior

* Interactive mode is enabled by default
* If a key is not passed as an argument, interactive mode prompts for it
* `--loop` allows removing multiple keys in one run
* In non-interactive mode, the key must be passed explicitly
* If usages are found:

  * deletion is refused unless `--force` is set
  * in interactive mode, the user can confirm forced deletion
* Without `--apply`, the command performs a dry-run and does not modify files

### Example

```bash
python3 l10n_sync.py remove-key   --res app/src/main/res   --project-root .   obsolete_key_name
```

### Example with apply

```bash
python3 l10n_sync.py remove-key   --res app/src/main/res   --project-root .   --apply   obsolete_key_name
```

### Example with force

```bash
python3 l10n_sync.py remove-key   --res app/src/main/res   --project-root .   --apply   --force   obsolete_key_name
```

### Example loop mode

```bash
python3 l10n_sync.py remove-key   --res app/src/main/res   --project-root .   --loop
```

### Options

* `--res` — path to Android `res/` directory
* `--project-root` — project root for usage scan (default: current working directory)
* `--apply` — apply changes
* `--force` — allow deletion even if usage exists
* `--interactive` — interactive mode (default)
* `--no-interactive` — disable interactive prompts
* `--loop` — repeatedly ask for keys until quit
* `key` — optional positional resource key name

---

## Android Locale Notes

Some Android resource directories use legacy ISO codes and must not be renamed:

| Directory   | Language   |
|-------------|------------|
| `values-iw` | Hebrew     |
| `values-in` | Indonesian |
| `values-ji` | Yiddish    |

---

## Help & Manual

```bash
python3 l10n_sync.py --help
python3 l10n_sync.py --man
```

---

## License

MIT or project-specific license.

---

## Author

Designed for the SMSecure / ShortMessageSecure project.


---

## Best Practices: plurals and xliff

To achieve high-quality translations across all languages (especially Slavic languages like Russian, Ukrainian, Czech, Polish), follow these guidelines.

### Plurals

Android uses CLDR plural rules. The full set of categories is:

```
zero
one
two
few
many
other
```

#### Recommendation

Include as many plural forms as possible in the base file:

```xml
<plurals name="messages_count">
    <item quantity="one">%1$d message</item>
    <item quantity="few">%1$d messages</item>
    <item quantity="many">%1$d messages</item>
    <item quantity="other">%1$d messages</item>
</plurals>
```

Even if English does not require all forms, they are critical for correct translations in other languages.

#### Why this matters

* DeepL does NOT know plural rules
* It translates each string independently
* Providing all plural variants improves accuracy significantly

---

### XLIFF placeholders

Always wrap format arguments using `<xliff:g>`:

```xml
<string name="new_messages">
    <xliff:g id="count">%1$d</xliff:g> new messages
</string>
```

```xml
<string name="recent_from">
    Most recent from: <xliff:g id="sender">%1$s</xliff:g>
</string>
```

#### Benefits

* Protects placeholders during translation
* Provides semantic meaning (`id="count"`, `id="sender"`)
* Improves translation quality
* Prevents corruption of `%1$d`, `%1$s`

---

### General recommendations

* Always use descriptive resource names
* Always add XML comments for ambiguous strings
* Keep strings short and context-rich
* Prefer full sentences over fragments when possible
* Avoid concatenation of multiple strings in code

---

### Summary

Best results are achieved when combining:

* full plural coverage (CLDR categories)
* XML comments
* XLIFF placeholders
* DeepL context (automatically handled by `translate`)

This results in near production-quality automatic translations.
