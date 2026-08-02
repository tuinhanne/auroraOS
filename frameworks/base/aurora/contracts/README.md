# Layer contracts

One file per Aurora layer. Read by `../tools/arch-test.sh`; nothing else parses them.

## Format

Plain text. One `key: value` pair per line. `#` starts a comment. A repeated key adds
another entry to that key's list — there is no array syntax.

| Key | Repeats | Meaning |
|---|---|---|
| `layer` | no | Short layer name, e.g. `runtime` |
| `module` | no | Soong module name, e.g. `aurora-runtime` |
| `package-root` | no | Java package prefix owned by this layer |
| `source-root` | no | Source directory, relative to `frameworks/base/aurora/` |
| `allow-aurora-import` | yes | `aurora.*` package prefixes this layer may import |
| `forbid-import` | yes | Import prefixes that must never appear |
| `allow-dep` | yes | Soong dependencies this module may declare |
| `forbid-dep` | yes | Soong dependencies that must never be declared |

## Rules

`allow-aurora-import` is a whitelist: any `aurora.*` import outside it, and outside the
layer's own `package-root`, is a violation. Non-Aurora imports are governed only by
`forbid-import`, because whitelisting every legal `java.*` import would produce constant
false failures.

`forbid-import` matches by prefix, anchored at the start of the imported package path, so
`android.` catches `android.content.Context` but not a package named `myandroid.foo`.

A layer whose `source-root` does not exist is reported as `skip`, not as a failure. A
layer that has not been built yet is not a violation.
