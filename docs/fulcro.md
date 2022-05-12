This document assumes knowledge of re-frame, specifically subscriptions.

If you do not currently have that knowledge, please take the time to acquire it.

See the readme for referecnes to learn more.

# Why?

The point of this integration is to solve the problem of rendering derived data in fulcro applications. Making it
simple and easy to ensure what is rendered onscreen always reflects the state in the fulcro app db without
the user having to tell fulcro which components should re-render.

The other pain point it is solving is the ability to query for this derived data in event handler code and to not need 
to store the derived data (by the user, this library handles that).

In bullet points:

- Normalized graph db in the client is the correct model for UI state
  - because updates are: assoc-in/update-in [table id attr] 
- We want derived data to only be (re)computed when needed to draw that data
- We don't want to do the book keeping of this derived data - it does not belong in view rendering paths.
  - we may want to make use of this derived data elsewhere
  - we may forget all the circuitous paths that may update the downstream sources of the derived data, ending up with state 
    derived data
  - we may forget which components need to redraw derived data after normalized data is updated

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

If this is something that interests you, PRs are welcome.

# Future ideas

Integrating with fulcro-inspect - probably by adding instrumentation inside of defsub that happens based on a compiler
flag, as well as during re-render - to allow inspecting how long subscriptions took to compute as well as which components
they caused to be re-rendered.
