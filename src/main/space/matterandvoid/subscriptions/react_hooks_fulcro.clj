(ns space.matterandvoid.subscriptions.react-hooks-fulcro)

(defmacro use-subs
  "A react hook that subscribes to multiple subscriptions, the return value of the hook is the return value of the
  subscriptions which will cause the consuming react function component to update when the subscriptions' values update.

  Takes an optional data source (fulcro application) and a variable number of subscription vectors.
  Returns a vector with the current values of the subscriptions in the corresponding positions in the vector as the input.

  You can optionally pass a datasource as the first argument, otherwise the subscriptions will use the suscription
  datasource-context to read the fulcro app from React context
  e.g.
  (use-subs [my-sub1] [my-sub2])"
  [& subs]
  (if (vector? (first subs))
    `[~@(mapv (fn [sub] `(use-sub ~sub)) subs)]
    (let [datasource (first subs)]
      `[~@(mapv (fn [sub] `(use-sub ~datasource ~sub)) (rest subs))])))

(defmacro use-sub-map
  "A react hook that subscribes to multiple subscriptions, the return value of the hook is the return value of the
  subscriptions which will cause the consuming react function component to update when the subscriptions' values update.

  Takes an optional data source (fulcro application) and a hashmap
  - keys are keywords (qualified or simple) that you make up.
  - values are subscription vectors.
  Returns a map with the same keys and the values are the subscriptions subscribed and deref'd (thus, being their current values).

  The single-arity version takes only a query map and will use the suscription datasource-context to read the fulcro app from
  React context."
  ([query-map]
   (assert (map? query-map) "You must pass a map literal to use-sub-map")
   (->> query-map (map (fn [[k query]] `[~k (use-sub ~query)])) (into {})))
  ([datasource query-map]
   (assert (map? query-map) "You must pass a map literal to use-sub-map")
   (->> query-map (map (fn [[k query]] `[~k (use-sub ~datasource ~query)])) (into {}))))
