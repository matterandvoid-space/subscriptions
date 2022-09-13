(ns space.matterandvoid.subscriptions.react-hook-fulcro
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.impl.hooks-common :as common]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.fulcro :as subs]))

(defn use-sub
  "A react hook that subscribes to a subscription, the return value of the hook is the return value of the
  subscription which will cause the consuming react function component to update when the subscription's value updates.

  Will cause the consuming component to re-render only once per animation frame (using requestAnimationFrame) when the subscription updates.

  Arguments are a fulcro application whose state atom is a reagent ratom and a subscription query vector
  (a vector of a keyword and an optional hashmap of arguments)."
  [data-source query]
  (when goog/DEBUG (assert (fulcro.app/fulcro-app? data-source)))
  (common/use-reaction (subs/subscribe data-source query)))

(defn use-sub-map
  "A react hook that subscribes to multiple subscriptions, the return value of the hook is the return value of the
  subscriptions which will cause the consuming react function component to update when the subscriptions' values update.

  Will cause the consuming component to re-render only once per animation frame (using requestAnimationFrame) when the subscriptions update.

  Takes a data source (fulcro application) and a hashmap
  - keys are keywords (qualified or simple) that you make up.
  - values are subscription vectors.
  Returns a map with the same keys and the values are the subscriptions subscribed and deref'd (thus, being their current values)."
  [data-source query-map]
  (when goog/DEBUG (assert (fulcro.app/fulcro-app? data-source)))
  (when goog/DEBUG (assert (map? query-map)))
  (common/use-reaction
    (ratom/make-reaction
      (fn []
        (->> query-map
          (reduce-kv
            (fn [acc k query-vec] (assoc acc k (subs/<sub data-source query-vec)))
            {}))))))

(defn use-in-reaction
  "A react hook that takes a function with no arguments (a thunk) and runs the provided function inside a reagent `run-in-reaction`,
   returning the passed in function's value to the calling component. re-runs passed in function when any reagent reactive updates fire.

  The hook causes the consuming component to re-render at most once per frame even if the reactive callback fires more than
  once per frame."
  [f] (common/use-in-reaction f))

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
  Returns current value of the Reaction"
  [r] (common/use-reaction r))
