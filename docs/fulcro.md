# Use subscriptions with Fulcro

This library only supports using subscriptions within normal React function components,
making use of fulcro only for its state management functionality (normalization, transactions, and form state).

There is no support for integrating subscriptions with `defsc` fulcro components and there is no planned support.

---

This document assumes knowledge of re-frame, especially subscriptions.

If you do not currently have that knowledge, please take the time to acquire it.

See the readme for references to learn more.

# Why?

Bring reactive UI rendering to fulcro.

Fulcro provides powerful features and abstractions but its rendering model makes dealing with derived data difficult 
to work with. Combined with the realization that a UI is mostly rendering derived data I didn't see a tractable way forward to 
continue using fulcro without a solution to this problem.

The way I think about this is that most UI libraries have a "pull" mental model where a component (the leaf) asks
for data it needs to render. Whereas in fulcro the model is "push" - a mutation augments the data and then tells the leaves to redraw,
pushing the data from the core out to the leaves.

The pull model is well studied and understood. It is the domain of dataflow programming and functional reactive programming.
You start with a core of data and perform pure functional transforms on this core to get the data in a shape needed to render.

The point of this integration is to bring the pull model to fulcro applications and solve the problem of rendering derived data. 
Making it simple (not interwoven) and easy (close at hand) to ensure what is rendered onscreen always reflects the state
in the fulcro app db without the user having to tell fulcro which components should re-render.

The other pain point it solves is the ability to query for this derived data in event handler code or other non-drawing code 
and to not need to store the derived data by the user, the library handles that.

In bullet points:

- Normalized graph db in the client is the correct model for UI state
  - because updates are: assoc-in/update-in [table id attr] 
  - if it isn't in this shape you now have a data synchronization problem
- We want derived data to only be (re)computed when needed to draw that data
- We don't want to do the bookkeeping of this derived data - it does not belong in view rendering paths.
  - If you store the derived data in app-db you no longer have a normalized source of truth. 
  - we may want to make use of this derived data elsewhere (e.g. event handlers)
  - we may forget all the circuitous paths that may update the downstream sources of the derived data, ending up with stale 
    derived data
  - we may forget which components need to redraw derived data after normalized data is updated
- There are quite a few large re-frame applications in production at this point and this model scales well both to large 
  codebases and to projects with numerous disparate contributors.
  - It removes decision making about when/where to compute derived data (it always goes in a subscription)
  - Views are only about rendering data, not transforming it.
  - The presence of these large re-frame applications shows that solving the DB problem by the developers (as re-frame does not
    have opinions about the shape of your app-db - no normalization by default) is tractable, whereas the author believes pushing the 
    responsibility of figuring out derived data to the application author does not scale, especially when you add more devs.
- One of the early and exciting selling points of Om/Fulcro was targeted refresh of only the components whose data has changed.
  There has recently been a move away from this render optimization because it is so hard to make sure all the components
  onscreen are redrawn that need to be. This library makes targeted refresh tractable (well really reagent does that and Mike Thompson's
  incredible discovery/invention of subscriptions).


After years of attempting to use fulcro and seeing other UI rending libraries I realized that all of the complexity of using
fulcro was in the UI half of the library. React hooks combined with subscriptions solve the UI half of the problem
while removing all the complexity fulcro brought. Subscriptions are how you get denormalized data for components
to render where components can be any React function component.

# Usage

For an example of how to use this with fulcro see:

https://github.com/matterandvoid-space/todomvc-fulcro-subscriptions

# Subscriptions support passing the fulcro application state map as a datasource

Subscriptions compute derived data and a common place to make use of that derived data is in mutations.

To make this integration even smoother for fulcro applications you can pass the fulcro state hashmap to a subscription
instead of a fulcro application. When using subscriptions inside components you will pass the fulcro application, but 
for mutation helpers that operate on the state map itself (functions that you would pass to `(swap!)`), you don't have
to also pass the fulcro app just to use subscriptions in that context.

# Subscription authoring tips

You can use Reagent RCursors for layer 2 subscriptions, these perform much better than Reactions for layer 2 subscriptions.

If you're using the EQL subscriptions in this library with fulcro, layer 2 subscriptions are implemented with RCursors for you.

# Future ideas

Integrating with fulcro-inspect - probably by adding instrumentation inside of defsub that happens based on a compiler
flag, as well as during re-render - to allow inspecting how long subscriptions took to compute as well as which components
they caused to be re-rendered.

