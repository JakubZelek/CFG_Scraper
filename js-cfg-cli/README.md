# js-cfg-cli

Standalone Node.js CLI used by the JavaScript and TypeScript `cfg-processor-*`
services to generate Control Flow Graphs from a single source file.

It is the JS/TS counterpart of [`java-cfg-cli/`](../java-cfg-cli/).

## How it works

The CLI uses ESLint's built-in [code path
analysis](https://eslint.org/docs/latest/extend/code-path-analysis) and emits
one CFG per function (plus one for the top-level program) in the JSON schema
expected by [`src/common/models/graph.py`](../src/common/models/graph.py):

```json
{
  "filepath": "/path/to/file.ts",
  "graphs": [
    {
      "name": "computeTotal",
      "graph_dict": { "s1_1": ["s1_2"], "s1_2": [] },
      "other_graph_info": {
        "origin": "function",
        "reachable_count": 2,
        "unreachable_count": 0,
        "loc": { "start": { "line": 12, "column": 0 }, "end": { "line": 25, "column": 1 } }
      }
    }
  ]
}
```

The parser is selected automatically from the file extension:

- `.ts` / `.tsx` / `.mts` / `.cts` → `@typescript-eslint/parser`
- everything else → `espree` (ESLint's default JS parser, JSX enabled)

## Usage

```bash
node ./bin/cli.js --filepath path/to/file.js
node ./bin/cli.js --filepath path/to/file.ts
```

Output is a single JSON document on stdout. Errors go to stderr with a
non-zero exit code, matching the contract used by the other language scrapers.

## Install

```bash
npm ci --omit=dev
```

In the Docker image this is performed at build time (see
`Dockerfile.CfgProcessorJavaScript` and `Dockerfile.CfgProcessorTypeScript`).
