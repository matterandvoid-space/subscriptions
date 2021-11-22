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
