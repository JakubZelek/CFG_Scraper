"use strict";

/**
 * Custom ESLint rule that captures every CodePath produced during analysis
 * and converts it to the project's `{node: [successors]}` adjacency list.
 *
 * One graph is emitted per CodePath (program, function, class field
 * initializer, class static block). Segment ids from ESLint's CodePathSegment
 * are used directly as graph node ids, so they are guaranteed to be unique
 * within a graph and stable for the lifetime of the analysis.
 */

function describeCodePath(codePath, node, parentName) {
  const origin = codePath.origin;

  const buildLocSuffix = () => {
    if (node && node.loc && node.loc.start) {
      return `:${node.loc.start.line}:${node.loc.start.column}`;
    }
    return "";
  };

  // Top-level script/module body. Mirrors the Python scraper's "<module>".
  if (origin === "program") {
    return "<module>";
  }

  if (origin === "class-field-initializer") {
    const keyName = readKeyName(node && node.parent);
    const base = keyName ? `<class-field:${keyName}>` : "<class-field>";
    return joinNames(parentName, `${base}${buildLocSuffix()}`);
  }

  if (origin === "class-static-block") {
    return joinNames(parentName, `<class-static>${buildLocSuffix()}`);
  }

  // origin === "function" — the node is the FunctionDeclaration /
  // FunctionExpression / ArrowFunctionExpression that owns the path.
  return joinNames(parentName, deriveFunctionName(node, buildLocSuffix));
}

function joinNames(parent, child) {
  if (!parent || parent === "<module>") {
    return child;
  }
  return `${parent}.${child}`;
}

function readKeyName(node) {
  if (!node) return null;
  // PropertyDefinition / MethodDefinition / Property
  if (node.key) {
    if (node.key.type === "Identifier") return node.key.name;
    if (node.key.type === "Literal") return String(node.key.value);
    if (node.key.type === "PrivateIdentifier") return `#${node.key.name}`;
  }
  return null;
}

function deriveFunctionName(node, locSuffix) {
  if (!node) {
    return `<anonymous>${locSuffix()}`;
  }

  if (node.id && node.id.name) {
    return node.id.name;
  }

  const parent = node.parent;
  if (parent) {
    // const foo = function () {} / const foo = () => {}
    if (parent.type === "VariableDeclarator" && parent.id && parent.id.name) {
      return parent.id.name;
    }
    // foo = function () {}
    if (
      parent.type === "AssignmentExpression" &&
      parent.left &&
      parent.left.type === "Identifier"
    ) {
      return parent.left.name;
    }
    // { foo() {} } / class Foo { bar() {} }
    if (
      parent.type === "MethodDefinition" ||
      parent.type === "Property" ||
      parent.type === "PropertyDefinition"
    ) {
      const keyName = readKeyName(parent);
      if (keyName) return keyName;
    }
  }

  return `<anonymous>${locSuffix()}`;
}

