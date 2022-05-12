This document assumes knowledge of re-frame, especially subscriptions.

If you do not currently have that knowledge, please take the time to acquire it.

See the readme for references to learn more.

# Why?

Fulcro provides powerful features and abstractions but its rendering model is backward to how most humans think about 
UI development. 

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
  onscreen are redrawn that need to be. This library makes targeted refresh tractable (well really reagent does that and Mike's
  incredible discovery/invention of subscriptions).

# How?

There is a `defsc` macro in this library which has the same exact API as the one in fulcro, but wraps the render function of
the component in a `reagent.ratom/run-in-reaction` - and provides it with a callback that will re-render the component
whenever any of the values of the subscriptions the component is subscribed to change. The data for bookkeeping is stored on the
component JS instance itself, and cleanup happens on component unmount.

Nothing else about using fulcro components has changed, they will still re-render following the usual fulcro usage.

Example:

```clojure 
(require '[space.matterandvoid.subscriptions.fulcro :as subs])

(defonce fulcro-app (subs/fulcro-app {:initial-db {:key1 500 :key2 "hi"}}))

(subs/defsub key1 :-> :key1)
;; shorthand for:

(subs/defsub key1 (fn [db] (:key1 db)))

;; and defsub expands to:

(subs/reg-sub ::key1 (fn [db] (:key1 db)))
(defn key1 [db] (deref (subs/subscribe db [::key1])))

(key1 fulcro-app) ;; => 500

(subs/defsc MyComponent [this props]
  {:query         (fn [] [::some-prop1 ::some-prop2])
   ::subs/signals  (fn [this props] 
                    {:key1-value [::key1]})
   :ident         (fn [_] [:component/id ::my-component])}
  (let [{:keys [key1-value]} (subs/signals-map this)]
    (dom/div (str "Key1's value is: " (pr-str key1)))))
    

(comment 
  ;; eval this in a repl and see the UI update
  (swap! (::fulcro.app/state-atom update :key1 inc))
  
  ;; defsub registers a subscription 
  ;; you can inspect the values of subscription by passing in your fulcro app (or a component instance)
  ;; 
  (key1 fulcro-app)
)
```

the `subs/fulcro-app` call delegates to com.fulcrologic.application/fulcro-app and then assoc'es the ::fulcro.app/state-atom to be
a reagent.ratom/atom with the initial-db value if one is passed in. It also assigns the rendering algorithm used by the app
to use the ident-optimized render. The intention of this library is that all derived data is computed using subscriptions
and rendered with their values - this way there are never any stale components on screen - just like in re-frame.

# Subscription authoring tips

All the subscription compute functions are memoized and thus you must be aware that if the inputs to a subscription do not
change then the computation will return the cached data.

When integrating with fulcro the following strategy is recommended to deal with the design tension of wanting optimal caching
while still ensuring the UI reflects the most up-to-date state of the app db.

At the layer 2 level use db->tree and depend on the db - this way you'll ensure your downstream subs will properly
invalidate the memoization cache (or really, compute on new inputs).

Then in layer 3 subs you can select only the parts of the data tree from the layer 2 inputs that you care about, and
pass those to another layer 3 sub - this way the leaf subscription which the UI will use to render - can be memoized while
the layer 2 subs (by using db->tree) will ensure they pick up the newest dependent data from a fulcro query.

The short way to say it is if your subscription takes as input an ident then you will likely get bit by the subscription
not updating properly.

So:
- never take an ident as input to a subscription (or a collection of them) - use db->tree instead
- add 'extractor' subscriptions on top of the db->tree ones to gain the benefits of memoization
    - for example if you are rendering a list of items but only their `::title` field and some other field changes on them,
      having a subscription that maps over them only pulling out the `::title` will enable the subscription to be cached.
- do not use the fulcro application from within a subscription to extract some data "side-band" because this is really
  hiding a subscription dependency - so refactor it to be another subscription, this way your compute graph will recompute
  correctly when dependent data changes.

# No hooks support

This library currently only supports integrating with fulcro components which produce JavaScript React class components.

If using hooks is something that interests you, PRs are welcome.

# Future ideas

Integrating with fulcro-inspect - probably by adding instrumentation inside of defsub that happens based on a compiler
flag, as well as during re-render - to allow inspecting how long subscriptions took to compute as well as which components
they caused to be re-rendered.
