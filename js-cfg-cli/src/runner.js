"use strict";

const fs = require("fs");
const path = require("path");
const { Linter } = require("eslint");

const createCfgRule = require("./cfg-rule");

const TS_EXTENSIONS = new Set([".ts", ".tsx", ".mts", ".cts"]);

function pickParser(filepath) {
  const ext = path.extname(filepath).toLowerCase();
  if (TS_EXTENSIONS.has(ext)) {
    return require("@typescript-eslint/parser");
  }
  // espree is bundled with eslint and is the default parser if none is set;
  // requiring it explicitly keeps the config self-contained.
  return require("espree");
}

function defaultParserOptions(filepath) {
  const ext = path.extname(filepath).toLowerCase();
  return {
    ecmaVersion: "latest",
    sourceType: ext === ".cjs" || ext === ".cts" ? "commonjs" : "module",
    ecmaFeatures: { jsx: true },
  };
}

function buildCfg(filepath, source) {
  const graphs = [];
  const cfgRule = createCfgRule(graphs);

  const linter = new Linter();

  // Flat-config-style options, see Linter#verify in ESLint v9+:
  // https://eslint.org/docs/latest/use/migrate-to-9.0.0#linter-now-expects-flat-config-format
  //
  // The `files` glob is required: without it, ESLint refuses to apply this
  // config to files whose extension isn't in its built-in JS list (e.g.
  // `.ts`, `.tsx`, `.mts`, `.cts`), reporting "No matching configuration
  // found".
  const config = {
    files: ["**/*.{js,jsx,mjs,cjs,ts,tsx,mts,cts}"],
    languageOptions: {
      parser: pickParser(filepath),
      parserOptions: defaultParserOptions(filepath),
    },
    plugins: {
      "cfg-scraper": {
        rules: {
          emit: cfgRule,
        },
      },
    },
    rules: {
      "cfg-scraper/emit": "error",
    },
  };

  // ESLint's flat-config matcher tests `files` globs against the value passed
  // as `filename`. Absolute paths outside the current working directory
  // therefore fail to match `**/*.{js,ts,...}` and ESLint reports
  // "No matching configuration found". Passing just the basename keeps the
  // glob match working while `physicalFilename` preserves the real path for
  // any rule that needs it.
  const basename = path.basename(filepath);

  // The Linter doesn't run the rule unless verify() is called. We don't care
  // about lint messages — the rule populates `graphs` as a side effect.
  linter.verify(source, config, {
    filename: basename,
    physicalFilename: filepath,
  });

  return {
    filepath,
    graphs,
  };
}

function run({ filepath }) {
  if (!filepath) {
    throw new Error("--filepath is required");
  }

  const absolute = path.resolve(filepath);
  const source = fs.readFileSync(absolute, "utf8");

  return buildCfg(filepath, source);
}

module.exports = { run, buildCfg };