module.exports = function createCfgRule(graphsOut) {
  return {
    meta: {
      type: "problem",
      docs: {
        description: "Capture ESLint code paths and emit them as CFG JSON.",
      },
      schema: [],
      messages: {},
    },
    create() {
      // Stack of code path infos, parent-most last on top while open.
      // Each entry: { id, name, originNode, segments, loopEdges,
      //               reachableSegments, unreachableSegments }
      const stack = [];
      const finished = [];

      const top = () => stack[stack.length - 1];

      const recordSegment = (segment, reachable) => {
        const info = top();
        if (!info) return;
        if (!info.segments.has(segment.id)) {
          info.segments.set(segment.id, segment);
        }
        if (reachable) {
          info.reachableSegments.add(segment.id);
        } else {
          info.unreachableSegments.add(segment.id);
        }
      };

      return {
        onCodePathStart(codePath, node) {
          const parentInfo = stack.length ? stack[stack.length - 1] : null;
          const name = describeCodePath(
            codePath,
            node,
            parentInfo ? parentInfo.name : null
          );

          stack.push({
            id: codePath.id,
            origin: codePath.origin,
            name,
            originNode: node,
            segments: new Map(),
            loopEdges: new Map(),
            reachableSegments: new Set(),
            unreachableSegments: new Set(),
          });
        },

        onCodePathEnd(codePath, node) {
          const info = stack.pop();
          if (!info || info.id !== codePath.id) {
            // Defensive: stack got out of sync, skip emitting.
            return;
          }

          // Make sure the initial segment is registered even if no segment
          // start event fired for it (rare but cheap to guard).
          if (codePath.initialSegment) {
            const init = codePath.initialSegment;
            if (!info.segments.has(init.id)) {
              info.segments.set(init.id, init);
              if (init.reachable) {
                info.reachableSegments.add(init.id);
              } else {
                info.unreachableSegments.add(init.id);
              }
            }
          }

          // Build the adjacency list straight from each segment's
          // nextSegments. ESLint's loop events are also reflected in
          // nextSegments by the time onCodePathEnd fires, but we also merge
          // the explicitly-recorded loop edges to be safe.
          const graphDict = {};
          const ensureKey = (id) => {
            if (!Object.prototype.hasOwnProperty.call(graphDict, id)) {
              graphDict[id] = [];
            }
          };

          for (const [id, seg] of info.segments) {
            ensureKey(id);
            for (const next of seg.nextSegments || []) {
              if (!graphDict[id].includes(next.id)) {
                graphDict[id].push(next.id);
              }
              ensureKey(next.id);
            }
          }

          for (const [from, tos] of info.loopEdges) {
            ensureKey(from);
            for (const to of tos) {
              if (!graphDict[from].includes(to)) {
                graphDict[from].push(to);
              }
              ensureKey(to);
            }
          }

          // Drop trivial graphs (single node, no edges) to match the
          // Python scraper's `len(graph) > 1` behaviour.
          const nodeCount = Object.keys(graphDict).length;
          const edgeCount = Object.values(graphDict).reduce(
            (sum, succs) => sum + succs.length,
            0
          );
          if (nodeCount <= 1 && edgeCount === 0) {
            return;
          }

          const nodeForLoc = node || info.originNode;
          finished.push({
            name: info.name,
            graph_dict: graphDict,
            other_graph_info: {
              origin: info.origin,
              reachable_count: info.reachableSegments.size,
              unreachable_count: info.unreachableSegments.size,
              loc:
                nodeForLoc && nodeForLoc.loc
                  ? {
                      start: nodeForLoc.loc.start,
                      end: nodeForLoc.loc.end,
                    }
                  : null,
            },
          });

          graphsOut.push(...finished.splice(0, finished.length));
        },

        onCodePathSegmentStart(segment) {
          recordSegment(segment, true);
        },

        onCodePathSegmentEnd() {
          // No-op: we don't need traversal state, only structural data.
        },

        onUnreachableCodePathSegmentStart(segment) {
          recordSegment(segment, false);
        },

        onUnreachableCodePathSegmentEnd() {
          // No-op.
        },

        onCodePathSegmentLoop(fromSegment, toSegment) {
          const info = top();
          if (!info) return;

          if (!info.segments.has(fromSegment.id)) {
            info.segments.set(fromSegment.id, fromSegment);
            (fromSegment.reachable
              ? info.reachableSegments
              : info.unreachableSegments
            ).add(fromSegment.id);
          }
          if (!info.segments.has(toSegment.id)) {
            info.segments.set(toSegment.id, toSegment);
            (toSegment.reachable
              ? info.reachableSegments
              : info.unreachableSegments
            ).add(toSegment.id);
          }

          let succs = info.loopEdges.get(fromSegment.id);
          if (!succs) {
            succs = new Set();
            info.loopEdges.set(fromSegment.id, succs);
          }
          succs.add(toSegment.id);
        },
      };
    },
  };
};
