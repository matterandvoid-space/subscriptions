This is an extraction of re-frame subscriptions into its own library, where the db is always passed explicitly instead
of accessed via a global Var.

The original intent was to use subscriptions with fulcro, but can easily be used with any single-storage app db data 
design.


Example:



```clojure 
;; where SPA is a fulcro app (which is just a hashmap)

(require '[re-frame-subs.subs :as subs])

(comment
  (subs/reg-sub SPA
    :dan-query (fn [db]
                 (.log js/console "IN REG SUB: " db)
                 500))

  (let [v (subs/subscribe SPA [:dan-query])]
    (.log js/console "AFTER Subscribe ")
    (let [output (deref v)]
      (.log js/console "Sub output: " output)))

  (subs/get-subscription-cache SPA) )

(comment
  ((subs/get-handler SPA :dan-query) (app/current-state SPA) [])

  (as-> (::app/state-atom SPA) $
    (deref $)
    ;(keys $)
    ;(sort $)
    (:re-frame-subs.subs/subs $)))
```

# Subscriptions authoring tips

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
- never take an ident as input (or a collection of them) - use db->tree instead
- add 'extractor' subscriptions on top of the db->tree ones to gain the benefits of memoization
  - for example if you are rendering a list of items but only their :title field and some other field changes on them,
  having a subscription that maps over them only pulling out the title will enable the subscription to be cached.
- do not use the fulcro application from within a subscription to extract some data "side-band" because this is really
  hiding a subscription dependency - so refactor it to be another subscription, this way your compute graph will recompute 
  correctly when dependent data changes.
