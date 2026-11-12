# Graph UI performance

The UI renders every concept and relationship. At 300 nodes or 1,000 edges it
defaults to 2D; users can still switch to 3D. An explicit mode choice is retained
during graph refreshes. Small graphs retain the 3D default.

## Rendering changes

- 2D displays deterministic spiral positions immediately, then refines them in a
  module Web Worker. Layout work no longer runs in a synchronous loop on the UI
  thread. If Workers are unavailable, the initial positions remain usable.
- Refinement is cancelled on unmount, graph replacement or node dragging. Delayed
  results cannot update a newer graph. A four-entry in-memory LRU cache reuses
  positions for the same topology, viewport and visible subset.
- Layout simulation uses size-dependent tick/collision budgets. These are visual
  layout choices; they do not alter concepts, edges or prerequisite semantics.
- Pan/zoom label updates run at most once per animation frame. A screen-space grid
  replaces all-pairs rectangle collision tests, and unchanged labels avoid style
  writes. Large graphs suppress shadows/fades, use straight edges and cap visible
  labels. Source data and selection/detail editing remain available for every node.
- Three.js/3D rendering is lazy-loaded separately. 3D label objects/textures are
  reused across selection changes, and only eligible labels allocate textures.
  The node-object accessor stays stable: changing it would cause three-forcegraph
  to dispose/rebuild every node. Large graphs use simpler spheres/lines and fewer
  simulation ticks; particles are disabled there.

## Measurements

One local Node.js run per graph, same synthetic topology before/after: a directed
chain plus extra links from `floor(i/3)`; viewport 1000 × 700. This measures CPU time
inside `computeForceLayout`, not browser FPS, API latency or full page load.

| Nodes | Edges | Before | After |
| --- | --- | --- | --- |
| 100 | 192 | 427 ms | 149 ms |
| 500 | 992 | 2,096 ms | 567 ms |
| 1,500 | 2,992 | 6,511 ms | 1,241 ms |

The remaining refinement time runs off-thread. Browser/device performance and the
time to retrieve the graph from the backend still need separate live measurement.
Worker startup/transfer and rendering costs are not included in the table.

The production build splits approximately 1.41 MB of 3D JavaScript (381 KB gzip)
from the 508 KB graph workspace (161 KB gzip). The module Worker is about 16 KB.
Sizes may change in later builds.

From `FE/edugraph-fe`:

```powershell
npm run benchmark:graph
npm run test:graph
npm run test:i18n
npm run lint
npm run build
```

Tests cover deterministic/finite positions without topology mutation, collision
grid equivalence, worker cancellation and cache isolation, unavailable Workers,
3D texture budgets/reuse/disposal, and existing graph editing behavior. No new
runtime dependencies or backend schema changes are needed.
