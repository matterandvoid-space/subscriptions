(ns space.matterandvoid.subscriptions.react-hooks
  (:require
    [space.matterandvoid.subscriptions.core :as subs]
    [space.matterandvoid.subscriptions.impl.react-hooks-common :as common]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

(defmacro use-sub-memo
  "Macro that expands expands to `use-sub`, memoizes the subscription vector so that the underlying subscription
  is reused across re-renders by React. If your subscription vector contains an arguments map literal, it is memoized with dependencies
  being the values of the map. If you pass a symbol as the arguments the symbol will be used as the dependency for useMemo;
  thus, you are expected to memoize the arguments yourself in that case.

  If you pass a symbol for the entire subscription vector, no memoization takes place.

  You can annotate the subscription vector with ^:no-memo to emit a plain call to `use-sub` that will not wrap the
  subscription vector in a react/useMemo call.
  You can also pass ^{:memo your-equality-fn} to change the memoization function used (for example to `=`)."
  ([datasource subscription-vector]
   `(do
      (assert (ratom/ratom? ~datasource) (str "You must pass a Reagent Ratom to `use-sub-map` as the datasource, you passed: " (pr-str ~datasource)))
      (common/use-sub-memo subs/subscribe ~datasource ~subscription-vector)))

  ([subscription-vector]
   `(let [datasource# (~'react/useContext subs/datasource-context)]
      (assert (ratom/ratom? datasource#) (str "The datasource from the React context is not a Reagent RAtom in `use-sub-memo`"))
      (common/use-sub-memo subs/subscribe datasource# ~subscription-vector))))

(defmacro use-sub-map
  "A react hook that subscribes to multiple subscriptions, the return value of the hook is the return value of the
  subscriptions which will cause the consuming react function component to update when the subscriptions' values update.

  Takes an optional data source (Reagent RAtom) and a hashmap
  - keys are keywords (qualified or simple) that you make up.
  - values are subscription vectors.
  Returns a map with the same keys and the values are the subscriptions subscribed and deref'd (thus, being their current values).

  You can annotate any of the subscription vectors with ^:no-memo to emit a plain call to `use-sub` that will not wrap the
  subscription vector in a react/useMemo call.
  You can also pass ^{:memo your-equality-fn} to change the memoization function used (for example to `=`).

  The single-arity version takes only a query map and will use the suscription datasource-context to read the Reagent RAtom
  from React context."
  ([query-map]
   (assert (map? query-map) "You must pass a map literal to use-sub-map")
   (let [datasource-sym (gensym "datasource")]
     `(let [~datasource-sym (react/useContext subs/datasource-context)]
        (assert (ratom/ratom? ~datasource-sym) (str "The datasource from the React context is not a Reagent RAtom in `use-sub-map`"))
        (common/use-sub-map subs/subscribe ~datasource-sym ~query-map))))

  ([datasource query-map]
   (assert (map? query-map) "You must pass a map literal to use-sub-map")
   `(do
      (assert (ratom/ratom? ~datasource) (str "You must pass a Reagent RAtom to `use-sub-map` as the datasource, you passed: " (pr-str ~datasource)))
      (common/use-sub-map subs/subscribe ~datasource ~query-map))))
