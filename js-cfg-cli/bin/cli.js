#!/usr/bin/env node
"use strict";

const { run } = require("../src/runner");

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--filepath") {
      out.filepath = argv[i + 1];
      i += 1;
    } else if (arg.startsWith("--filepath=")) {
      out.filepath = arg.slice("--filepath=".length);
    } else if (arg === "-h" || arg === "--help") {
      out.help = true;
    }
  }
  return out;
}

function printUsage() {
  process.stdout.write(
    [
      "Usage: js-cfg-cli --filepath <path>",
      "",
      "Generates per-function control flow graphs for a JavaScript or",
      "TypeScript file using ESLint code-path-analysis. Emits a single JSON",
      "document on stdout in the schema expected by the CFG_Scraper pipeline.",
      "",
    ].join("\n")
  );
}

function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.help) {
    printUsage();
    return 0;
  }

  if (!args.filepath) {
    process.stderr.write("error: --filepath is required\n");
    printUsage();
    return 2;
  }

  try {
    const result = run({ filepath: args.filepath });
    process.stdout.write(JSON.stringify(result));
    process.stdout.write("\n");
    return 0;
  } catch (err) {
    const message = err && err.stack ? err.stack : String(err);
    process.stderr.write(`js-cfg-cli failed for ${args.filepath}: ${message}\n`);
    return 1;
  }
}

process.exit(main());
