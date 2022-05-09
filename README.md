This is an extraction of re-frame subscriptions into its own library, where the db (as a reagent.ratom/atom) is always
passed explicitly instead of accessed via a global Var.

The original intent was to use subscriptions with fulcro, but the library can be used with any data source that is 
wrapped in reagent.ratom/atom.

# Integrations

There are two API entry namespaces
one for use with fulcro and one for use with a reagent.ratom/atom.

## Reagent.ratom/atom

(sut/reg-sub :hello
(fn [db] (:hello db)))

(sut/defsub sub2 :-> :sub2)

(defonce db (ratom/atom {:sub2 500 :hello "hello"}))

## Fulcro

The point of this integration is to solve the problem of rendering derived data in fulcro applications, making it
simple and easy and making sure what is rendered onscreen always reflects the state in the fulcro app db without 
the user having to tell fulcro which components should re-render.

How?

There is a `defsc` macro in this library which has the same exact API as the one in fulcro, but wraps the render function of 
the component in a `reagent.ratom/run-in-reaction` - and provides it with a callback that will re-render the component 
whenever any of the values of the subscriptions the component is subscribed to change.

Nothing else about using fulcro components has changed, they will still re-render following the usual fulcro design.

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
to use the ident-optimized render the intention of this library is that all derived data is computed using subscriptions 
and rendered with their values - this way there are never any stale components on screen - just like in re-frame. 

### Subscription authoring tips

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

### No hooks support

This library currently only supports integrating with fulcro components which produce JavaScript React class components.

If this is something that interests you, PRs are welcome.

### Future ideas

Integrating with fulcro-inspect - probably by adding instrumentation inside of defsub that happens based on a compiler 
flag, as well as during re-render - to allow inspecting how long subscriptions took to compute as well as which components
they caused to be re-rendered.

# Differences/modifications from upstream re-frame

## Memoized subscription computation functions.

The underlying reagent.core/reaction used in re-frame is cached - this library also does this.

The issue is that this leads to memory leaks if you attempt to invoke a subscription outside of the reactive propagation stage.

That is, the reaction cache is used for example in a re-frame web app when a `reset!` is called on the reagent.ratom/atom 
app-db - this triggers reagent code that will re-render views, it is during this stage that the subscription computation function
runs and the reaction cache is successfully used. 

The key part is that reagent adds an on-dispose handler for the reaction which is invoked when a component unmounts.

Thus, if you try to use a subscription outside of the reactive context the subscription's reaction will be cached
but never disposed, consuming memory that is never relinquished until the application is restarted.

This library incporates two changes to make sure there are no memory leaks and subscriptions can be used in any context 
- and with a cache in both contexts.

The changes are:

- do not cache reactions if we are not in a reactive context (reagent indicates a reactive context binding a dynamic variable.)
- memoize all subscription computation functions with a bounded cache that evicts the least recently used subscription when full.

This is possible because subscriptions are pure functions. 

Another change in this library is that all subscriptions are forced to receive only one argument: a hashmap.

Taking a tip from many successful clojure projects which are able to be extended and grown and integrated over time,
this library forces all subscriptions arguments to be one hashmap - this forces you to name all your arguments and allows
easily flowing data. It also encourages the use of fully qualifed keywords.

That is, they must look like this:

```clojure
(subscribe [::my-sub {:arg1 5}])
```

## `defsub` macro

Creates a function that derefs the subscription, this allows for better editor integration such as jump-to-definition 
support as well as searching for the use of the subscription across a codebase.

You could also use your own defsub macro to add instrumentation, for example, around subscriptions.



# Development 

clone the repo and 

```bash
bb dev
```
